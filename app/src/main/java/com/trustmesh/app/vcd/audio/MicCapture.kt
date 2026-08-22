package com.trustmesh.app.vcd.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Microphone capture at 16 kHz mono PCM 16-bit.
 *
 * SCOPE BOUNDARY: the audio source is [MediaRecorder.AudioSource.MIC] and nothing else. VOICE_CALL,
 * VOICE_UPLINK, VOICE_DOWNLINK and friends are signature-only on Android and are not attempted
 * here, not as a fallback and not behind a flag. During a call this class hears the far party only
 * because the call is on speakerphone and the sound is physically in the room — the same audio a
 * person standing nearby would hear.
 *
 * The class also carries the Phase 0 diagnostics, because "is the mic even producing samples during
 * MODE_IN_CALL on this handset" is a question only a real device can answer, and the answer is
 * OEM-dependent.
 */
class MicCapture(private val context: Context) {

    /** Why capture is not producing usable audio. Every one of these is shown, never swallowed. */
    enum class Failure {
        PERMISSION_DENIED,
        DEVICE_BUSY,
        UNSUPPORTED_CONFIG,

        /** The OS is feeding us zeros on purpose — privacy toggle, or another app owns the mic. */
        SILENCED_BY_SYSTEM,

        /** AudioRecord is running but every sample is ~0. On some OEMs this is what MODE_IN_CALL does. */
        PRODUCING_SILENCE,

        READ_ERROR,
    }

    data class State(
        val running: Boolean = false,
        val rms: Float = 0f,
        val peak: Float = 0f,
        val samplesCaptured: Long = 0,
        val failure: Failure? = null,
        val detail: String? = null,
        /** AudioManager.getMode() at the last poll — MODE_IN_CALL is the interesting one. */
        val audioMode: Int = AudioManager.MODE_INVALID,
        val speakerphoneOn: Boolean = false,
        val routedDevice: String? = null,
        val systemSilenced: Boolean = false,
    ) {
        val healthy: Boolean get() = running && failure == null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var callbackThread: HandlerThread? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    /** Consecutive near-silent reads before we call it a failure rather than a quiet moment. */
    private var silentChunks = 0

    /**
     * Starts capture. [onAudio] is invoked on the capture thread with mono float samples; it must
     * return quickly and must not block, or samples will be dropped.
     *
     * @throws SecurityException if RECORD_AUDIO is not held — callers check first; this is a
     *   backstop, not the permission flow.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onAudio: (FloatArray, Int) -> Unit): Boolean {
        if (capturing) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            fail(Failure.UNSUPPORTED_CONFIG, "16 kHz mono PCM-16 is not supported by this device")
            return false
        }
        // Four times the minimum gives the reader room to fall behind briefly without the driver
        // overwriting samples we have not read yet.
        val bufferBytes = minBuffer * 4

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioConstants.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            fail(Failure.PERMISSION_DENIED, e.message)
            return false
        } catch (e: IllegalArgumentException) {
            fail(Failure.UNSUPPORTED_CONFIG, e.message)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail(Failure.DEVICE_BUSY, "AudioRecord could not be initialised (state=${rec.state})")
            return false
        }

        record = rec
        capturing = true
        silentChunks = 0
        _state.value = State(running = true).withRouting()

        registerSilenceCallback(rec.audioSessionId)

        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            stop()
            fail(Failure.DEVICE_BUSY, e.message)
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stop()
            fail(Failure.DEVICE_BUSY, "AudioRecord refused to enter the recording state")
            return false
        }

        val chunkSamples = AudioConstants.SAMPLE_RATE / 10 // 100 ms
        thread(name = "vcd-mic-capture", isDaemon = true) {
            val shorts = ShortArray(chunkSamples)
            val floats = FloatArray(chunkSamples)
            while (capturing) {
                val read = try {
                    rec.read(shorts, 0, chunkSamples)
                } catch (t: Throwable) {
                    fail(Failure.READ_ERROR, t.message); break
                }
                when {
                    read > 0 -> {
                        Pcm.shortsToFloat(shorts, read, floats)
                        publishLevels(floats, read)
                        onAudio(floats, read)
                    }

                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE ||
                        read == AudioRecord.ERROR_DEAD_OBJECT ||
                        read == AudioRecord.ERROR -> {
                        fail(Failure.READ_ERROR, "AudioRecord.read returned $read")
                        break
                    }
                }
            }
        }
        Log.i(TAG, "mic capture started, buffer=$bufferBytes bytes, mode=${audioManager.mode}")
        return true
    }

    fun stop() {
        capturing = false
        unregisterSilenceCallback()
        record?.let { rec ->
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        record = null
        _state.value = _state.value.copy(running = false, rms = 0f, peak = 0f)
    }

    /** Re-reads audio routing for the Phase 0 diagnostics panel. */
    fun refreshRouting() {
        _state.value = _state.value.withRouting()
    }

    private fun publishLevels(samples: FloatArray, length: Int) {
        var acc = 0.0
        var peak = 0f
        for (i in 0 until length) {
            val s = samples[i]
            acc += s.toDouble() * s
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
        }
        val rms = sqrt(acc / length).toFloat()

        // A running AudioRecord that only ever yields zeros is the classic MODE_IN_CALL failure on
        // some OEM builds. Reporting a "score" from that audio would be reporting a score from
        // silence, so it is escalated to a visible failure instead.
        if (peak < DIGITAL_SILENCE) silentChunks++ else silentChunks = 0
        val silenceFailure = if (silentChunks >= SILENT_CHUNKS_BEFORE_FAILURE) {
            Failure.PRODUCING_SILENCE
        } else {
            null
        }

        val prev = _state.value
        _state.value = prev.copy(
            rms = rms,
            peak = peak,
            samplesCaptured = prev.samplesCaptured + length,
            failure = silenceFailure ?: prev.failure.takeIf { it != Failure.PRODUCING_SILENCE },
            detail = if (silenceFailure != null) {
                silenceDiagnosis(
                    "AudioRecord is running but has returned only digital silence for " +
                        "${silentChunks / 10} s."
                )
            } else if (prev.failure == Failure.PRODUCING_SILENCE) {
                null
            } else {
                prev.detail
            },
        )
    }

    /**
     * Turns "no audio" into the specific reason for it.
     *
     * Worth the extra branch because the two cases need completely different things from the user.
     * Silence outside a call is usually something they can fix — the privacy toggle, another app
     * holding the mic. Silence *during* a call is the platform refusing to let a third-party app
     * hear call audio, which no setting and no version of this app can change. Telling someone to
     * check their privacy toggle when the real answer is "this handset will never do this" wastes
     * their time and makes the app look broken rather than blocked.
     *
     * The audio mode is read live rather than from cached state, because nothing refreshes routing
     * on the live-verification path — only the Phase 0 screen polls it.
     */
    private fun silenceDiagnosis(observation: String): String {
        val mode = audioManager.mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        return if (inCall) {
            "$observation A call is in progress (${audioModeName(mode)}), and this handset does " +
                "not pass call audio to third-party apps through the microphone. That is an " +
                "Android/OEM restriction, not a fault in this app and not something a permission " +
                "can unlock — the APIs that can read call audio are reserved for the system " +
                "dialler. Use Test Mode on a recording of the call instead."
        } else {
            "$observation No call is in progress (${audioModeName(mode)}), so this is more likely " +
                "the microphone privacy toggle, or another app holding the mic. Close other apps " +
                "that record audio and check the mic toggle in Quick Settings."
        }
    }

    private fun State.withRouting(): State {
        val speaker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.productName?.toString()
        } else {
            null
        }
        return copy(audioMode = audioManager.mode, speakerphoneOn = speaker, routedDevice = routed)
    }

    @SuppressLint("NewApi")
    private fun registerSilenceCallback(sessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val t = HandlerThread("vcd-mic-config").apply { start() }
        callbackThread = t
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
                val silenced = mine.isClientSilenced
                val prev = _state.value
                _state.value = prev.copy(
                    systemSilenced = silenced,
                    failure = if (silenced) Failure.SILENCED_BY_SYSTEM
                    else prev.failure.takeIf { it != Failure.SILENCED_BY_SYSTEM },
                    detail = if (silenced) {
                        silenceDiagnosis(
                            "Android reports this app's microphone input is being silenced by " +
                                "the system."
                        )
                    } else {
                        prev.detail
                    },
                )
            }
        }
        recordingCallback = cb
        audioManager.registerAudioRecordingCallback(cb, Handler(t.looper))
    }

    private fun unregisterSilenceCallback() {
        recordingCallback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        callbackThread?.quitSafely()
        callbackThread = null
    }

    private fun fail(failure: Failure, detail: String?) {
        Log.w(TAG, "mic capture failure: $failure — $detail")
        _state.value = _state.value.copy(running = false, failure = failure, detail = detail)
    }

    companion object {
        private const val TAG = "MicCapture"

        /** Below this peak amplitude a 100 ms chunk is treated as digital silence. */
        private const val DIGITAL_SILENCE = 1e-4f

        /** 100 ms chunks; 30 of them is 3 s of nothing at all. */
        private const val SILENT_CHUNKS_BEFORE_FAILURE = 30

        fun audioModeName(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
            else -> "mode $mode"
        }
    }
}

package com.trustmesh.app.vcd.voip

import android.util.Log
import com.trustmesh.app.vcd.audio.AudioConstants
import com.trustmesh.app.vcd.audio.AudioRingBuffer
import com.trustmesh.app.vcd.audio.AudioWindow
import com.trustmesh.app.vcd.audio.SincResampler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The seam between WebRTC and the existing ML pipeline, and the reason this whole module exists.
 *
 * WebRTC hands us the remote party's decoded PCM *before* it reaches the speaker, so the audio the
 * models see is the audio that came off the wire — not a room recording of a phone speaker. That
 * distinction is the entire point: routing remote audio out through the speaker and back in through
 * the microphone would add the room, the speaker's response, the microphone's response and the
 * device's own echo canceller to every sample, and every one of those is exactly the kind of
 * artefact an anti-spoofing model mistakes for evidence of synthesis.
 *
 * Nothing here decides anything. It converts formats and hands windows to the pipeline that already
 * exists; scoring, thresholds and fusion stay where they are.
 *
 * Audio never touches disk. The ring buffer is in-memory and [zeroize] scrubs it when the call ends.
 */
class RemoteAudioAdapter : AudioTrackSink {

    private val _diagnostics = MutableStateFlow(CallDiagnostics())
    val diagnostics: StateFlow<CallDiagnostics> = _diagnostics.asStateFlow()

    /**
     * Held at the *source* sample rate rather than at 16 kHz.
     *
     * Resampling each 10 ms callback in isolation would restart the sinc filter 100 times a second,
     * and the discontinuity at every block boundary is broadband noise — precisely the artefact the
     * anti-spoofing model is trained to notice. Buffering at the native rate and resampling one
     * whole analysis window at a time means the filter runs across continuous audio and the only
     * edges are the window edges, which exist anyway.
     */
    @Volatile private var ring: AudioRingBuffer? = null

    @Volatile private var sourceRate = 0
    @Volatile private var sourceChannels = 0

    /** Set once when the call is placed, so first-frame latency is measured against something. */
    @Volatile private var callStartedAtMs: Long = 0

    /** Scratch reused across callbacks; the callback runs at 100 Hz and should not allocate. */
    private var scratch = FloatArray(0)

    fun markCallStarted(atMs: Long) {
        callStartedAtMs = atMs
    }

    /**
     * Called by WebRTC on an internal audio thread, roughly every 10 ms.
     *
     * [audioData] is only valid for the duration of this call, so everything is copied out before
     * returning. Nothing slow belongs in here — a stall shows up as choppy audio for the user, not
     * just as a late score.
     */
    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        if (bitsPerSample != 16) {
            // Every WebRTC build in practice delivers signed 16-bit here. Refusing is better than
            // silently misreading the bytes and scoring noise.
            noteOnce("remote track delivered $bitsPerSample-bit audio; only 16-bit is handled")
            return
        }
        if (numberOfFrames <= 0 || numberOfChannels <= 0) return

        ensureBuffers(sampleRate, numberOfChannels)

        val mono = scratch
        val buffer = audioData.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0f
        var sumSquares = 0.0

        for (f in 0 until numberOfFrames) {
            var acc = 0
            for (c in 0 until numberOfChannels) {
                acc += buffer.short.toInt()
            }
            val v = (acc.toFloat() / numberOfChannels) * PCM16_SCALE
            mono[f] = v
            val a = if (v < 0f) -v else v
            if (a > peak) peak = a
            sumSquares += v.toDouble() * v
        }

        ring?.write(mono, 0, numberOfFrames)

        val rms = Math.sqrt(sumSquares / numberOfFrames).toFloat()
        val now = System.currentTimeMillis()
        val voiced = peak >= CallDiagnostics.DIGITAL_SILENCE

        _diagnostics.value = _diagnostics.value.let { d ->
            d.copy(
                trackAttached = true,
                sampleRate = sampleRate,
                channels = numberOfChannels,
                bitsPerSample = bitsPerSample,
                framesReceived = d.framesReceived + 1,
                voicedFrames = if (voiced) d.voicedFrames + 1 else d.voicedFrames,
                rms = rms,
                peak = peak,
                firstFrameAtMs = d.firstFrameAtMs ?: now,
                lastFrameAtMs = now,
                firstFrameLatencyMs = d.firstFrameLatencyMs
                    ?: (if (callStartedAtMs > 0) now - callStartedAtMs else null),
                silentFrameRun = if (voiced) 0 else d.silentFrameRun + 1,
            )
        }
    }

    /**
     * The most recent analysis window, resampled to the 16 kHz the models expect, or null if there
     * is not yet a full window of remote audio.
     *
     * Never pads. A short read padded with silence would produce a score from audio that was never
     * received, which is the one output this app must not produce.
     */
    fun latestWindow(startSampleIndex: Long): AudioWindow? {
        val buffer = ring ?: return null
        val rate = sourceRate
        if (rate <= 0) return null

        if (rate == AudioConstants.SAMPLE_RATE) {
            val samples = buffer.readLatest(AudioConstants.WINDOW_SAMPLES) ?: return null
            return AudioWindow(samples, startSampleIndex, AudioWindow.Provenance.REMOTE_VOIP)
        }

        val needed = sourceSamplesForWindow(rate)
        val raw = buffer.readLatest(needed) ?: return null
        val resampled = SincResampler.resample(raw, rate, AudioConstants.SAMPLE_RATE)

        // Rounding in the resampler can land a sample either side of the target width. The window
        // must be exact, so trim the oldest samples rather than padding the newest.
        val samples = when {
            resampled.size == AudioConstants.WINDOW_SAMPLES -> resampled
            resampled.size > AudioConstants.WINDOW_SAMPLES ->
                resampled.copyOfRange(resampled.size - AudioConstants.WINDOW_SAMPLES, resampled.size)
            else -> return null
        }
        return AudioWindow(samples, startSampleIndex, AudioWindow.Provenance.REMOTE_VOIP)
    }

    /** Source-sample cursor for the streaming STT tap, independent of the windowed ML reader. */
    @Volatile private var sttConsumed = 0L

    /**
     * Returns remote audio captured since the last call, resampled to 16 kHz, or null if none.
     *
     * This is the streaming tap speech-to-text needs: continuous, non-overlapping chunks fed to the
     * recogniser as they arrive, rather than the fixed overlapping windows the ML path uses. Feeding
     * Vosk overlapping 4 s blocks every 3 s made it lag several seconds and re-transcribe the same
     * words; a steady stream of new audio is what it is designed for.
     */
    fun drainForStt(): FloatArray? {
        val buffer = ring ?: return null
        val rate = sourceRate
        if (rate <= 0) return null
        val total = buffer.samplesWritten
        var newCount = total - sttConsumed
        if (newCount <= 0L) return null
        // If STT fell behind, take the newest bufferful and accept a gap rather than reading
        // samples that have already been overwritten.
        val cap = buffer.capacity.toLong()
        if (newCount > cap) newCount = cap
        val raw = buffer.readLatest(newCount.toInt()) ?: return null
        sttConsumed = total
        return if (rate == AudioConstants.SAMPLE_RATE) raw
        else SincResampler.resample(raw, rate, AudioConstants.SAMPLE_RATE)
    }

    /** How many source-rate samples are needed to yield one 16 kHz analysis window. */
    private fun sourceSamplesForWindow(rate: Int): Int {
        val exact = AudioConstants.WINDOW_SAMPLES.toLong() * rate / AudioConstants.SAMPLE_RATE
        // A couple of extra input samples so the resampler's floor() cannot land one short.
        return (exact + RESAMPLE_MARGIN).toInt()
    }

    fun recordWindowAnalysed(inferenceMs: Long, speechToResultMs: Long) {
        _diagnostics.value = _diagnostics.value.let {
            it.copy(
                windowsAnalysed = it.windowsAnalysed + 1,
                lastInferenceMs = inferenceMs,
                lastSpeechToResultMs = speechToResultMs,
            )
        }
    }

    private fun ensureBuffers(sampleRate: Int, channels: Int) {
        if (scratch.size < sampleRate) scratch = FloatArray(maxOf(sampleRate, 4096))
        if (ring != null && sourceRate == sampleRate && sourceChannels == channels) return

        // Format changed mid-call, or this is the first frame. Either way the old buffer holds
        // samples at a rate that no longer applies, so it is replaced rather than reinterpreted.
        val capacity = sourceSamplesForWindow(sampleRate) * RING_WINDOWS
        ring?.zeroize()
        ring = AudioRingBuffer(capacity)
        sttConsumed = 0L
        sourceRate = sampleRate
        sourceChannels = channels
        Log.i(TAG, "remote track format: $sampleRate Hz, $channels ch; ring holds $capacity samples")
    }

    private fun noteOnce(message: String) {
        if (_diagnostics.value.note == message) return
        Log.w(TAG, message)
        _diagnostics.value = _diagnostics.value.copy(note = message)
    }

    /** Scrubs buffered audio. Called when the call ends, whatever the reason. */
    fun zeroize() {
        ring?.zeroize()
        ring = null
        sourceRate = 0
        sourceChannels = 0
        sttConsumed = 0L
        java.util.Arrays.fill(scratch, 0f)
        _diagnostics.value = CallDiagnostics()
    }

    private companion object {
        const val TAG = "RemoteAudioAdapter"
        const val PCM16_SCALE = 1f / 32768f

        /** Two windows of headroom so a late reader still finds a complete window. */
        const val RING_WINDOWS = 2

        const val RESAMPLE_MARGIN = 8L
    }
}

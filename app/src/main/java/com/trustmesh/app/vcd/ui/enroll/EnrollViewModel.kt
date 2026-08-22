package com.trustmesh.app.vcd.ui.enroll

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.audio.AudioConstants
import com.trustmesh.app.vcd.audio.MicCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

/** The prompts an enrolling contact reads. Varied phonetics, ~15-20 s each when read naturally. */
val ENROLLMENT_PROMPTS = listOf(
    EnrollPrompt(
        title = "Introduce yourself",
        text = "Say your full name, then: “I am recording this myself, and I agree to my voice " +
            "being used to verify calls that claim to be from me.”",
    ),
    EnrollPrompt(
        title = "Count and describe",
        text = "Count slowly from one to fifteen. Then describe what you can see out of the " +
            "nearest window, in a couple of sentences.",
    ),
    EnrollPrompt(
        title = "Read this aloud",
        text = "“The early autumn rain arrived just before six, and by the time we reached the " +
            "junction the whole street had emptied out. She asked whether we should turn back, " +
            "but nobody answered.”",
    ),
)

data class EnrollPrompt(val title: String, val text: String)

class EnrollViewModel(private val app: VcdApp) : ViewModel() {

    enum class Stage { CONSENT, DETAILS, RECORDING, PROCESSING, DONE, FAILED }

    data class State(
        val stage: Stage = Stage.CONSENT,
        val name: String = "",
        val relationship: String = "",
        /** Optional shared-secret codeword/question a clone can't know. Empty = none. */
        val challenge: String = "",
        val consentAtEpochMs: Long? = null,
        val promptIndex: Int = 0,
        val recording: Boolean = false,
        val recordedSeconds: Float = 0f,
        val rms: Float = 0f,
        val peak: Float = 0f,
        val micFailure: String? = null,
        val error: String? = null,
        val savedContactId: Long? = null,
        /** What the anti-spoofing model made of this person's own genuine recording. */
        val baselineSynthetic: Float? = null,
    ) {
        val enoughForMinimum: Boolean get() = recordedSeconds >= MIN_SECONDS
        val atMaximum: Boolean get() = recordedSeconds >= MAX_SECONDS
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mic = MicCapture(app)

    /**
     * Enrolment audio lives here and nowhere else — never a file, never a cache, never a
     * MediaRecorder output path. It is handed to the encoder once and then overwritten with zeros.
     */
    private var buffer = FloatArray(AudioConstants.SAMPLE_RATE * MAX_SECONDS.toInt())
    private var written = 0

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setRelationship(v: String) = _state.update { it.copy(relationship = v) }
    fun setChallenge(v: String) = _state.update { it.copy(challenge = v) }

    fun acceptConsent() = _state.update {
        it.copy(stage = Stage.DETAILS, consentAtEpochMs = System.currentTimeMillis())
    }

    fun toDetailsDone() = _state.update { it.copy(stage = Stage.RECORDING) }

    fun nextPrompt() = _state.update {
        it.copy(promptIndex = (it.promptIndex + 1).coerceAtMost(ENROLLMENT_PROMPTS.lastIndex))
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording() {
        if (_state.value.recording) return
        _state.update { it.copy(micFailure = null) }

        val ok = mic.start { samples, length ->
            val room = buffer.size - written
            if (room > 0) {
                val take = minOf(room, length)
                System.arraycopy(samples, 0, buffer, written, take)
                written += take
                _state.update {
                    it.copy(recordedSeconds = written.toFloat() / AudioConstants.SAMPLE_RATE)
                }
            }
            // Hitting the cap must not tear down AudioRecord from inside its own read callback —
            // stop() releases the object the calling thread is still driving. Hand it off instead.
            if (written >= buffer.size) viewModelScope.launch { stopRecording() }
        }

        if (!ok) {
            _state.update {
                it.copy(
                    recording = false,
                    micFailure = mic.state.value.detail
                        ?: "The microphone could not be opened for enrolment.",
                )
            }
            return
        }

        _state.update { it.copy(recording = true) }
        viewModelScope.launch {
            mic.state.collect { m ->
                _state.update {
                    it.copy(
                        rms = m.rms,
                        peak = m.peak,
                        micFailure = if (m.failure != null) {
                            m.detail ?: "Microphone capture failed: ${m.failure}"
                        } else {
                            it.micFailure
                        },
                    )
                }
                if (m.failure != null) stopRecording()
            }
        }
    }

    fun stopRecording() {
        mic.stop()
        _state.update { it.copy(recording = false, rms = 0f, peak = 0f) }
    }

    /**
     * FR-VOICE-ENR-3: derive the embedding, then destroy the audio.
     *
     * The zero-fill is not decorative. Until it runs, roughly a minute of someone's speech is
     * sitting in the heap where a later heap dump or a memory-scraping bug could reach it. The
     * app promised to keep a vector, not a recording, and this is the line that makes that true.
     */
    fun finishEnrolment() {
        val s = _state.value
        if (!s.enoughForMinimum) return
        stopRecording()
        _state.update { it.copy(stage = Stage.PROCESSING) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val pipeline = app.models.pipelineOrNull()
                        ?: error(
                            "The on-device models are not bundled, so no voiceprint can be " +
                                "created. Run tools/convert_models.py and rebuild."
                        )
                    val audio = buffer.copyOfRange(0, written)
                    // One recording, several voiceprints: the microphone condition plus what the
                    // same voice would look like arriving over a call. A microphone print scores
                    // 0.7655 against narrowband call audio from the same speaker, against 0.9766
                    // for a channel-matched one, so storing only the first is what made a
                    // person's own voice come back as "not confirmed".
                    //
                    // Each variant carries its own anti-spoofing baseline, measured on the one
                    // recording the app knows for certain is genuine because the person just
                    // made it after consenting.
                    val variants = pipeline.enrolVariants(audio)
                    Arrays.fill(audio, 0f)
                    variants
                }
            }

            // Scrub the capture buffer whether or not the embedding succeeded.
            Arrays.fill(buffer, 0f)
            val seconds = written.toFloat() / AudioConstants.SAMPLE_RATE
            written = 0

            result.onSuccess { variants ->
                val baseline = variants.firstOrNull()?.baselineSynthetic
                Log.i(
                    TAG,
                    "enrolled ${variants.size} variants: " +
                        variants.joinToString { "${it.label}=${it.baselineSynthetic}" },
                )
                runCatching {
                    app.contacts.enroll(
                        name = s.name,
                        relationship = s.relationship.ifBlank { null },
                        voiceprints = variants,
                        enrolledSeconds = seconds,
                        consentAcknowledgedAtEpochMs = s.consentAtEpochMs ?: 0L,
                        challenge = s.challenge.ifBlank { null },
                    )
                }.onSuccess { id ->
                    _state.update {
                        it.copy(stage = Stage.DONE, savedContactId = id, baselineSynthetic = baseline)
                    }
                }.onFailure { t ->
                    Log.e(TAG, "storing voiceprint failed", t)
                    _state.update {
                        it.copy(stage = Stage.FAILED, error = "Could not save the voiceprint: ${t.message}")
                    }
                }
            }.onFailure { t ->
                Log.e(TAG, "embedding failed", t)
                _state.update {
                    it.copy(stage = Stage.FAILED, error = t.message ?: "Voiceprint extraction failed.")
                }
            }
        }
    }

    fun discardAndReset() {
        stopRecording()
        Arrays.fill(buffer, 0f)
        written = 0
        _state.value = State()
    }

    override fun onCleared() {
        stopRecording()
        Arrays.fill(buffer, 0f)
        buffer = FloatArray(0)
        super.onCleared()
    }

    private inline fun MutableStateFlow<State>.update(block: (State) -> State) {
        value = block(value)
    }

    companion object {
        private const val TAG = "EnrollViewModel"

        /** FR-VOICE-ENR-2: 30-60 s of speech. */
        const val MIN_SECONDS = 30f
        const val MAX_SECONDS = 60f
    }
}

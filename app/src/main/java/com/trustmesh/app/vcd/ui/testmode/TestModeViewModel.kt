package com.trustmesh.app.vcd.ui.testmode

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trustmesh.app.vcd.VcdApp
import com.trustmesh.app.vcd.audio.AudioConstants
import com.trustmesh.app.vcd.audio.AudioFileDecoder
import com.trustmesh.app.vcd.audio.WindowSlicer
import com.trustmesh.app.vcd.data.EnrolledContact
import com.trustmesh.app.vcd.pipeline.FusionThresholds
import com.trustmesh.app.vcd.pipeline.Level
import com.trustmesh.app.vcd.pipeline.SessionScores
import com.trustmesh.app.vcd.pipeline.VerificationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

/**
 * Test Mode.
 *
 * This is the load-bearing path, not a convenience: it is the only way to get a repeatable,
 * side-by-side comparison of a real clip and a cloned clip of the same voice, and it works with no
 * microphone permission, no live call, and no second device. It calls exactly the same
 * [VerificationPipeline.analyze] the live service calls, on windows of exactly the same width, so
 * a result here means something about the live path rather than about a parallel implementation.
 */
class TestModeViewModel(private val app: VcdApp) : ViewModel() {

    data class Run(
        val fileName: String,
        val contactName: String?,
        val durationSeconds: Float,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val mimeType: String?,
        val scores: SessionScores,
        val elapsedMillis: Long,
        /** The selected contact's enrolment baseline, so the run can say what its scores are worth. */
        val baselineSynthetic: Float? = null,
    ) {
        /** Median is used for the headline number: one odd window should not move it. */
        val medianSimilarity: Float? get() = median(scores.history.mapNotNull { it.verdict.voiceSimilarity })
        val medianSynthetic: Float? get() = median(scores.history.mapNotNull { it.verdict.syntheticProbability })

        private fun median(values: List<Float>): Float? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }

    data class State(
        val contacts: List<EnrolledContact> = emptyList(),
        val selectedContactId: Long? = null,
        val pickedUri: Uri? = null,
        val pickedName: String? = null,
        val running: Boolean = false,
        val progress: Float = 0f,
        val error: String? = null,
        val current: Run? = null,
        /** Kept so the real-clip result stays on screen while the cloned clip is run. */
        val previous: Run? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val thresholds = FusionThresholds.PROVISIONAL

    init {
        viewModelScope.launch {
            app.contacts.observeContacts().collect { list ->
                _state.value = _state.value.copy(
                    contacts = list,
                    selectedContactId = _state.value.selectedContactId
                        ?: list.firstOrNull { it.usableWithCurrentModel }?.id,
                )
            }
        }
    }

    fun selectContact(id: Long?) {
        _state.value = _state.value.copy(selectedContactId = id)
    }

    fun pickFile(context: Context, uri: Uri) {
        _state.value = _state.value.copy(
            pickedUri = uri,
            pickedName = displayName(context, uri),
            error = null,
        )
    }

    fun clearComparison() {
        _state.value = _state.value.copy(current = null, previous = null, error = null)
    }

    fun run(context: Context) {
        val s = _state.value
        val uri = s.pickedUri ?: return
        if (s.running) return

        _state.value = s.copy(running = true, progress = 0f, error = null)

        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val pipeline = app.models.pipelineOrNull()
                        ?: error(
                            "The on-device models are not bundled, so nothing can be scored. " +
                                "Run tools/convert_models.py and rebuild the app."
                        )

                    val contactName = s.selectedContactId?.let { id ->
                        s.contacts.firstOrNull { it.id == id }?.name
                    }
                    val voiceprints = s.selectedContactId?.let { app.contacts.loadVoiceprints(it) }
                        ?: emptyList()
                    val baseline = s.contacts.firstOrNull { it.id == s.selectedContactId }
                        ?.baselineSynthetic
                    if (s.selectedContactId != null && voiceprints.isEmpty()) {
                        error(
                            "The voiceprint for ${contactName ?: "that contact"} could not be " +
                                "loaded, so this clip cannot be compared against them."
                        )
                    }

                    val decoded = AudioFileDecoder.decode(context, uri)
                    val windows = WindowSlicer.slice(decoded.samples)
                    if (windows.isEmpty()) {
                        error(
                            "That clip is ${"%.1f".format(decoded.durationSeconds)} s long. " +
                                "At least ${"%.1f".format(WindowSlicer.minimumClipSeconds)} s is " +
                                "needed for one analysis window, and padding it out with silence " +
                                "would mean scoring audio that was never in the file."
                        )
                    }

                    var scores = SessionScores()
                    windows.forEachIndexed { index, window ->
                        val analysis = pipeline.analyze(
                            window = window,
                            voiceprints = voiceprints,
                            contactName = contactName,
                            thresholds = thresholds,
                        )
                        scores = scores.accept(analysis, thresholds, baseline)
                        _state.value = _state.value.copy(
                            progress = (index + 1f) / windows.size
                        )
                    }

                    // The decoded clip is scrubbed as soon as it has been scored — the same rule
                    // the live path follows, applied to the file path so Test Mode does not become
                    // the loophole that leaves audio lying around.
                    Arrays.fill(decoded.samples, 0f)

                    Run(
                        fileName = s.pickedName ?: "clip",
                        contactName = contactName,
                        durationSeconds = windows.size * AudioConstants.FILE_HOP_SAMPLES /
                            AudioConstants.SAMPLE_RATE.toFloat(),
                        sourceSampleRate = decoded.sourceSampleRate,
                        sourceChannels = decoded.sourceChannels,
                        mimeType = decoded.mimeType,
                        scores = scores,
                        elapsedMillis = System.currentTimeMillis() - started,
                        baselineSynthetic = baseline,
                    )
                }
            }

            outcome.onSuccess { run ->
                _state.value = _state.value.copy(
                    running = false,
                    progress = 1f,
                    current = run,
                    previous = _state.value.current,
                )
            }.onFailure { t ->
                Log.e(TAG, "test mode run failed", t)
                _state.value = _state.value.copy(
                    running = false,
                    error = t.message ?: "That file could not be analysed.",
                )
            }
        }
    }

    /** Raw per-window scores as CSV, for calibrating thresholds outside the app. */
    fun resultsAsCsv(run: Run): String = buildString {
        appendLine("window_start_s,rms,voice_similarity,synthetic_probability,level,reason,inference_ms")
        run.scores.history.forEach { a ->
            append("%.2f".format(a.startSeconds)).append(',')
            append("%.5f".format(a.rms)).append(',')
            append(a.verdict.voiceSimilarity?.let { "%.5f".format(it) } ?: "").append(',')
            append(a.verdict.syntheticProbability?.let { "%.5f".format(it) } ?: "").append(',')
            append(a.verdict.level.name).append(',')
            append(a.verdict.reason.name).append(',')
            appendLine(a.inferenceMillis)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "clip"
    }

    companion object {
        private const val TAG = "TestModeViewModel"

        fun levelSummary(scores: SessionScores): Level = scores.peakLevel
    }
}

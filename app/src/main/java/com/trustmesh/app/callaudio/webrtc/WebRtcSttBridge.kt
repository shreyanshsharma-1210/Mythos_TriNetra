package com.trustmesh.app.callaudio.webrtc

import android.content.Context
import android.util.Log
import com.trustmesh.app.vcd.audio.AudioConstants
import com.trustmesh.app.vcd.voip.RemoteAudioAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Bridges the WebRTC [RemoteAudioAdapter] to Vosk on-device STT.
 *
 * Every [POLL_INTERVAL_MS] we pull the latest 3-second audio window from the ring buffer
 * (which is already 16 kHz mono — exactly what Vosk expects), convert Float → Int16 PCM,
 * and feed it to the recognizer. Vosk returns a JSON object with a "text" field when it
 * has a final hypothesis, or a "partial" field mid-utterance.
 *
 * Hindi model:   assets/models/vosk-model-hi     (~50 MB)
 * English model: assets/models/vosk-model-en-in  (~40 MB)
 *
 * Audio never touches disk. The byte scratch buffers are reused across calls.
 */
class WebRtcSttBridge(
    private val context: Context,
    private val adapter: RemoteAudioAdapter,
    private val scope: CoroutineScope,
) {
    private val _chunks = MutableSharedFlow<SttChunk>(replay = 1)
    val chunks: SharedFlow<SttChunk> = _chunks.asSharedFlow()

    private var pollJob: Job? = null
    private var hiModel: Model? = null
    private var enModel: Model? = null
    private var hiRecognizer: Recognizer? = null
    private var enRecognizer: Recognizer? = null

    // Scratch buffer reused every poll cycle — avoids per-call allocation on the hot path
    private var pcmScratch = ShortArray(WINDOW_SAMPLES)

    data class SttChunk(
        val text: String,
        val isFinal: Boolean,
        val languageCode: String,     // "hi" or "en"
        val timestampMs: Long = System.currentTimeMillis()
    )

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            loadModels()
            if (hiRecognizer == null && enRecognizer == null) {
                Log.w(TAG, "No Vosk models available — STT disabled")
                return@launch
            }
            Log.i(TAG, "STT bridge started (hi=${hiModel != null}, en=${enModel != null})")
            // Use LIVE_HOP_SAMPLES (48000 = 3 s) as both the polling interval and the window advance.
            // latestWindow() takes an absolute sample-index as a hint; we pass the next expected
            // start to avoid reading the same samples twice.
            var nextStartSample = 0L
            while (isActive) {
                val window = adapter.latestWindow(nextStartSample)
                if (window != null) {
                    processWindow(window.samples)
                    nextStartSample = window.startSampleIndex + AudioConstants.LIVE_HOP_SAMPLES
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        hiRecognizer?.close()
        enRecognizer?.close()
        hiModel?.close()
        enModel?.close()
        hiRecognizer = null
        enRecognizer = null
        hiModel = null
        enModel = null
    }

    private fun loadModels() {
        // Copy model from assets to cache dir if not already there, then load
        hiModel = tryLoadModel("vosk-model-hi")
        enModel = tryLoadModel("vosk-model-en-in")
        hiRecognizer = hiModel?.let { Recognizer(it, SAMPLE_RATE_F) }
        enRecognizer = enModel?.let { Recognizer(it, SAMPLE_RATE_F) }
    }

    private fun tryLoadModel(assetDir: String): Model? = runCatching {
        val dest = File(context.filesDir, assetDir)
        if (!dest.exists()) {
            copyAssetDir(assetDir, dest)
        }
        if (!dest.exists()) return@runCatching null
        Model(dest.absolutePath)
    }.onFailure { Log.w(TAG, "Could not load Vosk model $assetDir: ${it.message}") }
        .getOrNull()

    /**
     * Copies an asset directory tree to the app's internal files dir.
     * Vosk requires the model on the regular filesystem, not inside the APK.
     */
    private fun copyAssetDir(assetDir: String, dest: File) {
        val assets = context.assets
        val children = runCatching { assets.list(assetDir) }.getOrNull() ?: return
        if (children.isEmpty()) {
            // It's a file
            dest.parentFile?.mkdirs()
            assets.open(assetDir).use { src -> dest.outputStream().use { it.write(src.readBytes()) } }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAssetDir("$assetDir/$child", File(dest, child))
            }
        }
    }

    private fun processWindow(floatSamples: FloatArray) {
        if (pcmScratch.size < floatSamples.size) pcmScratch = ShortArray(floatSamples.size)
        // Convert normalized Float [-1,1] → Int16 PCM
        for (i in floatSamples.indices) {
            pcmScratch[i] = (floatSamples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val bytes = ShortArray(floatSamples.size) { pcmScratch[it] }.let { shorts ->
            ByteArray(shorts.size * 2).also { buf ->
                for (i in shorts.indices) {
                    buf[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
                    buf[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
                }
            }
        }

        // Try Hindi first, then English — emit whichever gives a non-blank result
        val hiResult = hiRecognizer?.let { rec ->
            val accepted = rec.acceptWaveForm(bytes, bytes.size)
            if (accepted) parseResult(rec.result, isFinal = true)
            else parsePartial(rec.partialResult)
        }

        val enResult = enRecognizer?.let { rec ->
            val accepted = rec.acceptWaveForm(bytes, bytes.size)
            if (accepted) parseResult(rec.result, isFinal = true)
            else parsePartial(rec.partialResult)
        }

        // Prefer Hindi if it has text; fallback to English
        val best = when {
            !hiResult?.text.isNullOrBlank() -> hiResult!!.copy(languageCode = "hi")
            !enResult?.text.isNullOrBlank() -> enResult!!.copy(languageCode = "en")
            else -> null
        }
        if (best != null) {
            scope.launch { _chunks.emit(best) }
        }
    }

    private fun parseResult(json: String, isFinal: Boolean): SttChunk? = runCatching {
        val text = JSONObject(json).optString("text", "").trim()
        if (text.isBlank()) null else SttChunk(text, isFinal, "")
    }.getOrNull()

    private fun parsePartial(json: String): SttChunk? = runCatching {
        val text = JSONObject(json).optString("partial", "").trim()
        if (text.isBlank()) null else SttChunk(text, isFinal = false, languageCode = "")
    }.getOrNull()

    companion object {
        private const val TAG = "WebRtcSttBridge"
        private const val POLL_INTERVAL_MS = 3000L
        private const val SAMPLE_RATE_F = 16000f
        private val WINDOW_SAMPLES = AudioConstants.WINDOW_SAMPLES
    }
}

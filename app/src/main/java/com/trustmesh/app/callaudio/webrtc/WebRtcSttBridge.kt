package com.trustmesh.app.callaudio.webrtc

import android.content.Context
import android.util.Log
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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Bridges the WebRTC [RemoteAudioAdapter] to Vosk on-device STT.
 *
 * Feeds Vosk a *continuous stream* of the remote party's 16 kHz audio, in small chunks, the way a
 * streaming recogniser is designed to be driven — and drives exactly one language model.
 *
 * An earlier version pulled overlapping 4 s windows every 3 s and ran the Hindi and English models
 * on the same audio, keeping "whichever produced text". That was slow (a several-second lag) and
 * inaccurate (the wrong-language model transcribed speech into confident gibberish that often won,
 * and re-fed overlapping audio corrupted the recogniser's running context). This version pulls only
 * new audio [POLL_INTERVAL_MS] apart via [RemoteAudioAdapter.drainForStt] and feeds one recogniser,
 * emitting a running partial for a live feel and a final when the recogniser detects an endpoint.
 *
 * Models live in assets/models/. The active one is chosen by [ACTIVE_LANG]; both are bundled so the
 * choice is a one-line change. English-India is the default because it handles Indian-accented
 * English and common code-mixing far better than feeding English to the Hindi model did.
 *
 * Audio never touches disk beyond the one-time model copy Vosk requires out of the APK.
 */
class WebRtcSttBridge(
    private val context: Context,
    private val adapter: RemoteAudioAdapter,
    private val scope: CoroutineScope,
) {
    private val _chunks = MutableSharedFlow<SttChunk>(replay = 1)
    val chunks: SharedFlow<SttChunk> = _chunks.asSharedFlow()

    private var pollJob: Job? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    private var byteScratch = ByteArray(0)
    private var lastPartial = ""

    data class SttChunk(
        val text: String,
        val isFinal: Boolean,
        val languageCode: String,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            val rec = loadRecognizer()
            if (rec == null) {
                Log.w(TAG, "Vosk model '$ACTIVE_MODEL' unavailable — STT disabled")
                return@launch
            }
            recognizer = rec
            Log.i(TAG, "STT bridge started (lang=$ACTIVE_LANG, model=$ACTIVE_MODEL)")

            while (isActive) {
                val fresh = adapter.drainForStt()
                if (fresh != null && fresh.isNotEmpty()) {
                    feed(rec, fresh)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        lastPartial = ""
    }

    private fun loadRecognizer(): Recognizer? {
        model = tryLoadModel(ACTIVE_MODEL) ?: return null
        return runCatching { Recognizer(model, SAMPLE_RATE_F) }
            .onFailure { Log.w(TAG, "Recognizer init failed: ${it.message}") }
            .getOrNull()
    }

    private fun feed(rec: Recognizer, floatSamples: FloatArray) {
        val needed = floatSamples.size * 2
        if (byteScratch.size < needed) byteScratch = ByteArray(needed)
        val bytes = byteScratch
        for (i in floatSamples.indices) {
            val s = (floatSamples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }

        val endpointed = rec.acceptWaveForm(bytes, needed)
        if (endpointed) {
            val text = jsonField(rec.result, "text")
            lastPartial = ""
            if (text.isNotBlank()) emit(text, isFinal = true)
        } else {
            val partial = jsonField(rec.partialResult, "partial")
            // Only emit when the partial actually grew, so the UI updates smoothly instead of
            // re-emitting the same string every poll.
            if (partial.isNotBlank() && partial != lastPartial) {
                lastPartial = partial
                emit(partial, isFinal = false)
            }
        }
    }

    private fun emit(text: String, isFinal: Boolean) {
        scope.launch { _chunks.emit(SttChunk(text, isFinal, ACTIVE_LANG)) }
    }

    private fun jsonField(json: String, field: String): String =
        runCatching { JSONObject(json).optString(field, "").trim() }.getOrDefault("")

    private fun tryLoadModel(assetDir: String): Model? = runCatching {
        val dest = File(context.filesDir, assetDir)
        if (!dest.exists()) copyAssetDir(assetDir, dest)
        if (!dest.exists()) return@runCatching null
        Model(dest.absolutePath)
    }.onFailure { Log.w(TAG, "Could not load Vosk model $assetDir: ${it.message}") }
        .getOrNull()

    /** Copies an asset directory tree to internal storage; Vosk needs the model on the filesystem. */
    private fun copyAssetDir(assetDir: String, dest: File) {
        val assets = context.assets
        val children = runCatching { assets.list(assetDir) }.getOrNull() ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetDir).use { src -> dest.outputStream().use { src.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (child in children) copyAssetDir("$assetDir/$child", File(dest, child))
        }
    }

    companion object {
        private const val TAG = "WebRtcSttBridge"

        /** ~0.3 s of new audio per feed: low latency, well inside real-time on-device. */
        private const val POLL_INTERVAL_MS = 300L
        private const val SAMPLE_RATE_F = 16000f

        /**
         * Active recognition language. Both models are bundled under assets/models/, so switching to
         * Hindi is a one-line change to these two constants. Running both at once is deliberately not
         * done — it halved accuracy by letting the wrong-language model win.
         */
        private const val ACTIVE_LANG = "en"
        private const val ACTIVE_MODEL = "models/vosk-model-en-in"
    }
}

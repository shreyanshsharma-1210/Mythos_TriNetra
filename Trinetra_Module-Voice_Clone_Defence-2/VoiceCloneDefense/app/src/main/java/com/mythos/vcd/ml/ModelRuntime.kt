package com.mythos.vcd.ml

import android.content.Context
import android.util.Log
import com.mythos.vcd.pipeline.VerificationPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Metadata written by tools/convert_models.py at conversion time.
 *
 * The parity numbers here are copied from an actual measurement run, not typed in by hand. If the
 * conversion script has not been run, the manifest is absent and the app says the models are
 * missing — it does not fall back to a stub that produces plausible-looking scores.
 */
data class ModelManifest(
    val modelId: String,
    val speakerEncoder: String,
    val spoofDetector: String,
    val quantization: String,
    val convertedAtUtc: String,
    /** Measured mean cosine between the exported and PyTorch embeddings, null if not measured. */
    val speakerParityCosine: Double?,
    /** Measured mean absolute difference in synthetic_probability, or null if not measured. */
    val spoofParityMaxAbsDelta: Double?,
    val notes: String?,
) {
    companion object {
        fun parse(json: String): ModelManifest {
            val o = JSONObject(json)
            return ModelManifest(
                modelId = o.getString("model_id"),
                speakerEncoder = o.optString("speaker_encoder", "unknown"),
                spoofDetector = o.optString("spoof_detector", "unknown"),
                quantization = o.optString("quantization", "unknown"),
                convertedAtUtc = o.optString("converted_at_utc", "unknown"),
                speakerParityCosine = if (o.isNull("speaker_parity_cosine")) null
                else o.optDouble("speaker_parity_cosine"),
                spoofParityMaxAbsDelta = if (o.isNull("spoof_parity_max_abs_delta")) null
                else o.optDouble("spoof_parity_max_abs_delta"),
                notes = o.optString("notes").takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Owns the two interpreters and hands out the shared pipeline.
 *
 * Loading is lazy and failure is a first-class state rather than an exception that vanishes into a
 * log. Everything downstream can then ask "are the models actually here" before offering to score
 * anything, which is what keeps the app from showing a confident number it did not compute.
 */
class ModelRuntime(private val context: Context) {

    sealed interface Status {
        data object NotLoaded : Status
        data class Ready(val manifest: ModelManifest?) : Status
        data class Unavailable(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.NotLoaded)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var pipeline: VerificationPipeline? = null
    @Volatile private var manifest: ModelManifest? = null
    @Volatile private var manifestLoaded = false
    private val manifestLock = Any()

    /**
     * Identifier stored with every voiceprint so prints are never compared across encoders.
     *
     * Read from the bundled manifest on demand, deliberately independent of whether the pipeline
     * has been built. It used to be a side effect of loading the models, which meant that before
     * anything ran an inference this returned the placeholder id — so a freshly enrolled contact
     * was reported as "enrolled with an older model", and worse, loadVoiceprints refused to
     * decrypt a perfectly good print. Verification then failed silently on a valid contact, which
     * is the exact class of quiet wrongness this app is supposed to avoid.
     */
    fun modelId(): String = manifestOrNull?.modelId ?: UNCONVERTED_MODEL_ID

    val manifestOrNull: ModelManifest?
        get() {
            if (manifestLoaded) return manifest
            synchronized(manifestLock) {
                if (!manifestLoaded) {
                    manifest = readManifest()
                    manifestLoaded = true
                }
            }
            return manifest
        }

    /** Returns the shared pipeline, loading it on first use. Null means the models are unusable. */
    @Synchronized
    fun pipelineOrNull(): VerificationPipeline? {
        pipeline?.let { return it }
        return try {
            // Touch the manifest so Status.Ready carries it; the read itself is cached.
            val loaded = manifestOrNull
            val built = VerificationPipeline(
                embedder = OrtSpeakerEmbedder(context),
                spoofDetector = OrtSpoofDetector(context),
            )
            pipeline = built
            _status.value = Status.Ready(loaded)
            built
        } catch (e: ModelUnavailableException) {
            Log.e(TAG, "model load failed", e)
            _status.value = Status.Unavailable(e.message ?: "Models could not be loaded.")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "unexpected model load failure", t)
            _status.value = Status.Unavailable(
                "Models could not be loaded: ${t.message ?: t::class.java.simpleName}"
            )
            null
        }
    }

    /** Cheap check used by the home screen; does not build interpreters. */
    fun assetsPresent(): Boolean =
        ModelAssets.exists(context, ModelAssets.SPEAKER_ENCODER) &&
            ModelAssets.exists(context, ModelAssets.SPOOF_DETECTOR)

    private fun readManifest(): ModelManifest? = try {
        context.assets.open(ModelAssets.MANIFEST).bufferedReader().use {
            ModelManifest.parse(it.readText())
        }
    } catch (t: Throwable) {
        Log.w(TAG, "no model manifest bundled", t)
        null
    }

    @Synchronized
    fun close() {
        pipeline?.close()
        pipeline = null
        _status.value = Status.NotLoaded
    }

    companion object {
        private const val TAG = "ModelRuntime"

        /**
         * Used only when no manifest is bundled. Voiceprints tagged with this id will refuse to
         * compare against a later, properly-converted build — which is the correct outcome.
         */
        const val UNCONVERTED_MODEL_ID = "unconverted-dev"
    }
}

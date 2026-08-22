package com.mythos.vcd.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * Asset paths for the two bundled models.
 *
 * These are ONNX rather than TFLite. The conversion chain to TFLite produced files that would not
 * load — see the header of tools/convert_models.py for the exact failures — while the ONNX exports
 * match the PyTorch checkpoints exactly. Inference is still entirely on-device; the runtime is
 * onnxruntime-android and the app holds no INTERNET permission.
 */
object ModelAssets {
    const val SPEAKER_ENCODER = "models/speaker_encoder.onnx"
    const val SPOOF_DETECTOR = "models/spoof_detector.onnx"
    const val MANIFEST = "models/manifest.json"

    fun exists(context: Context, path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (_: Exception) {
        false
    }
}

private fun readModel(context: Context, assetPath: String): ByteArray = try {
    context.assets.open(assetPath).use { it.readBytes() }
} catch (e: Exception) {
    throw ModelUnavailableException(
        "Model asset '$assetPath' is missing. Run tools/convert_models.py and rebuild the app.",
        e,
    )
}

private fun sessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
    // Two threads is enough for both graphs and leaves headroom for the audio thread. The capture
    // callback missing its deadline costs real samples, which matters more than shaving
    // milliseconds off an inference that already fits inside a 3 s budget.
    setIntraOpNumThreads(2)
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
}

/**
 * Reads a `[1, N]` float output tensor, whatever concrete Java type ORT hands back.
 *
 * ORT's `value` is `Array<FloatArray>` for a 2-D float tensor, but relying on that cast alone
 * makes an opset or version change surface as a ClassCastException deep inside a scoring loop.
 * Going through the FloatBuffer is version-stable.
 */
private fun OnnxTensor.readRow(expected: Int): FloatArray {
    val buffer = floatBuffer
    val out = FloatArray(expected)
    require(buffer.remaining() >= expected) {
        "model returned ${buffer.remaining()} values, expected $expected"
    }
    buffer.get(out, 0, expected)
    return out
}

/**
 * Speaker embedding via the converted Resemblyzer encoder.
 *
 * The exported graph contains the mel front end, so this class only ever hands it a waveform.
 * Input  : float32 [1, inputSamples]
 * Output : float32 [1, embeddingDim], already L2-normalised inside the graph
 */
class OrtSpeakerEmbedder(context: Context) : SpeakerEmbedder {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = try {
        env.createSession(readModel(context, ModelAssets.SPEAKER_ENCODER), sessionOptions())
    } catch (e: ModelUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw ModelUnavailableException("Could not initialise the speaker encoder", e)
    }

    private val inputName: String = session.inputNames.first()

    override val inputSamples: Int =
        session.inputInfo.values.first().info.let { info ->
            (info as ai.onnxruntime.TensorInfo).shape.last().toInt()
        }

    override val embeddingDim: Int =
        session.outputInfo.values.first().info.let { info ->
            (info as ai.onnxruntime.TensorInfo).shape.last().toInt()
        }

    init {
        Log.i(TAG, "speaker encoder ready: input=$inputSamples samples, output=$embeddingDim dims")
    }

    @Synchronized
    override fun embedPartial(waveform: FloatArray): FloatArray {
        require(waveform.size == inputSamples) {
            "speaker encoder expects $inputSamples samples, got ${waveform.size}"
        }
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(waveform),
            longArrayOf(1, inputSamples.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val raw = (result[0] as OnnxTensor).readRow(embeddingDim)
                // The graph normalises already. Doing it again is cheap and keeps this class
                // honest about its contract even if a future export drops the final layer.
                return Vec.l2Normalize(raw)
            }
        }
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val TAG = "OrtSpeakerEmbedder"
    }
}

/**
 * Anti-spoofing via the converted AASIST checkpoint.
 *
 * Input  : float32 [1, inputSamples] raw waveform
 * Output : float32 [1, 2] logits ordered [spoof, bonafide], matching the upstream AASIST code,
 *          which reads index 1 as the bonafide score. synthetic_probability is therefore
 *          softmax(logits)[0]. The conversion script verifies that ordering numerically against
 *          genuine speech rather than trusting this comment.
 */
class OrtSpoofDetector(context: Context) : SpoofDetector {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = try {
        env.createSession(readModel(context, ModelAssets.SPOOF_DETECTOR), sessionOptions())
    } catch (e: ModelUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw ModelUnavailableException("Could not initialise the anti-spoofing model", e)
    }

    private val inputName: String = session.inputNames.first()

    override val inputSamples: Int =
        session.inputInfo.values.first().info.let { info ->
            (info as ai.onnxruntime.TensorInfo).shape.last().toInt()
        }

    private val outputClasses: Int =
        session.outputInfo.values.first().info.let { info ->
            (info as ai.onnxruntime.TensorInfo).shape.last().toInt()
        }

    init {
        require(outputClasses == 2) {
            "anti-spoofing model should emit 2 logits, got $outputClasses"
        }
        Log.i(TAG, "anti-spoofing model ready: input=$inputSamples samples")
    }

    @Synchronized
    override fun syntheticProbability(waveform: FloatArray): Float {
        require(waveform.size == inputSamples) {
            "anti-spoofing model expects $inputSamples samples, got ${waveform.size}"
        }
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(waveform),
            longArrayOf(1, inputSamples.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val logits = (result[0] as OnnxTensor).readRow(2)
                return softmaxFirst(logits[0], logits[1])
            }
        }
    }

    /** Numerically stable two-class softmax, returning P(first class). */
    private fun softmaxFirst(a: Float, b: Float): Float {
        val m = maxOf(a, b)
        val ea = exp((a - m).toDouble())
        val eb = exp((b - m).toDouble())
        return (ea / (ea + eb)).toFloat().coerceIn(0f, 1f)
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val TAG = "OrtSpoofDetector"
    }
}

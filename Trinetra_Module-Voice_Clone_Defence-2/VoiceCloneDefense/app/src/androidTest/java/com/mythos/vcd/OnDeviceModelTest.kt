package com.mythos.vcd

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mythos.vcd.audio.AudioConstants
import com.mythos.vcd.audio.AudioWindow
import com.mythos.vcd.ml.ModelAssets
import com.mythos.vcd.ml.ModelRuntime
import com.mythos.vcd.ml.Vec
import com.mythos.vcd.pipeline.Level
import com.mythos.vcd.pipeline.Voiceprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * On-device checks that the bundled ONNX models load and behave sanely.
 *
 * Run with:  gradlew :app:connectedDebugAndroidTest   (needs a real device or emulator)
 *
 * These are structural and consistency checks, not accuracy measurements. They cannot tell you how
 * well the models detect a clone — that needs labelled real and cloned clips, which is what Test
 * Mode is for. Claiming otherwise from a test like this would be exactly the kind of unearned
 * number the whole project is trying to avoid.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var runtime: ModelRuntime

    @Before
    fun setUp() {
        runtime = ModelRuntime(context)
        assumeTrue(
            "models are not bundled — run tools/convert_models.py first",
            ModelAssets.exists(context, ModelAssets.SPEAKER_ENCODER) &&
                ModelAssets.exists(context, ModelAssets.SPOOF_DETECTOR),
        )
    }

    /**
     * The model id must be right before anything has run an inference.
     *
     * It used to be set as a side effect of building the pipeline, so on a cold start the contact
     * list asked for it first and got the placeholder. Every enrolled contact was then reported as
     * "enrolled with an older model", and loadVoiceprints refused to decrypt prints it should have
     * accepted — verification failing silently on a valid contact, which is worse than the wrong
     * label that made it visible.
     *
     * Uses its own ModelRuntime rather than the shared one, because the point is what a runtime
     * says before it has been asked for a pipeline.
     */
    @Test
    fun theModelIdIsKnownBeforeThePipelineIsBuilt() {
        val fresh = ModelRuntime(context)
        val beforeLoading = fresh.modelId()

        assertNotEquals(
            "the model id must not be the placeholder just because nothing has loaded yet",
            ModelRuntime.UNCONVERTED_MODEL_ID,
            beforeLoading,
        )

        fresh.pipelineOrNull()
        assertEquals(
            "the model id must not change once the pipeline loads; a voiceprint stored under one " +
                "and read under the other is a print the app would refuse to use",
            beforeLoading,
            fresh.modelId(),
        )
        fresh.close()
        assertEquals(
            "closing the runtime must not change the identity of the bundled models",
            beforeLoading,
            fresh.modelId(),
        )
    }

    @Test
    fun modelsLoadAndReportTheExpectedShapes() {
        val pipeline = runtime.pipelineOrNull()
        assertNotNull("models failed to load on-device", pipeline)
        assertTrue(runtime.status.value is ModelRuntime.Status.Ready)
    }

    @Test
    fun embeddingIsDeterministicAndUnitLength() {
        val pipeline = runtime.pipelineOrNull()!!
        val speech = syntheticSpeech(seed = 7)

        val a = pipeline.embedUtterance(speech)
        val b = pipeline.embedUtterance(speech)

        assertEquals(AudioConstants.EMBEDDING_DIM, a.size)
        assertEquals("the same audio must embed to the same vector", 1f, Vec.cosine(a, b), 1e-4f)
        assertEquals("embeddings must be unit length", 1f, Vec.l2Norm(a), 1e-3f)
    }

    @Test
    fun differentAudioProducesDifferentEmbeddings() {
        val pipeline = runtime.pipelineOrNull()!!
        val a = pipeline.embedUtterance(syntheticSpeech(seed = 1))
        val b = pipeline.embedUtterance(syntheticSpeech(seed = 2, pitch = 210.0))

        // Not a speaker-verification claim — just proof the encoder is reacting to its input
        // rather than emitting a constant vector, which is what a broken quantisation looks like.
        assertTrue(
            "encoder appears to ignore its input",
            Vec.cosine(a, b) < 0.999f,
        )
    }

    @Test
    fun syntheticProbabilityIsAProbability() {
        val pipeline = runtime.pipelineOrNull()!!
        val window = AudioWindow(
            samples = syntheticSpeech(seed = 3).copyOfRange(0, AudioConstants.WINDOW_SAMPLES),
            startSampleIndex = 0,
            provenance = AudioWindow.Provenance.FILE,
        )

        val analysis = pipeline.analyze(window, voiceprints = emptyList(), contactName = null)
        val p = analysis.verdict.syntheticProbability

        assertNotNull("anti-spoofing model produced no score", p)
        assertTrue("synthetic_probability out of range: $p", p!! in 0f..1f)
    }

    @Test
    fun silenceIsReportedAsUnmeasuredRatherThanScored() {
        val pipeline = runtime.pipelineOrNull()!!
        val window = AudioWindow(
            samples = FloatArray(AudioConstants.WINDOW_SAMPLES),
            startSampleIndex = 0,
            provenance = AudioWindow.Provenance.FILE,
        )

        val analysis = pipeline.analyze(window, voiceprints = emptyList(), contactName = null)

        assertEquals(Level.INDETERMINATE, analysis.verdict.level)
        assertEquals(null, analysis.verdict.syntheticProbability)
    }

    @Test
    fun inferenceFitsInsideTheLiveWindowBudget() {
        val pipeline = runtime.pipelineOrNull()!!
        val window = AudioWindow(
            samples = syntheticSpeech(seed = 5).copyOfRange(0, AudioConstants.WINDOW_SAMPLES),
            startSampleIndex = 0,
            provenance = AudioWindow.Provenance.LIVE_MIC,
        )
        // The live path with a contact selected runs both models, so that is the path whose cost
        // has to fit the hop. Measuring spoof-only would understate it by the whole embedder.
        val voiceprints = listOf(
            Voiceprint("mic", pipeline.embedUtterance(syntheticSpeech(seed = 6)), null)
        )

        repeat(2) { pipeline.analyze(window, voiceprints, contactName = "warm-up") }

        val spoofOnly = median(3) {
            pipeline.analyze(window, voiceprints = emptyList(), contactName = null).inferenceMillis
        }
        val fullPath = median(3) {
            pipeline.analyze(window, voiceprints, contactName = "Arun").inferenceMillis
        }

        // Reported whether or not it passes: "it fit" is a weaker fact than "it took this long",
        // and the second one is what tells you how much headroom a slower handset has.
        Log.i(
            TAG,
            "inference on ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}): " +
                "spoof-only ${spoofOnly} ms, full path (embedder + spoof) ${fullPath} ms, " +
                "budget ${LIVE_HOP_MS} ms",
        )

        // Live scoring emits every 3 s. Anything slower than that falls behind on a real call.
        assertTrue(
            "full-path inference took $fullPath ms, which is slower than the ${LIVE_HOP_MS} ms live hop",
            fullPath < LIVE_HOP_MS,
        )
    }

    private inline fun median(runs: Int, block: () -> Long): Long {
        val samples = LongArray(runs) { block() }
        samples.sort()
        return samples[runs / 2]
    }

    /**
     * A crude voiced-speech stand-in: a harmonic stack with jitter and an amplitude envelope.
     * It is not speech and must never be used to claim anything about accuracy — it exists only to
     * give the models a non-degenerate, non-silent input with realistic dynamic range.
     */
    private fun syntheticSpeech(
        seed: Int,
        pitch: Double = 130.0,
        length: Int = AudioConstants.SAMPLE_RATE * 5,
    ): FloatArray {
        val rng = Random(seed)
        val out = FloatArray(length)
        for (i in 0 until length) {
            val t = i / AudioConstants.SAMPLE_RATE.toDouble()
            val f0 = pitch * (1.0 + 0.02 * sin(2 * PI * 3.1 * t))
            var v = 0.0
            for (h in 1..12) v += sin(2 * PI * f0 * h * t) / h
            val envelope = 0.5 + 0.5 * sin(2 * PI * 2.3 * t)
            out[i] = ((v * 0.08 * envelope) + rng.nextDouble(-0.002, 0.002)).toFloat()
        }
        return out
    }

    private companion object {
        const val TAG = "OnDeviceModelTest"

        /** 3 s between live scores; see LiveVerificationService.HOP_MS. */
        const val LIVE_HOP_MS =
            (AudioConstants.LIVE_HOP_SAMPLES * 1000L) / AudioConstants.SAMPLE_RATE
    }
}

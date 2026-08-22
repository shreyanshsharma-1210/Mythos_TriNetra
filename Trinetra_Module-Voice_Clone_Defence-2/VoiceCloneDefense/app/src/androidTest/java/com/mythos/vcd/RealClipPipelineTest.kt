package com.mythos.vcd

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mythos.vcd.audio.AudioFileDecoder
import com.mythos.vcd.audio.ChannelSim
import com.mythos.vcd.audio.WindowSlicer
import com.mythos.vcd.ml.ModelRuntime
import com.mythos.vcd.pipeline.Fusion
import com.mythos.vcd.pipeline.FusionThresholds
import com.mythos.vcd.pipeline.Level
import com.mythos.vcd.pipeline.VerificationPipeline
import com.mythos.vcd.pipeline.Voiceprint
import com.mythos.vcd.pipeline.bestMatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs real recordings through the exact code path Test Mode uses — decoder, window slicer,
 * pipeline, fusion — on the device, and prints what came out.
 *
 * This exists because driving the SAF file picker through synthetic taps is unreliable, and
 * because a screenshot of Test Mode proves the UI rendered, not that the numbers are right. Here
 * the scores land in logcat where they can be read and argued with.
 *
 * It skips itself unless the clips have been pushed to the app's external files directory:
 *
 *   adb push voice1.mp3 /sdcard/Android/data/com.mythos.vcd.debug/files/voice1.mp3
 *   adb push voice2.mp3 /sdcard/Android/data/com.mythos.vcd.debug/files/voice2.mp3
 *
 * They are deliberately not committed. They are recordings of a real person, and this repo is not
 * where someone's voice should live.
 */
@RunWith(AndroidJUnit4::class)
class RealClipPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val thresholds = FusionThresholds.PROVISIONAL

    private lateinit var runtime: ModelRuntime
    private lateinit var pipeline: VerificationPipeline

    private fun clip(name: String): File? =
        File(context.getExternalFilesDir(null), name).takeIf { it.isFile }

    @Before
    fun setUp() {
        assumeTrue("push voice1.mp3 and voice2.mp3 first — see the class comment", haveClips())
        runtime = ModelRuntime(context)
        pipeline = runtime.pipelineOrNull()
            ?: throw AssertionError("models are not bundled; run tools/convert_models.py")
    }

    @After
    fun tearDown() {
        if (this::runtime.isInitialized) runtime.close()
    }

    private fun haveClips() = clip(ENROL) != null && clip(PROBE) != null

    @Test
    fun twoGenuineRecordingsOfOnePersonMatchAndDoNotTriggerACloneAlert() {
        val enrolAudio = decode(ENROL)
        val probeAudio = decode(PROBE)

        val variants = pipeline.enrolVariants(enrolAudio)
        val baseline = variants.first().baselineSynthetic
        val spoofCheck = Fusion.spoofCheckStatus(baseline, thresholds)
        Log.i(TAG, "enrolled variants: " + variants.joinToString { "${it.label}=${it.baselineSynthetic}" })

        Log.i(TAG, "enrolled from $ENROL: ${seconds(enrolAudio)} s")
        Log.i(
            TAG,
            "anti-spoofing baseline on known-genuine audio = $baseline -> $spoofCheck " +
                "(effective alert threshold ${Fusion.effectiveSyntheticThreshold(baseline, thresholds)})",
        )

        val windows = WindowSlicer.slice(probeAudio)
        assertTrue("$PROBE is too short to score", windows.isNotEmpty())

        val analyses = windows.map { window ->
            pipeline.analyze(window, variants, contactName = "enrolled speaker", thresholds = thresholds)
        }

        analyses.forEach { a ->
            Log.i(
                TAG,
                "%6.1fs  sim %s  syn %s  %-13s %s".format(
                    a.startSeconds,
                    a.verdict.voiceSimilarity?.let { "%.4f".format(it) } ?: "  —   ",
                    a.verdict.syntheticProbability?.let { "%.4f".format(it) } ?: "  —   ",
                    a.verdict.level,
                    a.verdict.reason,
                ),
            )
        }

        val sims = analyses.mapNotNull { it.verdict.voiceSimilarity }
        val syns = analyses.mapNotNull { it.verdict.syntheticProbability }
        val worst = analyses.maxBy { it.verdict.level.severity }
        Log.i(
            TAG,
            "median similarity ${median(sims)}, median synthetic ${median(syns)}, " +
                "worst window ${worst.verdict.level}/${worst.verdict.reason}, " +
                "median inference ${median(analyses.map { it.inferenceMillis.toFloat() })} ms",
        )

        // Identity: the two clips are the same person, so the encoder must say so.
        assertTrue(
            "median similarity ${median(sims)} should clear the ${thresholds.similarityHigh} match threshold",
            median(sims)!! >= thresholds.similarityHigh,
        )

        // The whole point of the baseline guard. Both clips are genuine; the detector scores them
        // at the ceiling; the app must not call a real person a clone.
        val criticals = analyses.count { it.verdict.level == Level.CRITICAL }
        assertEquals(
            "genuine audio produced $criticals CRITICAL windows — the baseline guard is not holding",
            0,
            criticals,
        )
    }

    @Test
    fun theBaselineGuardIsWhatIsSuppressingTheAlert() {
        // Guards the guard: without a baseline the same audio must still be capable of CRITICAL,
        // otherwise this suite would keep passing if the clone check silently stopped working.
        val enrolAudio = decode(ENROL)
        val probeAudio = decode(PROBE)
        val embedding = pipeline.embedUtterance(enrolAudio)
        val uncalibratedPrint = listOf(Voiceprint("mic", embedding, baselineSynthetic = null))

        val window = WindowSlicer.slice(probeAudio).first()
        val uncalibrated = pipeline.analyze(
            window, uncalibratedPrint, contactName = "enrolled speaker", thresholds = thresholds,
        )

        Log.i(
            TAG,
            "same window with no baseline: ${uncalibrated.verdict.level}/${uncalibrated.verdict.reason} " +
                "(synthetic ${uncalibrated.verdict.syntheticProbability})",
        )

        assertEquals(Level.CRITICAL, uncalibrated.verdict.level)
        assertNotEquals(
            "the suppression must come from the measured baseline, not from the alert being broken",
            Level.CRITICAL,
            pipeline.analyze(
                window,
                listOf(
                    Voiceprint("mic", embedding, pipeline.measureSyntheticBaseline(enrolAudio))
                ),
                contactName = "enrolled speaker",
                thresholds = thresholds,
            ).verdict.level,
        )
    }

    /**
     * The claim multi-condition enrolment rests on, checked on the device that will run it.
     *
     * A voiceprint taken over the microphone is scored against the same speaker arriving over a
     * simulated call channel, and compared against the full set of channel-matched prints. On the
     * desk (tools/channel_experiment.py) the microphone print scored 0.7655 against narrowband
     * audio while the matched print scored 0.9766 — the difference between "not confirmed" and a
     * comfortable match, on one person talking to themselves.
     *
     * Asserted as a relationship rather than against fixed numbers, because the exact values
     * depend on the recordings and the point is the ordering, not the digits.
     */
    @Test
    fun channelMatchedPrintsBeatAMicrophoneOnlyPrintOnCallLikeAudio() {
        val enrolAudio = decode(ENROL)
        val probeAudio = decode(PROBE)

        val micOnly = listOf(Voiceprint("mic", pipeline.embedUtterance(enrolAudio), null))
        val allVariants = pipeline.enrolVariants(enrolAudio)

        for (condition in ChannelSim.Condition.entries) {
            val degradedProbe = ChannelSim.apply(probeAudio, condition)
            val probeEmbedding = pipeline.embedUtterance(degradedProbe)

            val micScore = micOnly.bestMatch(probeEmbedding)!!.similarity
            val best = allVariants.bestMatch(probeEmbedding)!!

            Log.i(
                TAG,
                "probe over %-8s : mic-only %.4f · best %.4f via %s%s".format(
                    condition.label,
                    micScore,
                    best.similarity,
                    best.voiceprint.label,
                    if (micScore < thresholds.similarityHigh) "  (mic-only would MISS)" else "",
                ),
            )

            assertTrue(
                "a channel-matched print must never score worse than the microphone one " +
                    "(${condition.label}: mic $micScore, best ${best.similarity})",
                best.similarity >= micScore - 1e-4f,
            )
        }

        // The condition the fix exists for. Narrowband is where a microphone print falls apart.
        val narrowband = pipeline.embedUtterance(
            ChannelSim.apply(probeAudio, ChannelSim.Condition.VOIP_NARROWBAND)
        )
        val micNarrow = micOnly.bestMatch(narrowband)!!.similarity
        val bestNarrow = allVariants.bestMatch(narrowband)!!

        assertTrue(
            "narrowband is the case this was built for: matched $bestNarrow beat mic-only " +
                "$micNarrow by too little to be worth the storage",
            bestNarrow.similarity > micNarrow + 0.05f,
        )
        assertEquals(
            "the narrowband probe should be matched by the narrowband print",
            ChannelSim.Condition.VOIP_NARROWBAND.label,
            bestNarrow.voiceprint.label,
        )
        assertTrue(
            "the matched print should clear the match threshold on call-like audio",
            bestNarrow.similarity >= thresholds.similarityHigh,
        )
    }

    private fun decode(name: String): FloatArray {
        val file = clip(name)!!
        val decoded = AudioFileDecoder.decode(context, Uri.fromFile(file))
        Log.i(
            TAG,
            "$name decoded: ${seconds(decoded.samples)} s, source ${decoded.sourceSampleRate} Hz " +
                "${decoded.sourceChannels} ch, ${decoded.mimeType}",
        )
        return decoded.samples
    }

    private fun seconds(samples: FloatArray) =
        "%.1f".format(samples.size / com.mythos.vcd.audio.AudioConstants.SAMPLE_RATE.toFloat())

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private companion object {
        const val TAG = "RealClipPipelineTest"
        const val ENROL = "voice1.mp3"
        const val PROBE = "voice2.mp3"
    }
}

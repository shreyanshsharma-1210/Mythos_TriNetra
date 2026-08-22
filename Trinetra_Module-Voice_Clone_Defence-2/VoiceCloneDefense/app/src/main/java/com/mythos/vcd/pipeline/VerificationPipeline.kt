package com.mythos.vcd.pipeline

import android.util.Log
import com.mythos.vcd.audio.AudioConstants
import com.mythos.vcd.audio.AudioNormalize
import com.mythos.vcd.audio.AudioWindow
import com.mythos.vcd.audio.ChannelSim
import com.mythos.vcd.ml.SpeakerEmbedder
import com.mythos.vcd.ml.SpoofDetector
import com.mythos.vcd.ml.Vec
import java.io.Closeable

/**
 * Phase 4 — the single inference path.
 *
 * Live capture and Test Mode both call [analyze]. Neither of them owns any scoring logic of its
 * own; the only difference between them is where the [AudioWindow] came from, and that difference
 * is carried in [AudioWindow.provenance] rather than in duplicated code. That is what makes the
 * file-based demo a genuine test of the live path instead of a parallel implementation that
 * happens to look similar.
 */
class VerificationPipeline(
    private val embedder: SpeakerEmbedder,
    private val spoofDetector: SpoofDetector,
) : Closeable {

    /** One measured window, ready for display. */
    data class Analysis(
        val verdict: Verdict,
        val rms: Float,
        val startSeconds: Float,
        val provenance: AudioWindow.Provenance,
        val inferenceMillis: Long,
    )

    /**
     * Resemblyzer-style utterance embedding: split into overlapping 1.6 s partials, embed each,
     * average, and re-normalise. Used for enrolment (30-60 s of speech) and for each live window.
     */
    fun embedUtterance(samples: FloatArray): FloatArray {
        val width = embedder.inputSamples
        require(samples.size >= width) {
            "need at least $width samples (${width / AudioConstants.SAMPLE_RATE.toFloat()} s) to embed, got ${samples.size}"
        }

        // Normalise over the whole utterance before splitting, exactly as the reference does.
        // Per-partial normalisation would flatten the natural loudness contour of speech and
        // change the embedding.
        val normalized = AudioNormalize.toTargetDbfs(samples)

        val hop = AudioConstants.PARTIAL_HOP_SAMPLES
        val partials = ArrayList<FloatArray>()
        var start = 0
        while (start + width <= normalized.size) {
            partials += embedder.embedPartial(normalized.copyOfRange(start, start + width))
            start += hop
        }
        // Cover the tail when the utterance length is not a whole number of hops, so the last
        // fraction of a second of speech still contributes.
        if (start - hop + width < normalized.size) {
            partials += embedder.embedPartial(
                normalized.copyOfRange(normalized.size - width, normalized.size)
            )
        }

        return Vec.l2Normalize(Vec.mean(partials))
    }

    /**
     * Derives one voiceprint per channel condition from a single enrolment recording.
     *
     * The person records once. The app then asks what that same voice would look like arriving
     * over a call, and stores a print for each answer, because a microphone print scores 0.7655
     * against narrowband call audio from the same speaker — on top of the 0.75 match threshold —
     * while a channel-matched print scores 0.9766. See [com.mythos.vcd.audio.ChannelSim].
     *
     * Each variant carries its own anti-spoofing baseline, since the reading that matters is the
     * one taken through the channel the call actually arrives over.
     *
     * The degraded copies are scrubbed before returning; only the embeddings survive.
     */
    fun enrolVariants(samples: FloatArray): List<Voiceprint> {
        val out = ArrayList<Voiceprint>(ENROLMENT_CONDITIONS.size)
        for (condition in ENROLMENT_CONDITIONS) {
            val degraded = try {
                ChannelSim.apply(samples, condition)
            } catch (t: Throwable) {
                Log.e(TAG, "channel ${condition.label} failed; skipping that variant", t)
                continue
            }
            try {
                val embedding = embedUtterance(degraded)
                val baseline = measureSyntheticBaseline(degraded)
                out += Voiceprint(condition.label, embedding, baseline)
                Log.i(TAG, "enrolled variant ${condition.label}: baseline $baseline")
            } catch (t: Throwable) {
                Log.e(TAG, "embedding failed for channel ${condition.label}", t)
            } finally {
                // MIC returns the caller's own array; scrubbing it here would destroy the audio
                // the next condition still needs.
                if (degraded !== samples) java.util.Arrays.fill(degraded, 0f)
            }
        }
        require(out.isNotEmpty()) { "no voiceprint could be derived from this recording" }
        return out
    }

    /**
     * Scores one window against an optional voiceprint.
     *
     * Every failure path here returns [Level.INDETERMINATE] rather than a number. A score the app
     * is not sure was computed from real audio is worse than no score at all — it is the one
     * output that could talk a user into trusting a call they should not.
     */
    fun analyze(
        window: AudioWindow,
        voiceprints: List<Voiceprint>,
        contactName: String?,
        thresholds: FusionThresholds = FusionThresholds.PROVISIONAL,
    ): Analysis {
        val rms = window.rms()
        val startedAt = System.nanoTime()

        if (rms < thresholds.minRms) {
            return Analysis(
                verdict = Verdict(Level.INDETERMINATE, Reason.NO_SPEECH, null, null, contactName),
                rms = rms,
                startSeconds = window.startSeconds,
                provenance = window.provenance,
                inferenceMillis = 0,
            )
        }

        val synthetic = try {
            spoofDetector.syntheticProbability(fitTo(window.samples, spoofDetector.inputSamples))
        } catch (t: Throwable) {
            Log.e(TAG, "anti-spoofing inference failed", t)
            null
        }

        // Every stored print is scored and the best one wins, along with its own baseline. The
        // prints differ by channel on purpose, so the one that matches is the one that says
        // something; the others describe conditions this call is not in.
        val match = if (voiceprints.isEmpty()) {
            null
        } else {
            try {
                voiceprints.bestMatch(embedUtterance(window.samples))
            } catch (t: Throwable) {
                Log.e(TAG, "speaker embedding failed", t)
                null
            }
        }
        val similarity = match?.similarity
        val baselineSynthetic = match?.voiceprint?.baselineSynthetic

        // A requested comparison that could not be computed is a failure, not a "no contact" run.
        // Without this, a broken embedder would quietly downgrade into spoof-only mode and the
        // user would never learn the identity check had stopped happening.
        if (voiceprints.isNotEmpty() && similarity == null) {
            return Analysis(
                verdict = Verdict(Level.INDETERMINATE, Reason.PIPELINE_UNAVAILABLE, null, synthetic, contactName),
                rms = rms,
                startSeconds = window.startSeconds,
                provenance = window.provenance,
                inferenceMillis = (System.nanoTime() - startedAt) / 1_000_000,
            )
        }

        val verdict = Fusion.fuse(similarity, synthetic, contactName, thresholds, baselineSynthetic)

        // Every scored window goes to logcat with the numbers that produced it and the thresholds
        // it was judged against. Without this a surprising verdict is unanswerable — "it said
        // SUSPICIOUS" gives no way to tell a borderline identity score from an elevated spoofing
        // one, and those need completely different responses. Scores only; no audio is logged.
        Log.i(
            TAG,
            "%s %.1fs rms=%.4f via=%s sim=%s syn=%s -> %s/%s (match>=%.2f elevated>=%.2f alert>=%.2f baseline=%s) %d ms"
                .format(
                    window.provenance,
                    window.startSeconds,
                    rms,
                    match?.voiceprint?.label ?: "no-print",
                    similarity?.let { "%.4f".format(it) } ?: "n/a",
                    synthetic?.let { "%.4f".format(it) } ?: "n/a",
                    verdict.level,
                    verdict.reason,
                    thresholds.similarityHigh,
                    Fusion.effectiveElevatedThreshold(baselineSynthetic, thresholds),
                    Fusion.effectiveSyntheticThreshold(baselineSynthetic, thresholds),
                    baselineSynthetic?.let { "%.4f".format(it) } ?: "none",
                    (System.nanoTime() - startedAt) / 1_000_000,
                ),
        )

        return Analysis(
            verdict = verdict,
            rms = rms,
            startSeconds = window.startSeconds,
            provenance = window.provenance,
            inferenceMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    /**
     * Measures how the anti-spoofing model behaves on audio known to be genuine.
     *
     * Called during enrolment, where the recording was just made by a consenting person in front
     * of the device, so the ground truth is not in doubt. The median across windows becomes that
     * contact's baseline.
     *
     * This is not a refinement. The ASVspoof-2019-trained checkpoint returns ~0.999 on genuine
     * recordings from some sources — measured, not hypothetical — and without a per-contact
     * baseline the app would report every call from those people as a clone. Anchoring to the
     * model's behaviour on this specific voice, microphone and recording chain is what separates
     * "this audio looks synthetic" from "this model does not understand this audio".
     *
     * Returns null when the audio yields no scoreable window.
     */
    fun measureSyntheticBaseline(samples: FloatArray): Float? {
        val width = spoofDetector.inputSamples
        if (samples.size < width) return null

        val scores = ArrayList<Float>()
        var start = 0
        while (start + width <= samples.size) {
            val window = samples.copyOfRange(start, start + width)
            val rms = rmsOf(window)
            if (rms >= FusionThresholds.PROVISIONAL.minRms) {
                try {
                    scores += spoofDetector.syntheticProbability(window)
                } catch (t: Throwable) {
                    Log.e(TAG, "baseline inference failed", t)
                }
            }
            start += width
        }
        if (scores.isEmpty()) return null

        // Median, not mean: one anomalous window should not define the baseline in either
        // direction. A high mean pulled up by a single outlier would suppress real detections.
        scores.sort()
        val mid = scores.size / 2
        return if (scores.size % 2 == 1) scores[mid] else (scores[mid - 1] + scores[mid]) / 2f
    }

    private fun rmsOf(samples: FloatArray): Float {
        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        return Math.sqrt(acc / samples.size).toFloat()
    }

    /**
     * The two models want different input widths (25 600 vs 64 600 samples). Windows are sized to
     * the larger one, so this only ever trims; it never pads real audio with silence.
     */
    private fun fitTo(samples: FloatArray, width: Int): FloatArray = when {
        samples.size == width -> samples
        samples.size > width -> samples.copyOfRange(0, width)
        else -> throw IllegalArgumentException(
            "window of ${samples.size} samples is shorter than the required $width; refusing to pad"
        )
    }

    override fun close() {
        embedder.close()
        spoofDetector.close()
    }

    private companion object {
        const val TAG = "VerificationPipeline"
    }
}

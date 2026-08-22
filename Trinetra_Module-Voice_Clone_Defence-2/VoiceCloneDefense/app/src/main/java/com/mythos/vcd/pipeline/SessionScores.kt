package com.mythos.vcd.pipeline

/**
 * Running state for one verification session — live or file-based.
 *
 * Holds two views of the same data on purpose, because a live call and a file need opposite things.
 *
 * **Per-window** ([currentLevel], [peakLevel]) is what each 3-second frame said on its own. For a
 * file that is the right headline: a clone that only shows itself in part of a clip is still a
 * clone, and the worst window is the finding.
 *
 * **Stabilised** ([stableLevel], [stablePeakLevel]) is a decision taken across several windows. For
 * a live call that is the only honest headline. Scores near a threshold cross it constantly through
 * ordinary variation, so a per-window verdict flips between SAFE, SUSPICIOUS and CRITICAL while the
 * same person talks — measured on real calls, not hypothesised. A status that changes every three
 * seconds tells the user nothing except that the app is unsure, and latching the worst single
 * window turns one noisy frame into a permanent accusation.
 *
 * The stabiliser does three things: it decides on the median of a rolling buffer rather than one
 * frame, it requires a new level to hold for several evaluations before it is shown, and it makes
 * de-escalation slower than escalation so a genuine finding is not wiped out by one clean frame.
 */
data class SessionScores(
    val latest: VerificationPipeline.Analysis? = null,
    val smoothedSimilarity: Float? = null,
    val smoothedSynthetic: Float? = null,

    /** The most recent single window's level. Diagnostic; too jumpy to show as a call status. */
    val currentLevel: Level = Level.INDETERMINATE,

    /** Worst single window seen. The right headline for a file, the wrong one for a live call. */
    val peakLevel: Level = Level.INDETERMINATE,
    val peakVerdict: Verdict? = null,

    /** The level being shown to the user on a live call. */
    val stableLevel: Level = Level.INDETERMINATE,
    val stableVerdict: Verdict? = null,

    /** Worst level that ever became stable. What a CRITICAL takeover should fire on. */
    val stablePeakLevel: Level = Level.INDETERMINATE,
    val stablePeakVerdict: Verdict? = null,

    val measuredWindows: Int = 0,
    val skippedWindows: Int = 0,
    val history: List<VerificationPipeline.Analysis> = emptyList(),

    /** A level waiting to be confirmed, and how many evaluations it has held for. */
    val pendingLevel: Level? = null,
    val pendingCount: Int = 0,
) {

    fun accept(
        analysis: VerificationPipeline.Analysis,
        thresholds: FusionThresholds = FusionThresholds.PROVISIONAL,
        baselineSynthetic: Float? = null,
    ): SessionScores {
        val verdict = analysis.verdict
        val measured = verdict.level != Level.INDETERMINATE

        val nextHistory = (history + analysis).takeLast(MAX_HISTORY)
        val nextSimilarity = ema(smoothedSimilarity, verdict.voiceSimilarity)
        val nextSynthetic = ema(smoothedSynthetic, verdict.syntheticProbability)

        val nextPeakLevel = if (verdict.level.severity > peakLevel.severity) verdict.level else peakLevel
        val nextPeakVerdict = if (verdict.level.severity > peakLevel.severity) verdict else peakVerdict

        val stabilised = stabilise(nextHistory, thresholds, baselineSynthetic)

        return copy(
            latest = analysis,
            smoothedSimilarity = nextSimilarity,
            smoothedSynthetic = nextSynthetic,
            currentLevel = if (measured) verdict.level else currentLevel,
            peakLevel = nextPeakLevel,
            peakVerdict = nextPeakVerdict,
            stableLevel = stabilised.level,
            stableVerdict = stabilised.verdict,
            stablePeakLevel = if (stabilised.level.severity > stablePeakLevel.severity) {
                stabilised.level
            } else {
                stablePeakLevel
            },
            stablePeakVerdict = if (stabilised.level.severity > stablePeakLevel.severity) {
                stabilised.verdict
            } else {
                stablePeakVerdict
            },
            measuredWindows = measuredWindows + if (measured) 1 else 0,
            skippedWindows = skippedWindows + if (measured) 0 else 1,
            history = nextHistory,
            pendingLevel = stabilised.pendingLevel,
            pendingCount = stabilised.pendingCount,
        )
    }

    private data class Stabilised(
        val level: Level,
        val verdict: Verdict?,
        val pendingLevel: Level?,
        val pendingCount: Int,
    )

    private fun stabilise(
        history: List<VerificationPipeline.Analysis>,
        thresholds: FusionThresholds,
        baselineSynthetic: Float?,
    ): Stabilised {
        val recent = history
            .filter { it.verdict.level != Level.INDETERMINATE }
            .takeLast(BUFFER_WINDOWS)

        // Not enough evidence yet. Reporting "unmeasured" is the honest state, and it is why the
        // call screen says it is still listening rather than showing a level it would take back.
        if (recent.size < MIN_WINDOWS) {
            return Stabilised(Level.INDETERMINATE, null, null, 0)
        }

        // Median, not mean: one window landing on an outlier should move the decision by nothing,
        // and a mean would drag the whole buffer toward it.
        val similarity = median(recent.mapNotNull { it.verdict.voiceSimilarity })
        val synthetic = median(recent.mapNotNull { it.verdict.syntheticProbability })
        val contactName = recent.last().verdict.contactName

        val candidate = Fusion.fuse(similarity, synthetic, contactName, thresholds, baselineSynthetic)

        if (candidate.level == stableLevel) {
            return Stabilised(stableLevel, candidate, null, 0)
        }

        // The first real verdict is committed as soon as there is enough audio for one. Waiting
        // for confirmation here would mean the call sat on "listening" long after it could have
        // answered — MIN_WINDOWS is already the confirmation for this transition.
        if (stableLevel == Level.INDETERMINATE) {
            return Stabilised(candidate.level, candidate, null, 0)
        }

        // Escalating is quicker than de-escalating. A finding that has been shown should not vanish
        // because one frame came back clean, but a user should not wait long to be warned either.
        val required = if (candidate.level.severity > stableLevel.severity) {
            ESCALATE_WINDOWS
        } else {
            DE_ESCALATE_WINDOWS
        }

        val count = if (pendingLevel == candidate.level) pendingCount + 1 else 1
        return if (count >= required) {
            Stabilised(candidate.level, candidate, null, 0)
        } else {
            Stabilised(stableLevel, stableVerdict, candidate.level, count)
        }
    }

    val hasAnyMeasurement: Boolean get() = measuredWindows > 0

    /** True while there is not yet enough audio to say anything. Drives "Listening…" in the UI. */
    val stillListening: Boolean get() = stableLevel == Level.INDETERMINATE

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private fun ema(previous: Float?, sample: Float?): Float? = when {
        sample == null -> previous
        previous == null -> sample
        else -> previous * (1f - ALPHA) + sample * ALPHA
    }

    companion object {
        /** Responsive enough to move within two windows, damped enough not to jitter. */
        const val ALPHA = 0.4f
        const val MAX_HISTORY = 240

        /** Windows the stable decision is taken over. Five 3 s hops is about fifteen seconds. */
        const val BUFFER_WINDOWS = 5

        /** Below this, the app says it is still listening rather than guessing. */
        const val MIN_WINDOWS = 3

        /** Consecutive evaluations agreeing on a worse level before it is shown. */
        const val ESCALATE_WINDOWS = 2

        /** Consecutive evaluations agreeing on a better level before a finding is withdrawn. */
        const val DE_ESCALATE_WINDOWS = 4
    }
}

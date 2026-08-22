package com.mythos.vcd.pipeline

/**
 * Phase 5 fusion.
 *
 * The one rule worth stating out loud: high similarity and high synthetic probability is NOT
 * averaged into a middling score. Averaging is exactly the mistake this module exists to avoid —
 * a good clone is optimised to score high on similarity, so a mean of the two signals would let a
 * successful attack cancel itself out into "probably fine". The combination is treated as its own
 * finding, and it is the most severe one the app can report.
 */
object Fusion {

    /**
     * @param baselineSynthetic median synthetic_probability measured on this contact's own
     *   known-genuine enrolment audio, or null if it was never measured. See the note on
     *   [effectiveSyntheticThreshold] for why a per-contact baseline is not optional.
     */
    fun fuse(
        voiceSimilarity: Float?,
        syntheticProbability: Float?,
        contactName: String?,
        thresholds: FusionThresholds = FusionThresholds.PROVISIONAL,
        baselineSynthetic: Float? = null,
    ): Verdict {
        // A missing spoofing score means the anti-spoofing model did not run. Similarity alone is
        // not a safety signal here, so we refuse to grade rather than reporting a partial result.
        if (syntheticProbability == null) {
            return Verdict(
                level = Level.INDETERMINATE,
                reason = Reason.PIPELINE_UNAVAILABLE,
                voiceSimilarity = voiceSimilarity,
                syntheticProbability = null,
                contactName = contactName,
            )
        }

        val spoofCheck = spoofCheckStatus(baselineSynthetic, thresholds)

        // Spoof-only mode: Test Mode run with no contact chosen, or live run before enrolment.
        if (voiceSimilarity == null) {
            val level = if (syntheticProbability >= thresholds.syntheticHigh) {
                Level.SUSPICIOUS
            } else {
                // Cannot be SAFE: without a voiceprint the identity half was never checked.
                Level.INDETERMINATE
            }
            val reason = if (syntheticProbability >= thresholds.syntheticHigh) {
                Reason.SYNTHETIC_UNKNOWN_SPEAKER
            } else {
                Reason.NO_VOICEPRINT_SELECTED
            }
            return Verdict(level, reason, null, syntheticProbability, contactName, spoofCheck)
        }

        val similarityIsHigh = voiceSimilarity >= thresholds.similarityHigh
        val similarityIsLow = voiceSimilarity < thresholds.similarityLow
        val syntheticThreshold = effectiveSyntheticThreshold(baselineSynthetic, thresholds)
        val elevatedThreshold = effectiveElevatedThreshold(baselineSynthetic, thresholds)

        fun verdict(level: Level, reason: Reason) = Verdict(
            level, reason, voiceSimilarity, syntheticProbability, contactName, spoofCheck,
        )

        return when {
            // Identity matches, but the anti-spoofing model has already been shown not to work on
            // this voice — it called the contact's own consented enrolment recording synthetic.
            // Reporting a clone on that basis would accuse a real person, so the check is dropped
            // and the user is told it was dropped.
            //
            // Note that NO_BASELINE deliberately does NOT get this treatment. "We never measured"
            // is not the same claim as "we measured and it was useless", and suppressing the clone
            // verdict for every un-calibrated contact would quietly turn the detector off for the
            // exact users who have not finished setting the app up. Un-calibrated contacts get the
            // ordinary absolute thresholds plus a caveat on the verdict; see Verdict.caveat().
            similarityIsHigh && spoofCheck == SpoofCheck.UNRELIABLE ->
                verdict(Level.SAFE, Reason.MATCH_SPOOF_CHECK_UNRELIABLE)

            // The clone signature. Flagged as a combination, never as an average.
            similarityIsHigh && syntheticProbability >= syntheticThreshold ->
                verdict(Level.CRITICAL, Reason.CLONE_SIGNATURE)

            similarityIsHigh && syntheticProbability >= elevatedThreshold ->
                verdict(Level.SUSPICIOUS, Reason.POSSIBLE_SYNTHESIS)

            similarityIsHigh -> verdict(Level.SAFE, Reason.MATCH_AUTHENTIC)

            // Low similarity is decided on its own terms, whatever the spoofing score says. It is
            // reported as "not the claimed contact" rather than folded into the clone verdict,
            // because those are different problems with different advice attached.
            similarityIsLow -> verdict(Level.SUSPICIOUS, Reason.NOT_CLAIMED_CONTACT)

            else -> verdict(Level.SUSPICIOUS, Reason.BORDERLINE_SIMILARITY)
        }
    }

    enum class SpoofCheck {
        /** A baseline was measured and leaves room for a clone to score meaningfully higher. */
        USABLE,

        /** The contact's own genuine enrolment audio already scores at or near the ceiling. */
        UNRELIABLE,

        /** Enrolled before baselines were measured. */
        NO_BASELINE,
    }

    fun spoofCheckStatus(
        baselineSynthetic: Float?,
        thresholds: FusionThresholds = FusionThresholds.PROVISIONAL,
    ): SpoofCheck = when {
        baselineSynthetic == null -> SpoofCheck.NO_BASELINE
        // If genuine audio from this person already sits within the margin of the top of the
        // scale, there is nowhere left for a clone to score higher, so the check carries no
        // information for this voice. Measured on real recordings: 0.999 on known-genuine audio.
        baselineSynthetic + thresholds.syntheticBaselineMargin >= 1.0f -> SpoofCheck.UNRELIABLE
        else -> SpoofCheck.USABLE
    }

    /**
     * The threshold a live score must clear to count as evidence of synthesis.
     *
     * It is the higher of the absolute threshold and the contact's own genuine baseline plus a
     * margin. A voice whose real recordings read 0.4 needs 0.55 before anything is said about it;
     * a voice whose real recordings read 0.05 is held to the ordinary 0.50 bar. Anchoring to what
     * the model does on known-genuine audio from that specific person, microphone and recording
     * chain is what keeps a domain mismatch from being reported as a fraud finding.
     */
    fun effectiveSyntheticThreshold(
        baselineSynthetic: Float?,
        thresholds: FusionThresholds = FusionThresholds.PROVISIONAL,
    ): Float {
        if (baselineSynthetic == null) return thresholds.syntheticHigh
        return maxOf(thresholds.syntheticHigh, baselineSynthetic + thresholds.syntheticBaselineMargin)
    }

    /**
     * The threshold above which a matching voice stops being reported as clean.
     *
     * This moves with the baseline for the same reason the alert threshold does, and forgetting to
     * move it was a real bug rather than a subtlety. A contact whose genuine enrolment recording
     * scores 0.605 sits permanently above a fixed 0.30 elevated line, so every call from them was
     * reported SUSPICIOUS and SAFE was unreachable — no matter who was actually speaking. Measured
     * on a real enrolment, not hypothesised.
     *
     * Half the alert margin, so the bands keep their shape: baseline, then a "leaning" band, then
     * the alert. A voice that reads 0.605 at enrolment needs 0.68 to raise an eyebrow and 0.755
     * before anything is claimed.
     */
    fun effectiveElevatedThreshold(
        baselineSynthetic: Float?,
        thresholds: FusionThresholds = FusionThresholds.PROVISIONAL,
    ): Float {
        if (baselineSynthetic == null) return thresholds.syntheticElevated
        return maxOf(
            thresholds.syntheticElevated,
            baselineSynthetic + thresholds.syntheticBaselineMargin / 2f,
        )
    }
}

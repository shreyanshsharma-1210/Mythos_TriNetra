package com.mythos.vcd.pipeline

/**
 * The three-level status the user actually sees, plus an explicit fourth state for
 * "we could not measure this", which is not a risk level and must never be rendered as one.
 */
enum class Level {
    SAFE,
    SUSPICIOUS,
    CRITICAL,

    /**
     * No usable measurement: no speech in the window, no voiceprint to compare against, or a
     * model that failed to run. Phase 6 rule — an unmeasurable window shows as unmeasured, never
     * as SAFE.
     */
    INDETERMINATE,
    ;

    /** Higher wins when latching the worst result seen during a session. */
    val severity: Int
        get() = when (this) {
            INDETERMINATE -> 0
            SAFE -> 1
            SUSPICIOUS -> 2
            CRITICAL -> 3
        }
}

/** Why the pipeline landed on a level. Drives the plain-language explanation in the UI. */
enum class Reason {
    /** High similarity, low synthetic evidence. */
    MATCH_AUTHENTIC,

    /**
     * High similarity AND high synthetic evidence. This specific combination is the clone
     * signature and is the whole reason this module exists.
     */
    CLONE_SIGNATURE,

    /** High similarity, but enough synthetic evidence to stop short of calling it clean. */
    POSSIBLE_SYNTHESIS,

    /** Similarity too low to be the enrolled contact, whatever the spoofing score says. */
    NOT_CLAIMED_CONTACT,

    /** Similarity sits between the thresholds — not a match, not a clear mismatch. */
    BORDERLINE_SIMILARITY,

    /** Spoof-only run with no voiceprint selected, and the audio looks synthetic. */
    SYNTHETIC_UNKNOWN_SPEAKER,

    /** Spoof-only run with no voiceprint selected, and the audio looks natural. */
    NO_VOICEPRINT_SELECTED,

    /**
     * The voice matches, and the anti-spoofing model was suppressed because it already called this
     * contact's own known-genuine enrolment audio synthetic. Identity is reported; the clone check
     * is not, because it has been shown not to work on this voice.
     */
    MATCH_SPOOF_CHECK_UNRELIABLE,

    /** Window was silence or near-silence; no inference was run on it. */
    NO_SPEECH,

    /** A model or the capture path failed. */
    PIPELINE_UNAVAILABLE,
}

/**
 * Thresholds are configuration, not constants, and they ship uncalibrated on purpose.
 *
 * The PRD (§9.2, §14) explicitly refuses to state accuracy figures before measuring them, and the
 * same discipline applies here: these defaults are starting points for calibration against a real
 * demo set, not measured operating points. The UI labels them as provisional wherever a score is
 * shown, and Test Mode exposes the raw scores so they can actually be tuned against real clips.
 */
data class FusionThresholds(
    val similarityHigh: Float = 0.75f,
    val similarityLow: Float = 0.60f,
    val syntheticHigh: Float = 0.50f,
    val syntheticElevated: Float = 0.30f,
    /** Windows quieter than this are treated as silence and never scored. */
    val minRms: Float = 0.005f,
    val calibrated: Boolean = false,
    /**
     * How far above a contact's genuine baseline a live score must sit before the clone verdict is
     * believed. Applied on top of [syntheticHigh], not instead of it.
     *
     * Without this, a contact whose genuine enrolment audio already reads 0.99 would be flagged as
     * a clone on every call — measured, not hypothetical: the ASVspoof-2019-trained AASIST
     * checkpoint returns ~0.999 on real recordings from some sources.
     */
    val syntheticBaselineMargin: Float = 0.15f,
) {
    init {
        require(similarityLow <= similarityHigh) { "similarityLow must not exceed similarityHigh" }
        require(syntheticElevated <= syntheticHigh) { "syntheticElevated must not exceed syntheticHigh" }
    }

    companion object {
        val PROVISIONAL = FusionThresholds()
    }
}

/**
 * One fused judgement about one audio window.
 *
 * [spoofCheck] records how much the anti-spoofing half of this verdict is worth. It is deliberately
 * separate from [level] and [reason]: how confident the app is in a signal is a different fact from
 * what the signal said, and folding the two together is what produced the bug this field exists to
 * prevent — an un-calibrated contact silently losing clone detection altogether.
 */
data class Verdict(
    val level: Level,
    val reason: Reason,
    val voiceSimilarity: Float?,
    val syntheticProbability: Float?,
    val contactName: String?,
    val spoofCheck: Fusion.SpoofCheck = Fusion.SpoofCheck.USABLE,
) {
    /** Plain-language wording, per FR-VOICE-ALT-2. Scores are named, never just averaged away. */
    fun headline(): String = caveat()?.let { "${baseHeadline()} $it" } ?: baseHeadline()

    /**
     * A qualifier on how far the anti-spoofing number should be trusted, or null when nothing needs
     * qualifying. Reported alongside the verdict rather than in place of it: a contact who was never
     * calibrated still gets a clone alert, they just also get told the check is uncalibrated.
     */
    fun caveat(): String? = when {
        // Nothing to qualify — these verdicts never consulted the anti-spoofing score.
        reason == Reason.NO_SPEECH || reason == Reason.PIPELINE_UNAVAILABLE -> null
        // The suppression is already the whole content of this verdict's headline.
        reason == Reason.MATCH_SPOOF_CHECK_UNRELIABLE -> null
        spoofCheck == Fusion.SpoofCheck.NO_BASELINE && contactName != null ->
            "The computer-generation check has not been calibrated for this voice yet — re-enrol " +
                "$contactName to calibrate it."
        else -> null
    }

    private fun baseHeadline(): String = when (reason) {
        Reason.MATCH_AUTHENTIC ->
            "This sounds like ${contactName ?: "the enrolled contact"}, and shows no signs of being computer-generated."
        Reason.CLONE_SIGNATURE ->
            "This sounds like ${contactName ?: "the enrolled contact"}, but shows signs of being computer-generated."
        Reason.POSSIBLE_SYNTHESIS ->
            "This sounds like ${contactName ?: "the enrolled contact"}, but some of the audio looks artificial."
        Reason.NOT_CLAIMED_CONTACT ->
            "This does not sound like ${contactName ?: "the enrolled contact"}."
        Reason.BORDERLINE_SIMILARITY ->
            "Not a clear match to ${contactName ?: "the enrolled contact"}, and not a clear mismatch either."
        Reason.SYNTHETIC_UNKNOWN_SPEAKER ->
            "This voice shows signs of being computer-generated. No contact was selected, so it was not checked against anyone."
        Reason.NO_VOICEPRINT_SELECTED ->
            "No signs of computer generation. No contact was selected, so identity was not checked."
        Reason.MATCH_SPOOF_CHECK_UNRELIABLE ->
            "This sounds like ${contactName ?: "the enrolled contact"}. The computer-generation " +
                "check was skipped — it does not work reliably on this voice."
        Reason.NO_SPEECH ->
            "Not enough speech in this window to measure anything."
        Reason.PIPELINE_UNAVAILABLE ->
            "Voice verification is unavailable, so no score was produced."
    }
}

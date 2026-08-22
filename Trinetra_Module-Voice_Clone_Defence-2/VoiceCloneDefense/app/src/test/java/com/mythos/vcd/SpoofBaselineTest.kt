package com.mythos.vcd

import com.mythos.vcd.pipeline.Fusion
import com.mythos.vcd.pipeline.FusionThresholds
import com.mythos.vcd.pipeline.Level
import com.mythos.vcd.pipeline.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-contact anti-spoofing baseline.
 *
 * This exists because of a measured failure, not a hypothetical one. Two known-genuine recordings
 * of a real person were scored by the ASVspoof-2019-trained AASIST checkpoint at a median
 * synthetic_probability of 0.9991 and 0.9997 — and by AASIST-L at 0.9988 and 1.0000. Codec,
 * clipping and denoising were each ruled out with controls. Without the baseline rule, this app
 * tells that person's family their real relative is a computer-generated clone, on every call.
 */
class SpoofBaselineTest {

    private val t = FusionThresholds.PROVISIONAL

    @Test
    fun `contact whose genuine voice already scores at the ceiling never triggers a clone alert`() {
        // The measured baseline from voice1.mp3.
        val baseline = 0.9991f

        val verdict = Fusion.fuse(0.94f, 0.9991f, "Arun", t, baselineSynthetic = baseline)

        assertEquals(Level.SAFE, verdict.level)
        assertEquals(Reason.MATCH_SPOOF_CHECK_UNRELIABLE, verdict.reason)
        assertTrue(
            "the user must be told the check was skipped, not left assuming it passed",
            verdict.headline().contains("does not work reliably"),
        )
    }

    @Test
    fun `a usable baseline still lets a genuine clone through to CRITICAL`() {
        // A contact whose real voice reads low: the check has headroom and must keep working.
        val baseline = 0.05f

        val safe = Fusion.fuse(0.94f, 0.04f, "Arun", t, baselineSynthetic = baseline)
        assertEquals(Level.SAFE, safe.level)
        assertEquals(Reason.MATCH_AUTHENTIC, safe.reason)

        val clone = Fusion.fuse(0.94f, 0.97f, "Arun", t, baselineSynthetic = baseline)
        assertEquals(Level.CRITICAL, clone.level)
        assertEquals(Reason.CLONE_SIGNATURE, clone.reason)
    }

    @Test
    fun `a moderately elevated baseline raises the bar rather than disabling the check`() {
        val baseline = 0.60f
        assertEquals(Fusion.SpoofCheck.USABLE, Fusion.spoofCheckStatus(baseline, t))

        // 0.60 + 0.15 margin = 0.75, well above the absolute 0.50 threshold.
        assertEquals(0.75f, Fusion.effectiveSyntheticThreshold(baseline, t), 1e-6f)

        // A score that would have fired against the absolute threshold no longer does.
        val notEnough = Fusion.fuse(0.94f, 0.70f, "Arun", t, baselineSynthetic = baseline)
        assertTrue("0.70 is below this contact's raised bar", notEnough.level != Level.CRITICAL)

        val enough = Fusion.fuse(0.94f, 0.80f, "Arun", t, baselineSynthetic = baseline)
        assertEquals(Level.CRITICAL, enough.level)
    }

    @Test
    fun `a low baseline never lowers the bar below the absolute threshold`() {
        // Baseline 0.0 must not make 0.20 count as evidence of synthesis.
        assertEquals(t.syntheticHigh, Fusion.effectiveSyntheticThreshold(0.0f, t), 1e-6f)
        val verdict = Fusion.fuse(0.94f, 0.20f, "Arun", t, baselineSynthetic = 0.0f)
        assertTrue(verdict.level != Level.CRITICAL)
    }

    @Test
    fun `a contact enrolled before baselines existed still gets the clone verdict, with a caveat`() {
        // "Never measured" is not "measured and useless". Suppressing CRITICAL here would turn the
        // detector off for every contact enrolled before baselines existed — silently, and for the
        // users least likely to notice. The uncertainty is reported next to the verdict instead.
        val verdict = Fusion.fuse(0.94f, 0.99f, "Arun", t, baselineSynthetic = null)

        assertEquals(Level.CRITICAL, verdict.level)
        assertEquals(Reason.CLONE_SIGNATURE, verdict.reason)
        assertEquals(Fusion.SpoofCheck.NO_BASELINE, verdict.spoofCheck)
        assertTrue(
            "the user must be told the check is uncalibrated, not have the alert withheld",
            verdict.headline().contains("not been calibrated"),
        )
    }

    @Test
    fun `an uncalibrated contact with a clean score is safe and carries no scare text`() {
        val verdict = Fusion.fuse(0.94f, 0.02f, "Arun", t, baselineSynthetic = null)
        assertEquals(Level.SAFE, verdict.level)
        assertEquals(Reason.MATCH_AUTHENTIC, verdict.reason)
        assertTrue(verdict.headline().contains("not been calibrated"))
    }

    @Test
    fun `a contact whose genuine voice reads mid-scale can still be reported safe`() {
        // Measured on a real enrolment: sakshi, 36 s of consented microphone audio, baseline 0.605.
        //
        // The baseline was applied to the alert threshold but not to the elevated one, so this
        // contact sat permanently above the fixed 0.30 elevated line and SAFE was unreachable —
        // every call from her read SUSPICIOUS whoever was speaking. That is worse than useless: an
        // app that always says the same thing carries no information, and the one it kept saying
        // was an accusation.
        val baseline = 0.605f
        assertEquals(Fusion.SpoofCheck.USABLE, Fusion.spoofCheckStatus(baseline, t))
        assertEquals(0.6800f, Fusion.effectiveElevatedThreshold(baseline, t), 1e-4f)
        assertEquals(0.7550f, Fusion.effectiveSyntheticThreshold(baseline, t), 1e-4f)

        // At or a little above her own baseline, a matching voice is clean.
        val atBaseline = Fusion.fuse(0.88f, 0.605f, "sakshi", t, baselineSynthetic = baseline)
        assertEquals(Level.SAFE, atBaseline.level)
        assertEquals(Reason.MATCH_AUTHENTIC, atBaseline.reason)

        // The bands still exist, they have just moved to where this voice actually lives.
        val leaning = Fusion.fuse(0.88f, 0.70f, "sakshi", t, baselineSynthetic = baseline)
        assertEquals(Level.SUSPICIOUS, leaning.level)
        assertEquals(Reason.POSSIBLE_SYNTHESIS, leaning.reason)

        val alert = Fusion.fuse(0.88f, 0.80f, "sakshi", t, baselineSynthetic = baseline)
        assertEquals(Level.CRITICAL, alert.level)
        assertEquals(Reason.CLONE_SIGNATURE, alert.reason)
    }

    @Test
    fun `an uncalibrated contact keeps the absolute elevated band`() {
        assertEquals(t.syntheticElevated, Fusion.effectiveElevatedThreshold(null, t), 1e-6f)
        val v = Fusion.fuse(0.88f, 0.40f, "Arun", t, baselineSynthetic = null)
        assertEquals(Level.SUSPICIOUS, v.level)
        assertEquals(Reason.POSSIBLE_SYNTHESIS, v.reason)
    }

    @Test
    fun `a low baseline never lowers the elevated band below the absolute one`() {
        assertEquals(t.syntheticElevated, Fusion.effectiveElevatedThreshold(0.0f, t), 1e-6f)
        assertEquals(t.syntheticElevated, Fusion.effectiveElevatedThreshold(0.05f, t), 1e-6f)
    }

    @Test
    fun `identity mismatch is still reported regardless of the baseline`() {
        // Suppressing the spoof check must not suppress "this is not your contact".
        val verdict = Fusion.fuse(0.20f, 0.9991f, "Arun", t, baselineSynthetic = 0.9991f)
        assertEquals(Level.SUSPICIOUS, verdict.level)
        assertEquals(Reason.NOT_CLAIMED_CONTACT, verdict.reason)
    }

    @Test
    fun `spoof check status classifies the three cases`() {
        assertEquals(Fusion.SpoofCheck.NO_BASELINE, Fusion.spoofCheckStatus(null, t))
        assertEquals(Fusion.SpoofCheck.USABLE, Fusion.spoofCheckStatus(0.10f, t))
        assertEquals(Fusion.SpoofCheck.UNRELIABLE, Fusion.spoofCheckStatus(0.90f, t))
        // Exactly at the boundary: 0.85 + 0.15 == 1.0, so there is no headroom left.
        assertEquals(Fusion.SpoofCheck.UNRELIABLE, Fusion.spoofCheckStatus(0.85f, t))
        assertEquals(Fusion.SpoofCheck.USABLE, Fusion.spoofCheckStatus(0.84f, t))
    }

    @Test
    fun `the default fuse call is unchanged for callers that pass no baseline`() {
        // Test Mode with no contact selected, and every pre-existing call site.
        val spoofOnly = Fusion.fuse(null, 0.99f, null, t)
        assertEquals(Level.SUSPICIOUS, spoofOnly.level)
        assertEquals(Reason.SYNTHETIC_UNKNOWN_SPEAKER, spoofOnly.reason)
    }
}

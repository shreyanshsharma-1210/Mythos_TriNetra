package com.trustmesh.app.core.voicescan

import com.trustmesh.app.core.events.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceScanControllerTest {

    // ── Control-code matching ─────────────────────────────────────────────────

    @Test
    fun `bare codes are matched`() {
        assertEquals("7000", VoiceScanController.matchControlCode("7000"))
        assertEquals("6000", VoiceScanController.matchControlCode("6000"))
    }

    @Test
    fun `surrounding whitespace and punctuation do not block a code`() {
        assertEquals("7000", VoiceScanController.matchControlCode("  7000\n"))
        assertEquals("6000", VoiceScanController.matchControlCode("6000."))
        assertEquals("7000", VoiceScanController.matchControlCode("run 7000 now"))
    }

    @Test
    fun `a code embedded in a longer number is not a code`() {
        assertNull(VoiceScanController.matchControlCode("16000"))
        assertNull(VoiceScanController.matchControlCode("70001"))
        assertNull(VoiceScanController.matchControlCode("Your OTP is 970005"))
    }

    @Test
    fun `ordinary messages carry no code`() {
        assertNull(VoiceScanController.matchControlCode(""))
        assertNull(VoiceScanController.matchControlCode("Call me back"))
        assertNull(VoiceScanController.matchControlCode("5000"))
    }

    // ── State derivations ─────────────────────────────────────────────────────

    @Test
    fun `an idle scan defers to the pipeline level`() {
        val idle = VoiceScanState()
        assertFalse(idle.active)
        assertFalse(idle.hasScore)
        assertEquals(RiskLevel.CRITICAL, idle.effectiveRiskLevel(RiskLevel.CRITICAL))
    }

    @Test
    fun `the first buffer of a call has nothing to show and does not override`() {
        val buffering = VoiceScanState(
            phase = VoiceScanPhase.BUFFERING,
            verdict = null,
            secondsRemaining = 7,
        )
        assertTrue(buffering.active)
        assertFalse(buffering.hasScore)
        assertEquals(RiskLevel.ELEVATED, buffering.effectiveRiskLevel(RiskLevel.ELEVATED))
    }

    @Test
    fun `a re-analysis buffer keeps the standing verdict on screen`() {
        val reanalysing = VoiceScanState(
            phase = VoiceScanPhase.BUFFERING,
            verdict = VoiceScanVerdict.SYNTHETIC,
            riskScore = 58,
            secondsRemaining = 4,
        )
        assertTrue(reanalysing.hasScore)
        // The warning must not soften just because a second look has started.
        assertEquals(RiskLevel.HIGH, reanalysing.effectiveRiskLevel(RiskLevel.LOW))
    }

    @Test
    fun `level tracks the score so colour and number never disagree`() {
        fun at(score: Int) = VoiceScanState(
            phase = VoiceScanPhase.ANALYZING,
            verdict = VoiceScanVerdict.GENUINE,
            riskScore = score,
        ).effectiveRiskLevel(RiskLevel.CRITICAL)

        assertEquals(RiskLevel.HIGH, at(58))
        assertEquals(RiskLevel.ELEVATED, at(30))
        assertEquals(RiskLevel.LOW, at(12))
    }

    @Test
    fun `the synthetic band lands in the alerting range`() {
        // 52-63 is the band the synthetic run walks; every point in it must read as HIGH, which is
        // what raises the popup and what the ten-second buzz signals.
        for (score in 52..63) {
            val state = VoiceScanState(
                phase = VoiceScanPhase.ANALYZING,
                verdict = VoiceScanVerdict.SYNTHETIC,
                riskScore = score,
            )
            assertEquals("score $score", RiskLevel.HIGH, state.effectiveRiskLevel(RiskLevel.LOW))
        }
    }

    @Test
    fun `flagged markers are counted for the popup headline`() {
        val state = VoiceScanState(
            phase = VoiceScanPhase.ANALYZING,
            markers = listOf(
                VoiceScanMarker("a", "", flagged = true),
                VoiceScanMarker("b", "", flagged = true),
                VoiceScanMarker("c", "", flagged = false),
            ),
        )
        assertEquals(2, state.flaggedMarkers)
    }
}

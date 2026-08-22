package com.mythos.vcd

import com.mythos.vcd.pipeline.Fusion
import com.mythos.vcd.pipeline.FusionThresholds
import com.mythos.vcd.pipeline.Level
import com.mythos.vcd.pipeline.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionTest {

    private val t = FusionThresholds.PROVISIONAL

    @Test
    fun `high similarity and low synthetic is safe`() {
        val v = Fusion.fuse(0.88f, 0.05f, "Arun", t)
        assertEquals(Level.SAFE, v.level)
        assertEquals(Reason.MATCH_AUTHENTIC, v.reason)
    }

    @Test
    fun `high similarity and high synthetic is critical, not averaged away`() {
        val v = Fusion.fuse(0.88f, 0.93f, "Arun", t)
        assertEquals(Level.CRITICAL, v.level)
        assertEquals(Reason.CLONE_SIGNATURE, v.reason)

        // The whole point: a naive average of 0.88 similarity and 0.93 synthetic would sit in the
        // middle of the range and read as unremarkable. The combination must not soften.
        val average = (0.88f + (1f - 0.93f)) / 2f
        assertTrue("guard: average of the two signals is misleadingly mid-range", average in 0.4f..0.6f)
        assertNotEquals(Level.SUSPICIOUS, v.level)
    }

    @Test
    fun `low similarity is not the claimed contact regardless of synthetic score`() {
        val quiet = Fusion.fuse(0.20f, 0.02f, "Arun", t)
        val loud = Fusion.fuse(0.20f, 0.99f, "Arun", t)

        assertEquals(Reason.NOT_CLAIMED_CONTACT, quiet.reason)
        assertEquals(Reason.NOT_CLAIMED_CONTACT, loud.reason)
        assertEquals("synthetic score must not change the identity finding", quiet.reason, loud.reason)
    }

    @Test
    fun `borderline similarity is never reported as safe`() {
        val v = Fusion.fuse(0.68f, 0.01f, "Arun", t)
        assertEquals(Level.SUSPICIOUS, v.level)
        assertEquals(Reason.BORDERLINE_SIMILARITY, v.reason)
    }

    @Test
    fun `match with elevated but sub-threshold synthetic is suspicious`() {
        val v = Fusion.fuse(0.90f, 0.40f, "Arun", t)
        assertEquals(Level.SUSPICIOUS, v.level)
        assertEquals(Reason.POSSIBLE_SYNTHESIS, v.reason)
    }

    @Test
    fun `spoof-only run cannot be safe because identity was never checked`() {
        val clean = Fusion.fuse(null, 0.02f, null, t)
        assertEquals(Level.INDETERMINATE, clean.level)
        assertEquals(Reason.NO_VOICEPRINT_SELECTED, clean.reason)

        val synthetic = Fusion.fuse(null, 0.95f, null, t)
        assertEquals(Level.SUSPICIOUS, synthetic.level)
        assertEquals(Reason.SYNTHETIC_UNKNOWN_SPEAKER, synthetic.reason)
    }

    @Test
    fun `missing anti-spoofing score refuses to grade rather than trusting similarity alone`() {
        val v = Fusion.fuse(0.95f, null, "Arun", t)
        assertEquals(Level.INDETERMINATE, v.level)
        assertEquals(Reason.PIPELINE_UNAVAILABLE, v.reason)
    }

    @Test
    fun `exact threshold values count as meeting the threshold`() {
        val atMatch = Fusion.fuse(t.similarityHigh, 0.0f, "Arun", t)
        assertEquals(Level.SAFE, atMatch.level)

        val atSynthetic = Fusion.fuse(t.similarityHigh, t.syntheticHigh, "Arun", t)
        assertEquals(Level.CRITICAL, atSynthetic.level)
    }

    @Test
    fun `critical headline names both findings in plain language`() {
        val headline = Fusion.fuse(0.9f, 0.9f, "Priya", t).headline()
        assertTrue(headline.contains("Priya"))
        assertTrue(headline.contains("computer-generated"))
    }

    @Test
    fun `severity ordering supports latching the worst result`() {
        assertTrue(Level.CRITICAL.severity > Level.SUSPICIOUS.severity)
        assertTrue(Level.SUSPICIOUS.severity > Level.SAFE.severity)
        assertTrue(Level.SAFE.severity > Level.INDETERMINATE.severity)
    }
}

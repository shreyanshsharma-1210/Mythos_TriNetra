package com.mythos.vcd

import com.mythos.vcd.audio.AudioWindow
import com.mythos.vcd.ml.Vec
import com.mythos.vcd.pipeline.Fusion
import com.mythos.vcd.pipeline.Level
import com.mythos.vcd.pipeline.Reason
import com.mythos.vcd.pipeline.SessionScores
import com.mythos.vcd.pipeline.VerificationPipeline
import com.mythos.vcd.pipeline.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScoresTest {

    private fun analysis(
        level: Level,
        reason: Reason,
        similarity: Float?,
        synthetic: Float?,
    ) = VerificationPipeline.Analysis(
        verdict = Verdict(level, reason, similarity, synthetic, "Arun"),
        rms = 0.1f,
        startSeconds = 0f,
        provenance = AudioWindow.Provenance.FILE,
        inferenceMillis = 12,
    )

    @Test
    fun `a critical window stays latched even if later windows look clean`() {
        var scores = SessionScores()
        scores = scores.accept(analysis(Level.SAFE, Reason.MATCH_AUTHENTIC, 0.9f, 0.05f))
        scores = scores.accept(analysis(Level.CRITICAL, Reason.CLONE_SIGNATURE, 0.9f, 0.95f))
        scores = scores.accept(analysis(Level.SAFE, Reason.MATCH_AUTHENTIC, 0.9f, 0.05f))

        assertEquals("the headline follows the latest window", Level.SAFE, scores.currentLevel)
        assertEquals("but the worst result is remembered", Level.CRITICAL, scores.peakLevel)
        assertEquals(Reason.CLONE_SIGNATURE, scores.peakVerdict?.reason)
    }

    @Test
    fun `unmeasured windows are counted but do not move the status`() {
        var scores = SessionScores()
        scores = scores.accept(analysis(Level.SUSPICIOUS, Reason.NOT_CLAIMED_CONTACT, 0.2f, 0.1f))
        scores = scores.accept(analysis(Level.INDETERMINATE, Reason.NO_SPEECH, null, null))

        assertEquals(Level.SUSPICIOUS, scores.currentLevel)
        assertEquals(1, scores.measuredWindows)
        assertEquals(1, scores.skippedWindows)
    }

    @Test
    fun `smoothing moves toward the new value without jumping to it`() {
        var scores = SessionScores()
        scores = scores.accept(analysis(Level.SAFE, Reason.MATCH_AUTHENTIC, 0.9f, 0.1f))
        assertEquals(0.9f, scores.smoothedSimilarity!!, 1e-5f)

        scores = scores.accept(analysis(Level.SAFE, Reason.MATCH_AUTHENTIC, 0.5f, 0.1f))
        val expected = 0.9f * (1f - SessionScores.ALPHA) + 0.5f * SessionScores.ALPHA
        assertEquals(expected, scores.smoothedSimilarity!!, 1e-5f)
        assertTrue(scores.smoothedSimilarity!! > 0.5f)
    }

    @Test
    fun `a null score leaves the smoothed value untouched instead of resetting it`() {
        var scores = SessionScores()
        scores = scores.accept(analysis(Level.SAFE, Reason.MATCH_AUTHENTIC, 0.8f, 0.1f))
        scores = scores.accept(analysis(Level.INDETERMINATE, Reason.NO_SPEECH, null, null))
        assertEquals(0.8f, scores.smoothedSimilarity!!, 1e-5f)
    }

    @Test
    fun `history is bounded so a long call cannot grow without limit`() {
        var scores = SessionScores()
        repeat(SessionScores.MAX_HISTORY + 50) {
            scores = scores.accept(analysis(Level.SAFE, Reason.MATCH_AUTHENTIC, 0.9f, 0.05f))
        }
        assertEquals(SessionScores.MAX_HISTORY, scores.history.size)
    }

    /**
     * A window whose per-window verdict comes from the real fusion rules, so the raw level and the
     * stabilised one can disagree the way they do on a live call.
     */
    private fun scored(similarity: Float, synthetic: Float): VerificationPipeline.Analysis {
        val verdict = Fusion.fuse(similarity, synthetic, "Arun")
        return VerificationPipeline.Analysis(
            verdict = verdict,
            rms = 0.1f,
            startSeconds = 0f,
            provenance = AudioWindow.Provenance.REMOTE_VOIP,
            inferenceMillis = 12,
        )
    }

    @Test
    fun `nothing is claimed until enough windows have been measured`() {
        var scores = SessionScores()
        repeat(SessionScores.MIN_WINDOWS - 1) { scores = scores.accept(scored(0.9f, 0.05f)) }

        assertEquals(Level.INDETERMINATE, scores.stableLevel)
        assertTrue("the UI should still say it is listening", scores.stillListening)

        scores = scores.accept(scored(0.9f, 0.05f))
        assertEquals(Level.SAFE, scores.stableLevel)
        assertTrue(!scores.stillListening)
    }

    @Test
    fun `one noisy window does not flip the status`() {
        // The complaint this exists for: the same person talking produced SAFE, then SUSPICIOUS,
        // then CRITICAL on consecutive windows, because scores near a threshold cross it through
        // ordinary variation. A status that changes every three seconds carries no information.
        var scores = SessionScores()
        repeat(4) { scores = scores.accept(scored(0.90f, 0.05f)) }
        assertEquals(Level.SAFE, scores.stableLevel)

        // One window lands on the clone signature. It must not become the call's verdict alone.
        scores = scores.accept(scored(0.90f, 0.99f))
        assertEquals("a single window cannot escalate the call", Level.SAFE, scores.stableLevel)
        assertEquals(
            "but the raw window is still recorded",
            Level.CRITICAL,
            scores.currentLevel,
        )
    }

    @Test
    fun `a sustained finding does escalate`() {
        var scores = SessionScores()
        repeat(4) { scores = scores.accept(scored(0.90f, 0.05f)) }
        assertEquals(Level.SAFE, scores.stableLevel)

        // Enough windows for the median itself to move, then held long enough to be believed.
        repeat(6) { scores = scores.accept(scored(0.90f, 0.99f)) }
        assertEquals(Level.CRITICAL, scores.stableLevel)
        assertEquals(Reason.CLONE_SIGNATURE, scores.stableVerdict?.reason)
    }

    @Test
    fun `a finding is not withdrawn by one clean window`() {
        var scores = SessionScores()
        repeat(8) { scores = scores.accept(scored(0.90f, 0.99f)) }
        assertEquals(Level.CRITICAL, scores.stableLevel)

        scores = scores.accept(scored(0.90f, 0.02f))
        assertEquals(
            "de-escalation is deliberately slower than escalation",
            Level.CRITICAL,
            scores.stableLevel,
        )
    }

    @Test
    fun `the stable peak ignores a single spurious window`() {
        // peakVerdict drives the full-screen CRITICAL takeover. Latching it on one raw window meant
        // a single noisy frame produced a permanent clone accusation for the rest of the call.
        var scores = SessionScores()
        repeat(4) { scores = scores.accept(scored(0.90f, 0.05f)) }
        scores = scores.accept(scored(0.90f, 0.99f))
        repeat(4) { scores = scores.accept(scored(0.90f, 0.05f)) }

        assertEquals("the raw peak still remembers it", Level.CRITICAL, scores.peakLevel)
        assertEquals("the alert does not fire on it", Level.SAFE, scores.stablePeakLevel)
    }

    @Test
    fun `the stable decision uses the median so one outlier cannot drag it`() {
        var scores = SessionScores()
        // Four clean windows and one wild one: the median is clean, the mean would not be.
        scores = scores.accept(scored(0.90f, 0.02f))
        scores = scores.accept(scored(0.90f, 0.02f))
        scores = scores.accept(scored(0.90f, 1.00f))
        scores = scores.accept(scored(0.90f, 0.02f))
        scores = scores.accept(scored(0.90f, 0.02f))

        assertEquals(Level.SAFE, scores.stableLevel)
    }

    @Test
    fun `a fresh session has no measurement`() {
        val scores = SessionScores()
        assertTrue(!scores.hasAnyMeasurement)
        assertNull(scores.smoothedSimilarity)
        assertEquals(Level.INDETERMINATE, scores.currentLevel)
    }
}

class VecTest {

    @Test
    fun `cosine of a vector with itself is one`() {
        val v = floatArrayOf(0.3f, -0.5f, 0.81f, 0.02f)
        assertEquals(1f, Vec.cosine(v, v), 1e-6f)
    }

    @Test
    fun `cosine of opposed vectors is minus one and stays in range`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        assertEquals(-1f, Vec.cosine(a, b), 1e-6f)
        assertTrue(Vec.cosine(a, b) >= -1f)
    }

    @Test
    fun `cosine with a zero vector is zero rather than NaN`() {
        val zero = FloatArray(8)
        val v = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        assertEquals(0f, Vec.cosine(zero, v), 0f)
    }

    @Test
    fun `l2Normalize produces a unit vector`() {
        val v = floatArrayOf(3f, 4f)
        val n = Vec.l2Normalize(v)
        assertEquals(1f, Vec.l2Norm(n), 1e-6f)
        assertEquals(0.6f, n[0], 1e-6f)
    }

    @Test
    fun `l2Normalize leaves a zero vector alone`() {
        val n = Vec.l2Normalize(FloatArray(4))
        assertEquals(0f, Vec.l2Norm(n), 0f)
    }

    @Test
    fun `mean averages element-wise`() {
        val m = Vec.mean(listOf(floatArrayOf(0f, 2f), floatArrayOf(2f, 4f)))
        assertEquals(1f, m[0], 1e-6f)
        assertEquals(3f, m[1], 1e-6f)
    }

    @Test
    fun `byte round-trip is exact`() {
        val v = FloatArray(256) { (it - 128) / 137f }
        val back = Vec.fromBytes(Vec.toBytes(v))
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched dimensions are rejected rather than silently truncated`() {
        Vec.cosine(FloatArray(4), FloatArray(8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a truncated voiceprint blob is rejected`() {
        Vec.fromBytes(ByteArray(7))
    }
}

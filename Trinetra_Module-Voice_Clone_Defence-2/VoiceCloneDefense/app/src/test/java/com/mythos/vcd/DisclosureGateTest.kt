package com.mythos.vcd

import com.mythos.vcd.service.DisclosureGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The interlock behind hard constraint 3: capture never starts without the disclosure banner
 * already on screen.
 *
 * These are unit tests of the gate itself. The service-side enforcement — refusing to open the
 * microphone while the gate is shut, and stopping capture if it closes mid-session — lives in
 * LiveVerificationService and needs a device to exercise.
 */
class DisclosureGateTest {

    @Before
    fun reset() = DisclosureGate.markHidden()

    @After
    fun tearDown() = DisclosureGate.markHidden()

    @Test
    fun `gate starts shut`() {
        assertTrue(!DisclosureGate.isOpen)
        assertNull(DisclosureGate.shownAtMs.value)
    }

    @Test
    fun `showing the banner opens the gate and records when`() {
        val before = System.currentTimeMillis()
        DisclosureGate.markShown()
        val after = System.currentTimeMillis()

        assertTrue(DisclosureGate.isOpen)
        val at = DisclosureGate.shownAtMs.value
        assertNotNull(at)
        assertTrue("timestamp outside the window it was taken in", at!! in before..after)
    }

    @Test
    fun `marking shown twice keeps the original timestamp`() {
        DisclosureGate.markShown()
        val first = DisclosureGate.shownAtMs.value
        Thread.sleep(5)
        DisclosureGate.markShown()
        assertEquals(
            "recomposition must not reset the disclosure clock",
            first,
            DisclosureGate.shownAtMs.value,
        )
    }

    @Test
    fun `hiding the banner shuts the gate`() {
        DisclosureGate.markShown()
        DisclosureGate.markHidden()
        assertTrue(!DisclosureGate.isOpen)
        assertNull(DisclosureGate.shownAtMs.value)
    }

    @Test
    fun `latency is measured from the banner appearing, and stays positive`() {
        DisclosureGate.markShown()
        val shownAt = DisclosureGate.shownAtMs.value!!

        // FR-VOICE-CAP-2 requires the banner within 1 s of capture starting. A negative value here
        // would mean capture led the banner, which is the failure the interlock exists to prevent.
        val latency = DisclosureGate.latencyMsAt(shownAt + 250)
        assertEquals(250L, latency)
        assertTrue(latency!! >= 0)
        assertTrue("banner must precede capture by well under a second", latency < 1_000)
    }

    @Test
    fun `latency is null when the banner is not showing`() {
        assertNull(DisclosureGate.latencyMsAt(System.currentTimeMillis()))
    }
}

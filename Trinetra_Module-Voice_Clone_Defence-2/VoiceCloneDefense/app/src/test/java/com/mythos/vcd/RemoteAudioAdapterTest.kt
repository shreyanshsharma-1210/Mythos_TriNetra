package com.mythos.vcd

import com.mythos.vcd.audio.AudioConstants
import com.mythos.vcd.audio.AudioWindow
import com.mythos.vcd.voip.RemoteAudioAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The WebRTC-to-pipeline seam, tested without a device or a call.
 *
 * Everything here is arithmetic on buffers, and getting it wrong would be close to invisible: a
 * byte-order mistake or a channel-averaging slip still produces plausible-looking floats and a
 * plausible-looking score, from audio that was never on the wire. That is precisely the failure
 * mode this app exists to avoid, so it is worth pinning down on the JVM rather than discovering it
 * in a logcat line during a two-phone test.
 */
class RemoteAudioAdapterTest {

    private val webrtcRate = 48_000
    private val frameSamples = webrtcRate / 100 // WebRTC delivers 10 ms buffers

    @Test
    fun `mono 16-bit frames are scaled into the range the models expect`() {
        val adapter = RemoteAudioAdapter()
        val peakSample = 16_384.toShort() // exactly half of full scale
        adapter.feed(shortArrayOf(0, peakSample, (-peakSample).toShort()), channels = 1)

        val d = adapter.diagnostics.value
        assertTrue("adapter should report the track as attached", d.trackAttached)
        assertEquals(webrtcRate, d.sampleRate)
        assertEquals(1, d.channels)
        assertEquals(16, d.bitsPerSample)
        assertEquals(0.5f, d.peak, 1e-4f)
    }

    @Test
    fun `stereo frames are averaged to mono rather than read as twice the frames`() {
        val adapter = RemoteAudioAdapter()
        // Left at +half scale, right at -half scale: a correct average is silence. Reading the
        // buffer as mono would instead produce a full-amplitude square wave.
        val interleaved = ShortArray(frameSamples * 2)
        for (i in 0 until frameSamples) {
            interleaved[i * 2] = 16_384
            interleaved[i * 2 + 1] = -16_384
        }
        adapter.feed(interleaved, channels = 2)

        val d = adapter.diagnostics.value
        assertEquals(2, d.channels)
        assertEquals("opposite channels must cancel", 0f, d.peak, 1e-4f)
        assertEquals("one stereo buffer is one frame, not two", 1L, d.framesReceived)
    }

    @Test
    fun `a short buffer yields no window rather than a silence-padded one`() {
        val adapter = RemoteAudioAdapter()
        repeat(10) { adapter.feed(ShortArray(frameSamples), channels = 1) }

        assertNull(
            "a partial window padded with silence would be a score from audio never received",
            adapter.latestWindow(0),
        )
    }

    @Test
    fun `a full window comes out at exactly the model's input width and sample rate`() {
        val adapter = RemoteAudioAdapter()
        feedSeconds(adapter, seconds = 5.0, hz = 440.0)

        val window = adapter.latestWindow(startSampleIndex = 0)
        assertNotNull("five seconds of 48 kHz audio should fill one window", window)
        assertEquals(AudioConstants.WINDOW_SAMPLES, window!!.samples.size)
        assertEquals(AudioWindow.Provenance.REMOTE_VOIP, window.provenance)

        // 64600 samples at 16 kHz is 4.0375 s, which is what the AASIST checkpoint was built for.
        assertEquals(4.0375f, window.samples.size / AudioConstants.SAMPLE_RATE.toFloat(), 1e-4f)
    }

    @Test
    fun `resampling preserves the signal instead of producing noise`() {
        val adapter = RemoteAudioAdapter()
        feedSeconds(adapter, seconds = 5.0, hz = 440.0, amplitude = 0.5)

        val window = adapter.latestWindow(0)!!
        val samples = window.samples

        // A 440 Hz tone at amplitude 0.5 survives 48k -> 16k with its level intact. If the
        // resampler were misconfigured, or the buffer were being read at the wrong rate, the level
        // would collapse or the output would be broadband junk.
        var peak = 0f
        var sumSquares = 0.0
        // Skip the filter's edge transient at both ends.
        val margin = 2_000
        for (i in margin until samples.size - margin) {
            val a = abs(samples[i])
            if (a > peak) peak = a
            sumSquares += samples[i].toDouble() * samples[i]
        }
        val rms = Math.sqrt(sumSquares / (samples.size - 2 * margin)).toFloat()

        assertEquals("peak should survive resampling", 0.5f, peak, 0.02f)
        // RMS of a sine is peak / sqrt(2).
        assertEquals("rms should match a clean sine", 0.354f, rms, 0.02f)
    }

    @Test
    fun `sustained digital silence is reported as a finding, not as quiet audio`() {
        val adapter = RemoteAudioAdapter()
        repeat(SILENT_FRAMES) { adapter.feed(ShortArray(frameSamples), channels = 1) }

        val d = adapter.diagnostics.value
        assertTrue("a connected but silent track is its own diagnosis", d.silent)
        assertEquals(0L, d.voicedFrames)
        assertTrue(d.framesReceived > 0)
    }

    @Test
    fun `zeroize scrubs buffered audio and resets the diagnostics`() {
        val adapter = RemoteAudioAdapter()
        feedSeconds(adapter, seconds = 5.0, hz = 300.0)
        assertNotNull(adapter.latestWindow(0))

        adapter.zeroize()

        assertNull("buffered call audio must not survive the call", adapter.latestWindow(0))
        assertEquals(0L, adapter.diagnostics.value.framesReceived)
        assertTrue(!adapter.diagnostics.value.trackAttached)
    }

    private fun feedSeconds(
        adapter: RemoteAudioAdapter,
        seconds: Double,
        hz: Double,
        amplitude: Double = 0.8,
    ) {
        val frames = (seconds * 100).toInt()
        var phase = 0.0
        val step = 2 * PI * hz / webrtcRate
        val buffer = ShortArray(frameSamples)
        repeat(frames) {
            for (i in 0 until frameSamples) {
                buffer[i] = (sin(phase) * amplitude * 32_767).toInt().toShort()
                phase += step
            }
            adapter.feed(buffer, channels = 1)
        }
    }

    private companion object {
        /** 2 s of silence is what CallDiagnostics treats as a real finding; feed a little more. */
        const val SILENT_FRAMES = 210
    }

    /** Mimics what WebRTC hands to [RemoteAudioAdapter.onData]. */
    private fun RemoteAudioAdapter.feed(samples: ShortArray, channels: Int) {
        val bytes = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        bytes.rewind()
        onData(
            bytes,
            /* bitsPerSample = */ 16,
            /* sampleRate = */ webrtcRate,
            /* numberOfChannels = */ channels,
            /* numberOfFrames = */ samples.size / channels,
            /* absoluteCaptureTimestampMs = */ 0L,
        )
    }
}

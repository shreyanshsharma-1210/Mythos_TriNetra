package com.mythos.vcd

import com.mythos.vcd.audio.AudioConstants
import com.mythos.vcd.audio.AudioNormalize
import com.mythos.vcd.audio.AudioRingBuffer
import com.mythos.vcd.audio.AudioWindow
import com.mythos.vcd.audio.SincResampler
import com.mythos.vcd.audio.WavIo
import com.mythos.vcd.audio.WindowSlicer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioRingBufferTest {

    @Test
    fun `returns null until a full window has been captured`() {
        val ring = AudioRingBuffer(100)
        ring.write(FloatArray(40) { 1f })
        assertNull("a short read would become a silence-padded window", ring.readLatest(50))
        ring.write(FloatArray(20) { 1f })
        assertNotNull(ring.readLatest(50))
    }

    @Test
    fun `readLatest returns the most recent samples in chronological order across a wrap`() {
        val ring = AudioRingBuffer(10)
        ring.write(FloatArray(16) { it.toFloat() }) // wraps: keeps 6..15
        val out = ring.readLatest(10)!!
        assertEquals(listOf(6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f), out.toList())
    }

    @Test
    fun `a chunk longer than the buffer keeps only its tail`() {
        val ring = AudioRingBuffer(4)
        ring.write(FloatArray(10) { it.toFloat() })
        assertEquals(listOf(6f, 7f, 8f, 9f), ring.readLatest(4)!!.toList())
        assertEquals(10L, ring.samplesWritten)
    }

    @Test
    fun `zeroize scrubs the buffer and resets the fill state`() {
        val ring = AudioRingBuffer(8)
        ring.write(FloatArray(8) { 0.5f })
        ring.zeroize()
        assertNull(ring.readLatest(8))
        assertEquals(0L, ring.samplesWritten)
    }
}

class WindowSlicerTest {

    @Test
    fun `a clip shorter than one window yields nothing rather than padded silence`() {
        val short = FloatArray(AudioConstants.WINDOW_SAMPLES - 1) { 0.1f }
        assertTrue(WindowSlicer.slice(short).isEmpty())
    }

    @Test
    fun `windows are exactly one window wide and stepped by the hop`() {
        val samples = FloatArray(AudioConstants.WINDOW_SAMPLES * 3) { 0.1f }
        val windows = WindowSlicer.slice(samples)
        assertTrue(windows.isNotEmpty())
        windows.forEach { assertEquals(AudioConstants.WINDOW_SAMPLES, it.samples.size) }
        assertEquals(0L, windows.first().startSampleIndex)
        assertEquals(
            AudioConstants.FILE_HOP_SAMPLES.toLong(),
            windows[1].startSampleIndex,
        )
    }

    @Test
    fun `the tail of a clip is not silently dropped`() {
        // Length chosen so the last window does not fall on a hop boundary.
        val length = AudioConstants.WINDOW_SAMPLES + AudioConstants.FILE_HOP_SAMPLES + 777
        val windows = WindowSlicer.slice(FloatArray(length) { 0.1f })
        val lastStart = (length - AudioConstants.WINDOW_SAMPLES).toLong()
        assertEquals(lastStart, windows.last().startSampleIndex)
    }

    @Test
    fun `provenance travels with the samples`() {
        val windows = WindowSlicer.slice(FloatArray(AudioConstants.WINDOW_SAMPLES) { 0.1f })
        assertEquals(AudioWindow.Provenance.FILE, windows.single().provenance)
    }
}

class SincResamplerTest {

    @Test
    fun `downsampling preserves a tone that fits inside the target band`() {
        val fromRate = 48_000
        val toRate = 16_000
        val freq = 440.0
        val input = FloatArray(fromRate) { sin(2 * PI * freq * it / fromRate).toFloat() }

        val out = SincResampler.resample(input, fromRate, toRate)

        assertEquals(toRate, out.size)
        // Compare against an ideal 16 kHz tone, ignoring filter edges.
        val expected = FloatArray(toRate) { sin(2 * PI * freq * it / toRate).toFloat() }
        var maxErr = 0f
        for (i in 200 until toRate - 200) maxErr = maxOf(maxErr, abs(out[i] - expected[i]))
        assertTrue("resampled tone drifted by $maxErr", maxErr < 0.02f)
    }

    @Test
    fun `identical rates are a no-op`() {
        val input = FloatArray(64) { it.toFloat() }
        assertTrue(SincResampler.resample(input, 16_000, 16_000) === input)
    }

    @Test
    fun `downsampling attenuates content above the new Nyquist instead of aliasing it`() {
        val fromRate = 48_000
        val toRate = 16_000
        // 15 kHz is far above the 8 kHz Nyquist of the target rate. Linear interpolation would
        // fold this down into the audible band as a loud phantom tone.
        val input = FloatArray(fromRate) { sin(2 * PI * 15_000.0 * it / fromRate).toFloat() }

        val out = SincResampler.resample(input, fromRate, toRate)

        var acc = 0.0
        for (i in 400 until out.size - 400) acc += out[i].toDouble() * out[i]
        val rms = sqrt(acc / (out.size - 800))
        assertTrue("out-of-band tone survived at RMS $rms — the anti-alias filter is not working", rms < 0.05)
    }
}

class AudioNormalizeTest {

    @Test
    fun `quiet audio is lifted to the target level`() {
        val quiet = FloatArray(16_000) { (0.001 * sin(2 * PI * 200 * it / 16_000.0)).toFloat() }
        val out = AudioNormalize.toTargetDbfs(quiet)
        assertEquals(AudioNormalize.TARGET_DBFS.toDouble(), dbfs(out), 0.01)
    }

    @Test
    fun `loud audio is left alone when increaseOnly is set`() {
        val loud = FloatArray(16_000) { (0.5 * sin(2 * PI * 200 * it / 16_000.0)).toFloat() }
        val out = AudioNormalize.toTargetDbfs(loud, increaseOnly = true)
        assertTrue("loud audio should be returned untouched", out === loud)
    }

    @Test
    fun `loud audio is pulled down when increaseOnly is off`() {
        val loud = FloatArray(16_000) { (0.5 * sin(2 * PI * 200 * it / 16_000.0)).toFloat() }
        val out = AudioNormalize.toTargetDbfs(loud, increaseOnly = false)
        assertEquals(AudioNormalize.TARGET_DBFS.toDouble(), dbfs(out), 0.01)
    }

    @Test
    fun `digital silence is returned unchanged rather than amplified into noise`() {
        val silence = FloatArray(1000)
        assertTrue(AudioNormalize.toTargetDbfs(silence) === silence)
    }

    private fun dbfs(v: FloatArray): Double {
        var acc = 0.0
        for (x in v) acc += x.toDouble() * x
        return 20 * log10(sqrt(acc / v.size))
    }
}

class WavIoTest {

    @Test
    fun `round-trips 16-bit mono PCM`() {
        val samples = FloatArray(1000) { sin(2 * PI * 440 * it / 16_000.0).toFloat() * 0.5f }
        val wav = writeWav(samples, 16_000, 1)

        val read = WavIo.read(ByteArrayInputStream(wav))

        assertEquals(16_000, read.sampleRate)
        assertEquals(samples.size, read.samples.size)
        for (i in samples.indices) {
            assertTrue(abs(samples[i] - read.samples[i]) < 1e-4f)
        }
    }

    @Test
    fun `downmixes stereo to mono by averaging`() {
        val left = 0.8f
        val right = -0.4f
        val interleaved = FloatArray(200) { if (it % 2 == 0) left else right }
        val wav = writeWav(interleaved, 16_000, 2)

        val read = WavIo.read(ByteArrayInputStream(wav))

        assertEquals(100, read.samples.size)
        assertEquals((left + right) / 2f, read.samples[0], 1e-4f)
    }

    @Test
    fun `skips unknown chunks before the data chunk`() {
        val samples = FloatArray(64) { 0.25f }
        val wav = writeWav(samples, 16_000, 1, extraChunk = "LIST" to ByteArray(9) { 7 })
        val read = WavIo.read(ByteArrayInputStream(wav))
        assertEquals(64, read.samples.size)
        assertEquals(0.25f, read.samples[0], 1e-4f)
    }

    @Test
    fun `recognises a RIFF WAVE header`() {
        val wav = writeWav(FloatArray(16), 16_000, 1)
        assertTrue(WavIo.looksLikeWav(wav.copyOfRange(0, 12)))
        assertTrue(!WavIo.looksLikeWav("not audio at".toByteArray()))
    }

    private fun writeWav(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        extraChunk: Pair<String, ByteArray>? = null,
    ): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        val pcm = data.array()

        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)                                  // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)            // byte rate
            putShort((channels * 2).toShort())           // block align
            putShort(16)                                 // bits per sample
        }.array()

        val body = ByteArrayOutputStream()
        body.write("fmt ".toByteArray()); body.write(le32(fmt.size)); body.write(fmt)
        extraChunk?.let { (id, payload) ->
            body.write(id.toByteArray())
            body.write(le32(payload.size))
            body.write(payload)
            if (payload.size % 2 == 1) body.write(0) // pad to word boundary
        }
        body.write("data".toByteArray()); body.write(le32(pcm.size)); body.write(pcm)

        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(le32(4 + body.size()))
        out.write("WAVE".toByteArray())
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun le32(v: Int) =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}

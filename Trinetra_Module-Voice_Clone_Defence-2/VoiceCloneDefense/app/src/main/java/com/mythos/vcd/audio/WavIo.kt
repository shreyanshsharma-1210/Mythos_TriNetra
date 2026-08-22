package com.mythos.vcd.audio

import java.io.EOFException
import java.io.InputStream

/**
 * Minimal RIFF/WAVE reader for PCM 8/16/24/32-bit and IEEE float, mono or multi-channel.
 *
 * Android's MediaExtractor handles WAV perfectly well, but it does not exist on a plain JVM. This
 * reader is what lets the model-parity harness and the pipeline unit tests run with no device and
 * no emulator attached, which is the difference between a pipeline we have tested and one we have
 * merely written.
 */
object WavIo {

    data class Pcm(val samples: FloatArray, val sampleRate: Int) {
        override fun equals(other: Any?) = this === other ||
            (other is Pcm && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

        override fun hashCode() = samples.contentHashCode() * 31 + sampleRate
    }

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun looksLikeWav(header: ByteArray): Boolean =
        header.size >= 12 &&
            header.ascii(0, 4) == "RIFF" &&
            header.ascii(8, 4) == "WAVE"

    /** Reads a WAV stream and returns mono float samples at the rate declared by the file. */
    fun read(input: InputStream): Pcm {
        val riff = input.readExactly(12)
        require(looksLikeWav(riff)) { "not a RIFF/WAVE stream" }

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        while (true) {
            val header = try {
                input.readExactly(8)
            } catch (e: EOFException) {
                throw IllegalArgumentException("WAVE stream ended before a data chunk was found", e)
            }
            val id = header.ascii(0, 4)
            val size = header.le32(4)
            when (id) {
                "fmt " -> {
                    val fmt = input.readExactly(size)
                    format = fmt.le16(0)
                    channels = fmt.le16(2)
                    sampleRate = fmt.le32(4)
                    bitsPerSample = fmt.le16(14)
                    // WAVE_FORMAT_EXTENSIBLE hides the real tag in the sub-format GUID.
                    if (format == FORMAT_EXTENSIBLE && size >= 26) format = fmt.le16(24)
                }

                "data" -> {
                    require(format == FORMAT_PCM || format == FORMAT_FLOAT) {
                        "unsupported WAVE format tag $format (only PCM and IEEE float are handled)"
                    }
                    require(channels > 0 && sampleRate > 0) { "data chunk arrived before fmt chunk" }
                    val raw = input.readExactly(size)
                    return Pcm(decode(raw, format, bitsPerSample, channels), sampleRate)
                }

                else -> input.skipExactly(size.toLong() + (size and 1)) // chunks are word-aligned
            }
        }
    }

    private fun decode(raw: ByteArray, format: Int, bits: Int, channels: Int): FloatArray {
        val bytesPerSample = bits / 8
        require(bytesPerSample > 0) { "unsupported bit depth $bits" }
        val frames = raw.size / (bytesPerSample * channels)
        val out = FloatArray(frames)
        var b = 0
        for (f in 0 until frames) {
            var acc = 0.0
            for (c in 0 until channels) {
                acc += when {
                    format == FORMAT_FLOAT && bits == 32 -> Float.fromBits(raw.le32(b)).toDouble()
                    format == FORMAT_FLOAT && bits == 64 -> Double.fromBits(raw.le64(b))
                    bits == 8 -> ((raw[b].toInt() and 0xFF) - 128) / 128.0 // 8-bit WAV is unsigned
                    bits == 16 -> raw.le16Signed(b) / 32768.0
                    bits == 24 -> raw.le24Signed(b) / 8388608.0
                    bits == 32 -> raw.le32(b) / 2147483648.0
                    else -> throw IllegalArgumentException("unsupported bit depth $bits")
                }
                b += bytesPerSample
            }
            out[f] = (acc / channels).toFloat()
        }
        return out
    }

    private fun ByteArray.ascii(off: Int, len: Int) = String(this, off, len, Charsets.US_ASCII)

    private fun ByteArray.le16(off: Int) =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le16Signed(off: Int) = le16(off).toShort().toInt()

    private fun ByteArray.le24Signed(off: Int): Int {
        val v = (this[off].toInt() and 0xFF) or
            ((this[off + 1].toInt() and 0xFF) shl 8) or
            ((this[off + 2].toInt() and 0xFF) shl 16)
        return if (v and 0x800000 != 0) v or -0x1000000 else v
    }

    private fun ByteArray.le32(off: Int) = (this[off].toInt() and 0xFF) or
        ((this[off + 1].toInt() and 0xFF) shl 8) or
        ((this[off + 2].toInt() and 0xFF) shl 16) or
        ((this[off + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.le64(off: Int): Long =
        (le32(off).toLong() and 0xFFFFFFFFL) or (le32(off + 4).toLong() shl 32)

    private fun InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(out, read, n - read)
            if (r < 0) throw EOFException("wanted $n bytes, stream ended after $read")
            read += r
        }
        return out
    }

    private fun InputStream.skipExactly(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val s = skip(remaining)
            if (s <= 0) {
                if (read() < 0) return
                remaining--
                continue
            }
            remaining -= s
        }
    }
}

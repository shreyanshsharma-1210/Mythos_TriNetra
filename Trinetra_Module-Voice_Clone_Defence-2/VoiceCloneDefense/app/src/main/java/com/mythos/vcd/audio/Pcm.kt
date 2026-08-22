package com.mythos.vcd.audio

/** Conversions between the PCM 16-bit shorts Android hands us and the floats the models want. */
object Pcm {

    private const val SCALE = 1f / 32768f

    fun shortsToFloat(
        src: ShortArray,
        length: Int = src.size,
        dst: FloatArray = FloatArray(length),
    ): FloatArray {
        for (i in 0 until length) dst[i] = src[i] * SCALE
        return dst
    }

    /** Little-endian interleaved 16-bit PCM to mono float, averaging channels. */
    fun bytesToMonoFloat(src: ByteArray, byteLength: Int, channels: Int): FloatArray {
        require(channels >= 1)
        val frames = byteLength / (2 * channels)
        val out = FloatArray(frames)
        var b = 0
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val lo = src[b].toInt() and 0xFF
                val hi = src[b + 1].toInt()
                acc += (hi shl 8) or lo
                b += 2
            }
            out[f] = (acc.toFloat() / channels) * SCALE
        }
        return out
    }
}

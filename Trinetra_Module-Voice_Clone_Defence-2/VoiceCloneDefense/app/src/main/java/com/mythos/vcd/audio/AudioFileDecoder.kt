package com.mythos.vcd.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream

/**
 * Decodes any audio file the platform can open into 16 kHz mono float — the exact shape the
 * pipeline takes from the microphone.
 *
 * This is what makes Test Mode a real test rather than a separate demo path: after this function
 * returns, nothing downstream can tell whether the samples came from a file or from a live call.
 */
object AudioFileDecoder {

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Decoded(
        val samples: FloatArray,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val mimeType: String?,
    ) {
        val durationSeconds: Float get() = samples.size.toFloat() / AudioConstants.SAMPLE_RATE

        override fun equals(other: Any?) = this === other ||
            (other is Decoded && samples.contentEquals(other.samples) &&
                sourceSampleRate == other.sourceSampleRate)

        override fun hashCode() = samples.contentHashCode() * 31 + sourceSampleRate
    }

    fun decode(context: Context, uri: Uri): Decoded {
        // WAV first. MediaExtractor handles it, but a pure-Kotlin path avoids codec quirks on odd
        // bit depths and is the same code the JVM tests exercise.
        sniffWav(context, uri)?.let { return it }

        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw DecodeException("Could not open the selected file.")

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw DecodeException("That file has no audio track.")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val pcm = decodeTrack(extractor, format, mime, sourceChannels)
            val mono = Pcm.bytesToMonoFloat(pcm, pcm.size, sourceChannels)
            val resampled = SincResampler.resample(mono, sourceRate, AudioConstants.SAMPLE_RATE)

            return Decoded(resampled, sourceRate, sourceChannels, mime)
        } catch (e: DecodeException) {
            throw e
        } catch (t: Throwable) {
            throw DecodeException("Could not decode that file: ${t.message}", t)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun sniffWav(context: Context, uri: Uri): Decoded? = try {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val stream = BufferedInputStream(raw, 64 * 1024)
            stream.mark(16)
            val header = ByteArray(12)
            val read = stream.read(header)
            stream.reset()
            if (read == 12 && WavIo.looksLikeWav(header)) {
                val pcm = WavIo.read(stream)
                Decoded(
                    samples = SincResampler.resample(
                        pcm.samples, pcm.sampleRate, AudioConstants.SAMPLE_RATE,
                    ),
                    sourceSampleRate = pcm.sampleRate,
                    sourceChannels = 1,
                    mimeType = "audio/wav",
                )
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "WAV fast path failed, falling back to MediaExtractor", t)
        null
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String,
        channels: Int,
    ): ByteArray {
        val codec = MediaCodec.createDecoderByType(mime)
        val out = ByteArrayOutputStream(1 shl 20)
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            // Some decoders emit float PCM; the flag only appears on the output format.
            var outputIsFloat = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        outputIsFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(chunk, 0, info.size)
                            out.write(if (outputIsFloat) floatPcmToShortPcm(chunk) else chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        require(out.size() >= 2 * channels) { "decoder produced no audio" }
        return out.toByteArray()
    }

    /** Normalises float-PCM decoder output down to the 16-bit little-endian path. */
    private fun floatPcmToShortPcm(src: ByteArray): ByteArray {
        val bb = java.nio.ByteBuffer.wrap(src).order(java.nio.ByteOrder.nativeOrder())
        val count = src.size / 4
        val out = java.nio.ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(count) {
            val v = (bb.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            out.putShort(v)
        }
        return out.array()
    }

    private const val TAG = "AudioFileDecoder"
    private const val TIMEOUT_US = 10_000L
}

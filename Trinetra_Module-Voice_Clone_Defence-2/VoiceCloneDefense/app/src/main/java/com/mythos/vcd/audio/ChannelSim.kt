package com.mythos.vcd.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * Puts enrolment audio through the same kind of channel a call arrives over.
 *
 * Enrolment records straight from the microphone: clean, full-band, no codec. A call arrives after
 * the sender's gain control, through a low-bitrate codec, band-limited, out of a jitter buffer.
 * Same voice, different signal — and the speaker encoder notices.
 *
 * Measured on two genuine recordings of one person (tools/channel_experiment.py):
 *
 *     enrolment channel     mic     voip-wb   voip-nb
 *     mic                 0.9370    0.9144    0.7655
 *     voip-wb             0.9074    0.9388    0.7898
 *     voip-nb             0.7335    0.7753    0.9766
 *
 * A microphone voiceprint scores 0.7655 against narrowband call audio from the same person — on
 * top of a 0.75 match threshold, which is how somebody's own voice comes back as "not confirmed".
 * Enrolling through the matching channel recovers it to 0.9766.
 *
 * So enrolment derives a voiceprint per condition and stores all of them. Scoring takes the best
 * match, which is the standard multi-condition enrolment trick and costs one cosine per extra
 * print — nothing next to an inference.
 *
 * These are simulations of a channel, not a specific codec. They reproduce the parts that move the
 * embedding — band-limiting, gain, quantisation noise — without needing an encoder on the device.
 */
object ChannelSim {

    enum class Condition(val label: String) {
        /** The recording as captured. */
        MIC("mic"),

        /** Wideband VoIP: gain-normalised, 100 Hz - 7 kHz, a little line noise. */
        VOIP_WIDEBAND("voip-wb"),

        /** Narrowband telephony: 300 Hz - 3.4 kHz at 8 kHz through G.711, back up to 16 kHz. */
        VOIP_NARROWBAND("voip-nb"),
        ;

        companion object {
            /** Conditions a voiceprint is derived for at enrolment. */
            val ENROLMENT = listOf(MIC, VOIP_WIDEBAND, VOIP_NARROWBAND)
        }
    }

    fun apply(
        samples: FloatArray,
        condition: Condition,
        rate: Int = AudioConstants.SAMPLE_RATE,
    ): FloatArray = when (condition) {
        Condition.MIC -> samples
        Condition.VOIP_WIDEBAND -> {
            val gained = AudioNormalize.toTargetDbfs(samples, targetDbfs = -23f, increaseOnly = false)
            addNoise(bandpass(gained, 100f, 7000f, rate), snrDb = 32f, seed = 1234)
        }

        Condition.VOIP_NARROWBAND -> {
            val gained = AudioNormalize.toTargetDbfs(samples, targetDbfs = -23f, increaseOnly = false)
            val limited = bandpass(gained, 300f, 3400f, rate)
            val down = SincResampler.resample(limited, rate, NARROWBAND_RATE)
            val coded = muLawRoundTrip(down)
            val up = SincResampler.resample(coded, NARROWBAND_RATE, rate)
            addNoise(up, snrDb = 28f, seed = 5678)
        }
    }

    /**
     * A real G.711 mu-law encode/decode.
     *
     * The 8-bit quantisation is the point: without it this is an identity function, and the whole
     * reason narrowband hurts the embedding is the coarse quantisation, not the bandwidth alone.
     */
    private fun muLawRoundTrip(samples: FloatArray): FloatArray {
        val mu = 255.0
        val lnMu = ln(1.0 + mu)
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            val x = samples[i].coerceIn(-1f, 1f).toDouble()
            val encoded = sign(x) * ln(1.0 + mu * abs(x)) / lnMu
            val quantised = Math.round((encoded + 1.0) * 127.5) / 127.5 - 1.0
            val decoded = sign(quantised) * (1.0 / mu) * ((1.0 + mu).pow(abs(quantised)) - 1.0)
            out[i] = decoded.toFloat()
        }
        return out
    }

    /**
     * Two cascaded biquad band-passes, giving roughly a fourth-order response.
     *
     * Steep enough that the band edges actually matter to the embedding, and cheap enough to run
     * three times over a minute of audio during enrolment without the user noticing.
     */
    private fun bandpass(samples: FloatArray, lowHz: Float, highHz: Float, rate: Int): FloatArray {
        val nyquist = rate / 2f
        val high = minOf(highHz, nyquist * 0.99f)
        var out = highPass(samples, lowHz, rate)
        out = highPass(out, lowHz, rate)
        out = lowPass(out, high, rate)
        out = lowPass(out, high, rate)
        return out
    }

    private fun highPass(samples: FloatArray, cutoffHz: Float, rate: Int): FloatArray {
        val w0 = 2.0 * PI * cutoffHz / rate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * Q)
        val b0 = (1 + cosW0) / 2
        val b1 = -(1 + cosW0)
        val b2 = (1 + cosW0) / 2
        val a0 = 1 + alpha
        val a1 = -2 * cosW0
        val a2 = 1 - alpha
        return biquad(samples, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun lowPass(samples: FloatArray, cutoffHz: Float, rate: Int): FloatArray {
        val w0 = 2.0 * PI * cutoffHz / rate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * Q)
        val b0 = (1 - cosW0) / 2
        val b1 = 1 - cosW0
        val b2 = (1 - cosW0) / 2
        val a0 = 1 + alpha
        val a1 = -2 * cosW0
        val a2 = 1 - alpha
        return biquad(samples, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun biquad(
        samples: FloatArray,
        b0: Double,
        b1: Double,
        b2: Double,
        a1: Double,
        a2: Double,
    ): FloatArray {
        val out = FloatArray(samples.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0.toFloat()
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }

    /** Deterministic, so a contact enrolled twice gets the same voiceprints. */
    private fun addNoise(samples: FloatArray, snrDb: Float, seed: Int): FloatArray {
        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        val rms = Math.sqrt(acc / samples.size)
        if (rms <= 0.0) return samples

        val noiseRms = rms / 10.0.pow(snrDb / 20.0)
        val random = Random(seed)
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            // Box-Muller, so the noise is Gaussian rather than uniform — uniform noise has a
            // different spectrum and the models are sensitive to exactly that.
            val u1 = (random.nextDouble() + 1e-12).coerceAtMost(1.0)
            val u2 = random.nextDouble()
            val gaussian = Math.sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
            out[i] = (samples[i] + noiseRms * gaussian).toFloat().coerceIn(-1f, 1f)
        }
        return out
    }

    private const val NARROWBAND_RATE = 8_000

    /** Butterworth Q for a single biquad section. */
    private const val Q = 0.7071
}

package com.mythos.vcd.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Loudness normalisation, matching Resemblyzer's `audio.normalize_volume`.
 *
 * The speaker encoder was trained on audio normalised to -30 dBFS, so skipping this shifts every
 * similarity score the app produces — and shifts it by an amount that depends on how loudly the
 * caller happens to be speaking, which is exactly the kind of variable you do not want leaking
 * into an identity decision.
 *
 * The equivalent function lives in tools/vcd_models.py and the two are checked against each other
 * by SpeakerParityTest, so this cannot silently drift away from what the models were converted
 * against.
 */
object AudioNormalize {

    const val TARGET_DBFS = -30f

    fun toTargetDbfs(
        samples: FloatArray,
        targetDbfs: Float = TARGET_DBFS,
        increaseOnly: Boolean = true,
    ): FloatArray {
        if (samples.isEmpty()) return samples

        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        val rms = sqrt(acc / samples.size)
        if (rms < 1e-10) return samples // digital silence has no loudness to normalise

        val dbfs = 20.0 * log10(rms)
        val delta = targetDbfs - dbfs
        // increase_only mirrors the reference: quiet audio is brought up, loud audio is left
        // alone rather than being pulled down and losing headroom it already had.
        if (increaseOnly && delta < 0) return samples

        val gain = 10.0.pow(delta / 20.0).toFloat()
        return FloatArray(samples.size) { samples[it] * gain }
    }
}

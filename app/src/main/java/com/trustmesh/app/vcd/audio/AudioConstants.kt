package com.trustmesh.app.vcd.audio

/**
 * Every audio path in this app — mic and file alike — normalises to these values before it
 * reaches the pipeline. The window length is not a round number on purpose: it is exactly the
 * input width the AASIST anti-spoofing checkpoint was trained on (64600 samples), so the
 * anti-spoofing model never sees a padded or truncated frame.
 */
object AudioConstants {
    const val SAMPLE_RATE = 16_000

    /** 64600 samples = 4.0375 s. Fixed by the AASIST checkpoint's input width. */
    const val WINDOW_SAMPLES = 64_600

    /** Live capture emits a fresh score every 3 s, each covering the most recent 4.0375 s. */
    const val LIVE_HOP_SAMPLES = 48_000

    /** Test Mode steps a file forward by half a window so short clips still yield several scores. */
    const val FILE_HOP_SAMPLES = 32_300

    /** Resemblyzer partial-utterance width: 160 mel frames at 10 ms hop = 1.6 s. */
    const val PARTIAL_SAMPLES = 25_600

    /** Resemblyzer overlaps partials by 50 %. */
    const val PARTIAL_HOP_SAMPLES = 12_800

    /** Speaker embedding dimensionality produced by the bundled encoder. */
    const val EMBEDDING_DIM = 256

    val WINDOW_SECONDS: Float get() = WINDOW_SAMPLES.toFloat() / SAMPLE_RATE
}

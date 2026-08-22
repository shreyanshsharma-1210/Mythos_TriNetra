package com.mythos.vcd.audio

/**
 * Slices a decoded clip into the same fixed-width windows the live path produces.
 *
 * A clip shorter than one window yields nothing at all. Padding it out with silence would produce
 * a window that is mostly fabricated, and the score from that window would be a score of our own
 * zero-padding — so short clips are refused loudly by the caller instead.
 */
object WindowSlicer {

    fun slice(
        samples: FloatArray,
        windowSamples: Int = AudioConstants.WINDOW_SAMPLES,
        hopSamples: Int = AudioConstants.FILE_HOP_SAMPLES,
        provenance: AudioWindow.Provenance = AudioWindow.Provenance.FILE,
    ): List<AudioWindow> {
        require(hopSamples > 0) { "hop must be positive" }
        if (samples.size < windowSamples) return emptyList()

        val out = ArrayList<AudioWindow>()
        var start = 0
        while (start + windowSamples <= samples.size) {
            out += AudioWindow(
                samples = samples.copyOfRange(start, start + windowSamples),
                startSampleIndex = start.toLong(),
                provenance = provenance,
            )
            start += hopSamples
        }
        // Include the final full window when the clip length is not a whole number of hops, so the
        // end of a clip is not silently dropped.
        val lastStart = samples.size - windowSamples
        if (out.isNotEmpty() && out.last().startSampleIndex < lastStart) {
            out += AudioWindow(
                samples = samples.copyOfRange(lastStart, samples.size),
                startSampleIndex = lastStart.toLong(),
                provenance = provenance,
            )
        }
        return out
    }

    /** Minimum clip length that yields at least one window, in seconds. */
    val minimumClipSeconds: Float get() = AudioConstants.WINDOW_SECONDS
}

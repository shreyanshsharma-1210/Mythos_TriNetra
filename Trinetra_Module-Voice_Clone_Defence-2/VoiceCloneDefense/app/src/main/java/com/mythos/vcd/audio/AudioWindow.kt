package com.mythos.vcd.audio

/**
 * One unit of work for the pipeline: a fixed-width mono 16 kHz frame in [-1, 1].
 *
 * [provenance] travels with the samples so a score can never be displayed without the UI knowing
 * where the audio actually came from. This is what makes the Phase 6 rule enforceable — the app
 * must never show a number it cannot attribute to real captured audio.
 */
data class AudioWindow(
    val samples: FloatArray,
    val startSampleIndex: Long,
    val provenance: Provenance,
) {
    init {
        require(samples.size == AudioConstants.WINDOW_SAMPLES) {
            "AudioWindow must be exactly ${AudioConstants.WINDOW_SAMPLES} samples, got ${samples.size}"
        }
    }

    enum class Provenance {
        /** Device microphone. */
        LIVE_MIC,

        /** A file fed through Test Mode. */
        FILE,

        /**
         * The far end of a VoIP call, taken from the decoded WebRTC track before playback.
         *
         * Distinct from [LIVE_MIC] because it is a materially different signal: no room, no
         * speaker, no microphone, but a lossy codec and a jitter buffer instead. A score from
         * this source is not interchangeable with one from the microphone and the UI must be
         * able to tell the user which it is looking at.
         */
        REMOTE_VOIP,
    }

    val startSeconds: Float get() = startSampleIndex.toFloat() / AudioConstants.SAMPLE_RATE

    /** Root-mean-square level of the window, used to reject silence before wasting an inference. */
    fun rms(): Float {
        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        return Math.sqrt(acc / samples.size).toFloat()
    }

    fun peak(): Float {
        var p = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > p) p = a
        }
        return p
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioWindow &&
            startSampleIndex == other.startSampleIndex &&
            provenance == other.provenance &&
            samples.contentEquals(other.samples))

    override fun hashCode(): Int =
        (samples.contentHashCode() * 31 + startSampleIndex.hashCode()) * 31 + provenance.hashCode()
}

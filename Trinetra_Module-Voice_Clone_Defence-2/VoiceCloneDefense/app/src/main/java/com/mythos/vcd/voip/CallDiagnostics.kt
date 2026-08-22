package com.mythos.vcd.voip

/**
 * What the remote audio track is actually delivering.
 *
 * This is the Phase 1 deliverable and it stays in the shipped build rather than being deleted once
 * the POC works. The whole reason this module exists is that the previous audio source — the
 * microphone during a cellular call — returned digital silence with no error and no explanation,
 * and it took a diagnostics screen to establish that. Assuming the new source works any better,
 * without a panel that says so, would repeat exactly that mistake.
 *
 * [framesReceived] counting up while [voicedFrames] stays at zero is the signature of a track that
 * is connected and delivering nothing, which is a completely different problem from a track that
 * never connected.
 */
data class CallDiagnostics(
    /** True once at least one onData callback has arrived from the remote track. */
    val trackAttached: Boolean = false,

    /** Format as reported by WebRTC, not as assumed. */
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitsPerSample: Int = 0,

    /** 10 ms buffers received from the remote track. */
    val framesReceived: Long = 0,
    /** Frames whose peak cleared the digital-silence floor. */
    val voicedFrames: Long = 0,

    val rms: Float = 0f,
    val peak: Float = 0f,

    /** Wall clock at the first remote frame — the number that says media is really flowing. */
    val firstFrameAtMs: Long? = null,
    val lastFrameAtMs: Long? = null,

    /** Milliseconds from the call being placed to the first remote audio frame. */
    val firstFrameLatencyMs: Long? = null,

    /** Consecutive silent frames; drives [silent]. */
    val silentFrameRun: Int = 0,

    /** Analysis windows handed to the ML pipeline. */
    val windowsAnalysed: Long = 0,
    /** Median-ish rolling inference cost of the last analysed window, in ms. */
    val lastInferenceMs: Long = 0,
    /** Milliseconds from the newest sample in a window to its verdict being published. */
    val lastSpeechToResultMs: Long = 0,

    val droppedFrames: Long = 0,
    val note: String? = null,
) {
    /** Receiving audio, but all of it is digital silence for long enough to be a real finding. */
    val silent: Boolean get() = trackAttached && silentFrameRun >= SILENT_FRAMES_BEFORE_FLAG

    val receivedSeconds: Float
        get() = if (sampleRate <= 0) 0f else framesReceived.toFloat() * FRAME_MS / 1000f

    companion object {
        /** WebRTC delivers remote audio in 10 ms buffers. */
        const val FRAME_MS = 10

        /** Two seconds of unbroken silence before saying so. */
        const val SILENT_FRAMES_BEFORE_FLAG = 200

        /** Below this peak a frame is treated as digital silence rather than quiet speech. */
        const val DIGITAL_SILENCE = 1e-4f
    }
}

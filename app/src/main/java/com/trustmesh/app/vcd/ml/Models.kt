package com.trustmesh.app.vcd.ml

import java.io.Closeable

/**
 * The two model contracts the pipeline depends on.
 *
 * Both are deliberately narrow and take raw waveform rather than features. The mel-spectrogram
 * front end for the speaker encoder is folded into the exported graph itself (see
 * tools/convert_speaker_encoder.py), so there is exactly one implementation of that arithmetic,
 * in Python, validated against the reference model — rather than a second hand-written copy in
 * Kotlin that could silently drift and quietly degrade every similarity score in the app.
 */

/** Produces a fixed-length speaker embedding from one partial utterance. */
interface SpeakerEmbedder : Closeable {

    val embeddingDim: Int

    /** Width in samples of the waveform this encoder expects, at 16 kHz. */
    val inputSamples: Int

    /**
     * @param waveform exactly [inputSamples] mono float samples in [-1, 1]
     * @return an L2-normalised embedding of length [embeddingDim]
     */
    fun embedPartial(waveform: FloatArray): FloatArray
}

/** Estimates the probability that a waveform is synthetic, independent of who is speaking. */
interface SpoofDetector : Closeable {

    /** Width in samples of the waveform this detector expects, at 16 kHz. */
    val inputSamples: Int

    /**
     * @param waveform exactly [inputSamples] mono float samples in [-1, 1]
     * @return synthetic_probability in [0, 1]; higher means more evidence of AI generation
     */
    fun syntheticProbability(waveform: FloatArray): Float
}

/**
 * Raised when a model file is missing or will not load. The pipeline surfaces this to the UI as
 * an explicit unavailable state; it must never be swallowed into a default score.
 */
class ModelUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

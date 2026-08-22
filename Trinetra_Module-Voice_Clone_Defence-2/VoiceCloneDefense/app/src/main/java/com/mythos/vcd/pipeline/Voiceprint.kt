package com.mythos.vcd.pipeline

import com.mythos.vcd.audio.ChannelSim
import com.mythos.vcd.ml.Vec

/**
 * One stored voiceprint, and the channel it was taken through.
 *
 * A contact has several. Enrolment records once, then derives an embedding per channel condition,
 * because a print taken over the microphone does not transfer to a voice arriving over a call.
 * Measured on two genuine recordings of one person:
 *
 *     microphone print vs narrowband call audio   0.7655
 *     narrowband print vs narrowband call audio   0.9766
 *
 * The first sits on top of the 0.75 match threshold, which is how somebody's own voice comes back
 * as "not confirmed". The second is a comfortable match. Storing both costs one extra cosine per
 * comparison, which is nothing beside an inference.
 *
 * [baselineSynthetic] is per-variant for the same reason: the anti-spoofing reading that matters is
 * the one taken through the channel the call actually arrives over.
 */
data class Voiceprint(
    val label: String,
    val embedding: FloatArray,
    val baselineSynthetic: Float?,
) {
    override fun equals(other: Any?) = this === other ||
        (other is Voiceprint && label == other.label && embedding.contentEquals(other.embedding))

    override fun hashCode() = label.hashCode() * 31 + embedding.contentHashCode()
}

/** The best-matching stored print for a piece of audio, and what it scored. */
data class PrintMatch(
    val similarity: Float,
    val voiceprint: Voiceprint,
)

/**
 * Scores an utterance against every stored print and keeps the best.
 *
 * Taking the maximum rather than the mean is the point of multi-condition enrolment: the prints are
 * deliberately different from one another, so averaging their scores would drag a good match down
 * with the two conditions that do not apply to this call.
 */
fun List<Voiceprint>.bestMatch(embedding: FloatArray): PrintMatch? {
    var best: PrintMatch? = null
    for (print in this) {
        if (print.embedding.size != embedding.size) continue
        val similarity = Vec.cosine(embedding, print.embedding)
        if (best == null || similarity > best.similarity) {
            best = PrintMatch(similarity, print)
        }
    }
    return best
}

/**
 * The conditions enrolment derives prints for.
 *
 * Kept here rather than in the audio layer so there is one list, and adding a condition is a
 * one-line change that automatically flows into enrolment, storage and matching.
 */
val ENROLMENT_CONDITIONS: List<ChannelSim.Condition> get() = ChannelSim.Condition.ENROLMENT

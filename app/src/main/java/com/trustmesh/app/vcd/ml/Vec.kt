package com.trustmesh.app.vcd.ml

import kotlin.math.sqrt

/** Small vector helpers for speaker embeddings. Pure math, no Android, unit-testable. */
object Vec {

    fun l2Norm(v: FloatArray): Float {
        var acc = 0.0
        for (x in v) acc += x.toDouble() * x
        return sqrt(acc).toFloat()
    }

    /** Returns a new unit-length copy. A zero vector is returned unchanged rather than NaN-ed. */
    fun l2Normalize(v: FloatArray): FloatArray {
        val n = l2Norm(v)
        if (n <= 1e-12f) return v.copyOf()
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / n
        return out
    }

    /** Element-wise mean of equal-length vectors. */
    fun mean(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty()) { "cannot average an empty set of embeddings" }
        val dim = vectors[0].size
        val out = FloatArray(dim)
        for (v in vectors) {
            require(v.size == dim) { "embedding dimensionality mismatch: $dim vs ${v.size}" }
            for (i in 0 until dim) out[i] += v[i]
        }
        for (i in 0 until dim) out[i] /= vectors.size
        return out
    }

    /**
     * Cosine similarity, clamped to [-1, 1] so floating-point drift cannot produce a similarity
     * of 1.0000001 that then reads as a suspiciously perfect match in the UI.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "embedding dimensionality mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na <= 1e-12 || nb <= 1e-12) return 0f
        val c = dot / (sqrt(na) * sqrt(nb))
        return c.coerceIn(-1.0, 1.0).toFloat()
    }

    fun toBytes(v: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        require(b.size % 4 == 0) { "voiceprint blob is not a whole number of float32 values" }
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }
}

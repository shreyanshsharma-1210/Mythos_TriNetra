package com.mythos.vcd.audio

/**
 * In-memory rolling buffer for live capture. Holds at most [capacity] samples and overwrites the
 * oldest ones; nothing here is ever written to disk, and [zeroize] scrubs the backing array when
 * capture stops so raw audio does not linger in the heap longer than the feature needs it.
 */
class AudioRingBuffer(val capacity: Int) {

    private val buf = FloatArray(capacity)
    private var writeIndex = 0
    private var totalWritten = 0L
    private val lock = Any()

    /** Total samples ever pushed, including samples already overwritten. */
    val samplesWritten: Long get() = synchronized(lock) { totalWritten }

    fun write(src: FloatArray, offset: Int = 0, length: Int = src.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "bad range" }
        synchronized(lock) {
            var read = offset
            var remaining = length
            // A chunk longer than the buffer would only leave its tail behind; skip the dead part.
            if (remaining > capacity) {
                read += remaining - capacity
                totalWritten += remaining - capacity
                remaining = capacity
            }
            while (remaining > 0) {
                val run = minOf(remaining, capacity - writeIndex)
                System.arraycopy(src, read, buf, writeIndex, run)
                writeIndex = (writeIndex + run) % capacity
                read += run
                remaining -= run
                totalWritten += run
            }
        }
    }

    /**
     * Copies the most recent [n] samples in chronological order, or returns null if fewer than
     * [n] samples have been captured yet. Never returns a partially-filled array: a short read
     * would silently become a silence-padded window and produce a score from audio that was
     * never captured.
     */
    fun readLatest(n: Int): FloatArray? {
        require(n <= capacity) { "cannot read $n from a $capacity-sample buffer" }
        synchronized(lock) {
            if (totalWritten < n) return null
            val out = FloatArray(n)
            val start = ((writeIndex - n) % capacity + capacity) % capacity
            val firstRun = minOf(n, capacity - start)
            System.arraycopy(buf, start, out, 0, firstRun)
            if (firstRun < n) System.arraycopy(buf, 0, out, firstRun, n - firstRun)
            return out
        }
    }

    fun zeroize() {
        synchronized(lock) {
            java.util.Arrays.fill(buf, 0f)
            writeIndex = 0
            totalWritten = 0L
        }
    }
}

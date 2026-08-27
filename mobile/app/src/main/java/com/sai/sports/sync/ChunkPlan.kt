package com.sai.sports.sync

/**
 * Splits a file into fixed-size chunks and works out what still needs sending.
 *
 * The whole point of chunking here is the athlete's data allowance. On a patchy
 * 3G connection a 6MB upload will be interrupted, and re-sending from byte zero
 * every time burns megabytes the athlete paid for and may not be able to
 * replace. Resuming from the last acknowledged chunk is the difference between
 * a test that eventually syncs and one that never does.
 *
 * Chunk indices are 0-based. The final chunk is short unless the file divides
 * evenly.
 */
data class ChunkPlan(
    val fileSizeBytes: Long,
    val chunkSizeBytes: Int
) {

    init {
        require(chunkSizeBytes > 0) { "Chunk size must be positive" }
        require(fileSizeBytes >= 0) { "File size cannot be negative" }
    }

    val totalChunks: Int =
        if (fileSizeBytes == 0L) 0
        else ((fileSizeBytes + chunkSizeBytes - 1) / chunkSizeBytes).toInt()

    fun offsetOf(index: Int): Long {
        requireValid(index)
        return index.toLong() * chunkSizeBytes
    }

    fun sizeOf(index: Int): Int {
        requireValid(index)
        val remaining = fileSizeBytes - offsetOf(index)
        return minOf(remaining, chunkSizeBytes.toLong()).toInt()
    }

    /**
     * Chunk indices still to send, in order.
     *
     * Takes the set the SERVER says it has, not what the client believes it
     * sent. A chunk that left the device but never landed is exactly the case
     * resumption exists for, and trusting the client's own optimism here would
     * produce a corrupt file that only fails at the checksum.
     */
    fun remainingChunks(receivedChunks: Set<Int>): List<Int> =
        (0 until totalChunks).filterNot { it in receivedChunks }

    fun isComplete(receivedChunks: Set<Int>): Boolean =
        remainingChunks(receivedChunks).isEmpty()

    /** 0.0..1.0, for the progress bar on the sync screen. */
    fun progress(receivedChunks: Set<Int>): Float {
        if (totalChunks == 0) return 1f
        val received = receivedChunks.count { it in 0 until totalChunks }
        return received.toFloat() / totalChunks
    }

    private fun requireValid(index: Int) {
        require(index in 0 until totalChunks) {
            "Chunk $index is outside 0..${totalChunks - 1}"
        }
    }

    companion object {

        /**
         * 256KB.
         *
         * Sized for the network this app is built for, not for throughput. A
         * larger chunk means more data thrown away when a 2G/3G connection
         * drops mid-chunk; a smaller one means more round trips, each with its
         * own latency and its own chance to fail. On a connection that drops
         * every few seconds, small-and-frequent wins.
         */
        const val DEFAULT_CHUNK_SIZE_BYTES = 256 * 1024
    }
}

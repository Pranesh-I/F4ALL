package com.sai.sports.sync

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 over a file, streamed.
 *
 * Feeds `videos.checksum_sha256` in the DB schema. Two jobs:
 *
 *  - Integrity. A chunked upload reassembled in the wrong order, or with a
 *    chunk silently truncated by a proxy, produces a valid-looking MP4 that
 *    scores differently server-side. The hash catches that before a wrong score
 *    reaches an official.
 *  - Tamper-evidence. The hash is computed on the device over the exact bytes
 *    sent, so a video swapped in transit does not match.
 *
 * It is NOT proof the video is genuine — a modified client can hash whatever it
 * likes. That is what Sprint 6's cheat detection and the server's independent
 * re-scoring are for.
 *
 * Streamed in blocks because these files are megabytes and the target device
 * has very little headroom.
 */
object Checksum {

    private const val ALGORITHM = "SHA-256"
    private const val BLOCK_SIZE = 8 * 1024

    fun sha256(file: File): String {

        val digest = MessageDigest.getInstance(ALGORITHM)

        file.inputStream().use { stream ->
            val buffer = ByteArray(BLOCK_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().toHexString()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance(ALGORITHM).digest(bytes).toHexString()

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}

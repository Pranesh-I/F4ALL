package com.sai.sports.sync

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter, and a cap on how many times to try.
 *
 * The jitter is not decoration. Picture a coaching camp where forty athletes
 * record tests in the same hour on the same weak tower: without jitter every
 * device retries at exactly 1s, 2s, 4s, 8s after the same outage, and the
 * server gets forty synchronised bursts. Spreading them out costs nothing and
 * removes a self-inflicted thundering herd.
 */
class RetryPolicy(
    private val initialDelayMs: Long = INITIAL_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val multiplier: Double = MULTIPLIER,
    private val jitterFraction: Double = JITTER_FRACTION,
    val maxAttempts: Int = MAX_ATTEMPTS,
    private val random: Random = Random.Default
) {

    /**
     * Delay before attempt number [attemptCount] + 1, where [attemptCount] is
     * how many attempts have already failed.
     */
    fun delayForAttempt(attemptCount: Int): Long {

        if (attemptCount <= 0) return initialDelayMs

        val exponential = initialDelayMs * multiplier.pow(attemptCount)
        val capped = min(exponential, maxDelayMs.toDouble())

        // Jitter only ever shortens or lengthens within a band around the
        // capped value, so the backoff curve keeps its shape.
        val jitter = capped * jitterFraction
        val low = (capped - jitter).coerceAtLeast(0.0)
        val high = capped + jitter

        return random.nextDouble(low, high + 1).toLong()
    }

    fun hasAttemptsLeft(attemptCount: Int): Boolean = attemptCount < maxAttempts

    /**
     * Whether a failure is worth retrying at all.
     *
     * Retrying a 401 or a 413 forever just drains the athlete's battery to
     * arrive at the same answer — those need a human or a code change, so they
     * go straight to FAILED where the sync screen can surface them.
     */
    fun isRetryable(failure: UploadFailure): Boolean =
        when (failure) {
            UploadFailure.NETWORK -> true
            UploadFailure.SERVER_ERROR -> true
            UploadFailure.TIMEOUT -> true
            UploadFailure.RATE_LIMITED -> true
            UploadFailure.UNAUTHORIZED -> false
            UploadFailure.REJECTED -> false
            UploadFailure.CHECKSUM_MISMATCH -> false
            UploadFailure.FILE_MISSING -> false
        }

    companion object {
        const val INITIAL_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 6 * 60 * 60 * 1000L
        const val MULTIPLIER = 2.0
        const val JITTER_FRACTION = 0.25

        /**
         * Ten attempts spread across the capped backoff covers well over a day
         * of intermittent connectivity — an athlete who records on a Friday in
         * a village with no signal and reaches a town on Sunday is still inside
         * the window.
         */
        const val MAX_ATTEMPTS = 10
    }
}

/**
 * Why an upload attempt failed.
 *
 * Kept separate from the HTTP layer so [RetryPolicy] stays a pure decision
 * table and the reason can be shown to the athlete in words.
 */
enum class UploadFailure(val message: String) {
    NETWORK("No connection"),
    TIMEOUT("Connection timed out"),
    SERVER_ERROR("Server problem"),
    RATE_LIMITED("Server busy"),
    UNAUTHORIZED("Sign-in expired"),
    REJECTED("Upload rejected"),
    CHECKSUM_MISMATCH("Video was corrupted in transit"),
    FILE_MISSING("Recording is missing from this device")
}

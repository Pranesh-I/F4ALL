package com.sai.sports.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SyncStateTest {

    @Test
    fun `happy path walks recorded to synced`() {

        val path = listOf(
            SyncStatus.RECORDED,
            SyncStatus.COMPRESSING,
            SyncStatus.COMPRESSED,
            SyncStatus.QUEUED,
            SyncStatus.UPLOADING,
            SyncStatus.SYNCED
        )

        path.zipWithNext().forEach { (from, to) ->
            assertTrue("$from -> $to should be allowed", from.canTransitionTo(to))
        }
    }

    @Test
    fun `synced is terminal`() {

        SyncStatus.entries.forEach { status ->
            assertFalse(
                "Nothing may follow SYNCED, but $status was allowed",
                SyncStatus.SYNCED.canTransitionTo(status)
            )
        }

        assertTrue(SyncStatus.SYNCED.isTerminal)
    }

    @Test
    fun `a dropped upload returns to the queue rather than failing`() {

        // This is what preserves the already-delivered chunks and the upload id.
        assertTrue(SyncStatus.UPLOADING.canTransitionTo(SyncStatus.QUEUED))
    }

    @Test
    fun `an interrupted compression restarts from the original recording`() {

        assertTrue(SyncStatus.COMPRESSING.canTransitionTo(SyncStatus.RECORDED))
    }

    @Test
    fun `failed attempts can re-enter the pipeline`() {

        assertTrue(SyncStatus.FAILED.canTransitionTo(SyncStatus.QUEUED))
        assertTrue(SyncStatus.FAILED.canTransitionTo(SyncStatus.COMPRESSING))
    }

    @Test
    fun `stages cannot be skipped`() {

        // Uploading a video that was never compressed would send the full-size
        // recording over the athlete's mobile data.
        assertFalse(SyncStatus.RECORDED.canTransitionTo(SyncStatus.UPLOADING))
        assertFalse(SyncStatus.RECORDED.canTransitionTo(SyncStatus.SYNCED))
        assertFalse(SyncStatus.COMPRESSED.canTransitionTo(SyncStatus.SYNCED))
        assertFalse(SyncStatus.QUEUED.canTransitionTo(SyncStatus.SYNCED))
    }

    @Test
    fun `pending covers everything the athlete is still waiting on`() {

        assertTrue(SyncStatus.RECORDED.isPending)
        assertTrue(SyncStatus.COMPRESSING.isPending)
        assertTrue(SyncStatus.COMPRESSED.isPending)
        assertTrue(SyncStatus.QUEUED.isPending)
        assertTrue(SyncStatus.UPLOADING.isPending)

        assertFalse(SyncStatus.SYNCED.isPending)
        assertFalse(SyncStatus.FAILED.isPending)
    }

    @Test
    fun `every status has a transition rule`() {

        // A status with no entry would throw at runtime the first time an
        // attempt reached it.
        SyncStatus.entries.forEach { status ->
            status.canTransitionTo(SyncStatus.FAILED)
        }
    }
}

class RetryPolicyTest {

    @Test
    fun `delay grows exponentially`() {

        val policy = RetryPolicy(jitterFraction = 0.0)

        val first = policy.delayForAttempt(1)
        val second = policy.delayForAttempt(2)
        val third = policy.delayForAttempt(3)

        assertTrue(second > first)
        assertTrue(third > second)
        assertEquals(first * 2, second)
    }

    @Test
    fun `delay is capped`() {

        val policy = RetryPolicy(jitterFraction = 0.0)

        // Without a cap, attempt 20 would be measured in years.
        assertEquals(RetryPolicy.MAX_DELAY_MS, policy.delayForAttempt(20))
    }

    @Test
    fun `jitter spreads retries across devices`() {

        // Forty phones at the same camp recovering from the same outage must
        // not all retry on the same millisecond.
        val policy = RetryPolicy()

        val delays = (0 until 40).map { policy.delayForAttempt(3) }.toSet()

        assertTrue(
            "Expected varied delays, got ${delays.size} distinct values",
            delays.size > 20
        )
    }

    @Test
    fun `jitter stays within its band`() {

        val policy = RetryPolicy(jitterFraction = 0.25)

        val unjittered = RetryPolicy(jitterFraction = 0.0).delayForAttempt(3)

        repeat(200) {
            val delay = policy.delayForAttempt(3)
            assertTrue(delay >= (unjittered * 0.75).toLong() - 1)
            assertTrue(delay <= (unjittered * 1.25).toLong() + 1)
        }
    }

    @Test
    fun `attempt budget is finite`() {

        val policy = RetryPolicy()

        assertTrue(policy.hasAttemptsLeft(0))
        assertTrue(policy.hasAttemptsLeft(RetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(policy.hasAttemptsLeft(RetryPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun `transient failures retry and permanent ones do not`() {

        val policy = RetryPolicy()

        listOf(
            UploadFailure.NETWORK,
            UploadFailure.TIMEOUT,
            UploadFailure.SERVER_ERROR,
            UploadFailure.RATE_LIMITED
        ).forEach {
            assertTrue("$it should retry", policy.isRetryable(it))
        }

        listOf(
            UploadFailure.UNAUTHORIZED,
            UploadFailure.REJECTED,
            UploadFailure.CHECKSUM_MISMATCH,
            UploadFailure.FILE_MISSING
        ).forEach {
            assertFalse(
                "$it must not retry — it drains the battery to reach the same answer",
                policy.isRetryable(it)
            )
        }
    }

    @Test
    fun `every failure kind has a retry decision and a message`() {

        val policy = RetryPolicy()

        UploadFailure.entries.forEach { failure ->
            policy.isRetryable(failure)
            assertTrue(failure.message.isNotBlank())
        }
    }
}

class ChecksumTest {

    @Test
    fun `known vector matches`() {

        // SHA-256 of the empty input.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksum.sha256(ByteArray(0))
        )
    }

    @Test
    fun `file and byte hashing agree`() {

        val bytes = Random(seed = 3).nextBytes(9000)

        val file = File.createTempFile("checksum", ".bin").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

        assertEquals(Checksum.sha256(bytes), Checksum.sha256(file))

        file.delete()
    }

    @Test
    fun `a single flipped byte changes the hash`() {

        val original = Random(seed = 11).nextBytes(4096)
        val tampered = original.copyOf().also { it[2000] = (it[2000] + 1).toByte() }

        assertTrue(Checksum.sha256(original) != Checksum.sha256(tampered))
    }

    @Test
    fun `hash is lowercase hex of the expected length`() {

        val hash = Checksum.sha256(Random(seed = 5).nextBytes(128))

        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `streaming handles files larger than the read block`() {

        // Guards the streaming loop: an off-by-one in the block read would
        // produce a hash that never matches the server's.
        listOf(1, 8191, 8192, 8193, 100_000).forEach { size ->

            val bytes = Random(seed = size).nextBytes(size)

            val file = File.createTempFile("checksum-$size", ".bin").apply {
                writeBytes(bytes)
                deleteOnExit()
            }

            assertEquals("Size $size", Checksum.sha256(bytes), Checksum.sha256(file))

            file.delete()
        }
    }
}

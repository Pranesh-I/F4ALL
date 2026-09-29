package com.sai.sports.sync

import com.sai.sports.data.local.TestAttemptEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sprint 9: what the athlete is told about a test on its way to SAI. */
class SyncDeliveryTest {

    private fun attempt(
        status: SyncStatus,
        resultId: String? = null,
        attempts: Int = 0,
        lastError: String? = null
    ) = TestAttemptEntity(
        id = "a1",
        testType = "SQUATS",
        recordedAtMs = 1,
        provisionalScore = 12.0,
        scoreUnit = "reps",
        syncStatus = status,
        sourceVideoPath = "videos/a1.mp4",
        resultId = resultId,
        attemptCount = attempts,
        lastError = lastError
    )

    @Test
    fun `every stage before the server has the video is still sending`() {
        listOf(
            SyncStatus.RECORDED, SyncStatus.COMPRESSING, SyncStatus.COMPRESSED,
            SyncStatus.QUEUED, SyncStatus.UPLOADING
        ).forEach { assertEquals(it.name, Delivery.SENDING, Delivery.of(attempt(it))) }
    }

    @Test
    fun `a delivered video is not a submitted test`() {
        assertEquals(Delivery.WAITING_TO_SUBMIT, Delivery.of(attempt(SyncStatus.SYNCED)))
        assertEquals(Delivery.SUBMITTED, Delivery.of(attempt(SyncStatus.SYNCED, resultId = "r1")))
    }

    @Test
    fun `a refused submission is final and says so`() {
        val refused = attempt(SyncStatus.SYNCED, attempts = RetryPolicy.MAX_ATTEMPTS, lastError = "already submitted")
        assertEquals(Delivery.NOT_ACCEPTED, Delivery.of(refused))
    }

    @Test
    fun `a failed upload is failed, whatever else is set`() {
        assertEquals(Delivery.FAILED, Delivery.of(attempt(SyncStatus.FAILED, attempts = 3)))
    }

    @Test
    fun `failure hints say what the athlete can do`() {
        assertEquals(FailureHint.RECORDING_MISSING, FailureHint.of(UploadFailure.FILE_MISSING.message))
        assertFalse("Retrying a missing recording cannot help", FailureHint.RECORDING_MISSING.retryable)

        assertEquals(FailureHint.SIGN_IN, FailureHint.of(UploadFailure.UNAUTHORIZED.message))
        assertTrue(FailureHint.SIGN_IN.retryable)

        assertEquals(FailureHint.RETRY, FailureHint.of("Encoder crashed"))
        assertEquals(FailureHint.RETRY, FailureHint.of(null))
    }

    @Test
    fun `a second sync stands down while one is draining`() = runBlocking {
        assertTrue(SyncWorker.DRAIN_LOCK.tryLock())
        try {
            assertFalse("Two drains would reset each other's uploads", SyncWorker.DRAIN_LOCK.tryLock())
        } finally {
            SyncWorker.DRAIN_LOCK.unlock()
        }
        assertTrue("Free again once the first finishes", SyncWorker.DRAIN_LOCK.tryLock())
        SyncWorker.DRAIN_LOCK.unlock()
    }
}

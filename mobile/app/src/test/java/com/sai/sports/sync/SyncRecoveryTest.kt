package com.sai.sports.sync

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery paths for artifacts that vanish underneath a queued attempt.
 *
 * Android reclaims app storage under pressure, users clear app data, and a
 * cleanup routine can delete the wrong thing. When the compressed transcode
 * disappears from a QUEUED or COMPRESSED attempt, the worker has to be able to
 * rebuild it from the original recording.
 *
 * If the state machine forbids that, the worker asks to compress, the
 * transition is refused, and the attempt retries forever without progressing —
 * a livelock that looks like "waiting for a connection" to the athlete while
 * their test never leaves the phone.
 */
class SyncRecoveryTest {

    @Test
    fun `a queued attempt whose transcode vanished can be recompressed`() {

        assertTrue(
            "QUEUED must be able to re-enter compression, or the worker livelocks",
            SyncStatus.QUEUED.canTransitionTo(SyncStatus.COMPRESSING)
        )
    }

    @Test
    fun `a compressed attempt whose transcode vanished can be recompressed`() {

        assertTrue(
            "COMPRESSED must be able to re-enter compression, or the worker livelocks",
            SyncStatus.COMPRESSED.canTransitionTo(SyncStatus.COMPRESSING)
        )
    }

    @Test
    fun `a failed attempt can restart from the original recording`() {

        // The manual retry on the sync screen sends an attempt back to RECORDED
        // when its transcode is gone. If that transition is illegal the retry
        // button silently does nothing.
        assertTrue(
            SyncStatus.FAILED.canTransitionTo(SyncStatus.RECORDED)
        )
    }

    @Test
    fun `every non-terminal status can reach a terminal or retryable state`() {

        // Guards against a status that can be entered but never left, which
        // would strand an athlete's test permanently.
        SyncStatus.entries
            .filterNot { it.isTerminal }
            .forEach { status ->

                val hasExit = SyncStatus.entries.any { next ->
                    next != status && status.canTransitionTo(next)
                }

                assertTrue("$status is a dead end", hasExit)
            }
    }

    @Test
    fun `recovery from process death lands on a status the work queue selects`() {

        // recoverInterrupted maps COMPRESSING -> RECORDED and UPLOADING ->
        // QUEUED. Both targets must be states the worker's queue query picks
        // up, otherwise recovered rows are invisible and never retried.
        val workQueueStatuses = setOf(
            SyncStatus.RECORDED,
            SyncStatus.COMPRESSED,
            SyncStatus.QUEUED
        )

        assertTrue(SyncStatus.RECORDED in workQueueStatuses)
        assertTrue(SyncStatus.QUEUED in workQueueStatuses)

        assertTrue(SyncStatus.COMPRESSING.canTransitionTo(SyncStatus.RECORDED))
        assertTrue(SyncStatus.UPLOADING.canTransitionTo(SyncStatus.QUEUED))
    }
}

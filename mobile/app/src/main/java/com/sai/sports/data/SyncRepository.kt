package com.sai.sports.data

import android.content.Context
import android.util.Log
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.data.local.F4allDatabase
import com.sai.sports.data.local.SessionAttemptRow
import com.sai.sports.data.local.TestAttemptDao
import com.sai.sports.data.local.TestAttemptEntity
import com.sai.sports.sync.RetryPolicy
import com.sai.sports.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Single owner of an attempt's lifecycle from recording to synced.
 *
 * Sits over two stores that each hold what they are good at:
 *
 *  - [AttemptStore] — files. Video, pose sequence CSV, provisional result JSON.
 *  - Room — the queue row. Small, indexed, queried on every sync tick.
 *
 * The DAO is not exposed. Every status change goes through here so the
 * transition rules in [SyncStatus] are enforced in one place; a worker that
 * could write any status it liked would eventually write one that strands a
 * row outside the work queue forever.
 */
class SyncRepository(
    private val context: Context,
    private val dao: TestAttemptDao = F4allDatabase.get(context).testAttemptDao(),
    private val attemptStore: AttemptStore = AttemptStore(context)
) {

    /*
     * Everything that lists or drains the queue takes the athlete it is for.
     * The queue on a shared phone holds several athletes' tests, and each must
     * only ever see — and upload as — their own.
     */

    fun observeAttempts(athleteId: String): Flow<List<TestAttemptEntity>> = dao.observeAll(athleteId)

    fun observePendingCount(athleteId: String): Flow<Int> =
        dao.observePendingCount(athleteId, RetryPolicy.MAX_ATTEMPTS)

    suspend fun claimUnowned(athleteId: String): Int = dao.claimUnowned(athleteId)

    /** This athlete's queued and sent official session attempts. */
    fun observeSessionAttempts(athleteId: String): Flow<List<SessionAttemptRow>> =
        dao.observeSessionAttempts(athleteId)

    suspend fun sessionAttempts(athleteId: String, sessionId: String): List<SessionAttemptRow> =
        dao.sessionAttempts(athleteId, sessionId)

    suspend fun find(id: String): TestAttemptEntity? = dao.findById(id)

    suspend fun workQueue(athleteId: String): List<TestAttemptEntity> = dao.findWorkQueue(athleteId)

    /**
     * Registers a freshly recorded attempt in the queue.
     *
     * Only scored attempts are enqueued. Uploading a recording the device could
     * not score wastes the athlete's data on a video an official would reject
     * anyway — they are better told to retry immediately, while they are still
     * standing there.
     *
     * Practice attempts are never enqueued, whoever asks. The queue is the
     * only road to an official submission, so refusing here — rather than
     * trusting every caller to check — is what guarantees practice stays
     * practice.
     */
    suspend fun enqueue(attempt: Attempt, identity: IdentityPass? = null): Boolean {

        if (!UploadEligibility.isUploadable(attempt)) {
            return false
        }

        val sourceVideo = attemptStore.videoFile(attempt)

        if (!sourceVideo.exists()) {
            Log.e(TAG, "Refusing to enqueue ${attempt.id}: video file missing")
            return false
        }

        dao.insert(
            TestAttemptEntity(
                id = attempt.id,
                testType = attempt.testType.name,
                recordedAtMs = attempt.recordedAtMs,
                provisionalScore = attempt.result.score,
                scoreUnit = attempt.result.unit,
                syncStatus = SyncStatus.RECORDED,
                sourceVideoPath = relativePath(sourceVideo),
                sourceSizeBytes = sourceVideo.length(),
                athleteId = attempt.athleteId,
                sessionId = attempt.sessionId,
                // Only an official attempt carries the photo check.
                identityCheckId = identity?.checkId.takeIf { attempt.sessionId != null },
                identityPhotoPath = identity?.photoPath.takeIf { attempt.sessionId != null }
            )
        )

        return true
    }

    /**
     * Moves an attempt to [next], refusing transitions the state machine does
     * not allow.
     *
     * Returns false on an illegal transition rather than throwing: a worker
     * racing with a manual retry from the sync screen is an ordinary event, not
     * a crash.
     */
    suspend fun transition(
        id: String,
        next: SyncStatus,
        error: String? = null
    ): Boolean {

        val current = dao.findById(id) ?: return false

        if (current.syncStatus == next) return true

        if (!current.syncStatus.canTransitionTo(next)) {
            Log.w(TAG, "Blocked ${current.syncStatus} -> $next for $id")
            return false
        }

        dao.updateStatus(
            id = id,
            status = next,
            error = error,
            timestampMs = System.currentTimeMillis()
        )

        return true
    }

    suspend fun recordCompressionResult(
        id: String,
        compressedFile: File,
        checksum: String
    ) {
        val current = dao.findById(id) ?: return

        dao.update(
            current.copy(
                syncStatus = SyncStatus.COMPRESSED,
                compressedVideoPath = relativePath(compressedFile),
                compressedSizeBytes = compressedFile.length(),
                checksumSha256 = checksum,
                lastError = null
            )
        )
    }

    suspend fun unsubmitted(athleteId: String): List<TestAttemptEntity> = dao.findUnsubmitted(athleteId)

    /**
     * Records what the pending photo at [photoPath] became — a server check, or
     * nothing when it could not be checked — and deletes the photo.
     */
    suspend fun resolveIdentityPhoto(photoPath: String, checkId: String?) {
        dao.resolveIdentityPhoto(photoPath, checkId)
        File(context.filesDir, photoPath).delete()
    }

    fun identityPhotoFile(photoPath: String): File = File(context.filesDir, photoPath)

    /**
     * Deletes photos no queued attempt is waiting on — taken for a test the
     * athlete then did not record. They are faces; they do not linger.
     */
    suspend fun deleteOrphanIdentityPhotos(olderThanMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val directory = File(context.filesDir, IdentityPhotos.DIRECTORY)
        val waiting = dao.pendingIdentityPhotos().toSet()
        directory.listFiles()?.forEach { file ->
            val relative = "${IdentityPhotos.DIRECTORY}/${file.name}"
            if (relative !in waiting && nowMs - file.lastModified() > olderThanMs) {
                file.delete()
            }
        }
    }

    suspend fun recordResultId(id: String, resultId: String) =
        dao.updateResultId(id, resultId)

    suspend fun recordUploadId(id: String, uploadId: String) =
        dao.updateUploadId(id, uploadId)

    suspend fun recordUploadProgress(id: String, bytesSent: Long) =
        dao.updateUploadedBytes(id, bytesSent)

    suspend fun recordAttemptFailure(id: String, error: String) {
        val current = dao.findById(id) ?: return
        dao.update(
            current.copy(
                attemptCount = current.attemptCount + 1,
                lastError = error,
                lastAttemptAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * SAI refused the submission. Recorded as final — the attempt budget is
     * spent — so the worker stops asking and the sync screen shows the reason.
     */
    suspend fun recordSubmissionRefused(id: String, reason: String) {
        val current = dao.findById(id) ?: return
        dao.update(
            current.copy(
                attemptCount = RetryPolicy.MAX_ATTEMPTS,
                lastError = reason,
                lastAttemptAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun markSynced(id: String, videoId: String) {
        val current = dao.findById(id) ?: return

        dao.update(
            current.copy(
                syncStatus = SyncStatus.SYNCED,
                videoId = videoId,
                uploadedBytes = current.compressedSizeBytes,
                syncedAtMs = System.currentTimeMillis(),
                lastError = null,
                // A fresh budget for the submit step. Retries spent getting the
                // video through a bad connection say nothing about whether the
                // submission will succeed.
                attemptCount = 0
            )
        )

        // The original recording is typically several times the size of the
        // 480p upload. Once SAI has the video there is no reason to keep the
        // large one filling a phone that may only have a few GB free.
        deleteSourceRecording(current)
    }

    /**
     * Puts a failed attempt back in the queue at the athlete's request.
     *
     * Re-enters at RECORDED when the transcode is gone, otherwise straight at
     * QUEUED so the chunks already delivered are not re-sent.
     */
    suspend fun retry(id: String): Boolean {

        val current = dao.findById(id) ?: return false

        val compressed = current.compressedVideoPath
            ?.let { File(context.filesDir, it) }

        // Resume the upload if the transcode is still there; otherwise rebuild
        // it from the original recording.
        val target =
            if (compressed != null && compressed.exists()) SyncStatus.QUEUED
            else SyncStatus.RECORDED

        if (!current.syncStatus.canTransitionTo(target)) return false

        dao.update(
            current.copy(
                syncStatus = target,
                // Manual retry resets the counter: the athlete has made a fresh
                // decision, often after moving somewhere with signal, and should
                // not inherit an exhausted budget from yesterday.
                attemptCount = 0,
                lastError = null
            )
        )

        return true
    }

    /**
     * Called on app start, before any work is scheduled.
     *
     * Android force-stops backgrounded apps freely. Without this, a row caught
     * mid-compression or mid-upload keeps a status the work queue does not
     * select, and the athlete's test sits there looking busy forever.
     */
    suspend fun recoverInterrupted(): Int {
        val recovered = dao.recoverInterrupted()
        if (recovered > 0) {
            Log.i(TAG, "Recovered $recovered interrupted attempt(s) after restart")
        }
        return recovered
    }

    fun sourceVideoFile(entity: TestAttemptEntity): File =
        File(context.filesDir, entity.sourceVideoPath)

    fun compressedVideoFile(entity: TestAttemptEntity): File? =
        entity.compressedVideoPath?.let { File(context.filesDir, it) }

    fun compressedVideoTarget(id: String): File =
        File(File(context.filesDir, COMPRESSED_DIR), "$id.mp4")

    private fun deleteSourceRecording(entity: TestAttemptEntity) {
        runCatching {
            val source = sourceVideoFile(entity)
            if (source.exists() && entity.compressedVideoPath != null) {
                source.delete()
            }
        }.onFailure {
            Log.w(TAG, "Could not delete source recording for ${entity.id}", it)
        }
    }

    /** Paths are stored relative to filesDir — absolute paths break on restore. */
    private fun relativePath(file: File): String =
        file.absolutePath
            .removePrefix(context.filesDir.absolutePath)
            .trimStart(File.separatorChar, '/')

    companion object {
        private const val TAG = "SyncRepository"
        const val COMPRESSED_DIR = "compressed"
    }
}

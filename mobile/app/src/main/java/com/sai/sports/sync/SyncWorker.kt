package com.sai.sports.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.data.IdentityPhotos
import com.sai.sports.data.SyncRepository
import com.sai.sports.data.local.TestAttemptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drains the offline queue: compress what needs compressing, upload what is
 * ready, one attempt at a time.
 *
 * Runs under a connectivity constraint, so WorkManager only starts it when
 * there is a network — the airplane-mode-then-wifi journey in Sprint 4's
 * Definition of Done is handled by the OS, not by us polling.
 *
 * Sequential rather than parallel on purpose. The target device has four weak
 * cores and one hardware video encoder; two concurrent transcodes are slower
 * than two sequential ones and compete for the same battery. Concurrent uploads
 * on a 3G link are strictly worse than one at a time.
 */
class SyncWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    private val repository = SyncRepository(context)
    private val compressor = VideoCompressor(context)
    private val retryPolicy = RetryPolicy()

    private val session = AppServices.session(context)
    private val api = AppServices.api(context)
    private val profileStore = AthleteProfileStore(context)

    private val uploadClient = UploadClient(
        baseUrl = SyncConfig.baseUrl(context),
        authTokenProvider = session::accessToken
    )

    override suspend fun doWork(): Result {

        // The one-shot, the periodic safety net and an athlete's "send now" are
        // separate WorkManager jobs and can start together. Only one may drain:
        // a second one's recovery pass below would reset the first one's
        // in-flight upload to QUEUED and start uploading the same file again.
        // Whoever holds the lock sends everything, so the others just stand down.
        if (!DRAIN_LOCK.tryLock()) {
            Log.i(TAG, "Another sync is already draining the queue")
            return Result.success()
        }
        return try {
            drain()
        } finally {
            DRAIN_LOCK.unlock()
        }
    }

    private suspend fun drain(): Result {

        // Anything stranded by a process death is rescued before we decide
        // what the queue contains. Safe here only because no other drain is
        // running in this process (see DRAIN_LOCK).
        repository.recoverInterrupted()

        if (!session.isRegistered) {
            // Nothing is lost by waiting: the queue stays on the phone. Sending
            // it without an account would be refused as unauthorized, which the
            // retry policy treats as terminal and would mark the athlete's
            // tests FAILED. Login schedules a sync the moment it completes.
            Log.i(TAG, "Not signed in; leaving the queue for after login")
            return Result.success()
        }

        // Refresh (if needed) before any bytes move. No token here means no
        // signal for the refresh call, not a bad session — try again later.
        if (withContext(Dispatchers.IO) { session.accessToken() } == null) {
            return if (session.isLoggedIn) Result.retry() else Result.success()
        }

        // Only the signed-in athlete's tests are sent, under their account.
        // Another athlete's queue on a shared phone waits for them to sign in.
        val athleteId = session.current()?.athleteId ?: return Result.success()
        repository.claimUnowned(athleteId)
        repository.deleteOrphanIdentityPhotos(olderThanMs = IdentityPhotos.ORPHAN_AFTER_MS)

        val queue = repository.workQueue(athleteId)

        if (queue.isEmpty()) {
            return if (submitPending(athleteId)) Result.success() else Result.retry()
        }

        Log.i(TAG, "Draining ${queue.size} queued attempt(s)")

        var shouldRetryLater = false

        for (entity in queue) {

            if (isStopped) {
                // WorkManager is reclaiming us. Leave the remaining rows in
                // their current states; recoverInterrupted picks them up next
                // time round.
                return Result.retry()
            }

            when (process(entity)) {
                Outcome.DONE -> Unit
                Outcome.RETRY_LATER -> shouldRetryLater = true
                Outcome.GAVE_UP -> Unit
            }
        }

        if (!submitPending(athleteId)) {
            shouldRetryLater = true
        }

        return if (shouldRetryLater) Result.retry() else Result.success()
    }

    /**
     * Submits every uploaded video that has no test attached yet.
     *
     * Returns false when something should be retried later. Safe to repeat:
     * the server is idempotent on the video, so a submission whose response was
     * lost comes back as the same result rather than a duplicate attempt.
     */
    private suspend fun submitPending(athleteId: String): Boolean {
        var allDone = true

        for (entity in repository.unsubmitted(athleteId)) {
            if (isStopped) return false

            if (!retryPolicy.hasAttemptsLeft(entity.attemptCount)) continue

            val videoId = entity.videoId ?: continue

            // A photo taken offline before this attempt is checked first, so
            // the submission can name the check.
            val identityCheckId = when (val identity = identityFor(entity)) {
                IdentityOutcome.Wait -> {
                    allDone = false
                    continue
                }
                is IdentityOutcome.Ready -> identity.checkId
            }

            val response = withContext(Dispatchers.IO) {
                api.submitTest(
                    testType = entity.testType,
                    provisionalScore = entity.provisionalScore,
                    videoId = videoId,
                    // The server prefers the registered profile height; this is
                    // only a fallback for a profile saved without one.
                    heightCm = profileStore.heightCm(),
                    // The recording time, not now: the server checks it fell
                    // inside the session, however late the phone found signal.
                    sessionId = entity.sessionId,
                    recordedAtMs = entity.recordedAtMs,
                    identityCheckId = identityCheckId,
                    provisionalFormScore = repository.formScore(entity.id)
                )
            }

            when (val decision = SubmissionPolicy.decide(response)) {
                is SubmissionPolicy.Decision.Record -> {
                    repository.recordResultId(entity.id, decision.resultId)
                    Log.i(TAG, "Submitted ${entity.id} as result ${decision.resultId}")
                }

                SubmissionPolicy.Decision.RetryLater -> allDone = false

                is SubmissionPolicy.Decision.Refused -> {
                    // A refusal is SAI's answer about this test — already
                    // submitted, session closed — and asking again gets the
                    // same answer. It is shown to the athlete, not retried.
                    Log.w(TAG, "Submission refused for ${entity.id}: ${decision.reason}")
                    repository.recordSubmissionRefused(entity.id, decision.reason)
                }
            }
        }

        return allDone
    }

    private sealed interface IdentityOutcome {
        data object Wait : IdentityOutcome
        data class Ready(val checkId: String?) : IdentityOutcome
    }

    private suspend fun identityFor(entity: TestAttemptEntity): IdentityOutcome {
        val photoPath = entity.identityPhotoPath
            ?: return IdentityOutcome.Ready(entity.identityCheckId)

        val photo = repository.identityPhotoFile(photoPath)
        if (!photo.exists()) {
            // Another attempt from the same sitting already sent it; that row
            // was updated, but this copy of the entity predates the update.
            return IdentityOutcome.Ready(repository.find(entity.id)?.identityCheckId)
        }

        val response = withContext(Dispatchers.IO) {
            api.identityCheck(
                jpeg = photo.readBytes(),
                capturedAtMs = photo.lastModified(),
                sessionId = entity.sessionId
            )
        }

        return when (val decision = IdentityUploadPolicy.decide(response)) {
            is IdentityUploadPolicy.Decision.Attach -> {
                repository.resolveIdentityPhoto(photoPath, decision.checkId)
                IdentityOutcome.Ready(decision.checkId)
            }
            IdentityUploadPolicy.Decision.RetryLater -> IdentityOutcome.Wait
            IdentityUploadPolicy.Decision.SubmitWithout -> {
                Log.w(TAG, "Photo check for ${entity.id} could not be made; submitting without")
                repository.resolveIdentityPhoto(photoPath, null)
                IdentityOutcome.Ready(null)
            }
        }
    }

    private enum class Outcome { DONE, RETRY_LATER, GAVE_UP }

    private suspend fun process(entity: TestAttemptEntity): Outcome {

        val compressed = repository.compressedVideoFile(entity)

        val readyToUpload =
            entity.syncStatus == SyncStatus.QUEUED ||
                (entity.syncStatus == SyncStatus.COMPRESSED && compressed?.exists() == true)

        return when {
            readyToUpload && compressed != null && compressed.exists() ->
                upload(entity, compressed)

            else -> when (val result = compress(entity)) {
                Outcome.DONE -> {
                    // Compression just finished; upload in the same pass rather
                    // than waiting for the next scheduled run. The athlete may
                    // only have signal for another minute.
                    repository.find(entity.id)?.let { refreshed ->
                        repository.compressedVideoFile(refreshed)
                            ?.takeIf { it.exists() }
                            ?.let { upload(refreshed, it) }
                            ?: Outcome.RETRY_LATER
                    } ?: Outcome.RETRY_LATER
                }

                else -> result
            }
        }
    }

    private suspend fun compress(entity: TestAttemptEntity): Outcome {

        val source = repository.sourceVideoFile(entity)

        if (!source.exists()) {
            fail(entity, UploadFailure.FILE_MISSING.message)
            return Outcome.GAVE_UP
        }

        if (!repository.transition(entity.id, SyncStatus.COMPRESSING)) {
            return Outcome.RETRY_LATER
        }

        val destination = repository.compressedVideoTarget(entity.id)

        return when (val result = compressor.compress(source, destination)) {

            is VideoCompressor.Result.Success -> {

                Log.i(
                    TAG,
                    "Compressed ${entity.id}: " +
                        "${result.originalSizeBytes / 1024}KB -> " +
                        "${result.compressedSizeBytes / 1024}KB " +
                        "(${(result.ratio * 100).toInt()}%)"
                )

                repository.recordCompressionResult(
                    id = entity.id,
                    compressedFile = result.outputFile,
                    // Hashed AFTER compression, over the exact bytes that will
                    // be uploaded, so the server's verification covers what it
                    // actually received.
                    checksum = withContext(Dispatchers.IO) {
                        Checksum.sha256(result.outputFile)
                    }
                )

                repository.transition(entity.id, SyncStatus.QUEUED)

                Outcome.DONE
            }

            is VideoCompressor.Result.Failure -> {
                repository.recordAttemptFailure(entity.id, result.message)

                val attempts = entity.attemptCount + 1

                if (retryPolicy.hasAttemptsLeft(attempts)) {
                    repository.transition(entity.id, SyncStatus.RECORDED, result.message)
                    Outcome.RETRY_LATER
                } else {
                    fail(entity, result.message)
                    Outcome.GAVE_UP
                }
            }
        }
    }

    private suspend fun upload(
        entity: TestAttemptEntity,
        compressedFile: File
    ): Outcome {

        // Hashing megabytes is I/O plus CPU; keep it off the worker's dispatcher.
        val checksum = entity.checksumSha256
            ?: withContext(Dispatchers.IO) { Checksum.sha256(compressedFile) }

        if (!repository.transition(entity.id, SyncStatus.UPLOADING)) {
            return Outcome.RETRY_LATER
        }

        // OkHttp calls block. CoroutineWorker runs doWork on Dispatchers.Default,
        // a small pool sized for CPU work — blocking one of those threads for
        // the length of a 3G upload starves everything else scheduled on it.
        val result = withContext(Dispatchers.IO) {
            uploadClient.upload(
                file = compressedFile,
                checksumSha256 = checksum,
                testType = entity.testType,
                existingUploadId = entity.uploadId,
                onUploadIdIssued = { uploadId ->
                    // Persisted before any bytes move. This is what makes a
                    // killed process cost one chunk instead of the whole video.
                    runBlockingSafely { repository.recordUploadId(entity.id, uploadId) }
                },
                progressListener = { sent, _ ->
                    runBlockingSafely { repository.recordUploadProgress(entity.id, sent) }
                }
            )
        }

        return when (result) {

            is UploadClient.Result.Success -> {
                repository.markSynced(entity.id, result.videoId)
                Log.i(TAG, "Synced ${entity.id} as video ${result.videoId}")
                Outcome.DONE
            }

            is UploadClient.Result.Failure -> {

                val detail = result.detail ?: result.reason.message
                repository.recordAttemptFailure(entity.id, detail)

                val attempts = entity.attemptCount + 1

                if (retryPolicy.isRetryable(result.reason) &&
                    retryPolicy.hasAttemptsLeft(attempts)
                ) {
                    // Back to QUEUED, not FAILED: the delivered chunks and the
                    // upload id survive, so the next attempt resumes.
                    repository.transition(entity.id, SyncStatus.QUEUED, detail)
                    Outcome.RETRY_LATER
                } else {
                    fail(entity, result.reason.message)
                    Outcome.GAVE_UP
                }
            }
        }
    }

    private suspend fun fail(entity: TestAttemptEntity, reason: String) {
        Log.w(TAG, "Giving up on ${entity.id}: $reason")
        repository.transition(entity.id, SyncStatus.FAILED, reason)
    }

    /**
     * Bridges the upload client's synchronous callbacks into suspend calls.
     *
     * The callbacks fire on the worker's own coroutine thread while it is
     * blocked inside the HTTP call, so this cannot deadlock — but a failure
     * writing a progress row must never take down an upload that is otherwise
     * succeeding.
     */
    private fun runBlockingSafely(block: suspend () -> Unit) {
        runCatching {
            kotlinx.coroutines.runBlocking { block() }
        }.onFailure {
            Log.w(TAG, "Progress update failed", it)
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "f4all-sync"

        /** Held for the whole of a drain; see [doWork]. Workers share this process. */
        internal val DRAIN_LOCK = Mutex()
    }
}

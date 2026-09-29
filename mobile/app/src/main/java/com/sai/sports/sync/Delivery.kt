package com.sai.sports.sync

import com.sai.sports.data.local.TestAttemptEntity

/**
 * Where a test stands from the athlete's side — which is not quite the upload
 * state machine. The video reaching SAI (SYNCED) is only half of it; the test
 * also has to be submitted against that video, and SAI can refuse it.
 */
enum class Delivery {
    /** Still on the phone or on its way: compressing, queued, uploading. */
    SENDING,

    /** The video is with SAI; the test itself is still to be submitted. */
    WAITING_TO_SUBMIT,

    /** Submitted. SAI now verifies it. */
    SUBMITTED,

    /** SAI refused the submission (already submitted, session closed…). Final. */
    NOT_ACCEPTED,

    /** The phone gave up sending it; the athlete can retry. */
    FAILED;

    companion object {
        fun of(attempt: TestAttemptEntity, maxAttempts: Int = RetryPolicy.MAX_ATTEMPTS): Delivery =
            when {
                attempt.syncStatus == SyncStatus.FAILED -> FAILED
                attempt.syncStatus != SyncStatus.SYNCED -> SENDING
                attempt.resultId != null -> SUBMITTED
                attempt.attemptCount >= maxAttempts -> NOT_ACCEPTED
                else -> WAITING_TO_SUBMIT
            }
    }
}

/**
 * Why a FAILED attempt failed, in terms of what the athlete can do about it.
 *
 * The stored error is a developer diagnostic in English; this turns the
 * failures the worker records by name into an instruction.
 */
enum class FailureHint {
    /** The recording is gone from the phone. Retrying cannot help. */
    RECORDING_MISSING,

    /** Signed out, or the session expired for good. Sign in, then retry. */
    SIGN_IN,

    /** Anything else: retry, ideally somewhere with better signal. */
    RETRY;

    /** Whether the retry button can possibly help. */
    val retryable: Boolean get() = this != RECORDING_MISSING

    companion object {
        fun of(lastError: String?): FailureHint = when (lastError) {
            UploadFailure.FILE_MISSING.message -> RECORDING_MISSING
            UploadFailure.UNAUTHORIZED.message -> SIGN_IN
            else -> RETRY
        }
    }
}

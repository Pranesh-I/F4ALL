package com.sai.sports.sync

import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult

/**
 * What to do with the answer to a test submission.
 *
 * Kept apart from the worker so these decisions are verified on the JVM: getting
 * any of them wrong either loses a test silently or burns the athlete's data
 * retrying a call that will never succeed.
 */
object SubmissionPolicy {

    sealed interface Decision {
        /** Submitted. Store the id; the server now owns verification. */
        data class Record(val resultId: String) : Decision

        /** Try again next run, without spending the attempt budget. */
        data object RetryLater : Decision

        /** The server refused this submission. Counts against the budget. */
        data class Refused(val reason: String) : Decision
    }

    fun decide(result: ApiResult<String>): Decision = when (result) {
        is ApiResult.Success -> Decision.Record(result.value)

        is ApiResult.Failure -> when {
            // No signal, a server hiccup, rate limiting: nothing about the
            // submission is wrong, so it must not use up the attempt budget.
            result.kind.isTransient -> Decision.RetryLater

            // An expired or refreshing session. The athlete's test is fine;
            // the next run carries a fresh token.
            result.kind == ApiFailure.UNAUTHORIZED -> Decision.RetryLater

            else -> Decision.Refused(result.message)
        }
    }
}

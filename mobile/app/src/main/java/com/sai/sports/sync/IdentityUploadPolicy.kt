package com.sai.sports.sync

import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.IdentityCheckResult

/**
 * What to do with the answer to a photo taken offline and sent at sync.
 *
 * Whatever the check concluded, the attempt is still submitted: an attempt
 * without a confirmed identity reaches a reviewer, it is not refused. So the
 * only question here is whether to wait and try again, or move on.
 */
object IdentityUploadPolicy {

    sealed interface Decision {
        /** Attach this check (any outcome) to the attempts that waited on the photo. */
        data class Attach(val checkId: String) : Decision

        /** No signal, a busy server, or too many checks this hour: keep the photo, retry. */
        data object RetryLater : Decision

        /**
         * The check cannot happen — no registration photo, consent withdrawn, a
         * photo the server will not read. Delete the photo and submit without.
         */
        data object SubmitWithout : Decision
    }

    fun decide(response: ApiResult<IdentityCheckResult>): Decision = when (response) {
        is ApiResult.Success -> Decision.Attach(response.value.checkId)
        is ApiResult.Failure ->
            if (response.kind.isTransient || response.kind == ApiFailure.UNAUTHORIZED) {
                // An expired session is refreshed on the next run, not a reason
                // to throw the photo away.
                Decision.RetryLater
            } else {
                Decision.SubmitWithout
            }
    }
}

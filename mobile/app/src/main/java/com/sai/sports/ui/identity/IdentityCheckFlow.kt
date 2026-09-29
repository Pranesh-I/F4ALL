package com.sai.sports.ui.identity

import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.IdentityCheckResult

/** Where the photo check before an official test has got to. */
sealed interface IdentityStep {

    /** The camera is showing; the athlete takes the photo. */
    data object Ready : IdentityStep

    data object Checking : IdentityStep

    /** Matched. The test can start. */
    data class Passed(val checkId: String) : IdentityStep

    /**
     * No face, or no clear match. The athlete is told why and retakes; after
     * [IdentityCheckFlow.MAX_TRIES] they may also continue, and an official
     * compares the photos instead.
     */
    data class TryAgain(
        val reason: Reason,
        val failures: Int,
        val lastCheckId: String?
    ) : IdentityStep {
        val mayContinue: Boolean get() = failures >= IdentityCheckFlow.MAX_TRIES
    }

    /**
     * The server could not compare, or the athlete has checked too often this
     * hour. Nothing the athlete can fix by retaking: they continue, and an
     * official confirms it is them.
     */
    data class CouldNotCheck(val checkId: String?) : IdentityStep

    /** No signal. The photo is kept and checked when the phone syncs. */
    data object CheckLater : IdentityStep

    /** Consent or the registration photo is missing; the profile needs completing. */
    data object NeedsProfile : IdentityStep

    /** Something unexpected; the athlete can try again. */
    data object Failed : IdentityStep

    enum class Reason { NO_FACE, NO_MATCH }
}

/**
 * The decisions behind the photo check, apart from the camera and the screen.
 *
 * Built around one rule: the check routes doubt to a person, it never locks
 * an athlete out. The matcher is approximate and least reliable for exactly
 * the athletes this platform serves — young, rural, photographed on a cheap
 * camera in whatever light there is.
 */
class IdentityCheckFlow {

    var failures = 0
        private set

    private var lastCheckId: String? = null

    fun onResponse(response: ApiResult<IdentityCheckResult>): IdentityStep = when (response) {
        is ApiResult.Success -> onResult(response.value)
        is ApiResult.Failure -> when (response.kind) {
            ApiFailure.NETWORK, ApiFailure.SERVER -> IdentityStep.CheckLater
            ApiFailure.CONFLICT -> IdentityStep.NeedsProfile
            ApiFailure.RATE_LIMITED -> IdentityStep.CouldNotCheck(lastCheckId)
            else -> IdentityStep.Failed
        }
    }

    private fun onResult(result: IdentityCheckResult): IdentityStep = when (result.outcome) {
        IdentityCheckResult.MATCH -> IdentityStep.Passed(result.checkId)
        IdentityCheckResult.NO_FACE, IdentityCheckResult.NO_MATCH -> {
            failures += 1
            lastCheckId = result.checkId
            IdentityStep.TryAgain(
                reason = if (result.outcome == IdentityCheckResult.NO_FACE) {
                    IdentityStep.Reason.NO_FACE
                } else {
                    IdentityStep.Reason.NO_MATCH
                },
                failures = failures,
                lastCheckId = result.checkId
            )
        }
        // "unavailable", or an outcome a newer server knows and this app does not.
        else -> IdentityStep.CouldNotCheck(result.checkId)
    }

    companion object {
        /** Retakes before the athlete may continue without a match. */
        const val MAX_TRIES = 3
    }
}

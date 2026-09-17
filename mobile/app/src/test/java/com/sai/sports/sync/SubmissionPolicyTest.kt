package com.sai.sports.sync

import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionPolicyTest {

    @Test
    fun `a successful submission records the result id`() {
        assertEquals(
            SubmissionPolicy.Decision.Record("r-1"),
            SubmissionPolicy.decide(ApiResult.Success("r-1"))
        )
    }

    @Test
    fun `transient failures retry without spending the budget`() {
        listOf(ApiFailure.NETWORK, ApiFailure.SERVER, ApiFailure.RATE_LIMITED).forEach { kind ->
            assertEquals(
                "$kind must retry later",
                SubmissionPolicy.Decision.RetryLater,
                SubmissionPolicy.decide(ApiResult.Failure(kind, "x"))
            )
        }
    }

    @Test
    fun `an expired session retries rather than giving up on the test`() {
        assertEquals(
            SubmissionPolicy.Decision.RetryLater,
            SubmissionPolicy.decide(ApiResult.Failure(ApiFailure.UNAUTHORIZED, "expired"))
        )
    }

    @Test
    fun `a refused submission is recorded with its reason`() {
        val decision = SubmissionPolicy.decide(ApiResult.Failure(ApiFailure.INVALID, "Unknown video_id"))

        assertTrue(decision is SubmissionPolicy.Decision.Refused)
        assertEquals("Unknown video_id", (decision as SubmissionPolicy.Decision.Refused).reason)
    }
}

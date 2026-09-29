package com.sai.sports.data

import com.sai.sports.analyzer.AttemptStatus

/**
 * Whether an attempt may be queued for upload to SAI.
 *
 * Pure, so the rule every official submission passes through is pinned by a
 * JVM test rather than by every caller remembering to check.
 */
object UploadEligibility {

    /**
     * Official, scored, and owned. An attempt with no athlete could only be
     * uploaded as whoever happens to be signed in, which is exactly the
     * shared-phone mix-up ownership exists to prevent.
     */
    fun isUploadable(attempt: Attempt): Boolean =
        attempt.mode == AttemptMode.OFFICIAL &&
            attempt.result.status == AttemptStatus.COMPLETE &&
            attempt.athleteId != null
}

package com.sai.sports.ui.profile

import androidx.annotation.StringRes
import com.sai.sports.R
import com.sai.sports.ui.kit.StepState

/**
 * How result scores are presented to an athlete.
 *
 * The rule: a number is only called official once an official has approved it.
 * "Verified" means SAI's system re-measured the video and agreed; it is not the
 * final word, and the wording must never let an athlete believe they are done
 * when they are not.
 */
object ResultPresentation {

    /** The score to headline, and the label that says what kind of score it is. */
    fun headline(
        provisional: Double?,
        server: Double?,
        final: Double?
    ): Pair<Double?, Int> = when {
        final != null -> final to R.string.score_official
        server != null -> server to R.string.score_server
        else -> provisional to R.string.score_provisional
    }

    /**
     * How a result's score is written — the trials scoresheet's rule.
     *
     * The phone's count is pencil. SAI's re-measurement is pen, and when it
     * differs from the phone, the pencil number is shown crossed out beside
     * it. Only an official's approval earns the stamp. A refusal is struck
     * through in red.
     */
    fun writing(status: String, provisional: Double?, server: Double?, final: Double?): Writing = when {
        status == "rejected" -> Writing.STRUCK
        final != null -> Writing.STAMPED
        server != null && provisional != null && server != provisional -> Writing.CORRECTED
        server != null -> Writing.PEN
        else -> Writing.PENCIL
    }

    enum class Writing { PENCIL, PEN, CORRECTED, STAMPED, STRUCK }

    /** Has SAI's own measurement run? Everything past the upload queue has been checked. */
    fun checkStep(status: String): StepState =
        if (status == "uploaded" || status == "processing") StepState.Active else StepState.Done

    /** Where the official decision stands. */
    fun approvalStep(status: String): StepState = when (status) {
        "approved" -> StepState.Done
        "rejected", "pending_sync" -> StepState.Refused
        "verified", "flagged" -> StepState.Active
        else -> StepState.Waiting
    }

    @StringRes
    fun officialLabel(official: Boolean): Int =
        if (official) R.string.best_official else R.string.best_not_approved

    /**
     * Digits only, with no unit — the caller adds the translated unit.
     * Always Latin digits via Locale.US, so a score reads the same in every
     * language and matches what an official sees on the dashboard.
     */
    fun formatNumber(value: Double?): String {
        if (value == null) return "—"
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }
    }
}

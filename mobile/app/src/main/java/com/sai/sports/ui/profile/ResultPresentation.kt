package com.sai.sports.ui.profile

import java.util.Locale

/**
 * How server-side result states are described to an athlete.
 *
 * The rule every label follows: a number is only called official once an
 * official has approved it. "Verified" means SAI's system re-measured the video
 * and agreed; it is not the final word, and the wording must never let an
 * athlete believe they are finished when they are not.
 */
object ResultPresentation {

    fun statusLabel(status: String): String = when (status) {
        "processing" -> "Being checked by SAI"
        "verified" -> "Checked by SAI — awaiting official approval"
        "flagged" -> "Under review by an SAI official"
        "approved" -> "Approved by SAI"
        "rejected" -> "Not accepted"
        "pending_sync" -> "Resubmission requested"
        else -> "Status unknown"
    }

    /** The score to headline, and whether it may be called official. */
    fun headline(
        provisional: Double?,
        server: Double?,
        final: Double?
    ): Pair<Double?, String> = when {
        final != null -> final to "Official score"
        server != null -> server to "SAI measured score (not yet official)"
        else -> provisional to "Your phone's score (provisional)"
    }

    fun formatScore(value: Double?, unit: String): String {
        if (value == null) return "—"
        val number = if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$number $unit"
    }

    fun testName(code: String): String = when (code) {
        "SIT_UPS" -> "Sit-ups"
        "VERTICAL_JUMP" -> "Vertical Jump"
        else -> code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    fun reviewLabel(action: String): String = when (action) {
        "approved" -> "An official approved this result"
        "rejected" -> "An official did not accept this result"
        "requested_resubmission" -> "An official has asked you to record this test again"
        else -> "An official reviewed this result"
    }
}

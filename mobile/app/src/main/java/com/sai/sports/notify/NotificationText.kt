package com.sai.sports.notify

import com.sai.sports.R
import com.sai.sports.api.AppNotification

/**
 * How a notification reads, and where tapping it goes.
 *
 * The app phrases each event itself, in its own language setting, like every
 * other screen: the server sends a stable event code, and its own rendering is
 * only used for an event this version of the app does not know yet. Kept free
 * of Context so the mapping is unit-tested.
 */
object NotificationText {

    /** The title and body string resources for [eventType]; null for an unknown event. */
    fun strings(eventType: String): Pair<Int, Int>? = when (eventType) {
        "session_opened" -> R.string.notif_session_opened_title to R.string.notif_session_opened_body
        "session_ending_soon" -> R.string.notif_session_ending_title to R.string.notif_session_ending_body
        "submission_received" -> R.string.notif_received_title to R.string.notif_received_body
        "verification_completed" -> R.string.notif_verified_title to R.string.notif_verified_body
        "submission_flagged" -> R.string.notif_flagged_title to R.string.notif_flagged_body
        "submission_approved" -> R.string.notif_approved_title to R.string.notif_approved_body
        "submission_rejected" -> R.string.notif_rejected_title to R.string.notif_rejected_body
        "resubmission_requested" -> R.string.notif_resubmission_title to R.string.notif_resubmission_body
        else -> null
    }

    /** True for events about a session (the title names the session, not a test). */
    fun isSessionEvent(eventType: String): Boolean = eventType.startsWith("session_")

    /** Where tapping a notification takes the athlete. */
    fun destination(notification: AppNotification): Destination {
        notification.data["result_id"]?.takeIf { it.isNotBlank() }?.let { return Destination.Result(it) }
        if (isSessionEvent(notification.eventType)) return Destination.Home
        return Destination.Inbox
    }

    sealed interface Destination {
        data class Result(val resultId: String) : Destination
        /** Sessions are listed on the home screen. */
        data object Home : Destination
        data object Inbox : Destination
    }

    /** Which notifications to show as phone notifications now. */
    fun toPost(
        unread: List<AppNotification>,
        alreadyShown: Set<String>,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        limit: Int = MAX_AT_ONCE
    ): List<AppNotification> =
        unread
            .filter { it.id !in alreadyShown && nowMs - it.createdAtMs <= maxAgeMs }
            .sortedByDescending { it.createdAtMs }
            .take(limit)

    /** Older than this, it waits in the in-app inbox rather than buzzing the phone. */
    const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    /** A phone that was off for a week is not flooded on the first check. */
    const val MAX_AT_ONCE = 5
}

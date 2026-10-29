package com.sai.sports.notify

import android.content.Intent

/** Where a tapped phone notification asks the app to go. */
data class NotificationLink(
    val notificationId: String?,
    val resultId: String?,
    val openHome: Boolean
) {
    companion object {
        fun from(intent: Intent?): NotificationLink? {
            intent ?: return null
            val notificationId = intent.getStringExtra(NotificationPoster.EXTRA_NOTIFICATION_ID)
                ?: return null
            return NotificationLink(
                notificationId = notificationId,
                resultId = intent.getStringExtra(NotificationPoster.EXTRA_RESULT_ID),
                openHome = intent.getStringExtra(NotificationPoster.EXTRA_OPEN) == NotificationPoster.OPEN_HOME
            )
        }
    }
}

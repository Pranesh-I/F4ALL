package com.sai.sports.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sai.sports.MainActivity
import com.sai.sports.R
import com.sai.sports.api.AppNotification
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.ui.common.Labels

/**
 * Shows inbox notifications as phone notifications, in the app's language.
 *
 * Tapping one opens the app at the screen it is about; the activity reads the
 * extras below. Nothing is posted without the notification permission, and
 * posted notifications are cleared when the athlete signs out, so the next
 * person on a shared phone never sees them.
 */
object NotificationPoster {

    const val CHANNEL_ID = "results_and_sessions"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
    const val EXTRA_RESULT_ID = "open_result_id"
    const val EXTRA_OPEN = "open_screen"
    const val OPEN_HOME = "home"
    const val OPEN_INBOX = "inbox"

    fun canPost(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts each notification; returns the ids actually shown. */
    fun post(context: Context, notifications: List<AppNotification>): List<String> {
        if (notifications.isEmpty() || !canPost(context)) return emptyList()
        // The athlete's chosen language, not the phone's.
        val localized = LanguageStore.wrap(context)
        ensureChannel(localized)

        val manager = NotificationManagerCompat.from(context)
        return notifications.mapNotNull { notification ->
            val (title, body) = text(localized, notification)
            val built = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setWhen(notification.createdAtMs)
                .setShowWhen(true)
                // Results are personal: hidden on a locked screen.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(openIntent(context, notification))
                .build()
            try {
                manager.notify(notification.id.hashCode(), built)
                notification.id
            } catch (denied: SecurityException) {
                null
            }
        }
    }

    /** Clears everything posted, for signing out on a shared phone. */
    fun clearAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun text(context: Context, notification: AppNotification): Pair<String, String> {
        val strings = NotificationText.strings(notification.eventType)
            ?: return notification.title to notification.body
        val subject = if (NotificationText.isSessionEvent(notification.eventType)) {
            notification.data["session_name"].orEmpty()
        } else {
            context.getString(Labels.testName(notification.data["test_type"].orEmpty()))
        }
        return context.getString(strings.first, subject) to context.getString(strings.second)
    }

    private fun openIntent(context: Context, notification: AppNotification): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notification.id)
            when (val destination = NotificationText.destination(notification)) {
                is NotificationText.Destination.Result -> putExtra(EXTRA_RESULT_ID, destination.resultId)
                NotificationText.Destination.Home -> putExtra(EXTRA_OPEN, OPEN_HOME)
                NotificationText.Destination.Inbox -> putExtra(EXTRA_OPEN, OPEN_INBOX)
            }
        }
        return PendingIntent.getActivity(
            context,
            notification.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

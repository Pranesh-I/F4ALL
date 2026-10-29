package com.sai.sports.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Checks the signed-in athlete's inbox and shows anything new.
 *
 * This is how notifications reach the phone until SAI's Firebase project
 * exists for push: every 15 minutes with a network, plus once at start-up.
 * WorkManager's own minimum, and gentle on a prepaid data pack — one small
 * request. Signed out, it does nothing.
 */
class NotificationWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val athleteId = AppServices.session(applicationContext).current()?.athleteId
            ?: return Result.success()

        val page = withContext(Dispatchers.IO) {
            AppServices.api(applicationContext).notifications(unreadOnly = true, limit = 20)
        }
        if (page !is ApiResult.Success) {
            return if (page is ApiResult.Failure && page.kind.isTransient) Result.retry() else Result.success()
        }

        val shown = ShownNotifications(applicationContext)
        val fresh = NotificationText.toPost(
            unread = page.value.items,
            alreadyShown = shown.shown(athleteId),
            nowMs = System.currentTimeMillis()
        )
        // Only what was really posted is remembered, so a notification held back
        // for want of permission still appears once permission is given.
        shown.record(athleteId, NotificationPoster.post(applicationContext, fresh))
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "notification-check"
        private const val NOW = "notification-check-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun checkSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotificationWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

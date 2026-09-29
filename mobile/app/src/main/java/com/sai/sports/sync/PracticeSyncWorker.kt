package com.sai.sports.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Saves practice results to the athlete's account once there is a network.
 *
 * Separate from [SyncWorker] on purpose: that one moves official test videos
 * and must never be delayed or confused by practice, and practice results are
 * a few KB that should not wait behind a video upload.
 */
class PracticeSyncWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {

        val session = AppServices.session(applicationContext)
        val athleteId = session.current()?.athleteId ?: return Result.success()

        val outcome = withContext(Dispatchers.IO) {
            PracticeSync(AttemptStore(applicationContext), AppServices.api(applicationContext))
                .sync(athleteId)
        }

        return if (outcome.retryLater) Result.retry() else Result.success()
    }

    companion object {

        private const val WORK_NAME = "practice-sync"

        /** Called after a practice attempt is saved, and after signing in. */
        fun syncSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<PracticeSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RetryPolicy.INITIAL_DELAY_MS, TimeUnit.MILLISECONDS)
                .build()

            // APPEND_OR_REPLACE: an attempt saved while a sync is already
            // running still gets its own run afterwards.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

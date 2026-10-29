package com.sai.sports.fieldtest

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
import com.sai.sports.sync.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sends field attempts once there is a network: official runs to SAI, practice
 * to the athlete's account. A run recorded on a ground with no signal goes the
 * moment the phone finds one, without the athlete opening the app again.
 */
class FieldSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val athleteId = AppServices.session(applicationContext).current()?.athleteId
            ?: return Result.success()
        val outcome = withContext(Dispatchers.IO) {
            FieldSync(FieldAttemptStore(applicationContext), AppServices.api(applicationContext)).sync(athleteId)
        }
        return if (outcome.retryLater) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "field-sync"

        fun syncSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<FieldSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RetryPolicy.INITIAL_DELAY_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

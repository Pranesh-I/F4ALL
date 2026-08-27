package com.sai.sports.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Schedules the sync worker.
 *
 * Two schedules, doing different jobs:
 *
 *  - A one-shot kicked immediately after a recording, so an athlete who already
 *    has signal sees the upload happen rather than waiting for a timer.
 *  - A periodic safety net, so an attempt recorded in airplane mode still goes
 *    out days later without the athlete ever opening the app again.
 *
 * Both are unique work: without that, ten recordings in a row would enqueue ten
 * workers all trying to drain the same queue.
 */
object SyncScheduler {

    /**
     * CONNECTED rather than UNMETERED.
     *
     * Waiting for wifi would be kinder to the athlete's data bill, but many of
     * the athletes this is built for have mobile data and nothing else. A test
     * that only uploads on wifi is a test that never uploads. Compressing to
     * 480p first is how the data cost is kept defensible.
     *
     * No battery-not-low constraint either: the video matters more than the
     * last few percent, and the transcode is hardware-accelerated.
     */
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Called after a recording is enqueued, and from the sync screen's retry. */
    fun syncNow(context: Context) {

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RetryPolicy.INITIAL_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SyncWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.WORK_NAME,
            // KEEP, not REPLACE: a worker already uploading should be allowed
            // to finish. Replacing it mid-chunk throws away progress the
            // athlete already paid for in data.
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Called once at app startup. */
    fun ensurePeriodicSync(context: Context) {

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIODIC_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RetryPolicy.INITIAL_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
            .addTag(PERIODIC_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** True while a sync worker is actually running, for the sync screen. */
    fun observeSyncRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(SyncWorker.WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    private const val PERIODIC_WORK_NAME = "f4all-sync-periodic"

    /**
     * The floor WorkManager allows for periodic work is 15 minutes. Chosen
     * deliberately: the OS coalesces these with other apps' wakeups, so a short
     * interval costs little, and an athlete who regains signal briefly while
     * walking through a town should not miss the window.
     */
    private const val PERIODIC_INTERVAL_MINUTES = 15L
}

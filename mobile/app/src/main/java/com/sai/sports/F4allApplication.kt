package com.sai.sports

import android.app.Application
import android.util.Log
import com.sai.sports.data.SyncRepository
import com.sai.sports.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Startup wiring for the offline queue.
 *
 * Two things have to happen before the athlete touches anything, and both have
 * to happen even if they never open the sync screen:
 *
 *  1. Rescue attempts stranded mid-compression or mid-upload by a process
 *     death. Android force-stops backgrounded apps freely, and a row left in an
 *     in-progress state is invisible to the work queue — the athlete's test
 *     would sit there looking busy until they reinstalled.
 *
 *  2. Register the periodic sync. This is what delivers a test recorded in
 *     airplane mode days later, without the athlete opening the app again.
 */
class F4allApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        applicationScope.launch {
            runCatching {
                SyncRepository(this@F4allApplication).recoverInterrupted()
            }.onFailure {
                // Never let queue recovery stop the app from starting — the
                // athlete still needs to be able to record.
                Log.e(TAG, "Queue recovery failed at startup", it)
            }

            runCatching {
                SyncScheduler.ensurePeriodicSync(this@F4allApplication)
                SyncScheduler.syncNow(this@F4allApplication)
            }.onFailure {
                Log.e(TAG, "Could not schedule sync", it)
            }
        }
    }

    companion object {
        private const val TAG = "F4allApplication"
    }
}

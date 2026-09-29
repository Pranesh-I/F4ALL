package com.sai.sports.ui.practice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brings the athlete's practice up to date with their account whenever a
 * practice screen opens, and calls [onHistoryChanged] if anything arrived.
 *
 * The screen shows what is on the phone straight away and fills in anything
 * from the athlete's other phones a moment later; with no signal it simply
 * shows what is here.
 */
@Composable
internal fun SyncPracticeOnOpen(
    athleteId: String?,
    store: AttemptStore,
    onHistoryChanged: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(athleteId) {
        val id = athleteId ?: return@LaunchedEffect
        val outcome = withContext(Dispatchers.IO) {
            runCatching { PracticeSync(store, AppServices.api(context)).sync(id) }.getOrNull()
        }
        if (outcome?.changedHistory == true) onHistoryChanged()
    }
}

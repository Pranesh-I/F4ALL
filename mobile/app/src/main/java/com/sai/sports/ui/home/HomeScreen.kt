package com.sai.sports.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.SyncRepository
import com.sai.sports.sync.NetworkMonitor
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.ScreenTitle
import com.sai.sports.ui.common.SectionTitle
import kotlinx.coroutines.flow.flowOf

@Composable
fun HomeScreen(
    onSessionTest: (sessionId: String, TestType) -> Unit,
    onPractice: () -> Unit,
    onSyncStatus: () -> Unit,
    onProfile: () -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit,
    onCompleteProfile: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SyncRepository(context) }

    // This athlete's waiting tests, not everyone who has used the phone.
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val pendingCount by remember(athleteId) {
        athleteId?.let(repository::observePendingCount) ?: flowOf(0)
    }.collectAsStateWithLifecycle(initialValue = 0)
    val online by remember { NetworkMonitor.observeOnline(context) }
        .collectAsStateWithLifecycle(initialValue = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenTitle(stringResource(R.string.app_name))

        Spacer(modifier = Modifier.height(12.dp))

        /*
         * Practice comes first and looks different from the official tests: an
         * athlete who only wants to train must never submit an official
         * attempt by accident.
         */
        FilledTonalButton(onClick = onPractice, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_practice))
        }
        Text(
            stringResource(R.string.home_practice_note),
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Official tests need a complete profile; say so before the session does.
        ProfileCompletionBanner(athleteId = athleteId, onCompleteProfile = onCompleteProfile)

        // Official tests only exist inside a session SAI has opened.
        SessionsSection(athleteId = athleteId, onSessionTest = onSessionTest)

        Spacer(modifier = Modifier.height(12.dp))

        /*
         * The pending count is on the home screen, not buried in a menu.
         * An athlete in a low-connectivity area needs to know at a glance
         * that a test has not reached SAI yet — otherwise they assume it
         * has, and find out otherwise far too late.
         */
        OutlinedButton(onClick = onSyncStatus, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (pendingCount > 0) stringResource(R.string.home_sync_waiting, pendingCount)
                else stringResource(R.string.home_sync)
            )
        }
        if (pendingCount > 0 && !online) {
            Text(
                stringResource(R.string.home_sync_offline),
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedButton(onClick = onProfile, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_profile))
        }

        OutlinedButton(onClick = onLeaderboard, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.nav_leaderboard))
        }

        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.nav_settings))
        }

        Text(
            stringResource(R.string.home_provisional_note),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

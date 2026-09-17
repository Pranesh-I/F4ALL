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
import com.sai.sports.data.SyncRepository
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.ScreenTitle
import com.sai.sports.ui.common.SectionTitle

@Composable
fun HomeScreen(
    onTestSelected: (TestType) -> Unit,
    onSyncStatus: () -> Unit,
    onProfile: () -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SyncRepository(context) }

    val pendingCount by repository.observePendingCount()
        .collectAsStateWithLifecycle(initialValue = 0)

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

        SectionTitle(stringResource(R.string.home_choose_test))

        TestType.entries.forEach { type ->
            Button(onClick = { onTestSelected(type) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Labels.testName(type)))
            }
        }

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

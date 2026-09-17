package com.sai.sports.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.data.SyncRepository

@Composable
fun HomeScreen(
    onTestSelected: (String) -> Unit,
    onSyncStatus: () -> Unit,
    onProfile: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SyncRepository(context) }

    val pendingCount by repository.observePendingCount()
        .collectAsStateWithLifecycle(initialValue = 0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "SAI Sports Talent Assessment",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                onTestSelected("Vertical Jump")
            }
        ) {
            Text(text = "Vertical Jump")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                onTestSelected("Sit-ups")
            }
        ) {
            Text(text = "Sit-ups")
        }

        Spacer(modifier = Modifier.height(32.dp))

        /*
         * The pending count is on the home screen, not buried in a menu.
         * An athlete in a low-connectivity area needs to know at a glance
         * that a test has not reached SAI yet — otherwise they assume it
         * has, and find out otherwise far too late.
         */
        OutlinedButton(onClick = onProfile) {
            Text(text = "My profile & results")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onSyncStatus) {
            Text(
                text = if (pendingCount > 0) {
                    "Sync status ($pendingCount waiting)"
                } else {
                    "Sync status"
                }
            )
        }
    }
}
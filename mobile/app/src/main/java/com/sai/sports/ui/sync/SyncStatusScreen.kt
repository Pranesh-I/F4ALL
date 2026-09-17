package com.sai.sports.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.analyzer.TestType
import com.sai.sports.data.SyncRepository
import com.sai.sports.data.local.TestAttemptEntity
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the athlete what has reached SAI and what has not.
 *
 * This screen is the answer to a specific fear: an athlete in a low-connectivity
 * area has no way to tell whether their test was delivered or is quietly sitting
 * on the phone, and the failure mode of guessing wrong is travelling somewhere
 * to redo a test they already passed. Every attempt is listed with an honest
 * state, including the ones that failed.
 */
@Composable
fun SyncStatusScreen(
    onBack: () -> Unit,
    onResultSelected: (resultId: String) -> Unit = {}
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SyncRepository(context) }

    val attempts by repository.observeAttempts()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val isSyncing by SyncScheduler.observeSyncRunning(context)
        .collectAsStateWithLifecycle(initialValue = false)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "Sync status",
                style = MaterialTheme.typography.headlineSmall
            )

            TextButton(onClick = onBack) {
                Text("Back")
            }
        }

        val pending = attempts.count { it.syncStatus.isPending }

        Text(
            text = when {
                attempts.isEmpty() -> "No tests recorded yet."
                pending == 0 -> "All tests have reached SAI."
                isSyncing -> "Uploading… $pending test(s) remaining."
                else -> "$pending test(s) waiting for a connection."
            },
            style = MaterialTheme.typography.bodyMedium
        )

        if (pending > 0 && !isSyncing) {
            OutlinedButton(
                onClick = { SyncScheduler.syncNow(context) }
            ) {
                Text("Try now")
            }
        }

        if (attempts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Recorded tests will appear here until SAI has them.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(attempts, key = { it.id }) { attempt ->
                AttemptRow(
                    attempt = attempt,
                    onViewResult = onResultSelected,
                    onRetry = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                repository.retry(attempt.id)
                            }
                            SyncScheduler.syncNow(context)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AttemptRow(
    attempt: TestAttemptEntity,
    onViewResult: (String) -> Unit,
    onRetry: () -> Unit
) {

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                Text(
                    text = displayName(attempt.testType),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "${formatScore(attempt)} ${attempt.scoreUnit}",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Text(
                text = formatTimestamp(attempt.recordedAtMs),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = statusLabel(attempt.syncStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = when (attempt.syncStatus) {
                    SyncStatus.SYNCED -> MaterialTheme.colorScheme.primary
                    SyncStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            if (attempt.syncStatus == SyncStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { attempt.progress() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Once submitted, the athlete can follow the result through SAI's
            // checks. Before that there is nothing on the server to show.
            attempt.resultId?.let { resultId ->
                TextButton(onClick = { onViewResult(resultId) }) {
                    Text("See SAI result")
                }
            }

            // Shown only on FAILED. Surfacing the last transient error while an
            // attempt is still retrying happily would make normal operation
            // look broken.
            if (attempt.syncStatus == SyncStatus.FAILED) {

                attempt.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                OutlinedButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * Plain-language status.
 *
 * Deliberately avoids "synced", "queued", and every other word that means
 * something precise to us and nothing to a first-time smartphone user. SYNCED
 * says the recording was *sent*, not that the result is final — the score is
 * still provisional until SAI verifies it.
 */
private fun statusLabel(status: SyncStatus): String =
    when (status) {
        SyncStatus.RECORDED -> "Saved on this phone"
        SyncStatus.COMPRESSING -> "Preparing for upload…"
        SyncStatus.COMPRESSED -> "Ready to send"
        SyncStatus.QUEUED -> "Waiting for a connection"
        SyncStatus.UPLOADING -> "Sending to SAI…"
        SyncStatus.SYNCED -> "Sent to SAI ✓"
        SyncStatus.FAILED -> "Could not send"
    }

private fun displayName(testTypeName: String): String =
    runCatching { TestType.valueOf(testTypeName).displayName }
        .getOrDefault(testTypeName)

private fun formatScore(attempt: TestAttemptEntity): String =
    if (attempt.scoreUnit == "reps") attempt.provisionalScore.toInt().toString()
    else String.format(Locale.US, "%.1f", attempt.provisionalScore)

private fun formatTimestamp(timestampMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestampMs))

package com.sai.sports.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.R
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptMode
import com.sai.sports.data.SyncRepository
import com.sai.sports.data.local.TestAttemptEntity
import com.sai.sports.fieldtest.FieldAttempt
import com.sai.sports.fieldtest.FieldAttemptStore
import com.sai.sports.fieldtest.FieldOutcome
import com.sai.sports.fieldtest.FieldSyncWorker
import com.sai.sports.sync.Delivery
import com.sai.sports.sync.FailureHint
import com.sai.sports.sync.NetworkMonitor
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.sync.SyncStatus
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.RegisterRow
import com.sai.sports.ui.kit.RuleLine
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.StatusLine
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.profile.ResultPresentation
import com.sai.sports.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Only the signed-in athlete's queue: a shared phone may hold others'.
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val attempts by remember(athleteId) {
        athleteId?.let(repository::observeAttempts) ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val isSyncing by SyncScheduler.observeSyncRunning(context)
        .collectAsStateWithLifecycle(initialValue = false)

    val online by remember { NetworkMonitor.observeOnline(context) }
        .collectAsStateWithLifecycle(initialValue = true)

    // Official shuttle and endurance runs: no video, so not in the upload
    // queue above, but just as much "not sent yet" (Sprint 18).
    var fieldRefreshes by remember { mutableIntStateOf(0) }
    val fieldRuns by produceState(initialValue = emptyList<FieldAttempt>(), athleteId, fieldRefreshes, isSyncing) {
        value = withContext(Dispatchers.IO) {
            athleteId?.let { FieldAttemptStore(context).list(it) }.orEmpty()
                .filter { it.mode == AttemptMode.OFFICIAL && it.result.outcome == FieldOutcome.COMPLETE }
        }
    }
    val fieldWaiting = fieldRuns.count { it.waitingToSend }

    Page(scrollable = false, header = { TitleBar(stringResource(R.string.sync_title), onBack) }) {

        val pending = attempts.count {
            Delivery.of(it) == Delivery.SENDING || Delivery.of(it) == Delivery.WAITING_TO_SUBMIT
        } + fieldWaiting

        // Said plainly, because the fear is that closing the app loses the test.
        if (!online && pending > 0) {
            Sheet(style = SheetStyle.Field) {
                StatusLine(
                    text = stringResource(R.string.sync_offline_title),
                    icon = AppIcons.NoSignal,
                    color = AppColors.Accent
                )
                Text(stringResource(R.string.sync_offline_body), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            text = when {
                attempts.isEmpty() && fieldRuns.isEmpty() -> stringResource(R.string.sync_none)
                pending == 0 -> stringResource(R.string.sync_all_sent)
                isSyncing -> stringResource(R.string.sync_uploading_count, pending)
                else -> stringResource(R.string.sync_waiting_count, pending)
            },
            style = MaterialTheme.typography.titleMedium
        )

        if (pending > 0 && !isSyncing && online) {
            LineButton(
                text = stringResource(R.string.sync_try_now),
                onClick = {
                    SyncScheduler.sendNow(context)
                    FieldSyncWorker.syncSoon(context)
                    fieldRefreshes++
                },
                icon = AppIcons.Upload
            )
        }

        fieldRuns.forEach { run -> FieldRunRow(run, onResultSelected) }

        if (attempts.isEmpty()) {
            if (fieldRuns.isEmpty()) {
                Text(
                    text = stringResource(R.string.sync_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary
                )
            }
            return@Page
        }

        LazyColumn {
            items(attempts, key = { it.id }) { attempt ->
                AttemptRow(
                    attempt = attempt,
                    onViewResult = onResultSelected,
                    onRetry = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                repository.retry(attempt.id)
                            }
                            SyncScheduler.sendNow(context)
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
    val delivery = Delivery.of(attempt)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // The phone's own count: pencil, until SAI has checked it.
        RegisterRow(
            label = stringResource(Labels.testName(attempt.testType)),
            value = "${formatScore(attempt)} ${stringResource(Labels.unit(attempt.scoreUnit))}",
            valueColor = AppColors.TextSecondary,
            detail = formatTimestamp(attempt.recordedAtMs),
            divider = false
        )

        StatusLine(
            text = stringResource(
                when (delivery) {
                    Delivery.SENDING -> Labels.syncStatus(attempt.syncStatus)
                    Delivery.WAITING_TO_SUBMIT -> R.string.sync_waiting_to_submit
                    Delivery.SUBMITTED -> R.string.sync_submitted
                    Delivery.NOT_ACCEPTED -> R.string.sync_not_accepted
                    Delivery.FAILED -> R.string.sync_failed
                }
            ),
            icon = when (delivery) {
                Delivery.SENDING -> if (attempt.syncStatus == SyncStatus.UPLOADING) AppIcons.Upload else AppIcons.Clock
                Delivery.WAITING_TO_SUBMIT -> AppIcons.Clock
                Delivery.SUBMITTED -> AppIcons.Check
                Delivery.NOT_ACCEPTED, Delivery.FAILED -> AppIcons.Cross
            },
            color = when (delivery) {
                Delivery.FAILED, Delivery.NOT_ACCEPTED -> AppColors.Danger
                else -> AppColors.TextPrimary
            }
        )

        if (attempt.syncStatus == SyncStatus.UPLOADING) {
            LinearProgressIndicator(
                progress = { attempt.progress() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = AppColors.TextPrimary,
                trackColor = AppColors.SurfaceHigh,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            // In megabytes, because that is what the athlete's data pack is sold in.
            Text(
                text = stringResource(
                    R.string.sync_progress_mb,
                    megabytes(attempt.uploadedBytes),
                    megabytes(attempt.compressedSizeBytes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }

        // SAI's own words for why, since the athlete may need to act on
        // them (e.g. ask an official about a closed session).
        if (delivery == Delivery.NOT_ACCEPTED) {
            Text(
                text = stringResource(R.string.sync_not_accepted_hint),
                style = MaterialTheme.typography.bodySmall
            )
            attempt.lastError?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = AppColors.Danger)
            }
        }

        // Once submitted, the athlete can follow the result through SAI's
        // checks. Before that there is nothing on the server to show.
        attempt.resultId?.let { resultId ->
            PlainButton(
                text = stringResource(R.string.sync_see_result),
                onClick = { onViewResult(resultId) },
                icon = AppIcons.Forward
            )
        }

        // Shown only on FAILED. Surfacing the last transient error while an
        // attempt is still retrying happily would make normal operation
        // look broken.
        if (attempt.syncStatus == SyncStatus.FAILED) {

            // The stored error is an English diagnostic for developers;
            // the athlete gets a plain instruction in their language.
            val hint = FailureHint.of(attempt.lastError)
            Text(
                text = stringResource(
                    when (hint) {
                        FailureHint.RECORDING_MISSING -> R.string.sync_failed_missing
                        FailureHint.SIGN_IN -> R.string.sync_failed_sign_in
                        FailureHint.RETRY -> R.string.sync_failed_hint
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Danger
            )

            if (hint.retryable) {
                LineButton(text = stringResource(R.string.action_retry), onClick = onRetry)
            }
        }

        RuleLine(Modifier.padding(top = 6.dp))
    }
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))

private fun formatScore(attempt: TestAttemptEntity): String =
    if (attempt.scoreUnit == "reps") attempt.provisionalScore.toInt().toString()
    else String.format(Locale.US, "%.1f", attempt.provisionalScore)

private fun formatTimestamp(timestampMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestampMs))

/** An official shuttle or endurance run: its trace is sent in one small request. */
@Composable
private fun FieldRunRow(run: FieldAttempt, onViewResult: (String) -> Unit) {
    val resultId = run.resultId
    RegisterRow(
        label = stringResource(Labels.testName(run.testType.name)),
        value = "${ResultPresentation.formatNumber(run.result.score)} ${stringResource(R.string.unit_seconds)}",
        valueColor = AppColors.TextSecondary,
        detail = stringResource(
            when {
                resultId != null -> R.string.sync_submitted
                run.notAccepted -> R.string.sync_not_accepted
                else -> R.string.field_waiting_to_send
            }
        ),
        detailColor = if (run.notAccepted) AppColors.Danger else AppColors.TextSecondary,
        onClick = resultId?.let { { onViewResult(it) } },
        onClickLabel = resultId?.let { stringResource(R.string.sync_see_result) }
    )
}

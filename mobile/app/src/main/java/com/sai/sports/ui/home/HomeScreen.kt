package com.sai.sports.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeRecord
import com.sai.sports.data.PracticeStats
import com.sai.sports.data.ProfileStatusStore
import com.sai.sports.data.SyncRepository
import com.sai.sports.sync.NetworkMonitor
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.Bib
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.LaneLines
import com.sai.sports.ui.kit.OfflineBar
import com.sai.sports.ui.kit.RegisterRow
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.TrialsIcons
import com.sai.sports.ui.practice.SyncPracticeOnOpen
import com.sai.sports.ui.theme.TrialsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The athlete's home: their chest number, the official trials open to them,
 * their practice, and how to reach everything else.
 *
 * The two worlds look different on purpose. Official tests sit on the black
 * floodlit band with the marigold Start; practice is rough work on a dashed
 * pencil sheet. An athlete who only wants to train must never submit an
 * official attempt by accident.
 */
@Composable
fun HomeScreen(
    onSessionTest: (sessionId: String, TestType) -> Unit,
    onPractice: () -> Unit,
    onPracticeTest: (TestType) -> Unit,
    onPracticeHistory: () -> Unit,
    onSyncStatus: () -> Unit,
    onProfile: () -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit,
    onCompleteProfile: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SyncRepository(context) }
    val statusStore = remember { ProfileStatusStore(context) }
    val attempts = remember { AttemptStore(context) }

    // This athlete's waiting tests, not everyone who has used the phone.
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val pendingCount by remember(athleteId) {
        athleteId?.let(repository::observePendingCount) ?: flowOf(0)
    }.collectAsStateWithLifecycle(initialValue = 0)
    val online by remember { NetworkMonitor.observeOnline(context) }
        .collectAsStateWithLifecycle(initialValue = true)

    // What this phone last knew about the profile, shown at once, then
    // refreshed from the server: who the chest number belongs to, and what
    // still stands between the athlete and an official test.
    var card by remember(athleteId) { mutableStateOf(athleteId?.let(statusStore::card)) }
    var missing by remember(athleteId) { mutableStateOf(athleteId?.let(statusStore::missing).orEmpty()) }

    LaunchedEffect(athleteId) {
        if (athleteId == null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { AppServices.api(context).profile() }
        if (result is ApiResult.Success) {
            statusStore.save(result.value)
            missing = result.value.missing
            card = statusStore.card(athleteId)
        }
    }

    // Recomputed on every visit, so a best set a moment ago is already here.
    var practiceRefreshes by remember { mutableIntStateOf(0) }
    val practice by produceState<List<PracticeRecord>?>(initialValue = null, practiceRefreshes) {
        value = withContext(Dispatchers.IO) {
            PracticeStats.records(athleteId?.let(attempts::listAttempts).orEmpty())
        }
    }
    SyncPracticeOnOpen(athleteId, attempts) { practiceRefreshes++ }

    com.sai.sports.ui.kit.Page(
        header = { Masthead() },
        footer = {
            /*
             * The pending count is on the home screen, not buried in a menu.
             * An athlete in a low-connectivity area needs to know at a glance
             * that a test has not reached SAI yet — otherwise they assume it
             * has, and find out otherwise far too late.
             */
            if (pendingCount > 0 && !online) {
                OfflineBar(stringResource(R.string.home_sync_offline))
            }
        },
        spacing = 20.dp
    ) {
        Bib(
            number = athleteId?.let(BibNumber::of) ?: "—",
            name = card?.name,
            detail = card?.region
        )

        // Official tests need a complete profile; say so before the session does.
        ProfileCompletionBanner(missing = missing, onCompleteProfile = onCompleteProfile)

        // Official tests only exist inside a session SAI has opened.
        SessionsSection(athleteId = athleteId, onSessionTest = onSessionTest)

        PracticeSheet(
            records = practice,
            onPractice = onPractice,
            onPracticeTest = onPracticeTest,
            onPracticeHistory = onPracticeHistory
        )

        Column {
            RegisterRow(
                label = stringResource(R.string.home_profile),
                onClick = onProfile
            )
            RegisterRow(
                label = if (pendingCount > 0) {
                    stringResource(R.string.home_sync_waiting, pendingCount)
                } else {
                    stringResource(R.string.home_sync)
                },
                onClick = onSyncStatus,
                trailing = if (pendingCount > 0) {
                    { Icon(TrialsIcons.Clock, contentDescription = null, modifier = Modifier.size(20.dp)) }
                } else {
                    null
                }
            )
            RegisterRow(
                label = stringResource(R.string.nav_leaderboard),
                onClick = onLeaderboard
            )
            RegisterRow(
                label = stringResource(R.string.nav_settings),
                onClick = onSettings
            )
        }

        Text(
            stringResource(R.string.home_provisional_note),
            style = MaterialTheme.typography.bodySmall,
            color = TrialsColors.Pencil
        )
    }
}

/** The app's name over the lane lines, where other screens put their title. */
@Composable
private fun Masthead() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.app_name).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .semantics { heading() }
        )
        LaneLines()
    }
}

/**
 * Practice, as rough work: every test with the athlete's best at it, one tap
 * from starting. Showing the best turns practice into something to beat.
 */
@Composable
private fun PracticeSheet(
    records: List<PracticeRecord>?,
    onPractice: () -> Unit,
    onPracticeTest: (TestType) -> Unit,
    onPracticeHistory: () -> Unit
) {
    Sheet(
        style = SheetStyle.Rough,
        padding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        spacing = 0.dp
    ) {
        RegisterRow(
            label = stringResource(R.string.practice_title),
            labelStyle = MaterialTheme.typography.headlineSmall,
            detail = stringResource(R.string.practice_never_counts),
            onClick = onPractice,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TrialsIcons.Lock, contentDescription = null, tint = TrialsColors.Pencil, modifier = Modifier.size(18.dp))
                }
            }
        )

        if (records == null) {
            Text(
                stringResource(R.string.loading),
                style = MaterialTheme.typography.bodyMedium,
                color = TrialsColors.Pencil,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                records.forEachIndexed { index, record ->
                    val best = record.best
                    RegisterRow(
                        label = stringResource(Labels.testName(record.testType)),
                        index = index + 1,
                        value = best?.let {
                            "${it.result.formattedScore()} ${stringResource(Labels.unit(it.result.unit))}"
                        },
                        valueColor = TrialsColors.Pencil,
                        detail = when {
                            best != null -> null
                            record.attempts == 0 -> stringResource(R.string.practice_not_tried)
                            else -> stringResource(R.string.history_not_scored)
                        },
                        onClick = { onPracticeTest(record.testType) }
                    )
                }
            }
        }

        RegisterRow(
            label = stringResource(R.string.practice_history),
            onClick = onPracticeHistory,
            divider = false
        )
    }
}

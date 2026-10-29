package com.sai.sports.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.api.ApiResult
import com.sai.sports.api.HistoryItem
import com.sai.sports.auth.AppServices
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptStore
import com.sai.sports.fieldtest.FieldAttempt
import com.sai.sports.fieldtest.FieldAttemptStore
import com.sai.sports.fieldtest.FieldOutcome
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.SegmentedControl
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.practice.SyncPracticeOnOpen
import com.sai.sports.ui.profile.ResultPresentation
import com.sai.sports.ui.theme.AppColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every result the athlete has: official tests as SAI holds them, and their
 * private practice, newest first.
 *
 * Each row says when, which test, the score, where it stands and whether it
 * was official or practice — the official tag in ink, practice in pencil, so
 * the two can never be mistaken for each other. Official results come from
 * the account a page at a time; practice comes from this phone and the
 * athlete's account, never from another athlete who used the same phone.
 */
@Composable
fun HistoryScreen(
    onBack: (() -> Unit)? = null,
    onOfficialSelected: (String) -> Unit,
    onPracticeSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val store = remember { AttemptStore(context) }
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val scope = rememberCoroutineScope()

    var official by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var nextBefore by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var practiceRefreshes by remember { mutableIntStateOf(0) }
    var filterName by rememberSaveable { mutableStateOf(HistoryFilter.ALL.name) }
    val filter = HistoryFilter.valueOf(filterName)

    val practice by produceState<List<Attempt>>(initialValue = emptyList(), practiceRefreshes) {
        value = withContext(Dispatchers.IO) { athleteId?.let(store::listAttempts).orEmpty() }
    }
    SyncPracticeOnOpen(athleteId, store) { practiceRefreshes++ }
    val fieldPractice by produceState<List<FieldAttempt>>(initialValue = emptyList(), practiceRefreshes) {
        value = withContext(Dispatchers.IO) { athleteId?.let { FieldAttemptStore(context).list(it) }.orEmpty() }
    }

    LaunchedEffect(reload) {
        error = null
        when (val page = withContext(Dispatchers.IO) { api.history() }) {
            is ApiResult.Success -> {
                official = page.value.items
                nextBefore = page.value.nextBefore
            }
            is ApiResult.Failure -> error = Labels.failure(page.kind, R.string.error_load)
        }
    }

    fun loadOlder() {
        val cursor = nextBefore ?: return
        loadingMore = true
        scope.launch {
            when (val page = withContext(Dispatchers.IO) { api.history(before = cursor) }) {
                is ApiResult.Success -> {
                    official = official.orEmpty() + page.value.items
                    nextBefore = page.value.nextBefore
                }
                is ApiResult.Failure -> error = Labels.failure(page.kind, R.string.error_load)
            }
            loadingMore = false
        }
    }

    Page(scrollable = false, header = { TitleBar(stringResource(R.string.nav_progress), onBack) }) {

        SegmentedControl(
            options = HistoryFilter.entries,
            selected = filter,
            label = { option ->
                stringResource(
                    when (option) {
                        HistoryFilter.ALL -> R.string.history_all
                        HistoryFilter.OFFICIAL -> R.string.history_filter_official
                        HistoryFilter.PRACTICE -> R.string.history_filter_practice
                    }
                )
            },
            onSelect = { filterName = it.name }
        )

        val loaded = official
        val failure = error
        when {
            // Practice is on the phone, so it is shown even when the account
            // cannot be reached; only official results need the network.
            loaded == null && failure == null && filter != HistoryFilter.PRACTICE ->
                CircularProgressIndicator(color = AppColors.Accent)

            loaded == null && failure != null && filter != HistoryFilter.PRACTICE ->
                LoadFailed(stringResource(failure)) { reload += 1 }

            else -> {
                val entries = HistoryTimeline.merge(
                    official = loaded.orEmpty(),
                    moreOfficialToLoad = nextBefore != null,
                    attempts = practice,
                    filter = filter,
                    fieldAttempts = fieldPractice
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.history_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                    items(entries, key = { it.key }) { entry ->
                        when (entry) {
                            is HistoryEntry.Official -> OfficialRow(entry.item) { onOfficialSelected(entry.item.resultId) }
                            is HistoryEntry.Practice -> PracticeRow(entry.attempt) { onPracticeSelected(entry.attempt.id) }
                            is HistoryEntry.FieldPractice -> FieldPracticeRow(entry.attempt)
                        }
                    }
                    if (nextBefore != null && filter != HistoryFilter.PRACTICE) {
                        item {
                            LineButton(
                                text = stringResource(R.string.history_load_more),
                                onClick = ::loadOlder,
                                enabled = !loadingMore,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfficialRow(item: HistoryItem, onClick: () -> Unit) {
    val (score, _) = ResultPresentation.headline(item.provisionalScore, item.serverScore, item.finalScore)
    val writing = ResultPresentation.writing(item.status, item.provisionalScore, item.serverScore, item.finalScore)
    HistoryCard(
        official = true,
        title = stringResource(Labels.testName(item.testType)),
        value = ResultPresentation.formatNumber(score) + " " + stringResource(Labels.unit(item.unit)),
        valueColor = when (writing) {
            ResultPresentation.Writing.PENCIL -> AppColors.TextSecondary
            ResultPresentation.Writing.STRUCK -> AppColors.Danger
            else -> AppColors.TextPrimary
        },
        detail = listOf(
            formatDate(item.createdAtMs),
            item.sessionName ?: stringResource(R.string.history_official_test)
        ).joinToString(" · "),
        status = stringResource(Labels.resultStatus(item.status)),
        statusColor = when (writing) {
            ResultPresentation.Writing.STAMPED -> AppColors.Accent
            ResultPresentation.Writing.STRUCK -> AppColors.Danger
            else -> AppColors.TextSecondary
        },
        onClick = onClick
    )
}

@Composable
private fun PracticeRow(attempt: Attempt, onClick: () -> Unit) {
    val scored = attempt.result.status == AttemptStatus.COMPLETE
    HistoryCard(
        official = false,
        title = stringResource(Labels.testName(attempt.testType)),
        value = if (scored) {
            attempt.result.formattedScore() + " " + stringResource(Labels.unit(attempt.result.unit))
        } else {
            stringResource(R.string.history_not_scored)
        },
        // Practice is provisional grey; an attempt that was not scored is red.
        valueColor = if (scored) AppColors.TextSecondary else AppColors.Danger,
        detail = formatDate(attempt.recordedAtMs),
        status = stringResource(R.string.practice_never_counts),
        statusColor = AppColors.TextSecondary,
        onClick = onClick
    )
}

@Composable
private fun FieldPracticeRow(attempt: FieldAttempt) {
    val scored = attempt.result.outcome == FieldOutcome.COMPLETE
    HistoryCard(
        official = false,
        title = stringResource(Labels.testName(attempt.testType.name)),
        value = if (scored) {
            ResultPresentation.formatNumber(attempt.result.score) + " " + stringResource(R.string.unit_seconds)
        } else {
            stringResource(R.string.history_not_scored)
        },
        valueColor = if (scored) AppColors.TextSecondary else AppColors.Danger,
        detail = formatDate(attempt.recordedAtMs),
        status = if (scored) {
            stringResource(R.string.practice_never_counts)
        } else {
            stringResource(Labels.invalidReason(attempt.result.invalid))
        },
        statusColor = if (scored) AppColors.TextSecondary else AppColors.Danger,
        onClick = null
    )
}

/**
 * One attempt as a card: an icon for which world it is in, the test and
 * when, the status underneath, and the score on the right.
 */
@Composable
private fun HistoryCard(
    official: Boolean,
    title: String,
    value: String,
    valueColor: Color,
    detail: String,
    status: String,
    statusColor: Color,
    onClick: (() -> Unit)?
) {
    val openLabel = stringResource(R.string.profile_open_result)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Surface)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(if (official) AppColors.AccentSoft else AppColors.SurfaceHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (official) AppIcons.Shield else AppIcons.Dumbbell,
                contentDescription = null,
                tint = if (official) AppColors.Accent else AppColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

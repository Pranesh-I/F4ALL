package com.sai.sports.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptMode
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeRecord
import com.sai.sports.data.PracticeStats
import com.sai.sports.fieldtest.FieldAttempt
import com.sai.sports.fieldtest.FieldAttemptStore
import com.sai.sports.fieldtest.FieldOutcome
import com.sai.sports.fieldtest.FieldSync
import com.sai.sports.fieldtest.FieldTestType
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.TestArt
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RoundIconButton
import com.sai.sports.ui.kit.SectionHeader
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.profile.ResultPresentation
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The practice home: every test, with the athlete's best at each.
 *
 * Showing the best next to the test turns practice into something to beat,
 * which is the point of letting athletes practise at all.
 */
@Composable
fun PracticeScreen(
    onBack: (() -> Unit)? = null,
    onTestSelected: (TestType) -> Unit,
    onHistory: () -> Unit,
    onFieldTestSelected: (FieldTestType) -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { AttemptStore(context) }
    val athleteId = remember { AppServices.session(context).current()?.athleteId }

    // Bumped when the account brings in practice from another phone.
    var refreshes by remember { mutableIntStateOf(0) }

    // Recomputed on every visit, so a best set a moment ago is already here.
    // Only this athlete's attempts: a shared phone holds other people's too.
    val records by produceState<List<PracticeRecord>?>(initialValue = null, refreshes) {
        value = withContext(Dispatchers.IO) {
            PracticeStats.records(athleteId?.let(store::listAttempts).orEmpty())
        }
    }

    SyncPracticeOnOpen(athleteId, store) { refreshes++ }

    // Field tests keep their own practice; brought up to date with the account too.
    val fieldStore = remember { FieldAttemptStore(context) }
    val fieldPractice by produceState<List<FieldAttempt>>(initialValue = emptyList(), refreshes) {
        value = withContext(Dispatchers.IO) {
            athleteId?.let(fieldStore::list).orEmpty().filter { it.mode == AttemptMode.PRACTICE }
        }
    }
    LaunchedEffect(athleteId) {
        val id = athleteId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { FieldSync(fieldStore, AppServices.api(context)).sync(id) } }
        refreshes++
    }

    Page(
        header = {
            TitleBar(stringResource(R.string.tests_title), onBack) {
                RoundIconButton(
                    icon = AppIcons.List,
                    contentDescription = stringResource(R.string.practice_history),
                    onClick = onHistory
                )
            }
        },
        spacing = 16.dp
    ) {
        Text(
            text = stringResource(R.string.practice_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary
        )

        SectionHeader(stringResource(R.string.tests_camera_title))
        val loaded = records
        if (loaded == null) {
            Text(stringResource(R.string.loading), color = AppColors.TextSecondary)
        } else {
            loaded.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { record ->
                        val best = record.best
                        TestCard(
                            code = record.testType.name,
                            name = stringResource(Labels.testName(record.testType)),
                            detail = when {
                                best != null -> stringResource(
                                    R.string.practice_best,
                                    best.result.formattedScore() + " " + stringResource(Labels.unit(best.result.unit))
                                )
                                record.attempts == 0 -> stringResource(R.string.practice_not_tried)
                                else -> stringResource(R.string.history_not_scored)
                            },
                            highlight = best != null,
                            onClick = { onTestSelected(record.testType) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionHeader(stringResource(R.string.practice_field_title))
        Text(
            stringResource(R.string.practice_field_note),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            FieldTestType.entries.forEach { type ->
                val tries = fieldPractice.filter { it.testType == type }
                // Timed: the lowest time is the best.
                val best = tries.filter { it.result.outcome == FieldOutcome.COMPLETE }.minByOrNull { it.result.score }
                TestCard(
                    code = type.name,
                    name = stringResource(Labels.testName(type.name)),
                    detail = when {
                        best != null -> stringResource(
                            R.string.practice_best,
                            ResultPresentation.formatNumber(best.result.score) + " " + stringResource(R.string.unit_seconds)
                        )
                        tries.isEmpty() -> stringResource(R.string.practice_not_tried)
                        else -> stringResource(R.string.history_not_scored)
                    },
                    highlight = best != null,
                    onClick = { onFieldTestSelected(type) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        LineButton(
            text = stringResource(R.string.practice_history),
            onClick = onHistory,
            icon = AppIcons.List,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** One test in the grid: its picture, its name, and the best so far. */
@Composable
private fun TestCard(
    code: String,
    name: String,
    detail: String,
    highlight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            TestArt(code = code, size = maxWidth)
        }
        Text(name, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (highlight) AppIcons.Trophy else AppIcons.Flame,
                contentDescription = null,
                tint = if (highlight) AppColors.Accent else AppColors.TextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 2)
        }
    }
}

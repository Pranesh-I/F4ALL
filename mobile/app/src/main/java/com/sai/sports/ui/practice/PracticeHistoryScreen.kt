package com.sai.sports.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.coach.FormSummary
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeStats
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RegisterRow
import com.sai.sports.ui.kit.SheetShape
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.kit.ChoiceChip
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Every practice attempt, newest first, filterable by test.
 *
 * Each row answers what an athlete scrolling back wants to know: when, what,
 * how many, and whether it was done well. Tapping one opens the full result,
 * skeleton replay and form feedback included.
 */
@Composable
fun PracticeHistoryScreen(
    onBack: () -> Unit,
    onAttemptSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val store = remember { AttemptStore(context) }
    val athleteId = remember { AppServices.session(context).current()?.athleteId }

    var refreshes by remember { mutableIntStateOf(0) }

    // Only the signed-in athlete's attempts — never a friend's on a shared phone.
    val all by produceState<List<Attempt>?>(initialValue = null, refreshes) {
        value = withContext(Dispatchers.IO) { athleteId?.let(store::listAttempts).orEmpty() }
    }

    SyncPracticeOnOpen(athleteId, store) { refreshes++ }

    // The test's enum name, saved so rotation or a trip into a result keeps the filter.
    var filterName by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = filterName?.let { name -> TestType.entries.firstOrNull { it.name == name } }

    Page(scrollable = false, header = { TitleBar(stringResource(R.string.practice_history), onBack) }) {

        val attempts = all
        if (attempts == null) {
            Text(stringResource(R.string.loading), color = AppColors.TextSecondary)
            return@Page
        }

        val practice = PracticeStats.practiceAttempts(attempts)
        if (practice.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
            return@Page
        }

        // Only tests that have been practised; a chip that filters to nothing is noise.
        val practisedTests = TestType.entries.filter { type -> practice.any { it.testType == type } }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChoiceChip(
                selected = filter == null,
                onClick = { filterName = null },
                label = stringResource(R.string.history_all)
            )
            practisedTests.forEach { type ->
                ChoiceChip(
                    selected = filter == type,
                    onClick = { filterName = type.name },
                    label = stringResource(Labels.testName(type))
                )
            }
        }

        val bestIds = practisedTests.mapNotNull { PracticeStats.personalBest(attempts, it)?.id }.toSet()
        val shown = if (filter == null) practice else practice.filter { it.testType == filter }

        LazyColumn {
            items(shown, key = { it.id }) { attempt ->
                HistoryRow(
                    attempt = attempt,
                    isPersonalBest = attempt.id in bestIds,
                    onClick = { onAttemptSelected(attempt.id) }
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(attempt: Attempt, isPersonalBest: Boolean, onClick: () -> Unit) {

    val formScore = remember(attempt.id) { FormSummary.from(attempt.result.events)?.scorePercent }
    val recordedAt = remember(attempt.recordedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(attempt.recordedAtMs))
    }
    val scored = attempt.result.status == AttemptStatus.COMPLETE
    val form = formScore?.let { stringResource(R.string.history_form, it) }

    RegisterRow(
        label = stringResource(Labels.testName(attempt.testType)),
        value = if (scored) {
            "${attempt.result.formattedScore()} ${stringResource(Labels.unit(attempt.result.unit))}"
        } else {
            stringResource(R.string.history_not_scored)
        },
        // Practice is pencil; an attempt that was not scored is a red-pen note.
        valueColor = if (scored) AppColors.TextSecondary else AppColors.Danger,
        detail = listOfNotNull(recordedAt, form).joinToString(" · "),
        onClick = onClick,
        trailing = if (isPersonalBest) {
            {
                // The marigold garland: this is the one to beat.
                Text(
                    text = stringResource(R.string.history_personal_best),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextPrimary,
                    modifier = Modifier
                        .background(AppColors.Accent, SheetShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        } else {
            null
        }
    )
}

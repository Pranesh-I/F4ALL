package com.sai.sports.ui.practice

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeRecord
import com.sai.sports.data.PracticeStats
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.ModeTag
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RegisterRow
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.TrialsColors
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
    onBack: () -> Unit,
    onTestSelected: (TestType) -> Unit,
    onHistory: () -> Unit
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

    Page(header = { TitleBar(stringResource(R.string.practice_title), onBack) }) {

        ModeTag(official = false)

        Text(
            text = stringResource(R.string.practice_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = TrialsColors.Pencil
        )

        Sheet(
            style = SheetStyle.Rough,
            padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            spacing = 0.dp
        ) {
            val loaded = records
            if (loaded == null) {
                Text(
                    stringResource(R.string.loading),
                    color = TrialsColors.Pencil,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            } else {
                loaded.forEachIndexed { index, record ->
                    PracticeRow(
                        number = index + 1,
                        record = record,
                        last = index == loaded.lastIndex,
                        onClick = { onTestSelected(record.testType) }
                    )
                }
            }
        }

        LineButton(
            text = stringResource(R.string.practice_history),
            onClick = onHistory,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** One test on the rough sheet: its best, in pencil, and how often it has been tried. */
@Composable
private fun PracticeRow(number: Int, record: PracticeRecord, last: Boolean, onClick: () -> Unit) {
    val best = record.best
    RegisterRow(
        label = stringResource(Labels.testName(record.testType)),
        index = number,
        value = best?.let {
            stringResource(
                R.string.practice_best,
                "${it.result.formattedScore()} ${stringResource(Labels.unit(it.result.unit))}"
            )
        },
        valueColor = TrialsColors.Pencil,
        valueStyle = MaterialTheme.typography.titleSmall,
        detail = when {
            record.attempts == 0 -> stringResource(R.string.practice_not_tried)
            best == null -> stringResource(R.string.history_not_scored)
            else -> stringResource(R.string.practice_attempts, record.attempts)
        },
        onClick = onClick,
        divider = !last
    )
}

package com.sai.sports.ui.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeRecord
import com.sai.sports.data.PracticeStats
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.TitleBar
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(stringResource(R.string.practice_title), onBack)

        Text(
            text = stringResource(R.string.practice_intro),
            style = MaterialTheme.typography.bodyMedium
        )

        val loaded = records
        if (loaded == null) {
            Text(stringResource(R.string.loading))
        } else {
            loaded.forEach { record ->
                PracticeTestCard(record, onClick = { onTestSelected(record.testType) })
            }
        }

        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.practice_history))
        }
    }
}

@Composable
private fun PracticeTestCard(record: PracticeRecord, onClick: () -> Unit) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(Labels.testName(record.testType)),
                style = MaterialTheme.typography.titleMedium
            )

            val best = record.best
            Text(
                text = when {
                    best != null -> stringResource(
                        R.string.practice_best,
                        "${best.result.formattedScore()} ${stringResource(Labels.unit(best.result.unit))}"
                    )
                    record.attempts == 0 -> stringResource(R.string.practice_not_tried)
                    else -> stringResource(R.string.history_not_scored)
                },
                style = MaterialTheme.typography.bodyMedium
            )

            if (record.attempts > 0) {
                Text(
                    text = stringResource(R.string.practice_attempts, record.attempts),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

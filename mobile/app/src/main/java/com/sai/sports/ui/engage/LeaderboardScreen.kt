package com.sai.sports.ui.engage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Leaderboard
import com.sai.sports.auth.AppServices
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.common.TitleBar
import com.sai.sports.ui.profile.ResultPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the athlete stands among others their age, in their state or across India.
 *
 * Only athletes who chose to be listed appear, by first name and last initial.
 * The athlete always sees their own position; whether others see it is their
 * choice in Settings, and this screen says which it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeaderboardScreen(
    onBack: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }

    var testType by rememberSaveable { mutableStateOf(TestType.SIT_UPS) }
    var national by rememberSaveable { mutableStateOf(false) }
    var board by remember { mutableStateOf<Leaderboard?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(testType, national, reload) {
        board = null
        error = null
        val scope = if (national) "national" else "region"
        when (val result = withContext(Dispatchers.IO) { api.leaderboard(testType.name, scope) }) {
            is ApiResult.Success -> board = result.value
            is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.error_load)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TitleBar(stringResource(R.string.leaderboard_title), onBack)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestType.entries.forEach { type ->
                FilterChip(
                    selected = testType == type,
                    onClick = { testType = type },
                    label = { Text(stringResource(Labels.testName(type))) }
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !national,
                onClick = { national = false },
                label = { Text(stringResource(R.string.leaderboard_my_state)) }
            )
            FilterChip(
                selected = national,
                onClick = { national = true },
                label = { Text(stringResource(R.string.leaderboard_india)) }
            )
        }

        val current = board
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator()
            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }
            else -> BoardContent(current, onSettings)
        }
    }
}

@Composable
private fun BoardContent(board: Leaderboard, onSettings: () -> Unit) {
    val unit = stringResource(Labels.unit(board.unit))
    val (gender, ages) = Labels.parseCohort(board.cohort)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                stringResource(
                    R.string.leaderboard_cohort,
                    stringResource(Labels.gender(gender)),
                    ages,
                    board.region ?: stringResource(R.string.leaderboard_india)
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }

        item {
            val you = board.you
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (you == null) {
                        Text(stringResource(R.string.leaderboard_you_none))
                    } else {
                        Text(
                            stringResource(
                                R.string.leaderboard_you_rank,
                                you.rank,
                                "${ResultPresentation.formatNumber(you.score)} $unit"
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(
                                if (you.visibleToOthers) R.string.leaderboard_you_visible
                                else R.string.leaderboard_you_hidden
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!you.visibleToOthers) {
                            TextButton(onClick = onSettings) {
                                Text(stringResource(R.string.leaderboard_change_visibility))
                            }
                        }
                    }
                }
            }
        }

        if (board.entries.isEmpty()) {
            item {
                Text(stringResource(R.string.leaderboard_empty), style = MaterialTheme.typography.bodySmall)
            }
        }

        items(board.entries, key = { "${it.rank}-${it.displayName}" }) { entry ->
            val score = "${ResultPresentation.formatNumber(entry.score)} $unit"
            val spoken = stringResource(
                R.string.leaderboard_entry_spoken, entry.rank, entry.displayName, entry.region, score
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = spoken },
                colors = if (entry.isYou) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("${entry.rank}", fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(entry.displayName, fontWeight = FontWeight.SemiBold)
                        Text(entry.region, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(score)
                }
            }
        }

        item {
            Text(stringResource(R.string.leaderboard_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

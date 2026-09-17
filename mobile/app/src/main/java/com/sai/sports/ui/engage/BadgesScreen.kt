package com.sai.sports.ui.engage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Badge
import com.sai.sports.api.BadgeSummary
import com.sai.sports.auth.AppServices
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.common.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Progress badges.
 *
 * Unearned badges are shown too, with progress. An empty screen tells a new
 * athlete nothing; "1 of 2 tests" tells them what to do next.
 */
@Composable
fun BadgesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }

    var summary by remember { mutableStateOf<BadgeSummary?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        when (val result = withContext(Dispatchers.IO) { api.badges() }) {
            is ApiResult.Success -> summary = result.value
            is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.error_load)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(stringResource(R.string.badges_title), onBack)

        val current = summary
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator()
            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.badges_streak_current, current.currentStreakWeeks),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.badges_streak_longest, current.longestStreakWeeks),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                items(current.badges, key = { it.code }) { badge -> BadgeRow(badge) }
                item {
                    Text(stringResource(R.string.badges_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BadgeRow(badge: Badge) {
    val title = stringResource(Labels.badgeTitle(badge.code))
    val description = if (badge.target > 1) {
        stringResource(Labels.badgeDescription(badge.code), badge.target)
    } else {
        stringResource(Labels.badgeDescription(badge.code))
    }
    val state = if (badge.earned) {
        stringResource(R.string.badges_earned)
    } else {
        stringResource(R.string.badges_progress, badge.progress, badge.target)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // One announcement per badge — title, what it is for, and whether
            // it is earned — instead of three separate stops.
            .clearAndSetSemantics { contentDescription = "$title. $description. $state" }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (badge.earned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badge.earned) "★" else "☆",
                    color = if (badge.earned) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall)
                if (!badge.earned && badge.target > 1) {
                    LinearProgressIndicator(
                        progress = { badge.progress.toFloat() / badge.target },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(state, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

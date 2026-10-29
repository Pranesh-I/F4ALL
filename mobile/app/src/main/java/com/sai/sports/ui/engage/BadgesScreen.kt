package com.sai.sports.ui.engage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Badge
import com.sai.sports.api.BadgeSummary
import com.sai.sports.auth.AppServices
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.kit.BigNumber
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RuleLine
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.dashedBorder
import com.sai.sports.ui.theme.AppColors
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

    Page(scrollable = false, header = { TitleBar(stringResource(R.string.badges_title), onBack) }) {

        val current = summary
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator()
            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }
            else -> LazyColumn {
                item {
                    Sheet(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            BigNumber(current.currentStreakWeeks.toString())
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.badges_streak_current, current.currentStreakWeeks),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.badges_streak_longest, current.longestStreakWeeks),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSecondary
                                )
                            }
                        }
                    }
                }
                items(current.badges, key = { it.code }) { badge -> BadgeRow(badge) }
                item {
                    Text(
                        stringResource(R.string.badges_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * One badge. Earned ones wear the marigold garland; the rest are an empty
 * pencil ring with how far there is to go.
 */
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

    Column(
        // One announcement per badge — title, what it is for, and whether
        // it is earned — instead of three separate stops.
        Modifier.clearAndSetSemantics { contentDescription = "$title. $description. $state" }
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .then(
                        if (badge.earned) {
                            Modifier.background(AppColors.Accent, CircleShape)
                        } else {
                            Modifier.dashedBorder(1.5.dp, AppColors.TextSecondary, radius = 23.dp)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Star,
                    contentDescription = null,
                    tint = if (badge.earned) AppColors.TextPrimary else AppColors.TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                if (!badge.earned && badge.target > 1) {
                    LinearProgressIndicator(
                        progress = { badge.progress.toFloat() / badge.target },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = AppColors.TextPrimary,
                        trackColor = AppColors.SurfaceHigh,
                        strokeCap = StrokeCap.Butt,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                }
                Text(state, style = MaterialTheme.typography.labelMedium)
            }
        }
        RuleLine()
    }
}

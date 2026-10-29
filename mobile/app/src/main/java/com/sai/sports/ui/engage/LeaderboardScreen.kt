package com.sai.sports.ui.engage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Leaderboard
import com.sai.sports.api.LeaderboardEntry
import com.sai.sports.api.LeaderboardOptions
import com.sai.sports.api.LeaderboardQuery
import com.sai.sports.auth.AppServices
import com.sai.sports.data.Regions
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.Avatar
import com.sai.sports.ui.kit.BigNumber
import com.sai.sports.ui.kit.ChoiceChip
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.Tag
import com.sai.sports.ui.kit.TagTone
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.profile.ResultPresentation
import com.sai.sports.ui.theme.AppColors
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the athlete stands among others, filtered the way they choose.
 *
 * Test, place (their state, all of India, or any other state), age group (their
 * own by default) and assessment session. Only athletes who chose to be listed
 * appear, by first name and last initial, under every filter. The athlete
 * always sees their own position; whether others see it is their choice in
 * Settings, and this screen says which it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeaderboardScreen(
    onBack: (() -> Unit)? = null,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val scope = rememberCoroutineScope()

    var testType by rememberSaveable { mutableStateOf(TestType.SIT_UPS) }
    var place by rememberSaveable { mutableStateOf(LeaderboardQuery.PLACE_MY_STATE) }
    var ageBand by rememberSaveable { mutableStateOf(LeaderboardQuery.AGE_MINE) }
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val query = LeaderboardQuery(testType.name, place, ageBand, sessionId)

    var options by remember { mutableStateOf<LeaderboardOptions?>(null) }
    var board by remember { mutableStateOf<Leaderboard?>(null) }
    var entries by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        // Without the options the filters still work; only the session and
        // other-age choices are missing.
        (withContext(Dispatchers.IO) { api.leaderboardOptions() } as? ApiResult.Success)?.let {
            options = it.value
        }
    }

    LaunchedEffect(query, reload) {
        board = null
        entries = emptyList()
        error = null
        when (val result = withContext(Dispatchers.IO) { api.leaderboard(query) }) {
            is ApiResult.Success -> {
                board = result.value
                entries = result.value.entries
            }
            is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.error_load)
        }
    }

    fun loadMore() {
        loadingMore = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.leaderboard(query, offset = entries.size) }
            if (result is ApiResult.Success) {
                board = result.value
                entries = entries + result.value.entries
            }
            loadingMore = false
        }
    }

    Page(
        scrollable = false,
        spacing = 12.dp,
        header = { TitleBar(stringResource(R.string.leaderboard_title), onBack) }
    ) {
        val filters: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TestType.entries.forEach { type ->
                    ChoiceChip(
                        selected = testType == type,
                        onClick = { testType = type },
                        label = stringResource(Labels.testName(type))
                    )
                }
            }

            FilterRow(stringResource(R.string.leaderboard_filter_place)) {
                ChoiceChip(
                    selected = place == LeaderboardQuery.PLACE_MY_STATE,
                    onClick = { place = LeaderboardQuery.PLACE_MY_STATE },
                    label = stringResource(R.string.leaderboard_my_state)
                )
                ChoiceChip(
                    selected = place == LeaderboardQuery.PLACE_INDIA,
                    onClick = { place = LeaderboardQuery.PLACE_INDIA },
                    label = stringResource(R.string.leaderboard_india)
                )
                StatePicker(
                    chosen = place.takeIf { it in Regions.ALL },
                    exclude = options?.yourRegion,
                    onChosen = { place = it }
                )
            }

            FilterRow(stringResource(R.string.leaderboard_filter_age)) {
                ChoiceChip(
                    selected = ageBand == LeaderboardQuery.AGE_MINE,
                    onClick = { ageBand = LeaderboardQuery.AGE_MINE },
                    label = options?.yourAgeBand?.let { stringResource(R.string.leaderboard_my_age, it) }
                        ?: stringResource(R.string.leaderboard_my_age_plain)
                )
                ChoiceChip(
                    selected = ageBand == LeaderboardQuery.AGE_ALL,
                    onClick = { ageBand = LeaderboardQuery.AGE_ALL },
                    label = stringResource(R.string.leaderboard_all_ages)
                )
                options?.ageBands?.filter { it != options?.yourAgeBand }?.forEach { band ->
                    ChoiceChip(selected = ageBand == band, onClick = { ageBand = band }, label = band)
                }
            }

            options?.sessions?.takeIf { it.isNotEmpty() }?.let { sessions ->
                FilterRow(stringResource(R.string.leaderboard_filter_session)) {
                    ChoiceChip(
                        selected = sessionId == null,
                        onClick = { sessionId = null },
                        label = stringResource(R.string.leaderboard_all_results)
                    )
                    sessions.forEach { session ->
                        ChoiceChip(
                            selected = sessionId == session.id,
                            onClick = { sessionId = session.id },
                            label = session.name
                        )
                    }
                }
            }

            }
        }

        val current = board
        val failure = error

        when {
            current == null && failure == null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                filters()
                CircularProgressIndicator(color = AppColors.Accent)
            }
            current == null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                filters()
                LoadFailed(stringResource(failure!!)) { reload += 1 }
            }
            else -> BoardContent(current, entries, onSettings, loadingMore, ::loadMore, filters)
        }
    }
}

/** A labelled row of filter chips that wraps on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

/** "Other state": a chip that opens the list of states and shows the one chosen. */
@Composable
private fun StatePicker(chosen: String?, exclude: String?, onChosen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChoiceChip(
            selected = chosen != null,
            onClick = { open = true },
            label = chosen ?: stringResource(R.string.leaderboard_other_state)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Regions.ALL.filter { it != exclude }.forEach { state ->
                DropdownMenuItem(
                    text = { Text(state) },
                    onClick = {
                        open = false
                        onChosen(state)
                    }
                )
            }
        }
    }
}

@Composable
private fun BoardContent(
    board: Leaderboard,
    entries: List<LeaderboardEntry>,
    onSettings: () -> Unit,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    filters: @Composable () -> Unit
) {
    val unit = stringResource(Labels.unit(board.unit))
    val (gender, _) = Labels.parseCohort(board.cohort)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { filters() }

        item {
            val genderLabel = stringResource(Labels.gender(gender))
            val where = board.region ?: stringResource(R.string.leaderboard_india)
            val cohort = board.ageBand
                ?.let { stringResource(R.string.leaderboard_cohort, genderLabel, it, where) }
                ?: stringResource(R.string.leaderboard_cohort_all_ages, genderLabel, where)
            Text(
                board.sessionName?.let { stringResource(R.string.leaderboard_in_session, cohort, it) } ?: cohort,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        item {
            val you = board.you
            Sheet(style = SheetStyle.Field, modifier = Modifier.padding(bottom = 16.dp)) {
                if (you == null) {
                    Text(stringResource(R.string.leaderboard_you_none), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        BigNumber(String.format(Locale.US, "%02d", you.rank), color = AppColors.Accent)
                        Text(
                            stringResource(
                                R.string.leaderboard_you_rank,
                                you.rank,
                                "${ResultPresentation.formatNumber(you.score)} $unit"
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource(
                            if (you.visibleToOthers) R.string.leaderboard_you_visible
                            else R.string.leaderboard_you_hidden
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
                    )
                    if (!you.visibleToOthers) {
                        PlainButton(
                            text = stringResource(R.string.leaderboard_change_visibility),
                            onClick = onSettings,
                            color = AppColors.Accent
                        )
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.leaderboard_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
            }
        }

        val podium = entries.filter { it.rank in 1..3 }.sortedBy { it.rank }
        if (podium.size == 3) {
            item { Podium(podium, unit) }
        }
        val rest = if (podium.size == 3) entries.filter { it.rank > 3 } else entries

        itemsIndexed(rest, key = { index, entry -> "$index-${entry.displayName}" }) { _, entry ->
            val score = ResultPresentation.formatNumber(entry.score) + " " + unit
            val spoken = stringResource(
                R.string.leaderboard_entry_spoken, entry.rank, entry.displayName, entry.region, score
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.Surface, RoundedCornerShape(18.dp))
                    // The athlete's own line, edged in orange.
                    .then(
                        if (entry.isYou) {
                            Modifier.border(1.5.dp, AppColors.Accent, RoundedCornerShape(18.dp))
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .clearAndSetSemantics { contentDescription = spoken },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .border(1.dp, AppColors.SurfaceHighest, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(entry.rank.toString(), style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.displayName, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                    Text(entry.region, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
                Text(score, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                Avatar(entry.displayName, size = 38.dp, highlight = entry.isYou)
            }
        }

        if (board.hasMore) {
            item {
                LineButton(
                    text = stringResource(R.string.leaderboard_show_more),
                    onClick = onLoadMore,
                    enabled = !loadingMore,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        item {
            Text(
                stringResource(R.string.leaderboard_note),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

/**
 * The top three, on a podium: first in the middle and highest, with a crown;
 * second on the left, third on the right. Each column shows the place and
 * the score, so the order reads without colour.
 */
@Composable
private fun Podium(top: List<LeaderboardEntry>, unit: String) {
    val (first, second, third) = Triple(top[0], top[1], top[2])
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        PodiumPlace(second, unit, height = 108.dp, Modifier.weight(1f))
        PodiumPlace(first, unit, height = 140.dp, Modifier.weight(1f), crown = true)
        PodiumPlace(third, unit, height = 92.dp, Modifier.weight(1f))
    }
}

@Composable
private fun PodiumPlace(
    entry: LeaderboardEntry,
    unit: String,
    height: Dp,
    modifier: Modifier = Modifier,
    crown: Boolean = false
) {
    val score = ResultPresentation.formatNumber(entry.score) + " " + unit
    val spoken = stringResource(R.string.leaderboard_entry_spoken, entry.rank, entry.displayName, entry.region, score)
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (crown) {
            Icon(AppIcons.Crown, contentDescription = null, tint = AppColors.Warning, modifier = Modifier.size(22.dp))
        }
        Avatar(entry.displayName, size = if (crown) 60.dp else 50.dp, highlight = entry.isYou || crown)
        Text(
            entry.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(AppColors.SurfaceHigh, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                .padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(
                    when (entry.rank) {
                        1 -> R.string.leaderboard_place_1
                        2 -> R.string.leaderboard_place_2
                        else -> R.string.leaderboard_place_3
                    }
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.Accent
            )
            Tag(text = score, tone = TagTone.Accent, icon = AppIcons.Star)
        }
    }
}

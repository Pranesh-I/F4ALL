package com.sai.sports.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.api.AthleteSummary
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.data.ProfileStatusStore
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.Avatar
import com.sai.sports.ui.kit.Bib
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.RegisterRow
import com.sai.sports.ui.kit.RuleLine
import com.sai.sports.ui.kit.SectionHeader
import com.sai.sports.ui.kit.StatRow
import com.sai.sports.ui.kit.StatTile
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The athlete's profile, personal bests and every result SAI holds for them. */
@Composable
fun ProfileScreen(
    onBack: (() -> Unit)? = null,
    onResultSelected: (String) -> Unit,
    onHistory: () -> Unit,
    onBadges: () -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit,
    onCompleteProfile: () -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val profileStore = remember { AthleteProfileStore(context) }
    val statusStore = remember { ProfileStatusStore(context) }
    val scope = rememberCoroutineScope()

    var summary by remember { mutableStateOf<AthleteSummary?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmingRemoval by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        error = null
        when (val result = withContext(Dispatchers.IO) { api.summary() }) {
            is ApiResult.Success -> {
                summary = result.value
                // Keep the on-device jump calibration in step with the profile.
                result.value.profile.heightCm?.let(profileStore::setHeightCm)
                statusStore.save(result.value.profile)
            }
            is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.error_load)
        }
    }

    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text(stringResource(R.string.profile_remove_photo)) },
            text = { Text(stringResource(R.string.profile_remove_photo_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemoval = false
                    scope.launch {
                        when (val result = withContext(Dispatchers.IO) { api.withdrawFaceConsent() }) {
                            is ApiResult.Success -> {
                                statusStore.save(result.value)
                                reload += 1
                            }
                            is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.error_load)
                        }
                    }
                }) { Text(stringResource(R.string.profile_remove_photo_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Page(scrollable = false, header = { TitleBar(stringResource(R.string.nav_profile), onBack) }) {

        val current = summary
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator(color = AppColors.Accent)

            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    val profile = current.profile
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Avatar(profile.name, size = 88.dp, highlight = true)
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = AppColors.TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            listOfNotNull(profile.place, profile.city, profile.region).joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                item {
                    val profile = current.profile
                    StatRow {
                        StatTile(
                            icon = AppIcons.Calendar,
                            value = stringResource(R.string.profile_age_value, profile.ageYears),
                            label = stringResource(Labels.gender(profile.gender))
                        )
                        StatTile(
                            icon = AppIcons.Ruler,
                            value = profile.heightCm?.let {
                                ResultPresentation.formatNumber(it) + " " + stringResource(R.string.unit_cm)
                            } ?: "—",
                            label = stringResource(R.string.profile_stat_height)
                        )
                        StatTile(
                            icon = AppIcons.Globe,
                            value = profile.region,
                            label = stringResource(R.string.profile_stat_state)
                        )
                    }
                }

                item {
                    val profile = current.profile
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Bib(number = BibNumber.of(profile.athleteId), name = null, detail = null)
                        profile.achievements?.let {
                            Text(
                                stringResource(R.string.profile_achievements, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary
                            )
                        }
                        if (!profile.complete) {
                            ErrorText(stringResource(R.string.profile_incomplete))
                            MarigoldButton(
                                text = stringResource(R.string.profile_complete_action),
                                onClick = onCompleteProfile,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    MenuCard {
                        MenuRow(AppIcons.Chart, stringResource(R.string.nav_progress), onHistory)
                        MenuRow(AppIcons.Medal, stringResource(R.string.nav_badges), onBadges)
                        MenuRow(AppIcons.Trophy, stringResource(R.string.nav_leaderboard), onLeaderboard)
                        MenuRow(AppIcons.Gear, stringResource(R.string.nav_settings), onSettings, last = true)
                    }
                }

                item { SectionHeader(stringResource(R.string.profile_bests), Modifier.padding(top = 8.dp)) }

                item {
                    if (current.personalBests.isEmpty()) {
                        EmptyLine(stringResource(R.string.profile_bests_empty))
                    } else {
                        MenuCard {
                            current.personalBests.forEachIndexed { index, best ->
                                RegisterRow(
                                    label = stringResource(Labels.testName(best.testType)),
                                    value = ResultPresentation.formatNumber(best.score) + " " +
                                        stringResource(Labels.unit(best.unit)),
                                    // An approved best is white with the official word; the rest stay grey.
                                    valueColor = if (best.official) AppColors.TextPrimary else AppColors.TextSecondary,
                                    detail = stringResource(ResultPresentation.officialLabel(best.official)),
                                    detailColor = if (best.official) AppColors.Accent else AppColors.TextSecondary,
                                    divider = index < current.personalBests.lastIndex
                                )
                            }
                        }
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.profile_recent_results),
                        action = if (current.history.size > RECENT_RESULTS) stringResource(R.string.home_view_all) else null,
                        onAction = onHistory,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    if (current.history.isEmpty()) {
                        EmptyLine(stringResource(R.string.profile_results_empty))
                    } else {
                        // The latest few; Progress holds the rest, with practice.
                        val recent = current.history.take(RECENT_RESULTS)
                        MenuCard {
                            recent.forEachIndexed { index, item ->
                                val (score, _) = ResultPresentation.headline(
                                    item.provisionalScore, item.serverScore, item.finalScore
                                )
                                val writing = ResultPresentation.writing(
                                    item.status, item.provisionalScore, item.serverScore, item.finalScore
                                )
                                RegisterRow(
                                    label = stringResource(Labels.testName(item.testType)),
                                    value = ResultPresentation.formatNumber(score) + " " + stringResource(Labels.unit(item.unit)),
                                    valueColor = when (writing) {
                                        ResultPresentation.Writing.PENCIL -> AppColors.TextSecondary
                                        ResultPresentation.Writing.STRUCK -> AppColors.Danger
                                        else -> AppColors.TextPrimary
                                    },
                                    detail = stringResource(Labels.resultStatus(item.status)),
                                    detailColor = when (writing) {
                                        ResultPresentation.Writing.STAMPED -> AppColors.Accent
                                        ResultPresentation.Writing.STRUCK -> AppColors.Danger
                                        else -> AppColors.TextSecondary
                                    },
                                    // Names the action for screen readers, which would
                                    // otherwise announce only "double tap to activate".
                                    onClickLabel = stringResource(R.string.profile_open_result),
                                    onClick = { onResultSelected(item.resultId) },
                                    divider = index < recent.lastIndex
                                )
                            }
                        }
                    }
                }

                item {
                    val profile = current.profile
                    if (profile.complete && profile.hasReferencePhoto) {
                        PlainButton(
                            text = stringResource(R.string.profile_remove_photo),
                            onClick = { confirmingRemoval = true },
                            color = AppColors.Danger,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** A group of rows on one card. */
@Composable
private fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        content = content
    )
}

/** One place to go: an icon in a disc, its name, and a chevron. */
@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit, last: Boolean = false) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(AppColors.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(19.dp))
            }
            Text(label, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            Icon(AppIcons.Forward, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        if (!last) RuleLine()
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

private const val RECENT_RESULTS = 5

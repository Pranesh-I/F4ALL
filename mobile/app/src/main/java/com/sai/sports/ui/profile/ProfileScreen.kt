package com.sai.sports.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.sai.sports.ui.kit.Bib
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.RegisterRow
import com.sai.sports.ui.kit.SectionRule
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.TrialsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The athlete's profile, personal bests and every result SAI holds for them. */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onResultSelected: (String) -> Unit,
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

    Page(scrollable = false, header = { TitleBar(stringResource(R.string.profile_title), onBack) }) {

        val current = summary
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator()

            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }

            else -> LazyColumn {
                item {
                    val profile = current.profile
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Bib(
                            number = BibNumber.of(profile.athleteId),
                            name = profile.name,
                            detail = stringResource(
                                R.string.profile_summary_line,
                                profile.ageYears,
                                stringResource(Labels.gender(profile.gender)),
                                profile.region
                            )
                        )
                        profile.heightCm?.let {
                            Text(
                                stringResource(R.string.profile_height, ResultPresentation.formatNumber(it)),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        profile.city?.let { city ->
                            Text(listOfNotNull(profile.place, city).joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                        }
                        profile.achievements?.let {
                            Text(
                                stringResource(R.string.profile_achievements, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = TrialsColors.Pencil
                            )
                        }
                        if (!profile.complete) {
                            ErrorText(stringResource(R.string.profile_incomplete))
                            InkButton(
                                text = stringResource(R.string.profile_complete_action),
                                onClick = onCompleteProfile
                            )
                        } else if (profile.hasReferencePhoto) {
                            PlainButton(
                                text = stringResource(R.string.profile_remove_photo),
                                onClick = { confirmingRemoval = true },
                                color = TrialsColors.RedPen
                            )
                        }
                    }
                }

                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        RegisterRow(label = stringResource(R.string.nav_badges), onClick = onBadges)
                        RegisterRow(label = stringResource(R.string.nav_leaderboard), onClick = onLeaderboard)
                    }
                }

                item { SectionRule(stringResource(R.string.profile_bests), Modifier.padding(top = 24.dp)) }

                if (current.personalBests.isEmpty()) {
                    item { EmptyLine(stringResource(R.string.profile_bests_empty)) }
                }

                items(current.personalBests) { best ->
                    RegisterRow(
                        label = stringResource(Labels.testName(best.testType)),
                        value = "${ResultPresentation.formatNumber(best.score)} " + stringResource(Labels.unit(best.unit)),
                        // An approved best is in ink with the stamp's word; the rest are still pencil.
                        valueColor = if (best.official) TrialsColors.Ink else TrialsColors.Pencil,
                        detail = stringResource(ResultPresentation.officialLabel(best.official)),
                        detailColor = if (best.official) TrialsColors.Stamp else TrialsColors.Pencil
                    )
                }

                item { SectionRule(stringResource(R.string.profile_results), Modifier.padding(top = 24.dp)) }

                if (current.history.isEmpty()) {
                    item { EmptyLine(stringResource(R.string.profile_results_empty)) }
                }

                items(current.history, key = { it.resultId }) { item ->
                    val (score, _) = ResultPresentation.headline(
                        item.provisionalScore, item.serverScore, item.finalScore
                    )
                    val writing = ResultPresentation.writing(
                        item.status, item.provisionalScore, item.serverScore, item.finalScore
                    )
                    RegisterRow(
                        label = stringResource(Labels.testName(item.testType)),
                        value = "${ResultPresentation.formatNumber(score)} " + stringResource(Labels.unit(item.unit)),
                        valueColor = when (writing) {
                            ResultPresentation.Writing.PENCIL -> TrialsColors.Pencil
                            ResultPresentation.Writing.STRUCK -> TrialsColors.RedPen
                            else -> TrialsColors.Ink
                        },
                        detail = stringResource(Labels.resultStatus(item.status)),
                        detailColor = when (writing) {
                            ResultPresentation.Writing.STAMPED -> TrialsColors.Stamp
                            ResultPresentation.Writing.STRUCK -> TrialsColors.RedPen
                            else -> TrialsColors.Pencil
                        },
                        // Names the action for screen readers, which would
                        // otherwise announce only "double tap to activate".
                        onClickLabel = stringResource(R.string.profile_open_result),
                        onClick = { onResultSelected(item.resultId) }
                    )
                }

                item {
                    RegisterRow(
                        label = stringResource(R.string.nav_settings),
                        onClick = onSettings,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = TrialsColors.Pencil,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

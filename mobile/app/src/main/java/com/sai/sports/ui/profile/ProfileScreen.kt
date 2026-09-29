package com.sai.sports.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.sai.sports.ui.common.SectionTitle
import com.sai.sports.ui.common.TitleBar
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(stringResource(R.string.profile_title), onBack)

        val current = summary
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator()

            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    val profile = current.profile
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(
                                    R.string.profile_summary_line,
                                    profile.ageYears,
                                    stringResource(Labels.gender(profile.gender)),
                                    profile.region
                                )
                            )
                            profile.heightCm?.let {
                                Text(
                                    stringResource(
                                        R.string.profile_height,
                                        ResultPresentation.formatNumber(it)
                                    )
                                )
                            }
                            profile.city?.let { city ->
                                Text(listOfNotNull(profile.place, city).joinToString(", "))
                            }
                            profile.achievements?.let {
                                Text(
                                    stringResource(R.string.profile_achievements, it),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!profile.complete) {
                                ErrorText(stringResource(R.string.profile_incomplete))
                                TextButton(onClick = onCompleteProfile) {
                                    Text(stringResource(R.string.profile_complete_action))
                                }
                            } else if (profile.hasReferencePhoto) {
                                TextButton(onClick = { confirmingRemoval = true }) {
                                    Text(stringResource(R.string.profile_remove_photo))
                                }
                            }
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBadges, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.nav_badges))
                        }
                        OutlinedButton(onClick = onLeaderboard, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.nav_leaderboard))
                        }
                    }
                }

                item { SectionTitle(stringResource(R.string.profile_bests)) }

                if (current.personalBests.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.profile_bests_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                items(current.personalBests) { best ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(Labels.testName(best.testType)), fontWeight = FontWeight.SemiBold)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${ResultPresentation.formatNumber(best.score)} " +
                                        stringResource(Labels.unit(best.unit))
                                )
                                Text(
                                    stringResource(ResultPresentation.officialLabel(best.official)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item { SectionTitle(stringResource(R.string.profile_results)) }

                if (current.history.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.profile_results_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                items(current.history, key = { it.resultId }) { item ->
                    val (score, _) = ResultPresentation.headline(
                        item.provisionalScore, item.serverScore, item.finalScore
                    )
                    val openLabel = stringResource(R.string.profile_open_result)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResultSelected(item.resultId) }
                            // Names the action for screen readers, which would
                            // otherwise announce only "double tap to activate".
                            .semantics {
                                onClick(label = openLabel) {
                                    onResultSelected(item.resultId)
                                    true
                                }
                            }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(Labels.testName(item.testType)), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${ResultPresentation.formatNumber(score)} " +
                                        stringResource(Labels.unit(item.unit))
                                )
                            }
                            Text(
                                stringResource(Labels.resultStatus(item.status)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                item {
                    OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nav_settings))
                    }
                }
            }
        }
    }
}

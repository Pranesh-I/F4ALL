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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sai.sports.api.ApiResult
import com.sai.sports.api.AthleteSummary
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.ui.auth.ReferencePhotoStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The athlete's profile, personal bests and every result SAI holds for them. */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onResultSelected: (String) -> Unit,
    onLoggedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { AppServices.api(context) }
    val session = remember { AppServices.session(context) }
    val profileStore = remember { AthleteProfileStore(context) }

    var summary by remember { mutableStateOf<AthleteSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var addingPhoto by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        error = null
        when (val result = withContext(Dispatchers.IO) { api.summary() }) {
            is ApiResult.Success -> {
                summary = result.value
                // Keep the on-device jump calibration in step with the profile.
                result.value.profile.heightCm?.let(profileStore::setHeightCm)
            }
            is ApiResult.Failure -> error = result.message
        }
    }

    if (addingPhoto) {
        ReferencePhotoStep(onDone = {
            addingPhoto = false
            reload += 1
        })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("My profile", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }

        val current = summary

        when {
            current == null && error == null -> CircularProgressIndicator()

            current == null -> {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { reload += 1 }) { Text("Try again") }
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    val profile = current.profile
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge)
                            Text("${profile.ageYears} years · ${profile.gender.replaceFirstChar { it.uppercase() }} · ${profile.region}")
                            profile.heightCm?.let { Text("Height: ${ResultPresentation.formatScore(it, "cm")}") }
                            if (!profile.hasReferencePhoto) {
                                Text(
                                    "No photo on file — SAI cannot confirm your identity in test videos.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { addingPhoto = true }) { Text("Add photo") }
                            }
                        }
                    }
                }

                item {
                    Text("Personal bests", style = MaterialTheme.typography.titleMedium)
                }

                if (current.personalBests.isEmpty()) {
                    item {
                        Text(
                            "Your best scores appear here once SAI has checked a test.",
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
                            Text(ResultPresentation.testName(best.testType), fontWeight = FontWeight.SemiBold)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(ResultPresentation.formatScore(best.score, best.unit))
                                Text(
                                    if (best.official) "Official" else "Not yet approved",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item {
                    Text("All results", style = MaterialTheme.typography.titleMedium)
                }

                if (current.history.isEmpty()) {
                    item {
                        Text(
                            "Tests you record appear here after they reach SAI.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                items(current.history, key = { it.resultId }) { item ->
                    val (score, _) = ResultPresentation.headline(
                        item.provisionalScore, item.serverScore, item.finalScore
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResultSelected(item.resultId) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ResultPresentation.testName(item.testType), fontWeight = FontWeight.SemiBold)
                                Text(ResultPresentation.formatScore(score, item.unit))
                            }
                            Text(
                                ResultPresentation.statusLabel(item.status),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // Best effort: signing out must work with no signal.
                                    runCatching { api.logout(session.current()?.refreshToken) }
                                    session.clear()
                                }
                                onLoggedOut()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign out")
                    }
                }
            }
        }
    }
}

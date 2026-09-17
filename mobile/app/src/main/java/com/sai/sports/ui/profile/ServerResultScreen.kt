package com.sai.sports.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Benchmark
import com.sai.sports.api.ServerResult
import com.sai.sports.auth.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One result as SAI holds it, with the athlete's standing in their age group.
 *
 * The benchmark is only shown for a score SAI has measured itself. A phone's own
 * number is never ranked, because telling an athlete they placed in the top 25%
 * on an unverified measurement is exactly the confusion this system exists to
 * prevent.
 */
@Composable
fun ServerResultScreen(
    resultId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }

    var result by remember { mutableStateOf<ServerResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(resultId, reload) {
        error = null
        when (val response = withContext(Dispatchers.IO) { api.result(resultId) }) {
            is ApiResult.Success -> result = response.value
            is ApiResult.Failure -> error = response.message
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Result", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }

        val current = result

        when {
            current == null && error == null -> CircularProgressIndicator()

            current == null -> {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { reload += 1 }) { Text("Try again") }
            }

            else -> {
                val (score, label) = ResultPresentation.headline(
                    current.provisionalScore, current.serverScore, current.finalScore
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ResultPresentation.testName(current.testType), style = MaterialTheme.typography.titleLarge)
                        Text(ResultPresentation.formatScore(score, current.unit), style = MaterialTheme.typography.displaySmall)
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        Text(ResultPresentation.statusLabel(current.status), fontWeight = FontWeight.SemiBold)

                        if (current.serverScore != null && current.provisionalScore != null &&
                            current.serverScore != current.provisionalScore
                        ) {
                            Text(
                                "Your phone counted ${ResultPresentation.formatScore(current.provisionalScore, current.unit)}. " +
                                    "SAI's measurement is the one that counts.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                current.review?.let { review ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ResultPresentation.reviewLabel(review.action), fontWeight = FontWeight.SemiBold)
                            review.notes?.takeIf { it.isNotBlank() }?.let { Text("\"$it\"") }
                        }
                    }
                }

                current.benchmark?.let { BenchmarkCard(it) }

                current.benchmarkUnavailable?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BenchmarkCard(benchmark: Benchmark) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Compared with your age group", style = MaterialTheme.typography.titleMedium)
            Text(benchmark.label, fontWeight = FontWeight.SemiBold)

            benchmark.percentile?.let {
                Text("Around the ${ordinal(it)} percentile")
            }

            benchmark.nextTarget?.let {
                Text("Next goal: ${ResultPresentation.formatScore(it, benchmark.unit)}")
            }

            Text("Group: ${benchmark.cohort}", style = MaterialTheme.typography.bodySmall)

            if (benchmark.provisional) {
                // Shown every time, not dismissible. These norms are
                // placeholders, and an athlete must not take them as SAI's
                // official standard.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        "These comparison figures are provisional and are not SAI's " +
                            "official standards yet. Use them as a rough guide only.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

internal fun ordinal(value: Int): String {
    val suffix = if (value % 100 in 11..13) "th" else when (value % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$value$suffix"
}

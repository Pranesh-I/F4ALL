package com.sai.sports.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Benchmark
import com.sai.sports.api.ServerResult
import com.sai.sports.auth.AppServices
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.common.SectionTitle
import com.sai.sports.ui.common.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One result as SAI holds it, with the athlete's standing in their age group.
 *
 * The benchmark is only shown for a score SAI has measured itself. A phone's own
 * number is never ranked.
 */
@Composable
fun ServerResultScreen(
    resultId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }

    var result by remember { mutableStateOf<ServerResult?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(resultId, reload) {
        error = null
        when (val response = withContext(Dispatchers.IO) { api.result(resultId) }) {
            is ApiResult.Success -> result = response.value
            is ApiResult.Failure -> error = Labels.failure(response.kind, R.string.error_load)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleBar(stringResource(R.string.result_title), onBack)

        val current = result
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator()

            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }

            else -> {
                val (score, label) = ResultPresentation.headline(
                    current.provisionalScore, current.serverScore, current.finalScore
                )
                val unit = stringResource(Labels.unit(current.unit))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle(stringResource(Labels.testName(current.testType)))
                        Text(
                            "${ResultPresentation.formatNumber(score)} $unit",
                            style = MaterialTheme.typography.displaySmall
                        )
                        Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(Labels.resultStatus(current.status)), fontWeight = FontWeight.SemiBold)

                        if (current.serverScore != null && current.provisionalScore != null &&
                            current.serverScore != current.provisionalScore
                        ) {
                            Text(
                                stringResource(
                                    R.string.result_phone_counted,
                                    "${ResultPresentation.formatNumber(current.provisionalScore)} $unit"
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                current.review?.let { review ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(Labels.reviewAction(review.action)), fontWeight = FontWeight.SemiBold)
                            // The official's own words, in whatever language they wrote.
                            review.notes?.takeIf { it.isNotBlank() }?.let { Text("\"$it\"") }
                        }
                    }
                }

                current.benchmark?.let { BenchmarkCard(it, unit) }

                if (current.benchmark == null && current.benchmarkUnavailable != null) {
                    Text(
                        stringResource(
                            if (current.serverScore == null && current.finalScore == null) {
                                R.string.benchmark_not_verified
                            } else {
                                R.string.benchmark_no_cohort
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun BenchmarkCard(benchmark: Benchmark, unit: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.benchmark_title))
            Text(stringResource(Labels.benchmarkBand(benchmark.band)), fontWeight = FontWeight.SemiBold)

            benchmark.percentile?.let {
                Text(stringResource(R.string.benchmark_percentile, it))
            }

            benchmark.nextTarget?.let {
                Text(stringResource(R.string.benchmark_next_goal, "${ResultPresentation.formatNumber(it)} $unit"))
            }

            val (gender, ages) = Labels.parseCohort(benchmark.cohort)
            Text(
                stringResource(R.string.benchmark_group, stringResource(Labels.gender(gender)), ages),
                style = MaterialTheme.typography.bodySmall
            )

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
                        stringResource(R.string.benchmark_provisional),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

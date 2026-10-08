package com.sai.sports.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Benchmark
import com.sai.sports.api.ReviewNote
import com.sai.sports.api.ServerResult
import com.sai.sports.auth.AppServices
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RuleLine
import com.sai.sports.ui.kit.ScoreCorrection
import com.sai.sports.ui.kit.ScoreInk
import com.sai.sports.ui.kit.ScoreMark
import com.sai.sports.ui.kit.SectionRule
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.Slip
import com.sai.sports.ui.kit.SlipStep
import com.sai.sports.ui.kit.Stamp
import com.sai.sports.ui.kit.StepState
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.TrialsColors
import com.sai.sports.ui.theme.TrialsFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One result as SAI holds it, with the athlete's standing in their age group.
 *
 * The score is written the way a trials scoresheet is filled in: the phone's
 * count in pencil, SAI's re-measurement over it in pen, and the violet stamp
 * once an official approves it. The benchmark is only shown for a score SAI
 * has measured itself — a phone's own number is never ranked.
 */
@Composable
fun ServerResultScreen(
    resultId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val athleteId = remember { AppServices.session(context).current()?.athleteId }

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

    Page(header = { TitleBar(stringResource(R.string.result_title), onBack) }) {

        val current = result
        val failure = error

        when {
            current == null && failure == null -> CircularProgressIndicator()

            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }

            else -> {
                val unit = stringResource(Labels.unit(current.unit))

                ResultSlip(current, unit, athleteId)

                current.review?.let { ReviewCard(it) }

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
                        style = MaterialTheme.typography.bodySmall,
                        color = TrialsColors.Pencil
                    )
                }
            }
        }
    }
}

/** The result's slip: the score in the ink its status deserves, and each step to official. */
@Composable
private fun ResultSlip(result: ServerResult, unit: String, athleteId: String?) {
    val format = ResultPresentation::formatNumber
    val (headline, label) = ResultPresentation.headline(result.provisionalScore, result.serverScore, result.finalScore)
    val writing = ResultPresentation.writing(
        result.status, result.provisionalScore, result.serverScore, result.finalScore
    )

    Slip {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Labels.testName(result.testType)).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            athleteId?.let {
                Text(
                    text = BibNumber.of(it),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = TrialsFonts.Numerals)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (writing) {
                ResultPresentation.Writing.PENCIL ->
                    ScoreMark(format(headline), unit, ScoreInk.Pencil)

                ResultPresentation.Writing.PEN ->
                    ScoreMark(format(headline), unit, ScoreInk.Pen)

                ResultPresentation.Writing.CORRECTED ->
                    ScoreCorrection(format(result.provisionalScore), format(result.serverScore), unit)

                ResultPresentation.Writing.STAMPED -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    ScoreMark(format(headline), unit, ScoreInk.Official)
                    Stamp(
                        text = stringResource(R.string.official_badge),
                        caption = "SAI",
                        animate = true
                    )
                }

                ResultPresentation.Writing.STRUCK ->
                    ScoreMark(format(headline), unit, ScoreInk.Pen, struck = true, strikeColor = TrialsColors.RedPen)
            }

            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = TrialsColors.Pencil)
            Text(
                stringResource(Labels.resultStatus(result.status)),
                style = MaterialTheme.typography.titleMedium,
                color = if (writing == ResultPresentation.Writing.STRUCK) TrialsColors.RedPen else TrialsColors.Ink
            )
            if (writing == ResultPresentation.Writing.CORRECTED) {
                Text(
                    stringResource(R.string.result_phone_counted, "${format(result.provisionalScore)} $unit"),
                    style = MaterialTheme.typography.bodySmall,
                    color = TrialsColors.Pencil
                )
            }
        }

        RuleLine()

        Column {
            SlipStep(
                state = StepState.Done,
                title = stringResource(R.string.step_counted),
                detail = result.provisionalScore?.let { "${format(it)} $unit" }
            )
            SlipStep(
                state = StepState.Done,
                title = stringResource(R.string.home_sync),
                detail = stringResource(R.string.sync_submitted)
            )
            val checked = ResultPresentation.checkStep(result.status)
            SlipStep(
                state = checked,
                title = stringResource(R.string.step_checked),
                detail = if (checked == StepState.Done) {
                    result.serverScore?.let { "${format(it)} $unit" }
                } else {
                    stringResource(R.string.step_in_progress)
                }
            )
            val approval = ResultPresentation.approvalStep(result.status)
            SlipStep(
                state = approval,
                title = stringResource(R.string.step_approved),
                detail = if (approval == StepState.Waiting) {
                    stringResource(R.string.step_waiting)
                } else {
                    stringResource(Labels.resultStatus(result.status))
                },
                last = true
            )
        }
    }
}

/** What the official decided, and their own words — a red-pen note when the athlete must act. */
@Composable
private fun ReviewCard(review: ReviewNote) {
    val approved = review.action == "approved"
    Sheet(style = if (approved) SheetStyle.Paper else SheetStyle.Note) {
        Text(
            stringResource(Labels.reviewAction(review.action)),
            style = MaterialTheme.typography.titleMedium,
            color = if (approved) TrialsColors.Stamp else TrialsColors.RedPen
        )
        // The official's own words, in whatever language they wrote.
        review.notes?.takeIf { it.isNotBlank() }?.let {
            Text("“$it”", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BenchmarkCard(benchmark: Benchmark, unit: String) {
    Sheet(spacing = 8.dp) {
        SectionRule(stringResource(R.string.benchmark_title))
        Text(stringResource(Labels.benchmarkBand(benchmark.band)), style = MaterialTheme.typography.titleLarge)

        benchmark.percentile?.let {
            Text(stringResource(R.string.benchmark_percentile, it), style = MaterialTheme.typography.bodyLarge)
        }

        benchmark.nextTarget?.let {
            Text(
                stringResource(R.string.benchmark_next_goal, "${ResultPresentation.formatNumber(it)} $unit"),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        val (gender, ages) = Labels.parseCohort(benchmark.cohort)
        Text(
            stringResource(R.string.benchmark_group, stringResource(Labels.gender(gender)), ages),
            style = MaterialTheme.typography.bodySmall,
            color = TrialsColors.Pencil
        )

        if (benchmark.provisional) {
            // Shown every time, not dismissible. These norms are placeholders,
            // and an athlete must not take them as SAI's official standard.
            Sheet(style = SheetStyle.Rough, spacing = 0.dp) {
                Text(stringResource(R.string.benchmark_provisional), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

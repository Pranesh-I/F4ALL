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
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Benchmark
import com.sai.sports.api.ReviewNote
import com.sai.sports.api.ServerForm
import com.sai.sports.api.ServerResult
import com.sai.sports.auth.AppServices
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.BigNumber
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RegisterRow
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
import com.sai.sports.ui.kit.StatusLine
import com.sai.sports.ui.kit.StepState
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.theme.AppColors
import com.sai.sports.ui.theme.AppFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
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
    onBack: () -> Unit,
    /** Opens another official result, such as the attempt before this one. */
    onOpenResult: (String) -> Unit = {}
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

                RecordCard(current, unit, onOpenResult)

                if (TestType.entries.firstOrNull { it.name == current.testType }?.countsReps == true) {
                    ServerFormCard(current.serverForm, current.testType)
                }

                current.benchmark?.let { BenchmarkCard(it, unit) }

                if (current.benchmark == null && current.benchmarkUnavailable != null) {
                    Text(
                        stringResource(ResultPresentation.benchmarkUnavailable(current.status)),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
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
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = AppFonts.Numerals)
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
                    ScoreMark(format(headline), unit, ScoreInk.Pen, struck = true, strikeColor = AppColors.Danger)
            }

            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Text(
                stringResource(Labels.resultStatus(result.status)),
                style = MaterialTheme.typography.titleMedium,
                color = if (writing == ResultPresentation.Writing.STRUCK) AppColors.Danger else AppColors.TextPrimary
            )
            if (writing == ResultPresentation.Writing.CORRECTED) {
                Text(
                    stringResource(R.string.result_phone_counted, "${format(result.provisionalScore)} $unit"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
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

/**
 * Where this result stands in the athlete's record: when and where it was
 * taken, whether it counts, their best at this test and the attempt before.
 */
@Composable
private fun RecordCard(result: ServerResult, unit: String, onOpenResult: (String) -> Unit) {
    val format = ResultPresentation::formatNumber
    Sheet(spacing = 0.dp) {
        SectionRule(stringResource(R.string.result_record_title))

        result.createdAtMs?.let {
            RegisterRow(label = stringResource(R.string.result_date), value = formatDate(it))
        }

        RegisterRow(
            label = stringResource(R.string.result_session),
            value = result.sessionName ?: stringResource(R.string.history_official_test)
        )

        val validity = ResultPresentation.validity(result.status)
        RegisterRow(
            label = stringResource(R.string.result_validity),
            value = stringResource(ResultPresentation.validityLabel(validity)),
            valueColor = when (validity) {
                ResultPresentation.Validity.COUNTS -> AppColors.Accent
                ResultPresentation.Validity.DOES_NOT_COUNT -> AppColors.Danger
                else -> AppColors.TextSecondary
            },
            detail = result.serverInvalidReason?.let {
                stringResource(R.string.result_not_scored_by_sai, stringResource(Labels.invalidReason(it)))
            },
            detailColor = AppColors.Danger
        )

        val best = result.personalBest
        RegisterRow(
            label = stringResource(R.string.result_personal_best),
            value = best?.let { "${format(it.score)} " + stringResource(Labels.unit(it.unit)) } ?: "—",
            valueColor = if (best?.official == true) AppColors.TextPrimary else AppColors.TextSecondary,
            detail = when {
                best == null -> stringResource(R.string.result_best_none)
                ResultPresentation.isPersonalBest(best, result.resultId) ->
                    stringResource(R.string.result_best_this)
                else -> stringResource(ResultPresentation.officialLabel(best.official))
            },
            detailColor = if (best?.official == true) AppColors.Accent else AppColors.TextSecondary
        )

        val previous = result.previousAttempt
        if (previous == null) {
            RegisterRow(
                label = stringResource(R.string.result_previous),
                value = "—",
                detail = stringResource(R.string.result_previous_none),
                divider = false
            )
        } else {
            val (score, _) = ResultPresentation.headline(
                previous.provisionalScore, previous.serverScore, previous.finalScore
            )
            RegisterRow(
                label = stringResource(R.string.result_previous),
                value = "${format(score)} " + stringResource(Labels.unit(previous.unit)),
                valueColor = if (previous.status == "rejected") AppColors.Danger else AppColors.TextPrimary,
                detail = formatDate(previous.createdAtMs) + " · " +
                    stringResource(Labels.resultStatus(previous.status)),
                onClick = { onOpenResult(previous.resultId) },
                onClickLabel = stringResource(R.string.profile_open_result),
                divider = false
            )
        }
    }
}

/**
 * SAI's own verdict on the athlete's form: the share of good reps, and what
 * went wrong most often. Said plainly when SAI has not measured it yet.
 */
@Composable
private fun ServerFormCard(form: ServerForm?, testType: String) {
    Sheet(spacing = 8.dp) {
        SectionRule(stringResource(R.string.form_title))
        if (form == null) {
            Text(
                stringResource(R.string.result_form_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
            return@Sheet
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigNumber("${form.score}%")
            Text(
                stringResource(R.string.result_form_from_sai),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }

        val faults = ResultPresentation.formFaults(form)
        when {
            form.faults == null -> Unit
            faults.isEmpty() -> StatusLine(
                text = stringResource(R.string.form_all_good),
                icon = AppIcons.Check,
                color = AppColors.TextPrimary
            )
            else -> faults.forEach { (issue, reps) ->
                StatusLine(
                    text = stringResource(R.string.result_form_fault, stringResource(Labels.formIssue(issue)), reps),
                    icon = AppIcons.Forward,
                    color = AppColors.TextPrimary
                )
            }
        }
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

/** What the official decided, and their own words — a red-pen note when the athlete must act. */
@Composable
private fun ReviewCard(review: ReviewNote) {
    val approved = review.action == "approved"
    Sheet(style = if (approved) SheetStyle.Paper else SheetStyle.Note) {
        Text(
            stringResource(Labels.reviewAction(review.action)),
            style = MaterialTheme.typography.titleMedium,
            color = if (approved) AppColors.Accent else AppColors.Danger
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
            color = AppColors.TextSecondary
        )
        Text(
            stringResource(
                R.string.benchmark_norms_from,
                benchmark.region ?: stringResource(R.string.benchmark_all_india)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary
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

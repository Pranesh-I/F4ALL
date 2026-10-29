package com.sai.sports.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.R
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.coach.FormSummary
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptMode
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeComparison
import com.sai.sports.data.PracticeStats
import com.sai.sports.data.SyncRepository
import com.sai.sports.data.local.TestAttemptEntity
import com.sai.sports.sync.Delivery
import com.sai.sports.ui.capture.PoseOverlayView
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.BigNumber
import com.sai.sports.ui.kit.FlowProgress
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.ModeTag
import com.sai.sports.ui.kit.OfficialStep
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.RuleLine
import com.sai.sports.ui.kit.ScoreInk
import com.sai.sports.ui.kit.ScoreMark
import com.sai.sports.ui.kit.SectionRule
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetShape
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.Slip
import com.sai.sports.ui.kit.SlipStep
import com.sai.sports.ui.kit.StatusLine
import com.sai.sports.ui.kit.StepState
import com.sai.sports.ui.kit.SuccessBurst
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.AppColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The result of an attempt, straight after recording or from history.
 *
 * An official attempt comes back as an acknowledgement slip: the phone's
 * score in pencil, and where the attempt has got to on its way to an official
 * result. Practice is rough work on a dashed sheet. The skeleton replay is not
 * decoration: when the count is not what the athlete expected, the only useful
 * answer is showing them what the app actually saw — the same view an SAI
 * official gets on the dashboard.
 */
@Composable
fun ResultsScreen(
    attemptId: String,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    /**
     * True when opened from history to look back at an old attempt, rather
     * than straight after recording it: there is nothing to "try again".
     */
    reviewing: Boolean = false,
    /** Opens SAI's own record of a submitted attempt. */
    onServerResult: ((String) -> Unit)? = null
) {

    val context = LocalContext.current
    val store = remember { AttemptStore(context) }
    val queue = remember { SyncRepository(context) }
    val athleteId = remember { AppServices.session(context).current()?.athleteId }

    val loaded by produceState<LoadedAttempt?>(initialValue = null, attemptId) {
        value = withContext(Dispatchers.IO) {
            val attempt = store.load(attemptId)
            if (attempt == null) {
                null
            } else {
                LoadedAttempt(
                    attempt = attempt,
                    frames = store.loadSequence(attemptId),
                    // Compared only with the same athlete's own practice.
                    comparison = if (attempt.mode == AttemptMode.PRACTICE) {
                        PracticeStats.compare(
                            attempt,
                            store.listAttempts().filter { it.athleteId == attempt.athleteId }
                        )
                    } else {
                        null
                    }
                )
            }
        }
    }

    // An official attempt's place in the upload queue, followed live, so the
    // slip moves from "waiting for a connection" to "sent" in front of them.
    val queued by remember(athleteId, attemptId) {
        athleteId?.let { id ->
            queue.observeAttempts(id).map { attempts -> attempts.firstOrNull { it.id == attemptId } }
        } ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)

    val current = loaded

    if (current == null) {
        Page {
            Text(stringResource(R.string.loading), color = AppColors.TextSecondary)
        }
        return
    }

    val attempt = current.attempt
    val official = attempt.mode == AttemptMode.OFFICIAL

    Page(header = { TitleBar(stringResource(Labels.testName(attempt.testType)), onBack = null) }) {

        // Straight after recording, the moment gets its due: a finished test
        // is celebrated, an unscored one is told plainly, without the confetti.
        if (!reviewing) {
            CompletionHeader(
                scored = attempt.result.status == AttemptStatus.COMPLETE,
                official = official
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeTag(official = official)
            Text(
                text = formatTime(attempt.recordedAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }

        if (official) {
            // The last step of an official test: getting it to SAI.
            if (!reviewing) FlowProgress(current = OfficialStep.UPLOAD)
            OfficialSlip(attempt, athleteId, queued, onServerResult)
        } else {
            PracticeSheet(attempt.result, current.comparison, attempt)
        }

        if (current.frames.isNotEmpty()) {
            SkeletonReplay(
                frames = current.frames,
                imageWidth = attempt.imageWidth,
                imageHeight = attempt.imageHeight
            )
        } else if (!official) {
            // Practice from the athlete's other phone: the result came with
            // their account, the skeleton did not.
            Text(
                text = stringResource(R.string.practice_no_replay),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }

        FormSummary.from(attempt.result.events)?.let { summary ->
            FormCard(summary, attempt.testType)
        }

        QualityCard(attempt.result)

        if (attempt.result.events.isNotEmpty()) {
            EventsCard(attempt.result)
        }

        Text(
            text = stringResource(if (official) R.string.result_provisional_notice else R.string.practice_notice),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // A scored official session attempt used that test's one
            // submission: there is nothing to try again. An unscored one was
            // never queued, so the athlete may still go.
            val usedSubmission = attempt.sessionId != null && official &&
                attempt.result.status == AttemptStatus.COMPLETE

            if (reviewing || !usedSubmission) {
                LineButton(
                    text = stringResource(if (reviewing) R.string.action_back else R.string.action_try_again),
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                )
            }

            InkButton(
                text = stringResource(R.string.action_done),
                onClick = onDone,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * The acknowledgement slip for an official attempt: the phone's count in
 * pencil, then each step to an official result. Only SAI's re-measurement
 * and an official's approval turn the pencil into ink — the slip says so
 * before the athlete can wonder.
 */
@Composable
private fun CompletionHeader(scored: Boolean, official: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (scored) {
            SuccessBurst(size = 180.dp)
        } else {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .size(72.dp)
                    .background(AppColors.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Info, contentDescription = null, tint = AppColors.Warning, modifier = Modifier.size(34.dp))
            }
        }
        Text(
            stringResource(if (scored) R.string.result_complete_title else R.string.result_not_scored_title),
            style = MaterialTheme.typography.headlineLarge,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(
                when {
                    !scored -> R.string.result_not_scored_body
                    official -> R.string.result_complete_official
                    else -> R.string.result_complete_practice
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OfficialSlip(
    attempt: Attempt,
    athleteId: String?,
    queued: TestAttemptEntity?,
    onServerResult: ((String) -> Unit)?
) {
    val result = attempt.result
    Slip {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Labels.testName(attempt.testType)).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            athleteId?.let {
                Text(
                    text = BibNumber.of(it),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.sai.sports.ui.theme.AppFonts.Numerals)
                )
            }
        }

        if (result.status != AttemptStatus.COMPLETE) {
            NotScored(result)
            return@Slip
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ScoreMark(
                value = result.formattedScore(),
                unit = stringResource(Labels.unit(result.unit)),
                ink = ScoreInk.Pencil
            )
            Text(
                text = stringResource(R.string.score_provisional),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }

        RuleLine()

        val delivery = queued?.let(Delivery::of)
        Column {
            SlipStep(
                state = StepState.Done,
                title = stringResource(R.string.step_counted),
                detail = formatTime(attempt.recordedAtMs)
            )
            SlipStep(
                state = when (delivery) {
                    Delivery.SUBMITTED -> StepState.Done
                    Delivery.NOT_ACCEPTED, Delivery.FAILED -> StepState.Refused
                    null -> StepState.Waiting
                    else -> StepState.Active
                },
                title = stringResource(R.string.home_sync),
                detail = when (delivery) {
                    Delivery.SENDING -> queued?.let { stringResource(Labels.syncStatus(it.syncStatus)) }
                    Delivery.WAITING_TO_SUBMIT -> stringResource(R.string.sync_waiting_to_submit)
                    Delivery.SUBMITTED -> stringResource(R.string.sync_submitted)
                    Delivery.NOT_ACCEPTED -> stringResource(R.string.sync_not_accepted)
                    Delivery.FAILED -> stringResource(R.string.sync_failed)
                    null -> stringResource(R.string.step_waiting)
                }
            )
            SlipStep(
                state = if (delivery == Delivery.SUBMITTED) StepState.Active else StepState.Waiting,
                title = stringResource(R.string.step_checked),
                detail = stringResource(
                    if (delivery == Delivery.SUBMITTED) R.string.step_in_progress else R.string.step_waiting
                )
            )
            SlipStep(
                state = StepState.Waiting,
                title = stringResource(R.string.step_approved),
                detail = stringResource(R.string.step_waiting),
                last = true
            )
        }

        val resultId = queued?.resultId
        if (resultId != null && onServerResult != null) {
            PlainButton(
                text = stringResource(R.string.sync_see_result),
                onClick = { onServerResult(resultId) },
                icon = AppIcons.Forward
            )
        }
    }
}

/** Why there is no number: said in red pen, with the analyzer's reason in the athlete's language. */
@Composable
private fun NotScored(result: AnalyzerResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.result_not_scored),
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.Danger
        )
        Text(
            text = stringResource(Labels.invalidReason(result.invalidReason)),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * A practice result: rough work in pencil, with progress in the athlete's own
 * terms. A new personal best is highlighted in marigold — the garland colour.
 */
@Composable
private fun PracticeSheet(result: AnalyzerResult, comparison: PracticeComparison?, attempt: Attempt) {
    Sheet(style = SheetStyle.Rough, padding = PaddingValues(18.dp), spacing = 12.dp) {
        if (result.status != AttemptStatus.COMPLETE) {
            NotScored(result)
        } else {
            ScoreMark(
                value = result.formattedScore(),
                unit = stringResource(Labels.unit(result.unit)),
                ink = ScoreInk.Pencil
            )
        }
        Text(
            text = stringResource(R.string.practice_never_counts),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary
        )
        comparison?.let { PracticeProgress(it, attempt) }
    }
}

/** Progress, in the athlete's terms: a personal best, or how this compares with their best and their last. */
@Composable
private fun PracticeProgress(comparison: PracticeComparison, attempt: Attempt) {

    val unit = stringResource(Labels.unit(attempt.result.unit))

    fun format(value: Double) =
        if (attempt.testType.countsReps) value.toInt().toString()
        else String.format(Locale.getDefault(), "%.1f", value)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            comparison.isFirstScored -> Text(
                text = stringResource(R.string.practice_first_best),
                style = MaterialTheme.typography.titleMedium
            )

            comparison.isPersonalBest -> Text(
                text = stringResource(R.string.practice_new_best),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                modifier = Modifier
                    .background(AppColors.Accent, SheetShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            else -> comparison.previousBest?.let { best ->
                Text(
                    text = stringResource(R.string.practice_your_best, "${format(best)} $unit"),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        comparison.changeFromPrevious?.let { change ->
            Text(
                text = when {
                    change > 0.0 -> stringResource(R.string.practice_vs_last_up, "${format(change)} $unit")
                    change < 0.0 -> stringResource(R.string.practice_vs_last_down, "${format(-change)} $unit")
                    else -> stringResource(R.string.practice_vs_last_same)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Plays the recorded pose sequence back at its original timing.
 *
 * Frame timestamps come from the pose model, so the replay runs at whatever
 * rate the device actually managed — which on a low-end phone is part of what
 * the reviewer needs to see.
 */
@Composable
private fun SkeletonReplay(
    frames: List<PoseFrame>,
    imageWidth: Int,
    imageHeight: Int
) {

    var frameIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf<PoseOverlayView?>(null) }

    val safeWidth = if (imageWidth > 0) imageWidth else DEFAULT_FRAME_WIDTH
    val safeHeight = if (imageHeight > 0) imageHeight else DEFAULT_FRAME_HEIGHT

    LaunchedEffect(isPlaying, frames) {
        while (isPlaying && frameIndex < frames.lastIndex) {
            val currentTimestamp = frames[frameIndex].timestampMs
            val nextTimestamp = frames[frameIndex + 1].timestampMs

            // Guard against non-monotonic timestamps from the async pose
            // pipeline, and cap the wait so one long gap does not stall replay.
            val waitMs = (nextTimestamp - currentTimestamp)
                .coerceIn(MIN_FRAME_DELAY_MS, MAX_FRAME_DELAY_MS)

            delay(waitMs)
            frameIndex++
        }

        if (frameIndex >= frames.lastIndex) {
            isPlaying = false
        }
    }

    LaunchedEffect(frameIndex, overlay) {
        frames.getOrNull(frameIndex)?.let { frame ->
            overlay?.updatePose(
                points = frame.points,
                imageWidth = safeWidth,
                imageHeight = safeHeight
            )
        }
    }

    Sheet(spacing = 12.dp) {
        SectionRule(stringResource(R.string.result_replay_title))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(safeWidth.toFloat() / safeHeight.toFloat())
                .background(Color.Black, RoundedCornerShape(14.dp))
        ) {
            val replayDescription = stringResource(R.string.result_replay_description)
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = replayDescription },
                factory = { context ->
                    PoseOverlayView(context).also {
                        it.scaleMode = PoseOverlayView.ScaleMode.FIT_CENTER
                        overlay = it
                    }
                }
            )
        }

        Slider(
            value = frameIndex.toFloat(),
            onValueChange = {
                isPlaying = false
                frameIndex = it.toInt()
            },
            valueRange = 0f..frames.lastIndex.coerceAtLeast(1).toFloat()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineButton(
                text = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                onClick = {
                    if (frameIndex >= frames.lastIndex) {
                        frameIndex = 0
                    }
                    isPlaying = !isPlaying
                }
            )

            Text(
                text = stringResource(R.string.result_frame_counter, frameIndex + 1, frames.size),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }
    }
}

/** How much of the attempt the app could actually see. */
@Composable
private fun QualityCard(result: AnalyzerResult) {
    Sheet(spacing = 10.dp) {
        SectionRule(
            title = stringResource(R.string.result_tracking_title),
            trailing = "${(result.confidence * 100).toInt()}%"
        )

        LinearProgressIndicator(
            progress = { result.confidence.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = AppColors.TextPrimary,
            trackColor = AppColors.SurfaceHigh,
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )

        Text(
            text = stringResource(
                R.string.result_tracking_detail,
                (result.confidence * 100).toInt(),
                result.framesAnalyzed,
                result.framesRejected
            ),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary
        )

        if (result.framesRejected > result.framesAnalyzed) {
            ErrorText(stringResource(R.string.result_tracking_poor))
        }
    }
}

/**
 * What happened during the attempt, in plain language.
 *
 * Replaces the raw analyzer log, which was English diagnostics ("rep_rejected_
 * partial — Reached 82 deg"). What an athlete needs is "2 sit-ups did not count
 * because you did not sit all the way up" — in their own language.
 */
@Composable
private fun EventsCard(result: AnalyzerResult) {

    val summary = Labels.summarise(result.events)
    val lines = buildList {
        // The sit-up wording names the movement; every other rep test gets the generic lines.
        val sitUps = result.testType == TestType.SIT_UPS
        if (summary.repsCounted > 0) add(
            stringResource(
                if (sitUps) R.string.summary_reps_counted else R.string.summary_reps_counted_generic,
                summary.repsCounted
            ) to true
        )
        if (summary.partialReps > 0) add(
            stringResource(
                if (sitUps) R.string.summary_partial_reps else R.string.summary_partial_reps_generic,
                summary.partialReps
            ) to false
        )
        if (summary.tooFastReps > 0) add(stringResource(R.string.summary_too_fast_reps, summary.tooFastReps) to false)
        if (summary.formRejectedReps > 0) add(stringResource(R.string.summary_form_rejected, summary.formRejectedReps) to false)
        if (summary.formWarnings > 0) add(stringResource(R.string.summary_form_warnings, summary.formWarnings) to false)
        if (summary.wrongArmReps > 0) add(stringResource(R.string.summary_wrong_arm, summary.wrongArmReps) to false)
        if (summary.jumpsMeasured > 0) add(stringResource(R.string.summary_jumps_measured, summary.jumpsMeasured) to true)
        if (summary.jumpsRejected > 0) add(stringResource(R.string.summary_jumps_rejected, summary.jumpsRejected) to false)
        if (summary.trackingLost > 0) add(stringResource(R.string.summary_tracking_lost, summary.trackingLost) to false)
    }

    if (lines.isEmpty()) return

    Sheet(spacing = 8.dp) {
        SectionRule(stringResource(R.string.result_summary_title))
        // Counted in ink with a tick; what did not count in red pen.
        lines.forEach { (line, counted) ->
            StatusLine(
                text = line,
                icon = if (counted) AppIcons.Check else AppIcons.Cross,
                color = if (counted) AppColors.TextPrimary else AppColors.Danger
            )
        }
    }
}

/**
 * How well the reps were done, not just how many.
 *
 * The count says whether the athlete scored; this says what to practise. It
 * names at most two things, most frequent first — a list of every fault is a
 * list nobody acts on.
 */
@Composable
private fun FormCard(summary: FormSummary, testType: TestType) {
    Sheet(spacing = 8.dp) {
        SectionRule(stringResource(R.string.form_title))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BigNumber("${summary.scorePercent}%")
            Text(
                text = stringResource(R.string.form_good_reps, summary.goodReps, summary.attemptedReps),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }

        if (summary.toWorkOn.isEmpty()) {
            StatusLine(
                text = stringResource(R.string.form_all_good),
                icon = AppIcons.Check,
                color = AppColors.TextPrimary
            )
        } else {
            summary.toWorkOn.forEach { cue ->
                StatusLine(
                    text = stringResource(R.string.form_work_on, stringResource(Labels.cue(cue, testType))),
                    icon = AppIcons.Forward,
                    color = AppColors.TextPrimary
                )
            }
        }
    }
}

private fun formatTime(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestampMs))

private data class LoadedAttempt(
    val attempt: Attempt,
    val frames: List<PoseFrame>,
    val comparison: PracticeComparison? = null
)

private const val MIN_FRAME_DELAY_MS = 16L
private const val MAX_FRAME_DELAY_MS = 200L

/** Portrait 3:4, matching the default ImageAnalysis output the pose model receives. */
private const val DEFAULT_FRAME_WIDTH = 480
private const val DEFAULT_FRAME_HEIGHT = 640

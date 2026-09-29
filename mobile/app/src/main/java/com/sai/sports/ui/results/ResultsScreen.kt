package com.sai.sports.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sai.sports.R
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.analyzer.TestType
import com.sai.sports.coach.FormSummary
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptMode
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeComparison
import com.sai.sports.data.PracticeStats
import com.sai.sports.ui.capture.PoseOverlayView
import com.sai.sports.ui.common.Labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Shows the provisional score for a completed attempt, with a skeleton replay.
 *
 * The replay is not decoration. When the count is not what the athlete expected,
 * the only useful answer is showing them what the app actually saw — and it is
 * the same view an SAI official gets in the Sprint 8 dashboard.
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
    reviewing: Boolean = false
) {

    val context = LocalContext.current
    val store = remember { AttemptStore(context) }

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

    val current = loaded

    if (current == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.loading))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        if (current.attempt.mode == AttemptMode.PRACTICE) {
            PracticeLabel()
        }

        ScoreCard(current.attempt.result)

        current.comparison?.let { PracticeProgressCard(it, current.attempt) }

        if (current.frames.isNotEmpty()) {
            SkeletonReplay(
                frames = current.frames,
                imageWidth = current.attempt.imageWidth,
                imageHeight = current.attempt.imageHeight
            )
        } else if (current.attempt.mode == AttemptMode.PRACTICE) {
            // Practice from the athlete's other phone: the result came with
            // their account, the skeleton did not.
            Text(
                text = stringResource(R.string.practice_no_replay),
                style = MaterialTheme.typography.bodySmall
            )
        }

        FormSummary.from(current.attempt.result.events)?.let { summary ->
            FormCard(summary, current.attempt.testType)
        }

        QualityCard(current.attempt.result)

        if (current.attempt.result.events.isNotEmpty()) {
            EventsCard(current.attempt.result)
        }

        if (current.attempt.mode == AttemptMode.PRACTICE) {
            Text(
                text = stringResource(R.string.practice_notice),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            ProvisionalNotice()
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // A scored official session attempt used that test's one
            // submission: there is nothing to try again. An unscored one was
            // never queued, so the athlete may still go.
            val usedSubmission = current.attempt.sessionId != null &&
                current.attempt.mode == AttemptMode.OFFICIAL &&
                current.attempt.result.status == AttemptStatus.COMPLETE

            if (reviewing || !usedSubmission) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(if (reviewing) R.string.action_back else R.string.action_try_again))
                }
            }

            Button(
                onClick = onDone,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun ScoreCard(result: AnalyzerResult) {

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text = stringResource(Labels.testName(result.testType)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            if (result.status == AttemptStatus.COMPLETE) {

                Text(
                    text = result.formattedScore(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(Labels.unit(result.unit)),
                    style = MaterialTheme.typography.titleMedium
                )

            } else {

                Text(
                    text = stringResource(R.string.result_not_scored),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )

                Text(
                    text = stringResource(Labels.invalidReason(result.invalidReason)),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
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

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text = stringResource(R.string.result_replay_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(safeWidth.toFloat() / safeHeight.toFloat())
                    .background(Color.Black)
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

                OutlinedButton(
                    onClick = {
                        if (frameIndex >= frames.lastIndex) {
                            frameIndex = 0
                        }
                        isPlaying = !isPlaying
                    }
                ) {
                    Text(
                        stringResource(if (isPlaying) R.string.action_pause else R.string.action_play)
                    )
                }

                Text(
                    text = stringResource(R.string.result_frame_counter, frameIndex + 1, frames.size),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun QualityCard(result: AnalyzerResult) {

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text = stringResource(R.string.result_tracking_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )

            LinearProgressIndicator(
                progress = { result.confidence.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(
                    R.string.result_tracking_detail,
                    (result.confidence * 100).toInt(),
                    result.framesAnalyzed,
                    result.framesRejected
                ),
                style = MaterialTheme.typography.bodySmall
            )

            if (result.framesRejected > result.framesAnalyzed) {
                Text(
                    text = stringResource(R.string.result_tracking_poor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
            )
        )
        if (summary.partialReps > 0) add(
            stringResource(
                if (sitUps) R.string.summary_partial_reps else R.string.summary_partial_reps_generic,
                summary.partialReps
            )
        )
        if (summary.tooFastReps > 0) add(stringResource(R.string.summary_too_fast_reps, summary.tooFastReps))
        if (summary.formRejectedReps > 0) add(stringResource(R.string.summary_form_rejected, summary.formRejectedReps))
        if (summary.formWarnings > 0) add(stringResource(R.string.summary_form_warnings, summary.formWarnings))
        if (summary.wrongArmReps > 0) add(stringResource(R.string.summary_wrong_arm, summary.wrongArmReps))
        if (summary.jumpsMeasured > 0) add(stringResource(R.string.summary_jumps_measured, summary.jumpsMeasured))
        if (summary.jumpsRejected > 0) add(stringResource(R.string.summary_jumps_rejected, summary.jumpsRejected))
        if (summary.trackingLost > 0) add(stringResource(R.string.summary_tracking_lost, summary.trackingLost))
    }

    if (lines.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            Text(
                text = stringResource(R.string.result_summary_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )

            lines.forEach { line ->
                Text(text = "• $line", style = MaterialTheme.typography.bodySmall)
            }
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

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            Text(
                text = stringResource(R.string.form_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )

            Text(
                text = "${summary.scorePercent}%",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = stringResource(R.string.form_good_reps, summary.goodReps, summary.attemptedReps),
                style = MaterialTheme.typography.bodyMedium
            )

            if (summary.toWorkOn.isEmpty()) {
                Text(
                    text = stringResource(R.string.form_all_good),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                summary.toWorkOn.forEach { cue ->
                    Text(
                        text = stringResource(R.string.form_work_on, stringResource(Labels.cue(cue, testType))),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * The system's first invariant, said out loud to the athlete.
 *
 * They should never be surprised when the official number differs from what
 * their phone showed.
 */
@Composable
private fun ProvisionalNotice() {

    Text(
        text = stringResource(R.string.result_provisional_notice),
        style = MaterialTheme.typography.bodySmall
    )
}

/** Says "practice" in words, not only colour, so it survives a screen reader and bright sun. */
@Composable
private fun PracticeLabel() {
    Text(
        text = stringResource(R.string.practice_badge),
        style = MaterialTheme.typography.labelLarge,
        color = Color.Black,
        modifier = Modifier
            .background(Color(0xFFFFC107), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

/**
 * Progress, in the athlete's terms: a personal best, or how this attempt
 * compares with their best and their last one.
 */
@Composable
private fun PracticeProgressCard(comparison: PracticeComparison, attempt: Attempt) {

    val unit = stringResource(Labels.unit(attempt.result.unit))

    fun format(value: Double) =
        if (attempt.testType.countsReps) value.toInt().toString()
        else String.format(Locale.getDefault(), "%.1f", value)

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            when {
                comparison.isFirstScored -> Text(
                    text = stringResource(R.string.practice_first_best),
                    style = MaterialTheme.typography.titleMedium
                )

                comparison.isPersonalBest -> Text(
                    text = stringResource(R.string.practice_new_best),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
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
}

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

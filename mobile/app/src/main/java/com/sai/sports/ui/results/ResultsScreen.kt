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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.PoseFrame
import com.sai.sports.data.Attempt
import com.sai.sports.data.AttemptStore
import com.sai.sports.ui.capture.PoseOverlayView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    onDone: () -> Unit
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
                    frames = store.loadSequence(attemptId)
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
            Text("Loading result…")
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

        ScoreCard(current.attempt.result)

        if (current.frames.isNotEmpty()) {
            SkeletonReplay(
                frames = current.frames,
                imageWidth = current.attempt.imageWidth,
                imageHeight = current.attempt.imageHeight
            )
        }

        QualityCard(current.attempt.result)

        if (current.attempt.result.events.isNotEmpty()) {
            EventsCard(current.attempt.result)
        }

        ProvisionalNotice()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            ) {
                Text("Try again")
            }

            Button(
                onClick = onDone,
                modifier = Modifier.weight(1f)
            ) {
                Text("Done")
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
                text = result.testType.displayName,
                style = MaterialTheme.typography.titleMedium
            )

            if (result.status == AttemptStatus.COMPLETE) {

                Text(
                    text = result.formattedScore(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = result.unit,
                    style = MaterialTheme.typography.titleMedium
                )

            } else {

                Text(
                    text = "Attempt not scored",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )

                Text(
                    text = result.invalidReason ?: "Unknown problem",
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
                text = "What the app saw",
                style = MaterialTheme.typography.titleSmall
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(safeWidth.toFloat() / safeHeight.toFloat())
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
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
                    Text(if (isPlaying) "Pause" else "Play")
                }

                Text(
                    text = "Frame ${frameIndex + 1} / ${frames.size}",
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
                text = "Tracking quality",
                style = MaterialTheme.typography.titleSmall
            )

            LinearProgressIndicator(
                progress = { result.confidence.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "${(result.confidence * 100).toInt()}% — " +
                    "${result.framesAnalyzed} frames used, " +
                    "${result.framesRejected} skipped",
                style = MaterialTheme.typography.bodySmall
            )

            if (result.framesRejected > result.framesAnalyzed) {
                Text(
                    text = "Most frames were unusable. Move further back so your " +
                        "whole body is in shot, and make sure the area is well lit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EventsCard(result: AnalyzerResult) {

    Card(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            Text(
                text = "Attempt log",
                style = MaterialTheme.typography.titleSmall
            )

            result.events.forEach { event ->
                Text(
                    text = "• ${event.label.replace('_', ' ')}" +
                        if (event.detail.isNotEmpty()) " — ${event.detail}" else "",
                    style = MaterialTheme.typography.bodySmall
                )
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
        text = "This is a provisional score calculated on your phone. " +
            "Your official result is confirmed after SAI verifies the recording.",
        style = MaterialTheme.typography.bodySmall
    )
}

private data class LoadedAttempt(
    val attempt: Attempt,
    val frames: List<PoseFrame>
)

private const val MIN_FRAME_DELAY_MS = 16L
private const val MAX_FRAME_DELAY_MS = 200L

/** Portrait 3:4, matching the default ImageAnalysis output the pose model receives. */
private const val DEFAULT_FRAME_WIDTH = 480
private const val DEFAULT_FRAME_HEIGHT = 640

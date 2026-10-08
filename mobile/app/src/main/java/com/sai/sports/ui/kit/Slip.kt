package com.sai.sports.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sai.sports.ui.theme.TrialsColors

/**
 * The tear-off acknowledgement slip an office hands back when a form is
 * submitted. Holds a submitted result and where it has got to.
 */
@Composable
fun Slip(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides TrialsColors.Ink) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(TrialsColors.Paper, PerforatedShape)
                .border(1.dp, TrialsColors.Ink, PerforatedShape)
                .drawBehind {
                    // "Tear here."
                    val y = size.height - 18.dp.toPx()
                    drawLine(
                        color = TrialsColors.Line,
                        start = Offset(12.dp.toPx(), y),
                        end = Offset(size.width - 12.dp.toPx(), y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    )
                }
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

/** A rectangle whose bottom edge is a row of perforation notches. */
private object PerforatedShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 5.dp.toPx() }
        val period = with(density) { 18.dp.toPx() }
        val notches = ((size.width - period) / period).toInt().coerceAtLeast(1)
        val margin = (size.width - (notches - 1) * period) / 2

        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            for (i in notches - 1 downTo 0) {
                val cx = margin + i * period
                lineTo(cx + radius, size.height)
                arcTo(
                    rect = Rect(cx - radius, size.height - radius, cx + radius, size.height + radius),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = -180f,
                    forceMoveTo = false
                )
            }
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Where a result stands on its way to becoming official. */
enum class StepState { Done, Active, Waiting, Refused }

/**
 * One line of the slip: a marker on a running line, what the step is, and
 * when it happened or what it is waiting for.
 */
@Composable
fun SlipStep(
    state: StepState,
    title: String,
    detail: String?,
    last: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier.width(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StepMarker(state)
            if (!last) {
                Box(
                    Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(if (state == StepState.Done) TrialsColors.Ink else TrialsColors.Rule)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp, bottom = if (last) 0.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (state == StepState.Waiting) TrialsColors.Pencil else TrialsColors.Ink
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == StepState.Refused) TrialsColors.RedPen else TrialsColors.Pencil
                )
            }
        }
    }
}

@Composable
private fun StepMarker(state: StepState) {
    val size = Modifier.size(26.dp)
    when (state) {
        StepState.Done -> Box(size.background(TrialsColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
            Icon(TrialsIcons.Check, contentDescription = null, tint = TrialsColors.Paper, modifier = Modifier.size(15.dp))
        }
        StepState.Active -> Box(size.border(2.dp, TrialsColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(TrialsColors.Ink, CircleShape)
            )
        }
        StepState.Waiting -> Box(size.dashedBorder(1.5.dp, TrialsColors.Pencil, radius = 13.dp, dash = 3.dp, gap = 3.dp))
        StepState.Refused -> Box(size.background(TrialsColors.RedPen, CircleShape), contentAlignment = Alignment.Center) {
            Icon(TrialsIcons.Cross, contentDescription = null, tint = TrialsColors.Paper, modifier = Modifier.size(15.dp))
        }
    }
}

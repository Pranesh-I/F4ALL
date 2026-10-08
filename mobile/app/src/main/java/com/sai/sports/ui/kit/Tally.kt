package com.sai.sports.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.theme.TrialsColors

/**
 * Reps as a coach counts them on paper: four strokes and a fifth across.
 *
 * Readable from across the room and in any language — an athlete who cannot
 * read the number can still see the gates fill up. Reps that did not count are
 * a red-pen cross after the tally. The newest stroke draws itself in, so a
 * glance mid-set shows the rep was taken.
 */
@Composable
fun TallyMarks(
    counted: Int,
    missed: Int,
    modifier: Modifier = Modifier,
    color: Color = TrialsColors.Paper,
    missedColor: Color = TrialsColors.RedPenOnInk,
    markHeight: Dp = 40.dp,
    maxRows: Int = 2
) {
    val description = stringResource(R.string.tally_description, counted, missed)
    val newest = remember { Animatable(1f) }

    LaunchedEffect(counted) {
        if (counted > 0) {
            newest.snapTo(0f)
            newest.animateTo(1f, tween(durationMillis = 220))
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        val density = LocalDensity.current
        val available = with(density) { maxWidth.toPx() }

        // A long set shrinks the marks rather than covering the camera view.
        var plan = TallyPlan.of(counted, missed, available, with(density) { markHeight.toPx() }, density.density)
        if (plan.rows > maxRows) {
            plan = TallyPlan.of(counted, missed, available, with(density) { (markHeight * 0.65f).toPx() }, density.density)
        }
        val height = with(density) { plan.height.toDp() }

        Canvas(Modifier.fillMaxWidth().height(height)) {
            val stroke = plan.stroke
            plan.items.forEach { item ->
                when (item) {
                    is TallyPlan.Item.Gate -> {
                        for (mark in 0 until item.marks) {
                            val rep = item.firstRep + mark
                            val progress = if (rep == counted - 1) newest.value else 1f
                            if (mark < 4) {
                                // A hand-drawn lean that differs a little per stroke.
                                val lean = (((rep * 37) % 5) - 2) * 0.35f * density.density
                                val x = item.x + plan.inset + mark * plan.spacing
                                val top = Offset(x + lean, item.y + stroke)
                                val bottom = Offset(x, item.y + plan.markHeight - stroke)
                                drawLine(color, bottom, lerp(bottom, top, progress), stroke, StrokeCap.Round)
                            } else {
                                val start = Offset(item.x, item.y + plan.markHeight * 0.78f)
                                val end = Offset(item.x + plan.gateWidth, item.y + plan.markHeight * 0.24f)
                                drawLine(color, start, lerp(start, end, progress), stroke, StrokeCap.Round)
                            }
                        }
                    }
                    is TallyPlan.Item.Miss -> {
                        val size = plan.markHeight * 0.5f
                        val left = item.x
                        val top = item.y + (plan.markHeight - size) / 2
                        drawLine(missedColor, Offset(left, top), Offset(left + size, top + size), stroke, StrokeCap.Round)
                        drawLine(missedColor, Offset(left + size, top), Offset(left, top + size), stroke, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

private fun lerp(from: Offset, to: Offset, fraction: Float) =
    Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)

/** Where every gate and cross goes, flowing left to right and wrapping. */
private class TallyPlan(
    val items: List<Item>,
    val rows: Int,
    val height: Float,
    val markHeight: Float,
    val stroke: Float,
    val spacing: Float,
    val inset: Float,
    val gateWidth: Float
) {
    sealed interface Item {
        val x: Float
        val y: Float

        data class Gate(override val x: Float, override val y: Float, val firstRep: Int, val marks: Int) : Item
        data class Miss(override val x: Float, override val y: Float) : Item
    }

    companion object {
        fun of(counted: Int, missed: Int, width: Float, markHeight: Float, density: Float): TallyPlan {
            val scale = markHeight / (40f * density)
            val stroke = 4f * density * scale
            val spacing = 9f * density * scale
            val inset = 4f * density * scale
            val gateWidth = inset * 2 + spacing * 3
            val gap = 16f * density * scale
            val missWidth = markHeight * 0.5f
            val rowGap = 10f * density * scale

            val items = mutableListOf<Item>()
            var x = 0f
            var row = 0
            fun place(itemWidth: Float): Pair<Float, Float> {
                if (x > 0f && x + itemWidth > width) {
                    x = 0f
                    row++
                }
                val position = x to row * (markHeight + rowGap)
                x += itemWidth + gap
                return position
            }

            var rep = 0
            while (rep < counted) {
                val marks = minOf(5, counted - rep)
                val (gx, gy) = place(gateWidth)
                items += Item.Gate(gx, gy, rep, marks)
                rep += marks
            }
            repeat(missed) {
                val (mx, my) = place(missWidth)
                items += Item.Miss(mx, my)
            }

            val rows = if (items.isEmpty()) 0 else row + 1
            val height = if (rows == 0) 0f else rows * markHeight + (rows - 1) * rowGap
            return TallyPlan(items, rows, height, markHeight, stroke, spacing, inset, gateWidth)
        }
    }
}

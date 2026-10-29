package com.sai.sports.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.sai.sports.ui.theme.AppColors
import com.sai.sports.ui.theme.AppFonts
import java.util.Locale

/**
 * One row of a list: a label, and its value on the same baseline at the
 * right. The whole row is the touch target when [onClick] is set.
 *
 * @param index a lane number ("01") in front of the label, for ordered lists.
 * @param detail a second, quieter line under the label.
 * @param valueColor grey for provisional numbers, white for checked ones.
 */
@Composable
fun RegisterRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    index: Int? = null,
    detail: String? = null,
    detailColor: Color = AppColors.TextSecondary,
    valueColor: Color = LocalContentColor.current,
    labelStyle: TextStyle = MaterialTheme.typography.titleSmall,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .heightIn(min = 56.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            index?.let { LaneNumber(it) }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (value == null) {
                    Text(label, style = labelStyle)
                } else {
                    LeaderLine(
                        label = { Text(label, style = labelStyle) },
                        value = { Text(value, style = valueStyle, color = valueColor) }
                    )
                }
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = detailColor)
                }
            }

            trailing?.invoke()

            if (onClick != null) {
                Icon(
                    AppIcons.Forward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (divider) RuleLine()
    }
}

/** "01", "02" — a lane number, in the chest-number face. */
@Composable
fun LaneNumber(number: Int, color: Color = AppColors.TextSecondary) {
    Text(
        text = String.format(Locale.US, "%02d", number),
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = AppFonts.Numerals),
        color = color,
        modifier = Modifier.width(30.dp)
    )
}

/**
 * Lays out a label and a value on a shared baseline with dots between them.
 *
 * The label wraps rather than pushing the value off the edge; the dots sit on
 * the label's last line, the way a hand-ruled leader would.
 */
@Composable
fun LeaderLine(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color = Color.Transparent
) {
    Layout(
        modifier = modifier,
        content = {
            label()
            Canvas(Modifier) {
                val step = 5.dp.toPx()
                val radius = 1.dp.toPx()
                var x = radius
                while (x < size.width - radius) {
                    drawCircle(dotColor, radius, Offset(x, size.height / 2))
                    x += step
                }
            }
            value()
        }
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val minLeader = 16.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        val valuePlaceable = measurables[2].measure(loose)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val labelMax = if (width == Constraints.Infinity) Constraints.Infinity
            else (width - valuePlaceable.width - 2 * gap - minLeader).coerceAtLeast(0)
        val labelPlaceable = measurables[0].measure(loose.copy(maxWidth = labelMax))

        val total = if (width == Constraints.Infinity) {
            labelPlaceable.width + valuePlaceable.width + 2 * gap + minLeader
        } else {
            width
        }
        val leaderWidth = (total - labelPlaceable.width - valuePlaceable.width - 2 * gap).coerceAtLeast(0)
        val leaderPlaceable = measurables[1].measure(Constraints.fixed(leaderWidth, 2.dp.roundToPx()))

        fun baselineOf(line: Int, height: Int) = if (line == AlignmentLine.Unspecified) height else line
        val labelBaseline = baselineOf(labelPlaceable[LastBaseline], labelPlaceable.height)
        val valueBaseline = baselineOf(valuePlaceable[LastBaseline], valuePlaceable.height)
        val baseline = maxOf(labelBaseline, valueBaseline)
        val labelY = baseline - labelBaseline
        val valueY = baseline - valueBaseline
        val height = maxOf(labelY + labelPlaceable.height, valueY + valuePlaceable.height)

        layout(total, height) {
            labelPlaceable.place(0, labelY)
            leaderPlaceable.place(labelPlaceable.width + gap, baseline - leaderPlaceable.height - 2.dp.roundToPx())
            valuePlaceable.place(total - valuePlaceable.width, valueY)
        }
    }
}

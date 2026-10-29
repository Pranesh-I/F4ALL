package com.sai.sports.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sai.sports.ui.theme.AppColors
import java.util.Locale

/**
 * How final a score is, told by the tool it is written with — the way a
 * trials scoresheet is filled in.
 */
enum class ScoreInk {
    /** The phone's own count: grey, in a dashed box. Provisional. */
    Pencil,

    /** SAI's re-measurement: white. Checked, not yet official. */
    Pen,

    /** Approved by an official: white, and sealed (see [Stamp]). */
    Official
}

/**
 * A score, written in the ink its status deserves.
 *
 * [struck] crosses the number out — a pencil count the server corrected, or a
 * result that was not accepted — in [strikeColor].
 */
@Composable
fun ScoreMark(
    value: String,
    unit: String,
    ink: ScoreInk,
    modifier: Modifier = Modifier,
    large: Boolean = true,
    struck: Boolean = false,
    strikeColor: Color = AppColors.Danger
) {
    val numberColor = if (ink == ScoreInk.Pencil) AppColors.TextSecondary else AppColors.TextPrimary
    val numberStyle = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displaySmall
    val boxed = ink == ScoreInk.Pencil

    Row(
        modifier = modifier
            .then(if (boxed) Modifier.dashedBorder(1.5.dp, AppColors.TextSecondary, radius = 12.dp) else Modifier)
            .padding(horizontal = if (boxed) 14.dp else 0.dp, vertical = if (boxed) 4.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = value,
            style = numberStyle,
            color = numberColor,
            modifier = Modifier
                .alignByBaseline()
                .then(if (struck) Modifier.strikeThrough(strikeColor) else Modifier)
        )
        if (unit.isNotBlank()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = numberColor,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/**
 * The server's correction of the phone's count: the pencil number crossed
 * out, the measured one written beside it in pen. Shows at a glance that SAI's
 * number is the one that counts.
 */
@Composable
fun ScoreCorrection(
    pencil: String,
    pen: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScoreMark(value = pencil, unit = "", ink = ScoreInk.Pencil, large = false, struck = true)
        ScoreMark(value = pen, unit = unit, ink = ScoreInk.Pen)
    }
}

/** A hand-ruled line through a number, rising slightly as a pen stroke does. */
fun Modifier.strikeThrough(color: Color): Modifier = drawWithContent {
    drawContent()
    val y = size.height * 0.55f
    drawLine(
        color = color,
        start = Offset(-4.dp.toPx(), y + 3.dp.toPx()),
        end = Offset(size.width + 4.dp.toPx(), y - 3.dp.toPx()),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/**
 * The official seal: an accent-bordered badge with a check. Used for an
 * official result and for nothing else, so this badge always means "SAI has
 * approved this".
 *
 * With [animate], it lands with a small spring the moment an athlete first
 * sees an approval.
 */
@Composable
fun Stamp(
    text: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    animate: Boolean = false
) {
    val press = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) {
            press.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val spoken = listOfNotNull(text, caption).joinToString(", ")
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .graphicsLayer {
                val p = press.value
                val scale = 1.4f - 0.4f * p
                scaleX = scale
                scaleY = scale
                alpha = p.coerceIn(0f, 1f)
            }
            .background(AppColors.AccentSoft, shape)
            .border(1.5.dp, AppColors.Accent, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(26.dp)
                .background(AppColors.Accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Check, contentDescription = null, tint = AppColors.OnAccent, modifier = Modifier.size(16.dp))
        }
        Column {
            Text(
                text = text.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = AppColors.Accent
            )
            caption?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
            }
        }
    }
}

package com.sai.sports.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.theme.AppColors
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A 48dp round button with one icon: back, notifications, settings. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Boolean = false
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(AppColors.SurfaceHigh)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(22.dp))
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 11.dp, end = 12.dp)
                    .size(9.dp)
                    .background(AppColors.Accent, CircleShape)
            )
        }
    }
}

/**
 * Two or three options, one selected: a dark track with the chosen option as
 * a white pill. Each option is a radio button to a screen reader.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(16.dp))
            .padding(5.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val chosen = option == selected
            val fill by animateColorAsState(if (chosen) AppColors.Inverse else Color.Transparent, label = "segment")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fill)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (chosen) AppColors.OnInverse else AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** One fact in a row of three: an icon, a value, a label underneath. */
@Composable
fun RowScope.StatTile(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Column(
        modifier = modifier
            .weight(1f)
            .heightIn(min = 96.dp)
            .background(AppColors.Surface, ControlShape)
            .padding(horizontal = 10.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(22.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        label?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/** A row of [StatTile]s. */
@Composable
fun StatRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

enum class TagTone { Accent, Neutral, Success, Danger }

/** A small label on a tinted fill: "Official", "Practice", "Verified". */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.Accent,
    icon: ImageVector? = null
) {
    val (fill, ink) = when (tone) {
        TagTone.Accent -> AppColors.AccentSoft to AppColors.Accent
        TagTone.Neutral -> AppColors.SurfaceHigh to AppColors.TextSecondary
        TagTone.Success -> AppColors.SuccessSoft to AppColors.Success
        TagTone.Danger -> AppColors.DangerSoft to AppColors.Danger
    }
    Row(
        modifier = modifier
            .background(fill, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = ink)
    }
}

/** A ring that fills to [fraction], with [label] in the middle. */
@Composable
fun ProgressRing(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 5.dp,
    color: Color = AppColors.Accent
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(fraction) { sweep.animateTo(fraction.coerceIn(0f, 1f), tween(700)) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = Size(this.size.width - width, this.size.height - width)
            drawArc(
                AppColors.SurfaceHighest, 0f, 360f, false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width)
            )
            drawArc(
                color, -90f, 360f * sweep.value, false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width, cap = StrokeCap.Round)
            )
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
    }
}

/** A person as their initials in a circle: no photographs of children in lists. */
@Composable
fun Avatar(
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    highlight: Boolean = false
) {
    val initials = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .take(2).joinToString("") { it.first().uppercase(Locale.getDefault()) }.ifEmpty { "·" }
    Box(
        modifier = modifier
            .size(size)
            .background(
                Brush.linearGradient(
                    if (highlight) {
                        listOf(AppColors.Accent, Color(0xFFB8340A))
                    } else {
                        listOf(AppColors.SurfaceHighest, AppColors.SurfaceHigh)
                    }
                ),
                CircleShape
            )
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) AppColors.OnAccent else AppColors.TextPrimary
        )
    }
}

/** A section title with an optional "View all" action on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        if (action != null && onAction != null) {
            Text(
                action.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 14.dp)
            )
        }
    }
}

/** A small uppercase line above a title: a date, a category. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = AppColors.TextSecondary) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
    )
}

/**
 * The finish: an orange disc with a check, over rays that turn slowly into
 * place. Shown once, when something is done (a test recorded, an account
 * made).
 */
@Composable
fun SuccessBurst(modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val grow = remember { Animatable(0.6f) }
    val turn = remember { Animatable(-12f) }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) { turn.animateTo(0f, tween(900)) }

    Box(modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(this.size.width / 2, this.size.height / 2)
            val radius = min(this.size.width, this.size.height) / 2
            rotate(turn.value, centre) {
                val rays = 16
                repeat(rays) { i ->
                    val angle = Math.toRadians(i * 360.0 / rays)
                    val half = Math.toRadians(360.0 / rays / 4)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(centre.x, centre.y)
                        lineTo(
                            centre.x + (radius * cos(angle - half)).toFloat(),
                            centre.y + (radius * sin(angle - half)).toFloat()
                        )
                        lineTo(
                            centre.x + (radius * cos(angle + half)).toFloat(),
                            centre.y + (radius * sin(angle + half)).toFloat()
                        )
                        close()
                    }
                    drawPath(
                        path,
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                            center = centre,
                            radius = radius
                        )
                    )
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = grow.value
                    scaleY = grow.value
                }
                .size(size * 0.36f)
                .background(AppColors.Accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Check, contentDescription = null, tint = AppColors.Inverse, modifier = Modifier.size(size * 0.17f))
        }
    }
}

/**
 * A dark panel lit from one corner in the accent: the stand-in for a photo
 * behind a hero, so a screen has warmth without stock pictures of athletes.
 */
@Composable
fun GlowBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(AppColors.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(AppColors.Accent.copy(alpha = 0.55f), AppColors.Accent.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.05f),
                        radius = size.maxDimension * 0.9f
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, AppColors.Background),
                        startY = size.height * 0.45f,
                        endY = size.height
                    )
                )
            },
        content = content
    )
}

/** "Step 2 of 5" over a strip of segments, for a flow of several screens. */
@Composable
fun StepBar(step: Int, steps: Int, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.onboarding_step, step, steps)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(steps) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(
                            if (index < step) AppColors.Accent else AppColors.SurfaceHighest,
                            RoundedCornerShape(3.dp)
                        )
                )
            }
        }
        Eyebrow(spoken)
    }
}

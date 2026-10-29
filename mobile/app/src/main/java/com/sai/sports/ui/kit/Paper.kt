package com.sai.sports.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.theme.AppColors
import java.util.Locale

/** What a [Sheet] holds, which decides how it looks. */
enum class SheetStyle {
    /** An ordinary card. */
    Paper,

    /** Practice: a dashed edge, because it never counts. */
    Rough,

    /** Anything official: an orange edge and a warm glow from one corner. */
    Field,

    /** Something the athlete must act on, in the danger colour. */
    Note
}

/**
 * The basic surface: a rounded card one step lighter than the screen. Depth
 * comes from the step in colour, not from shadows.
 */
@Composable
fun Sheet(
    modifier: Modifier = Modifier,
    style: SheetStyle = SheetStyle.Paper,
    padding: PaddingValues = PaddingValues(18.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val background = if (style == SheetStyle.Note) AppColors.DangerSoft else AppColors.Surface
    val edge = when (style) {
        SheetStyle.Paper -> Modifier
        SheetStyle.Note -> Modifier.border(1.dp, AppColors.Danger.copy(alpha = 0.6f), SheetShape)
        SheetStyle.Rough -> Modifier.dashedBorder(1.dp, AppColors.SurfaceHighest, radius = 20.dp)
        SheetStyle.Field -> Modifier
            .officialGlow()
            .border(1.dp, AppColors.Accent.copy(alpha = 0.45f), SheetShape)
    }

    CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(background, SheetShape)
                .then(edge)
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

/** The warm corner glow that marks anything official. */
fun Modifier.officialGlow(): Modifier = drawBehind {
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(AppColors.Accent.copy(alpha = 0.28f), Color.Transparent),
            center = Offset(size.width, 0f),
            radius = size.maxDimension * 0.75f
        ),
        cornerRadius = CornerRadius(20.dp.toPx())
    )
}

/** A dashed edge, for practice and provisional numbers. */
fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    radius: Dp = 14.dp,
    dash: Dp = 6.dp,
    gap: Dp = 5.dp
): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()))
        )
    )
}

/**
 * Every screen's frame: the background colour, clear of the status bar, the
 * gesture bar and the keyboard (Android 15 draws apps edge to edge, so nothing
 * else keeps content out from under them).
 *
 * [header] stays put while the body scrolls; [footer] sits on the bottom edge.
 * Pass [scrollable] = false when the body holds its own lazy list.
 */
@Composable
fun Page(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            header?.invoke()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content
            )
            footer?.invoke()
        }
    }
}

/**
 * A screen's title, with the way back as a round button.
 *
 * Back is a chevron everyone recognises, named "Back" for screen readers.
 */
@Composable
fun TitleBar(
    title: String,
    onBack: (() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (onBack != null) {
            RoundIconButton(
                icon = AppIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack
            )
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.TextPrimary,
            maxLines = 2,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        trailing?.invoke(this)
    }
}

/** A quiet divider under a header. */
@Composable
fun LaneLines(modifier: Modifier = Modifier, color: Color = AppColors.Divider) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/**
 * A section heading: a sentence-case title, with an optional short value or
 * "View all" in the accent on the right. Announced as a heading, so
 * screen-reader users can jump between them.
 */
@Composable
fun SectionRule(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    color: Color = AppColors.TextPrimary
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        trailing?.let {
            Text(
                text = it.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.Accent
            )
        }
    }
}

/** A hairline between rows. */
@Composable
fun RuleLine(modifier: Modifier = Modifier, color: Color = AppColors.Divider) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

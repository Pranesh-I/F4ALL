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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.theme.TrialsColors
import java.util.Locale

/** What a [Sheet] is: the four kinds of paper on a trials day. */
enum class SheetStyle {
    /** A printed form: white, ruled in ink. */
    Paper,

    /** Rough work: practice. Dashed pencil edge — it never counts. */
    Rough,

    /** The floodlit field: anything official. Ink, with paper-white text. */
    Field,

    /** A correction in red pen: something the athlete must act on. */
    Note
}

/**
 * The basic surface. Replaces Material cards: no shadows, no rounded pills —
 * a sheet of paper with a ruled edge, so a screen reads like a scoresheet.
 */
@Composable
fun Sheet(
    modifier: Modifier = Modifier,
    style: SheetStyle = SheetStyle.Paper,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val background = if (style == SheetStyle.Field) TrialsColors.Ink else TrialsColors.Paper
    val contentColor = if (style == SheetStyle.Field) TrialsColors.Paper else TrialsColors.Ink
    val edge = when (style) {
        SheetStyle.Paper -> Modifier.border(1.dp, TrialsColors.Ink, SheetShape)
        SheetStyle.Note -> Modifier.border(1.5.dp, TrialsColors.RedPen, SheetShape)
        SheetStyle.Rough -> Modifier.dashedBorder(1.5.dp, TrialsColors.Pencil)
        SheetStyle.Field -> Modifier
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
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

/** A pencil-dashed edge, for rough work and provisional numbers. */
fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    radius: Dp = 2.dp,
    dash: Dp = 6.dp,
    gap: Dp = 4.dp
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
 * Every screen's frame: the ground colour, clear of the status bar, the
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrialsColors.Ground)
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

/**
 * A screen's title, with the way back, over a pair of lane lines.
 *
 * Back is an arrow everyone recognises, named "Back" for screen readers.
 */
@Composable
fun TitleBar(
    title: String,
    onBack: (() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(TrialsIcons.Back, contentDescription = stringResource(R.string.action_back))
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .semantics { heading() }
            )
            trailing?.invoke(this)
        }
        LaneLines()
    }
}

/** A thick and a thin rule: the lane markings on the ground, used as a header edge. */
@Composable
fun LaneLines(modifier: Modifier = Modifier, color: Color = TrialsColors.Ink) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(color)
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color)
        )
    }
}

/**
 * A section heading as a scoresheet prints it: a short capitalised label over
 * a rule. Announced as a heading, so screen-reader users can jump between them.
 */
@Composable
fun SectionRule(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    color: Color = LocalContentColor.current
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = title.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            trailing?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(color)
        )
    }
}

/** A hairline between register rows. */
@Composable
fun RuleLine(modifier: Modifier = Modifier, color: Color = TrialsColors.Rule) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

package com.sai.sports.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sai.sports.analyzer.TestType
import com.sai.sports.ui.instructions.TestGuides
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.theme.AppColors

/**
 * A test's picture: its movement drawing (the end position, where the effort
 * is) on a dark tile lit from the corner, with a play mark when it opens the
 * test. Field runs have no drawing; they show a timer bolt instead.
 *
 * Decorative: the test's name beside it carries the meaning.
 */
@Composable
fun TestArt(
    code: String,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    play: Boolean = true
) {
    val camera = runCatching { TestType.valueOf(code) }.getOrNull()
    Box(
        modifier = modifier
            .size(size)
            .background(AppColors.SurfaceHigh, RoundedCornerShape(16.dp))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(AppColors.Accent.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(this.size.width, 0f),
                        radius = this.size.maxDimension
                    )
                )
            }
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        if (camera != null) {
            Image(
                painter = painterResource(TestGuides.forType(camera).diagrams.last().first),
                contentDescription = null,
                colorFilter = ColorFilter.tint(AppColors.TextPrimary),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.14f)
            )
        } else {
            Icon(AppIcons.Bolt, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(size * 0.4f))
        }
        if (play) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(size * 0.3f)
                    .background(AppColors.Inverse, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Play, contentDescription = null, tint = AppColors.OnInverse, modifier = Modifier.size(size * 0.14f))
            }
        }
    }
}

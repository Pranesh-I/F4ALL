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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sai.sports.ui.theme.AppColors

/**
 * The receipt for a submitted result: a card that holds the result and the
 * steps it is going through on its way to becoming official.
 */
@Composable
fun Slip(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(AppColors.Surface, SheetShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
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
                        .background(if (state == StepState.Done) AppColors.Accent else AppColors.SurfaceHighest)
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
                color = if (state == StepState.Waiting) AppColors.TextSecondary else AppColors.TextPrimary
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == StepState.Refused) AppColors.Danger else AppColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StepMarker(state: StepState) {
    val size = Modifier.size(26.dp)
    when (state) {
        StepState.Done -> Box(size.background(AppColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Check, contentDescription = null, tint = AppColors.OnAccent, modifier = Modifier.size(15.dp))
        }
        StepState.Active -> Box(size.border(2.dp, AppColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(AppColors.Accent, CircleShape)
            )
        }
        StepState.Waiting -> Box(size.dashedBorder(1.5.dp, AppColors.TextSecondary, radius = 13.dp, dash = 3.dp, gap = 3.dp))
        StepState.Refused -> Box(size.background(AppColors.Danger, CircleShape), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Cross, contentDescription = null, tint = AppColors.OnInverse, modifier = Modifier.size(15.dp))
        }
    }
}

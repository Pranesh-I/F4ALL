package com.sai.sports.ui.kit

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.theme.AppColors

/** The steps of an official test, in the order the athlete meets them. */
enum class OfficialStep(@StringRes val label: Int) {
    IDENTITY(R.string.flow_identity),
    GESTURE(R.string.flow_gesture),
    COUNTDOWN(R.string.flow_countdown),
    TEST(R.string.flow_test),
    UPLOAD(R.string.flow_upload)
}

object OfficialFlow {
    /** Camera tests start with the hand signal; field tests have none. */
    fun steps(fieldTest: Boolean): List<OfficialStep> =
        if (fieldTest) OfficialStep.entries.filter { it != OfficialStep.GESTURE } else OfficialStep.entries

    /** "Step 2 of 5": the position of [current] in [steps], from 1. */
    fun position(steps: List<OfficialStep>, current: OfficialStep): Int = steps.indexOf(current) + 1
}

/**
 * Where the athlete is in an official test: a strip of segments, the done
 * ones white, the current one orange, the rest dark, with the current step
 * named. Read aloud as one sentence: "Step 2 of 5: hand signal".
 */
@Composable
fun FlowProgress(current: OfficialStep, modifier: Modifier = Modifier, fieldTest: Boolean = false) {
    val steps = OfficialFlow.steps(fieldTest)
    val position = OfficialFlow.position(steps, current)
    val label = stringResource(current.label)
    val spoken = stringResource(R.string.flow_step_of, position, steps.size, label)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            steps.forEachIndexed { index, _ ->
                val colour = when {
                    index + 1 < position -> AppColors.TextPrimary
                    index + 1 == position -> AppColors.Accent
                    else -> AppColors.SurfaceHighest
                }
                Row(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(colour, RoundedCornerShape(3.dp))
                ) {}
            }
        }
        Text(
            stringResource(R.string.flow_step_of, position, steps.size, label),
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextPrimary
        )
    }
}

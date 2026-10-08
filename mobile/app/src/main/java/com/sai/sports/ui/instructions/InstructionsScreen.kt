package com.sai.sports.ui.instructions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.SessionRepository
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.SectionTitle
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.kit.LaneNumber
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.ModeTag
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.TrialsColors
import java.util.Locale

/**
 * How to do the test, before the camera opens.
 *
 * Diagrams first and short numbered steps second: many first-time athletes will
 * read slowly or not at all in any language, and a picture of the start and top
 * positions answers the question the scorer is strictest about.
 *
 * The most common reason an honest attempt goes unscored is setup — the body
 * not fully in frame, or the phone moving — so setup is shown for every test.
 */
@Composable
fun InstructionsScreen(
    testType: TestType,
    onBack: () -> Unit,
    onStart: () -> Unit,
    /** Set for an official attempt; its rules are shown first. */
    sessionId: String? = null
) {
    val guide = TestGuides.forType(testType)
    val context = LocalContext.current

    val session = remember(sessionId) {
        val athleteId = AppServices.session(context).current()?.athleteId
        if (sessionId == null || athleteId == null) null
        else SessionRepository(context, AppServices.api(context)).session(athleteId, sessionId)
    }

    Page(header = { TitleBar(stringResource(Labels.testName(testType)), onBack) }) {

        // An official attempt: say so, on the floodlit field, and show SAI's
        // rules for this session before anything else.
        session?.let {
            Sheet(style = SheetStyle.Field) {
                Text(
                    text = (stringResource(R.string.official_badge) + " · " + it.name).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = TrialsColors.Marigold
                )
                Text(
                    text = stringResource(R.string.session_once_note),
                    style = MaterialTheme.typography.bodyLarge
                )
                it.rules?.let { rules ->
                    Text(
                        text = stringResource(R.string.session_rules_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = TrialsColors.Marigold
                    )
                    Text(text = rules, style = MaterialTheme.typography.bodyMedium, color = TrialsColors.InkMuted)
                }
            }
        } ?: ModeTag(official = false)

        SectionTitle(stringResource(R.string.instructions_setup))
        Sheet(padding = PaddingValues(12.dp)) {
            Diagram(R.drawable.ic_setup_camera, R.string.diagram_setup, Modifier.align(Alignment.CenterHorizontally))
        }
        guide.setupSteps.forEachIndexed { index, step -> Step(index + 1, step) }

        SectionTitle(stringResource(R.string.instructions_movement))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            guide.diagrams.forEach { (image, description) ->
                Sheet(modifier = Modifier.weight(1f), padding = PaddingValues(10.dp), spacing = 6.dp) {
                    Diagram(image, description, Modifier.align(Alignment.CenterHorizontally))
                    Text(
                        stringResource(description),
                        style = MaterialTheme.typography.bodySmall,
                        color = TrialsColors.Pencil,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
        guide.movementSteps.forEachIndexed { index, step -> Step(index + 1, step) }

        Sheet(style = SheetStyle.Rough) {
            Text(stringResource(guide.counts), style = MaterialTheme.typography.bodyMedium)
        }

        // The ready signal is new to every athlete; say what it is before the
        // camera asks for it.
        SectionTitle(stringResource(R.string.instructions_starting))
        Step(1, R.string.start_step_gesture)
        Step(2, R.string.start_step_countdown)

        // Marigold is the official action; practice starts in plain ink.
        if (session != null) {
            MarigoldButton(
                text = stringResource(R.string.instructions_start),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            InkButton(
                text = stringResource(R.string.instructions_start),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Diagram(@DrawableRes image: Int, @StringRes description: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(image),
        contentDescription = stringResource(description),
        colorFilter = ColorFilter.tint(TrialsColors.Ink),
        modifier = modifier.size(120.dp)
    )
}

/** A numbered step, its number in the chest-number face. */
@Composable
private fun Step(number: Int, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LaneNumber(number, color = TrialsColors.Ink)
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

data class TestGuide(
    val setupSteps: List<Int>,
    val diagrams: List<Pair<Int, Int>>,
    val movementSteps: List<Int>,
    @StringRes val counts: Int
)

object TestGuides {

    private val SETUP = listOf(
        R.string.setup_step_distance,
        R.string.setup_step_steady,
        R.string.setup_step_side_on,
        R.string.setup_step_light
    )

    /** Curls are filmed from the front: both arms must be seen to tell which one is working. */
    private val SETUP_FACING = listOf(
        R.string.setup_step_distance,
        R.string.setup_step_steady,
        R.string.setup_step_facing,
        R.string.setup_step_light
    )

    fun forType(type: TestType): TestGuide = when (type) {
        TestType.SQUATS -> TestGuide(
            setupSteps = SETUP,
            diagrams = listOf(
                R.drawable.ic_squat_stand to R.string.diagram_squat_stand,
                R.drawable.ic_squat_bottom to R.string.diagram_squat_bottom
            ),
            movementSteps = listOf(
                R.string.squat_step_stand,
                R.string.squat_step_down,
                R.string.squat_step_up,
                R.string.rep_step_repeat
            ),
            counts = R.string.squat_counts
        )

        TestType.PUSH_UPS -> TestGuide(
            setupSteps = SETUP,
            diagrams = listOf(
                R.drawable.ic_pushup_top to R.string.diagram_pushup_top,
                R.drawable.ic_pushup_bottom to R.string.diagram_pushup_bottom
            ),
            movementSteps = listOf(
                R.string.pushup_step_plank,
                R.string.pushup_step_down,
                R.string.pushup_step_up,
                R.string.rep_step_repeat
            ),
            counts = R.string.pushup_counts
        )

        TestType.BICEP_CURLS -> TestGuide(
            setupSteps = SETUP_FACING,
            diagrams = listOf(
                R.drawable.ic_curl_down to R.string.diagram_curl_down,
                R.drawable.ic_curl_up to R.string.diagram_curl_up
            ),
            movementSteps = listOf(
                R.string.curl_step_stand,
                R.string.curl_step_up,
                R.string.curl_step_down,
                R.string.rep_step_repeat
            ),
            counts = R.string.curl_counts
        )

        TestType.LUNGES -> TestGuide(
            setupSteps = SETUP,
            diagrams = listOf(
                R.drawable.ic_lunge_stand to R.string.diagram_lunge_stand,
                R.drawable.ic_lunge_bottom to R.string.diagram_lunge_bottom
            ),
            movementSteps = listOf(
                R.string.lunge_step_stand,
                R.string.lunge_step_down,
                R.string.lunge_step_up,
                R.string.rep_step_repeat
            ),
            counts = R.string.lunge_counts
        )

        TestType.SIT_UPS -> TestGuide(
            setupSteps = SETUP,
            diagrams = listOf(
                R.drawable.ic_situp_down to R.string.diagram_situp_down,
                R.drawable.ic_situp_up to R.string.diagram_situp_up
            ),
            movementSteps = listOf(
                R.string.situp_step_lie,
                R.string.situp_step_up,
                R.string.situp_step_down,
                R.string.situp_step_repeat
            ),
            counts = R.string.situp_counts
        )

        TestType.VERTICAL_JUMP -> TestGuide(
            setupSteps = SETUP,
            diagrams = listOf(
                R.drawable.ic_jump_stand to R.string.diagram_jump_stand,
                R.drawable.ic_jump_peak to R.string.diagram_jump_peak
            ),
            movementSteps = listOf(
                R.string.jump_step_still,
                R.string.jump_step_jump,
                R.string.jump_step_land,
                R.string.jump_step_repeat
            ),
            counts = R.string.jump_counts
        )
    }
}

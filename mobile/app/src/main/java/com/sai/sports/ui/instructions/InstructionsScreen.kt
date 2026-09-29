package com.sai.sports.ui.instructions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.sai.sports.ui.common.TitleBar

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TitleBar(stringResource(Labels.testName(testType)), onBack)

        // An official attempt: say so, and show SAI's rules for this session
        // before anything else.
        session?.let {
            Card {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.official_badge) + " · " + it.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.session_once_note),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    it.rules?.let { rules ->
                        Text(
                            text = stringResource(R.string.session_rules_title),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(text = rules, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.instructions_setup))
        Diagram(R.drawable.ic_setup_camera, R.string.diagram_setup)
        guide.setupSteps.forEachIndexed { index, step -> Step(index + 1, step) }

        SectionTitle(stringResource(R.string.instructions_movement))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            guide.diagrams.forEach { (image, description) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Diagram(image, description)
                    Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        guide.movementSteps.forEachIndexed { index, step -> Step(index + 1, step) }

        Card {
            Text(
                stringResource(guide.counts),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // The ready signal is new to every athlete; say what it is before the
        // camera asks for it.
        SectionTitle(stringResource(R.string.instructions_starting))
        Step(1, R.string.start_step_gesture)
        Step(2, R.string.start_step_countdown)

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.instructions_start))
        }
    }
}

@Composable
private fun Diagram(@DrawableRes image: Int, @StringRes description: Int) {
    Image(
        painter = painterResource(image),
        contentDescription = stringResource(description),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(120.dp)
    )
}

@Composable
private fun Step(number: Int, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number.", style = MaterialTheme.typography.titleMedium)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
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

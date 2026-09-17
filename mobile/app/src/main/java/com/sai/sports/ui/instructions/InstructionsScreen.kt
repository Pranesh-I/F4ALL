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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
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
    onStart: () -> Unit
) {
    val guide = TestGuides.forType(testType)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TitleBar(stringResource(Labels.testName(testType)), onBack)

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

    fun forType(type: TestType): TestGuide = when (type) {
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

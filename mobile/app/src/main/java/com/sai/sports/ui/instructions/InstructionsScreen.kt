package com.sai.sports.ui.instructions

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.SessionRepository
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.Eyebrow
import com.sai.sports.ui.kit.GlowBackdrop
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.ModeTag
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.RoundIconButton
import com.sai.sports.ui.kit.SectionHeader
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.StatRow
import com.sai.sports.ui.kit.StatTile
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.delay

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

    val facing = R.string.setup_step_facing in guide.setupSteps

    Page(
        contentPadding = PaddingValues(0.dp),
        spacing = 0.dp,
        footer = {
            // Orange is the official action; practice starts in white.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AppColors.Background)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                if (session != null) {
                    MarigoldButton(
                        text = stringResource(R.string.instructions_start),
                        onClick = onStart,
                        icon = AppIcons.Camera,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    InkButton(
                        text = stringResource(R.string.instructions_start),
                        onClick = onStart,
                        icon = AppIcons.Camera,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) {
        // The hero: the movement itself, large, lit from the corner.
        GlowBackdrop(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
        ) {
            DemoLoop(guide.diagrams, Modifier.align(Alignment.Center))
            RoundIconButton(
                icon = AppIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                modifier = Modifier.padding(16.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-28).dp)
                .background(AppColors.Background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModeTag(official = session != null)
                Text(
                    stringResource(Labels.testName(testType)),
                    style = MaterialTheme.typography.headlineLarge,
                    color = AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    stringResource(guide.counts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            StatRow {
                StatTile(
                    icon = AppIcons.Camera,
                    value = stringResource(if (facing) R.string.instructions_stat_facing else R.string.instructions_stat_side_on),
                    label = stringResource(R.string.instructions_stat_camera)
                )
                StatTile(
                    icon = AppIcons.Ruler,
                    value = stringResource(R.string.instructions_stat_distance),
                    label = stringResource(R.string.instructions_stat_distance_label)
                )
                StatTile(
                    icon = AppIcons.Bolt,
                    value = stringResource(R.string.instructions_stat_signal),
                    label = stringResource(R.string.instructions_stat_signal_label)
                )
            }

            // An official attempt: SAI's rules for this session, before anything else.
            session?.let {
                Sheet(style = SheetStyle.Field) {
                    Eyebrow(stringResource(R.string.official_badge) + " · " + it.name, color = AppColors.Accent)
                    Text(
                        text = stringResource(R.string.session_once_note),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    it.rules?.let { rules ->
                        Text(
                            text = stringResource(R.string.session_rules_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = AppColors.Accent
                        )
                        Text(text = rules, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    }
                }
            }

            StepCard(
                title = stringResource(R.string.instructions_setup),
                steps = guide.setupSteps,
                art = { Diagram(R.drawable.ic_setup_camera, R.string.diagram_setup, Modifier.size(88.dp)) }
            )

            SectionHeader(stringResource(R.string.instructions_movement))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                guide.diagrams.forEach { (image, description) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(AppColors.Surface, RoundedCornerShape(20.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Diagram(image, description, Modifier.size(110.dp))
                        Text(
                            stringResource(description),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            StepCard(title = null, steps = guide.movementSteps)

            // The ready signal is new to every athlete; say what it is before the
            // camera asks for it.
            StepCard(
                title = stringResource(R.string.instructions_starting),
                steps = listOf(R.string.start_step_gesture, R.string.start_step_countdown)
            )

            // How it ends, and what happens to the number: the two questions a
            // first-time athlete otherwise asks someone else.
            StepCard(
                title = stringResource(R.string.instructions_finishing),
                steps = listOf(
                    guide.finish,
                    if (session != null) R.string.result_step_official else R.string.result_step_practice
                )
            )
        }
    }
}

@Composable
private fun Diagram(@DrawableRes image: Int, @StringRes description: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(image),
        contentDescription = stringResource(description),
        colorFilter = ColorFilter.tint(AppColors.TextPrimary),
        modifier = modifier
    )
}

/**
 * The movement as a short loop: the two diagrams, start and end position,
 * one after the other. When the phone's animations are switched off it holds
 * on the end position, and the two diagrams further down still show both.
 */
@Composable
private fun DemoLoop(diagrams: List<Pair<Int, Int>>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var frame by remember { mutableIntStateOf(diagrams.lastIndex) }
    if (!animationsOff && diagrams.size >= 2) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(DEMO_FRAME_MS)
                frame = (frame + 1) % diagrams.size
            }
        }
    }
    Crossfade(targetState = frame, label = "demo", modifier = modifier) { index ->
        val (image, description) = diagrams[index]
        Diagram(image, description, Modifier.size(210.dp))
    }
}

private const val DEMO_FRAME_MS = 1100L

/**
 * A group of numbered steps on one card, like an exercise in a plan: an
 * optional title and picture, then each step with its number in a disc.
 */
@Composable
private fun StepCard(
    title: String?,
    steps: List<Int>,
    art: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (title != null || art != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                }
                art?.invoke()
            }
        }
        steps.forEachIndexed { index, step -> Step(index + 1, step) }
    }
}

/** A numbered step, its number in a dark disc. */
@Composable
private fun Step(number: Int, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .background(AppColors.SurfaceHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = AppColors.Accent)
        }
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

data class TestGuide(
    val setupSteps: List<Int>,
    val diagrams: List<Pair<Int, Int>>,
    val movementSteps: List<Int>,
    @StringRes val counts: Int,
    /** How the test ends (Sprint 19). */
    @StringRes val finish: Int = R.string.finish_step_reps
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
            counts = R.string.jump_counts,
            finish = R.string.finish_step_jump
        )
    }
}

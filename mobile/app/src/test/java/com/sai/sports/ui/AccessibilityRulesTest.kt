package com.sai.sports.ui

import androidx.compose.ui.graphics.Color
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.ui.instructions.TestGuides
import com.sai.sports.ui.kit.OfficialFlow
import com.sai.sports.ui.kit.OfficialStep
import com.sai.sports.ui.theme.AppColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Sprint 19: the rules that keep the app usable by everyone it is for, checked
 * on every build rather than by eye once.
 */
class AccessibilityRulesTest {

    // -- contrast (WCAG 2.1 AA: 4.5:1 for text, 3:1 for field borders) -------

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `every text colour reads on every surface it is used on`() {
        val c = AppColors
        // (text, surface, what it is) — the pairs the screens actually use.
        val pairs = listOf(
            Triple(c.TextPrimary, c.Background, "body text on the screen"),
            Triple(c.TextPrimary, c.Surface, "body text on a card"),
            Triple(c.TextPrimary, c.SurfaceHigh, "text on a tile or field"),
            Triple(c.TextPrimary, c.SurfaceHighest, "text on a track"),
            Triple(c.TextSecondary, c.Background, "secondary text on the screen"),
            Triple(c.TextSecondary, c.Surface, "secondary text on a card"),
            Triple(c.TextSecondary, c.SurfaceHigh, "secondary text on a tile"),
            Triple(c.Accent, c.Background, "an accent label on the screen"),
            Triple(c.Accent, c.Surface, "an accent label on a card"),
            Triple(c.Accent, c.SurfaceHigh, "an accent number in a tile"),
            Triple(c.Accent, c.AccentSoft, "an accent tag"),
            Triple(c.OnAccent, c.Accent, "the orange button"),
            Triple(c.OnInverse, c.Inverse, "the white button and the selected segment"),
            Triple(c.Success, c.Surface, "verified on a card"),
            Triple(c.Success, c.SuccessSoft, "a success tag"),
            Triple(c.Warning, c.Surface, "waiting on a card"),
            Triple(c.Danger, c.Surface, "an error on a card"),
            Triple(c.Danger, c.Background, "an error on the screen"),
            Triple(c.Danger, c.DangerSoft, "an error note"),
            Triple(c.OnInverse, c.Danger, "a fault cue on the camera")
        )
        pairs.forEach { (text, surface, what) ->
            val ratio = contrast(text, surface)
            assertTrue("$what: ${"%.2f".format(ratio)}:1 is under 4.5:1", ratio >= 4.5)
        }
    }

    @Test
    fun `field borders are visible`() {
        assertTrue(contrast(AppColors.FieldBorder, AppColors.Background) >= 3.0)
        assertTrue(contrast(AppColors.FieldBorder, AppColors.Surface) >= 3.0)
        assertTrue(contrast(AppColors.FieldBorder, AppColors.SurfaceHigh) >= 3.0)
    }

    // -- the official flow's progress ----------------------------------------

    @Test
    fun `an official camera test has five steps, a field test four`() {
        val camera = OfficialFlow.steps(fieldTest = false)
        assertEquals(
            listOf(OfficialStep.IDENTITY, OfficialStep.GESTURE, OfficialStep.COUNTDOWN, OfficialStep.TEST, OfficialStep.UPLOAD),
            camera
        )
        assertEquals(2, OfficialFlow.position(camera, OfficialStep.GESTURE))
        val field = OfficialFlow.steps(fieldTest = true)
        assertEquals(4, field.size)
        assertEquals(2, OfficialFlow.position(field, OfficialStep.COUNTDOWN))
    }

    // -- instructions ---------------------------------------------------------

    @Test
    fun `every camera test explains all eight things the plan asks for`() {
        val placement = setOf(R.string.setup_step_side_on, R.string.setup_step_facing)
        TestType.entries.forEach { type ->
            val guide = TestGuides.forType(type)
            // 1-2: setup and where the camera goes.
            assertTrue("$type setup", guide.setupSteps.size >= 3)
            assertTrue("$type camera placement", guide.setupSteps.any { it in placement })
            assertTrue("$type distance", R.string.setup_step_distance in guide.setupSteps)
            // 3 and 6: the body position and the movement, shown and told.
            assertEquals("$type diagrams", 2, guide.diagrams.size)
            assertTrue("$type movement", guide.movementSteps.size >= 3)
            // 7: how it ends.
            assertTrue("$type finish", guide.finish != 0)
        }
        // 4, 5 and 8 are the same for every test: the hand signal, the
        // countdown, and what happens to the result.
        listOf(
            R.string.start_step_gesture, R.string.start_step_countdown,
            R.string.result_step_practice, R.string.result_step_official
        ).forEach { assertTrue(it != 0) }
    }

    // -- nothing hard-coded -----------------------------------------------------

    @Test
    fun `no user-facing text is hard-coded in the screens`() {
        val roots = listOf("ui", "fieldtest", "notify").map { File(SOURCE_ROOT, it) }
        val literal = Regex(
            """(Text\(\s*"[\p{L}]|(text|label|title|contentDescription|detail|value)\s*=\s*"[\p{L}][^"]*\p{L}[^"]*")"""
        )
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    // An animation's `label` names it for the layout inspector, not for people.
                    val animationLabel = ANIMATION.containsMatchIn(code)
                    if (literal.containsMatchIn(code) && !animationLabel) "${file.name}:${index + 1}: ${line.trim()}" else null
                }
            }.toList()
        }
        assertTrue("Hard-coded text — move it to strings.xml:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    private companion object {
        const val SOURCE_ROOT = "src/main/java/com/sai/sports"
        val ANIMATION = Regex("""(Crossfade|AnimatedContent|animate\w*AsState|updateTransition|rememberInfiniteTransition)\(""")
    }
}

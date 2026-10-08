package com.sai.sports.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.sai.sports.i18n.AppLanguage

/**
 * Material components, mapped onto the trials palette.
 *
 * Primary is ink rather than marigold on purpose: Material uses "primary" for
 * text buttons, focused fields and switches, and marigold text on paper does
 * not read. Marigold stays the fill of the one action that matters, through
 * the kit's `MarigoldButton`.
 */
private val TrialsColorScheme = lightColorScheme(
    primary = TrialsColors.Ink,
    onPrimary = TrialsColors.Paper,
    primaryContainer = TrialsColors.Marigold,
    onPrimaryContainer = TrialsColors.Ink,
    inversePrimary = TrialsColors.Marigold,
    secondary = TrialsColors.Pencil,
    onSecondary = TrialsColors.Paper,
    secondaryContainer = TrialsColors.PaperShade,
    onSecondaryContainer = TrialsColors.Ink,
    tertiary = TrialsColors.Stamp,
    onTertiary = TrialsColors.Paper,
    tertiaryContainer = Color(0xFFE8E5F6),
    onTertiaryContainer = TrialsColors.Stamp,
    background = TrialsColors.Ground,
    onBackground = TrialsColors.Ink,
    surface = TrialsColors.Ground,
    onSurface = TrialsColors.Ink,
    surfaceVariant = TrialsColors.PaperShade,
    onSurfaceVariant = TrialsColors.Pencil,
    surfaceTint = Color.Transparent,
    inverseSurface = TrialsColors.Ink,
    inverseOnSurface = TrialsColors.Paper,
    error = TrialsColors.RedPen,
    onError = TrialsColors.Paper,
    errorContainer = TrialsColors.ErrorTint,
    onErrorContainer = Color(0xFF7A1010),
    outline = TrialsColors.Line,
    outlineVariant = TrialsColors.Rule,
    scrim = Color.Black,
    surfaceBright = TrialsColors.Paper,
    surfaceDim = TrialsColors.PaperShade,
    surfaceContainerLowest = TrialsColors.Paper,
    surfaceContainerLow = TrialsColors.Paper,
    surfaceContainer = TrialsColors.Paper,
    surfaceContainerHigh = TrialsColors.Paper,
    surfaceContainerHighest = TrialsColors.PaperShade
)

/** Paper has square corners. The 2dp only stops edges from looking jagged. */
private val TrialsShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp)
)

/** The language the screen is showing, for the few places that pick a font by script. */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

/**
 * The app's theme. Light only, deliberately: the sheets are paper, and the app
 * is used outdoors, where a light screen is the one that stays readable.
 */
@Composable
fun TrialsTheme(content: @Composable () -> Unit) {
    // The activity's context is already wrapped in the athlete's chosen
    // language, so the configuration says which script is on screen.
    val tag = LocalConfiguration.current.locales[0].language
    val language = AppLanguage.fromTag(tag) ?: AppLanguage.ENGLISH
    val typography = remember(language) { trialsTypography(language) }

    CompositionLocalProvider(LocalAppLanguage provides language) {
        MaterialTheme(
            colorScheme = TrialsColorScheme,
            typography = typography,
            shapes = TrialsShapes,
            content = content
        )
    }
}

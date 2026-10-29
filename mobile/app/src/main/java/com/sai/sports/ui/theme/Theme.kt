package com.sai.sports.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.sai.sports.i18n.AppLanguage

/**
 * Material components, mapped onto [AppColors].
 *
 * Primary is the orange accent: Material uses it for focused fields, switches
 * and progress, which is exactly where the accent belongs. Text buttons use
 * the content colour instead (see the kit's buttons), so orange text stays rare.
 */
private val AppColorScheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = AppColors.OnAccent,
    primaryContainer = AppColors.AccentSoft,
    onPrimaryContainer = AppColors.Accent,
    inversePrimary = AppColors.Accent,
    secondary = AppColors.Inverse,
    onSecondary = AppColors.OnInverse,
    secondaryContainer = AppColors.SurfaceHigh,
    onSecondaryContainer = AppColors.TextPrimary,
    tertiary = AppColors.Success,
    onTertiary = AppColors.OnInverse,
    tertiaryContainer = AppColors.SuccessSoft,
    onTertiaryContainer = AppColors.Success,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Background,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceHigh,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = AppColors.Inverse,
    inverseOnSurface = AppColors.OnInverse,
    error = AppColors.Danger,
    onError = AppColors.OnInverse,
    errorContainer = AppColors.DangerSoft,
    onErrorContainer = AppColors.Danger,
    outline = AppColors.FieldBorder,
    outlineVariant = AppColors.Divider,
    scrim = Color.Black,
    surfaceBright = AppColors.SurfaceHigh,
    surfaceDim = AppColors.Background,
    surfaceContainerLowest = AppColors.Background,
    surfaceContainerLow = AppColors.Surface,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceHigh,
    surfaceContainerHighest = AppColors.SurfaceHighest
)

/** Generous, consistent corners: cards 20, tiles and fields 14, chips 10. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** The language the screen is showing, for the few places that pick a font by script. */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

/**
 * The app's theme: dark, with one orange accent.
 *
 * Contrast is kept high (white on near-black, 15:1 and more) so screens stay
 * readable outdoors, where trials happen.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // The activity's context is already wrapped in the athlete's chosen
    // language, so the configuration says which script is on screen.
    val tag = LocalConfiguration.current.locales[0].language
    val language = AppLanguage.fromTag(tag) ?: AppLanguage.ENGLISH
    val typography = remember(language) { appTypography(language) }

    CompositionLocalProvider(LocalAppLanguage provides language) {
        MaterialTheme(
            colorScheme = AppColorScheme,
            typography = typography,
            shapes = AppShapes,
            content = content
        )
    }
}

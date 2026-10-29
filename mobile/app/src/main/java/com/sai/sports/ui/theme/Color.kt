package com.sai.sports.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's palette: near-black surfaces, white type, and one hot orange.
 *
 * Every colour has one job, so a colour on screen always means the same thing:
 *
 * - **Background → Surface → SurfaceHigh**: three steps of dark, from the
 *   screen to a card to a tile or field inside a card. Depth comes from these
 *   steps, not from shadows.
 * - **Accent** (orange): the action, and what counts. Start, record, the
 *   current step, an official result. Used sparingly: if everything is orange,
 *   nothing is.
 * - **Inverse** (white): the selected option, and the firm secondary action.
 * - **TextSecondary**: anything provisional or supporting: a phone's own
 *   score, a date, a hint.
 * - **Success / Warning / Danger**: verified, waiting, refused. Always with an
 *   icon or a word too, never colour alone.
 *
 * Every text pair meets WCAG AA (4.5:1) on the surface it is used on; the
 * pairs are checked in `AccessibilityRulesTest`.
 */
object AppColors {
    val Background = Color(0xFF0F0F0F)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceHigh = Color(0xFF252525)

    /** Tracks, pressed states and the unselected half of a segmented control. */
    val SurfaceHighest = Color(0xFF303030)

    /** Hairlines between rows. Decoration only, never text. */
    val Divider = Color(0xFF2B2B2B)

    /** Field borders: light enough (3:1) to see where to type. */
    val FieldBorder = Color(0xFF7A7A7A)

    val TextPrimary = Color(0xFFF4F4F1)
    val TextSecondary = Color(0xFFA3A3A3)

    val Accent = Color(0xFFFF5A1F)
    val OnAccent = Color(0xFF120A06)

    /** The orange-tinted fill behind a tag ("Official", "Practice best"). */
    val AccentSoft = Color(0xFF3A1D12)

    val Inverse = Color(0xFFFFFFFF)
    val OnInverse = Color(0xFF111111)

    val Success = Color(0xFF4ADE80)
    val SuccessSoft = Color(0xFF12301F)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFFF6B6B)
    val DangerSoft = Color(0xFF3A1616)
}

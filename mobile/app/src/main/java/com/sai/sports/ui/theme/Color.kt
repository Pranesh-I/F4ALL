package com.sai.sports.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The "Trials Day" palette: the materials of a district sports trial.
 *
 * Every colour is one real object with one job, so a colour on screen always
 * means the same thing:
 *
 * - **Ink** — text, chest numbers, and the floodlit field of an official test.
 * - **Paper / Ground** — the scoresheet and the lime-marked ground it sits on.
 * - **Marigold** — the one action colour: start an official test, record. It
 *   is the garland at a sports day, and it stays readable in full sun.
 * - **Pencil** — a score the phone worked out. Provisional, never final.
 * - **Red pen** — a correction: a missed rep, a flag, a refusal.
 * - **Stamp** — the violet rubber stamp. An official result, and nothing else.
 *
 * All text pairs meet WCAG AA (4.5:1) on the surface they are used on.
 */
object TrialsColors {
    val Ink = Color(0xFF171512)
    val Paper = Color(0xFFFFFFFF)
    val Ground = Color(0xFFF4F3EE)

    /** A sheet tinted towards the ground: selected rows, quiet fills. */
    val PaperShade = Color(0xFFECEAE3)

    /** Ruled lines and hairline dividers. Decoration only, never text. */
    val Rule = Color(0xFFD9D6CC)

    /** Field borders: dark enough (3:1) to see where to type. */
    val Line = Color(0xFF8C887E)

    val Marigold = Color(0xFFF2A900)
    val Pencil = Color(0xFF6B6B6B)
    val RedPen = Color(0xFFC81E1E)
    val Stamp = Color(0xFF463C9E)

    /** Secondary text on the ink field. */
    val InkMuted = Color(0xFFB9B5AA)

    /** The ink field one step up: rows inside an official band. */
    val InkRaised = Color(0xFF2A2621)

    /** Red pen on the ink field, where the darker red would not read. */
    val RedPenOnInk = Color(0xFFFF8A80)

    val ErrorTint = Color(0xFFFBE9E7)
}

package com.sai.sports.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The line icons the app needs, drawn on a 24-unit grid with a 2-unit stroke.
 *
 * Kept in code rather than pulling in the Material icon library: these are all
 * the app uses, and every kilobyte of the install counts on a prepaid pack.
 * Tinted with the current content colour wherever they are used.
 */
object AppIcons {
    val Back = icon("Back", "M15 18l-6-6 6-6")
    val Forward = icon("Forward", "M9 18l6-6-6-6")
    val ChevronDown = icon("ChevronDown", "M6 9l6 6 6-6")
    val ChevronUp = icon("ChevronUp", "M6 15l6-6 6 6")
    val Check = icon("Check", "M20 6L9 17l-5-5")
    val Cross = icon("Cross", "M18 6L6 18", "M6 6l12 12")
    val Plus = icon("Plus", "M12 5v14", "M5 12h14")
    val Clock = icon("Clock", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 7v5l3 2")
    val NoSignal = icon(
        "NoSignal",
        "M3 3l18 18",
        "M8.5 16.4a5 5 0 0 1 7 0",
        "M5 12.9a10 10 0 0 1 5.2-2.8",
        "M19 12.9a10 10 0 0 0-1.7-1.3",
        "M2 8.8a15 15 0 0 1 4.2-2.7",
        "M10.7 5.1A15 15 0 0 1 22 8.8",
        "M12 20h0.01"
    )
    val Lock = icon("Lock", "M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4")
    val SpeakerOn = icon(
        "SpeakerOn",
        "M11 5L6 9H2v6h4l5 4z",
        "M15.5 8.5a5 5 0 0 1 0 7",
        "M18.5 5.5a9 9 0 0 1 0 13"
    )
    val SpeakerOff = icon("SpeakerOff", "M11 5L6 9H2v6h4l5 4z", "M22 9l-6 6", "M16 9l6 6")
    val Star = icon("Star", "M12 2.5l2.9 6 6.6 0.9-4.8 4.6 1.2 6.5L12 17.4l-5.9 3.1 1.2-6.5-4.8-4.6 6.6-0.9z")
    val Upload = icon("Upload", "M12 16V4", "M7 9l5-5 5 5", "M4 20h16")

    // -- navigation ------------------------------------------------------------
    val Home = icon("Home", "M3 10.5L12 3l9 7.5", "M5 9.5V21h14V9.5", "M10 21v-6h4v6")
    val Dumbbell = icon("Dumbbell", "M6.5 7v10", "M17.5 7v10", "M3.5 9.5v5", "M20.5 9.5v5", "M6.5 12h11")
    val Chart = icon("Chart", "M4 20h16", "M7 16v-5", "M12 16V6", "M17 16v-8")
    val Trophy = icon(
        "Trophy",
        "M8 4h8v5a4 4 0 0 1-8 0z",
        "M8 6H5.5a2.5 2.5 0 0 0 2.5 4",
        "M16 6h2.5a2.5 2.5 0 0 1-2.5 4",
        "M12 13v4",
        "M8 20h8",
        "M9.5 17h5"
    )
    val User = icon("User", "M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8z", "M4 21a8 8 0 0 1 16 0")

    // -- actions and things ------------------------------------------------------
    val Bell = icon("Bell", "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z", "M10 21a2 2 0 0 0 4 0")
    val Gear = icon(
        "Gear",
        "M12 15a3 3 0 1 0 0-6a3 3 0 1 0 0 6z",
        "M12 2.5v3", "M12 18.5v3", "M2.5 12h3", "M18.5 12h3",
        "M5.3 5.3l2.1 2.1", "M16.6 16.6l2.1 2.1", "M5.3 18.7l2.1-2.1", "M16.6 7.4l2.1-2.1"
    )
    val Flame = icon(
        "Flame",
        "M12 21.5c3.9 0 6.5-2.8 6.5-6.5c0-3.8-2.8-6-3.8-9.5c-1.9 1.8-2.9 3.8-2.9 5.6c-0.9-0.9-1.6-2.1-1.7-3.6c-2.2 1.9-4.6 4.6-4.6 7.5c0 3.7 2.6 6.5 6.5 6.5z"
    )
    val Camera = icon("Camera", "M3 8h4l2-3h6l2 3h4v12H3z", "M12 17a4 4 0 1 0 0-8a4 4 0 1 0 0 8z")
    val Shield = icon("Shield", "M12 3l8 3v6c0 5-3.5 8-8 9c-4.5-1-8-4-8-9V6z", "M9 12l2 2 4-4")
    val Globe = icon(
        "Globe",
        "M12 21a9 9 0 1 0 0-18a9 9 0 1 0 0 18z",
        "M3 12h18",
        "M12 3a14 14 0 0 1 0 18",
        "M12 3a14 14 0 0 0 0 18"
    )
    val TrendUp = icon("TrendUp", "M3 17l6-6 4 4 8-8", "M15 7h6v6")
    val TrendDown = icon("TrendDown", "M3 7l6 6 4-4 8 8", "M15 17h6v-6")
    val Bolt = icon("Bolt", "M13 2L4 14h7l-1 8 9-12h-7z")
    val Calendar = icon("Calendar", "M4 6h16v15H4z", "M4 10h16", "M8 3v4", "M16 3v4")
    val Phone = icon("Phone", "M7 2.5h10v19H7z", "M11 18h2")
    val Ruler = icon("Ruler", "M3 12h18", "M6 9l-3 3 3 3", "M18 9l3 3-3 3")
    val Info = icon("Info", "M12 21a9 9 0 1 0 0-18a9 9 0 1 0 0 18z", "M12 11v5", "M12 8h0.01")
    val Logout = icon("Logout", "M15 3h4v18h-4", "M10 17l5-5-5-5", "M15 12H3")
    val Medal = icon("Medal", "M12 21a6 6 0 1 0 0-12a6 6 0 1 0 0 12z", "M8 3l4 6 4-6", "M12 13v4")
    val Crown = icon("Crown", "M3 8l4.5 4 4.5-7 4.5 7 4.5-4-2 11H5z")
    val List = icon("List", "M9 6h12", "M9 12h12", "M9 18h12", "M4 6h0.01", "M4 12h0.01", "M4 18h0.01")
    val Edit = icon("Edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Play = icon("Play", "M8 5v14l11-7z", filled = true)

    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        paths.forEach { data ->
            builder.addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }
}

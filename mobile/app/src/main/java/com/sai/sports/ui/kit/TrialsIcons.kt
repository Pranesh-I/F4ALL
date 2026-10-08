package com.sai.sports.ui.kit

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The handful of line icons the app needs, drawn on a 24-unit grid.
 *
 * Kept in code rather than pulling in the Material icon library: these few are
 * all the app uses, and every kilobyte of the install counts on a prepaid pack.
 * Stroked with the current content colour wherever they are tinted.
 */
object TrialsIcons {
    val Back = icon("Back", "M19 12H5", "M12 19l-7-7 7-7")
    val Forward = icon("Forward", "M9 18l6-6-6-6")
    val Check = icon("Check", "M20 6L9 17l-5-5")
    val Cross = icon("Cross", "M18 6L6 18", "M6 6l12 12")
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

    private fun icon(name: String, vararg paths: String): ImageVector {
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
                fill = null,
                stroke = SolidColor(androidx.compose.ui.graphics.Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }
}

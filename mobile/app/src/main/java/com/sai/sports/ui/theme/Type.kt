package com.sai.sports.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sai.sports.R
import com.sai.sports.i18n.AppLanguage

/**
 * Anek, by Ek Type (Mumbai): one type family for every language the app ships.
 *
 * A Latin display face has no Hindi, Bengali or Tamil, so a typical "display +
 * body" pairing makes every translated screen fall back to a different font and
 * look like a different app. Anek has a matching design per script, so a Tamil
 * screen and an English one read as the same product.
 *
 * The bundled files are subset (see assets/licenses/Anek-OFL.txt for the
 * licence): Latin keeps weight 400–800 and width 75–100. Scores use it
 * condensed and heavy, the scoreboard look; headings use it slightly narrowed
 * and bold. The Indian scripts keep weight 400–700 at normal width, because
 * condensed Devanagari, Bengali and Tamil are harder to read and every score is
 * written in Latin digits anyway.
 */
object AppFonts {

    /** Scores, counters and chest numbers. Latin glyphs only — never for translated text. */
    val Numerals: FontFamily = family(R.font.anek_latin, width = 75f, maxWeight = 800)

    private val LatinText = family(R.font.anek_latin, width = 100f, maxWeight = 800)

    /** Latin headings: a touch narrower than body text, so short titles feel athletic. */
    private val LatinHeading = family(R.font.anek_latin, width = 92f, maxWeight = 800)
    private val Devanagari = family(R.font.anek_devanagari, width = null, maxWeight = 700)
    private val Bangla = family(R.font.anek_bangla, width = null, maxWeight = 700)
    private val Tamil = family(R.font.anek_tamil, width = null, maxWeight = 700)

    /** Body and title text in [language]'s script; each file also carries Latin. */
    fun text(language: AppLanguage): FontFamily = when (language) {
        AppLanguage.ENGLISH -> LatinText
        AppLanguage.HINDI -> Devanagari
        AppLanguage.BENGALI -> Bangla
        AppLanguage.TAMIL -> Tamil
    }

    /** Headings: narrowed for Latin, the plain script face otherwise. */
    fun display(language: AppLanguage): FontFamily =
        if (language == AppLanguage.ENGLISH) LatinHeading else text(language)

    // Variation settings on a resource font are still marked experimental,
    // though Compose has shipped them since 1.2.
    @OptIn(ExperimentalTextApi::class)
    private fun family(resId: Int, width: Float?, maxWeight: Int): FontFamily =
        FontFamily(
            listOf(400, 500, 600, 700, 800)
                .filter { it <= maxWeight }
                .map { weight ->
                    val settings = listOfNotNull(
                        FontVariation.weight(weight),
                        width?.let { FontVariation.width(it) }
                    )
                    Font(
                        resId = resId,
                        weight = FontWeight(weight),
                        variationSettings = FontVariation.Settings(*settings.toTypedArray())
                    )
                }
        )
}

/**
 * The type scale for [language].
 *
 * Two script rules are built in. Letter-spacing is Latin-only: spacing out
 * Devanagari or Bengali breaks the headline stroke that joins the letters of a
 * word. And Indian scripts stack vowel signs above and below the line, so
 * their line heights are looser, or the marks of one line touch the next.
 */
fun appTypography(language: AppLanguage): Typography {
    val text = AppFonts.text(language)
    val display = AppFonts.display(language)
    val latin = language == AppLanguage.ENGLISH

    fun tracking(amount: Float): TextUnit = if (latin) amount.em else 0.em
    fun lines(size: Int): TextUnit = if (latin) size.sp else (size * 1.15f).sp

    fun numerals(size: Int, height: Int) = TextStyle(
        fontFamily = AppFonts.Numerals,
        fontWeight = FontWeight.ExtraBold,
        fontSize = size.sp,
        lineHeight = height.sp,
        letterSpacing = (-0.01).em
    )

    return Typography(
        displayLarge = numerals(96, 92),
        displayMedium = numerals(72, 72),
        displaySmall = numerals(52, 54),

        headlineLarge = TextStyle(
            fontFamily = display, fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp, lineHeight = lines(36), letterSpacing = tracking(-0.01f)
        ),
        headlineMedium = TextStyle(
            fontFamily = display, fontWeight = FontWeight.Bold,
            fontSize = 27.sp, lineHeight = lines(32), letterSpacing = tracking(-0.01f)
        ),
        headlineSmall = TextStyle(
            fontFamily = display, fontWeight = FontWeight.Bold,
            fontSize = 23.sp, lineHeight = lines(28)
        ),

        titleLarge = TextStyle(
            fontFamily = text, fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp, lineHeight = lines(26)
        ),
        titleMedium = TextStyle(
            fontFamily = text, fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp, lineHeight = lines(23)
        ),
        titleSmall = TextStyle(
            fontFamily = text, fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp, lineHeight = lines(21)
        ),

        bodyLarge = TextStyle(
            fontFamily = text, fontWeight = FontWeight.Normal,
            fontSize = 17.sp, lineHeight = lines(25)
        ),
        bodyMedium = TextStyle(
            fontFamily = text, fontWeight = FontWeight.Normal,
            fontSize = 15.sp, lineHeight = lines(22)
        ),
        bodySmall = TextStyle(
            fontFamily = text, fontWeight = FontWeight.Normal,
            fontSize = 13.sp, lineHeight = lines(19)
        ),

        // Buttons.
        labelLarge = TextStyle(
            fontFamily = text, fontWeight = FontWeight.Bold,
            fontSize = 16.sp, lineHeight = lines(22), letterSpacing = tracking(0.01f)
        ),
        // Eyebrows and tags: "WEDNESDAY, 9 APRIL", "VIEW ALL", "OFFICIAL".
        labelMedium = TextStyle(
            fontFamily = text, fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp, lineHeight = lines(18), letterSpacing = tracking(0.1f)
        ),
        labelSmall = TextStyle(
            fontFamily = text, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, lineHeight = lines(16), letterSpacing = tracking(0.06f)
        )
    )
}

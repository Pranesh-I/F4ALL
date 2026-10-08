package com.sai.sports.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sai.sports.R
import com.sai.sports.ui.theme.TrialsColors
import com.sai.sports.ui.theme.TrialsFonts
import java.util.Locale

/**
 * The athlete's chest number.
 *
 * At a real trial it is printed on paper and pinned on with four safety pins;
 * here it heads the home and profile screens. The number is the first eight
 * characters of the athlete ID — the same short ID SAI officials see on the
 * dashboard — so the athlete and the official can name the same person.
 */
@Composable
fun Bib(
    number: String,
    modifier: Modifier = Modifier,
    name: String? = null,
    detail: String? = null
) {
    val spoken = listOfNotNull(stringResource(R.string.bib_description, number), name, detail)
        .joinToString(". ")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TrialsColors.Paper, SheetShape)
            .border(2.dp, TrialsColors.Ink, SheetShape)
            .drawBehind {
                // The four safety-pin holes.
                val inset = 12.dp.toPx()
                val radius = 3.5.dp.toPx()
                listOf(
                    Offset(inset, inset),
                    Offset(size.width - inset, inset),
                    Offset(inset, size.height - inset),
                    Offset(size.width - inset, size.height - inset)
                ).forEach { centre ->
                    drawCircle(TrialsColors.Ground, radius, centre)
                    drawCircle(TrialsColors.Ink, radius, centre, style = Stroke(1.dp.toPx()))
                }
            }
            .padding(horizontal = 26.dp, vertical = 20.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, lineHeight = 50.sp),
            color = TrialsColors.Ink,
            maxLines = 1,
            softWrap = false
        )
        name?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = TrialsColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TrialsColors.Pencil)
        }
    }
}

/** Turns an athlete ID into its chest number. Kept free of Compose so it is unit-tested. */
object BibNumber {
    /**
     * "3f9c21ab-…" becomes "3F9C·21AB": the dashboard's eight-character short
     * ID, upper-cased and split in two so it can be read aloud in halves.
     */
    fun of(athleteId: String): String {
        val code = athleteId.filter { it.isLetterOrDigit() }.take(8).uppercase(Locale.US)
        return if (code.length > 4) code.substring(0, 4) + "·" + code.substring(4) else code
    }
}

/**
 * Says which world a screen belongs to, in words as well as colour, so it
 * survives a screen reader and bright sun: OFFICIAL on the ink field, or
 * PRACTICE in a dashed pencil box.
 */
@Composable
fun ModeTag(official: Boolean, modifier: Modifier = Modifier) {
    val text = stringResource(if (official) R.string.official_badge else R.string.practice_badge)
    if (official) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = TrialsColors.Marigold,
            modifier = modifier
                .background(TrialsColors.Ink, SheetShape)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = TrialsColors.Ink,
            modifier = modifier
                .background(TrialsColors.Paper, SheetShape)
                .dashedBorder(1.dp, TrialsColors.Pencil)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * The bar across the bottom when there is no signal and tests are waiting.
 *
 * Black, so it cannot be mistaken for part of the page: the fear it answers is
 * "has my test been lost?", and the answer must be impossible to miss.
 */
@Composable
fun OfflineBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TrialsColors.Ink)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(TrialsIcons.NoSignal, contentDescription = null, tint = TrialsColors.Marigold, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TrialsColors.Paper)
    }
}

/** A filter that is either on (inked in) or off (an empty ruled box). */
@Composable
fun TrialsChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) },
        shape = SheetShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = TrialsColors.Ink,
            selectedContainerColor = TrialsColors.Ink,
            selectedLabelColor = TrialsColors.Paper
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = TrialsColors.Ink,
            selectedBorderColor = TrialsColors.Ink,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        )
    )
}

/** A short state line with its icon: "Submitted", "Waiting to send". */
@Composable
fun StatusLine(
    text: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** A large count in the chest-number face, for streaks and ranks. */
@Composable
fun BigNumber(value: String, modifier: Modifier = Modifier, color: Color = TrialsColors.Ink) {
    Text(
        text = value,
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = TrialsFonts.Numerals),
        color = color,
        modifier = modifier
    )
}

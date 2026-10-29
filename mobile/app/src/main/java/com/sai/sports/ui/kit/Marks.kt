package com.sai.sports.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.sai.sports.ui.theme.AppColors
import com.sai.sports.ui.theme.AppFonts
import java.util.Locale

/**
 * The athlete's chest number, as a card: the number large in the scoreboard
 * face, the name and region beneath.
 *
 * The number is the first eight characters of the athlete ID — the same short
 * ID SAI officials see on the dashboard — so the athlete and the official can
 * name the same person.
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
            .background(AppColors.Surface, SheetShape)
            .officialGlow()
            .padding(horizontal = 22.dp, vertical = 20.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.bib_label).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.Accent
        )
        Text(
            text = number,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 46.sp, lineHeight = 50.sp),
            color = AppColors.TextPrimary,
            maxLines = 1,
            softWrap = false
        )
        name?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
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
 * survives a screen reader and bright sun: OFFICIAL in the accent, PRACTICE
 * in grey.
 */
@Composable
fun ModeTag(official: Boolean, modifier: Modifier = Modifier) {
    Tag(
        text = stringResource(if (official) R.string.official_badge else R.string.practice_badge),
        tone = if (official) TagTone.Accent else TagTone.Neutral,
        icon = if (official) AppIcons.Shield else AppIcons.Lock,
        modifier = modifier
    )
}

/**
 * The bar across the bottom when there is no signal and tests are waiting.
 * The fear it answers is "has my test been lost?", and the answer must be
 * impossible to miss.
 */
@Composable
fun OfflineBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(AppColors.SurfaceHigh, ControlShape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(AppIcons.NoSignal, contentDescription = null, tint = AppColors.Warning, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
    }
}

/** A filter that is either on (a white pill) or off (a dark one). */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleSmall) },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = AppColors.SurfaceHigh,
            labelColor = AppColors.TextPrimary,
            selectedContainerColor = AppColors.Inverse,
            selectedLabelColor = AppColors.OnInverse
        ),
        border = null,
        modifier = Modifier.heightIn(min = 44.dp)
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
fun BigNumber(value: String, modifier: Modifier = Modifier, color: Color = AppColors.TextPrimary) {
    Text(
        text = value,
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = AppFonts.Numerals),
        color = color,
        modifier = modifier
    )
}

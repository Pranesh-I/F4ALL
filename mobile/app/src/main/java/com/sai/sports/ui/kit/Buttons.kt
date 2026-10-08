package com.sai.sports.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sai.sports.ui.theme.TrialsColors

/** Paper corners: square, softened just enough not to look jagged. */
internal val SheetShape = RoundedCornerShape(2.dp)

private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

/** Tall enough to hit with a thumb in a hurry, or with gloves on at the ground. */
private val MinButtonHeight = 52.dp

/**
 * The one action colour: start an official test, record it.
 *
 * Use it once per screen at most. If everything is marigold, nothing is.
 */
@Composable
fun MarigoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = SheetShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = TrialsColors.Marigold,
            contentColor = TrialsColors.Ink,
            disabledContainerColor = TrialsColors.PaperShade,
            disabledContentColor = TrialsColors.Pencil
        ),
        modifier = modifier.heightIn(min = MinButtonHeight)
    ) { ButtonLabel(text, icon) }
}

/** A firm, ordinary action: save, continue, done. */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = SheetShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = TrialsColors.Ink,
            contentColor = TrialsColors.Paper,
            disabledContainerColor = TrialsColors.PaperShade,
            disabledContentColor = TrialsColors.Pencil
        ),
        modifier = modifier.heightIn(min = MinButtonHeight)
    ) { ButtonLabel(text, icon) }
}

/**
 * The second choice beside an [InkButton], drawn as a ruled box.
 *
 * [color] lets the same button sit on the ink field ([TrialsColors.Paper]) or
 * mark a destructive choice ([TrialsColors.RedPen]).
 */
@Composable
fun LineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = TrialsColors.Ink,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = SheetShape,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.5.dp, if (enabled) color else TrialsColors.Rule),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = color,
            disabledContentColor = TrialsColors.Pencil
        ),
        modifier = modifier.heightIn(min = MinButtonHeight)
    ) { ButtonLabel(text, icon) }
}

/** A quiet action in running text: change number, skip for now. */
@Composable
fun PlainButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = TrialsColors.Ink,
    icon: ImageVector? = null
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = SheetShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = TrialsColors.Pencil
        ),
        modifier = modifier.heightIn(min = 48.dp)
    ) { ButtonLabel(text, icon) }
}

@Composable
private fun ButtonLabel(text: String, icon: ImageVector?) {
    icon?.let {
        // The words name the action; the icon only repeats them.
        Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

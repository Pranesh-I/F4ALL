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
import com.sai.sports.ui.theme.AppColors

/** Cards and sheets. */
internal val SheetShape = RoundedCornerShape(20.dp)

/** Buttons, fields and tiles inside a card. */
internal val ControlShape = RoundedCornerShape(16.dp)

private val ButtonPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)

/** Tall enough to hit with a thumb in a hurry, or with gloves on at the ground. */
private val MinButtonHeight = 56.dp

/**
 * The one action that matters on a screen: start a test, record, continue.
 *
 * Orange, with dark text (6:1). Use it once per screen at most: if everything
 * is orange, nothing is.
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
        shape = ControlShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.Accent,
            contentColor = AppColors.OnAccent,
            disabledContainerColor = AppColors.SurfaceHigh,
            disabledContentColor = AppColors.TextSecondary
        ),
        modifier = modifier.heightIn(min = MinButtonHeight)
    ) { ButtonLabel(text, icon) }
}

/** A firm, ordinary action: save, done, sign in. White, with dark text. */
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
        shape = ControlShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.Inverse,
            contentColor = AppColors.OnInverse,
            disabledContainerColor = AppColors.SurfaceHigh,
            disabledContentColor = AppColors.TextSecondary
        ),
        modifier = modifier.heightIn(min = MinButtonHeight)
    ) { ButtonLabel(text, icon) }
}

/**
 * The second choice beside a filled button: an outlined box.
 *
 * [color] marks a destructive choice ([AppColors.Danger]) when needed.
 */
@Composable
fun LineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = AppColors.TextPrimary,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, if (enabled) AppColors.SurfaceHighest else AppColors.Divider),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = AppColors.Surface,
            contentColor = color,
            disabledContentColor = AppColors.TextSecondary
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
    color: Color = AppColors.TextPrimary,
    icon: ImageVector? = null
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = AppColors.TextSecondary
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

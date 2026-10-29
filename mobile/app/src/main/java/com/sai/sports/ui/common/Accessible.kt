package com.sai.sports.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.SectionRule
import com.sai.sports.ui.theme.AppColors

/**
 * A screen title that screen readers announce as a heading, so a TalkBack user
 * can jump between sections rather than listening to every line in order.
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { heading() }
    )
}

/** A section heading: a capitalised label over a rule, announced as a heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    SectionRule(title = text, modifier = modifier)
}

/** A title row with a back action. See [com.sai.sports.ui.kit.TitleBar]. */
@Composable
fun TitleBar(title: String, onBack: () -> Unit) {
    com.sai.sports.ui.kit.TitleBar(title = title, onBack = onBack)
}

/**
 * An error, in red pen, announced as soon as it appears.
 *
 * Without the live region, a screen-reader user who taps "Send code" and gets
 * a failure hears nothing at all.
 */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = AppColors.Danger,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** An error with a retry, for anything loaded over a connection that may drop. */
@Composable
fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ErrorText(message)
        LineButton(text = stringResource(R.string.action_try_again), onClick = onRetry)
    }
}

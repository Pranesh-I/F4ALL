package com.sai.sports.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sai.sports.R

/**
 * A screen title that screen readers announce as a heading, so a TalkBack user
 * can jump between sections rather than listening to every line in order.
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() }
    )
}

/** A title row with a back action. */
@Composable
fun TitleBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScreenTitle(title, modifier = Modifier.weight(1f))
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

/**
 * An error announced as soon as it appears.
 *
 * Without the live region, a screen-reader user who taps "Send code" and gets
 * a failure hears nothing at all.
 */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** An error with a retry, for anything loaded over a connection that may drop. */
@Composable
fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorText(message)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
    }
}

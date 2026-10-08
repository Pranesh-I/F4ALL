package com.sai.sports.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sai.sports.R
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.theme.TrialsColors

/**
 * Tells an athlete whose profile is not ready for official tests what to do
 * about it — before they walk to a session and find out at the camera.
 *
 * A red-pen note: something to act on, not an error. [missing] is what the
 * home screen last heard from the server (or what this phone last knew).
 */
@Composable
internal fun ProfileCompletionBanner(missing: List<String>, onCompleteProfile: () -> Unit) {
    if (missing.isEmpty()) return

    Sheet(style = SheetStyle.Note) {
        Text(
            stringResource(R.string.home_profile_incomplete),
            style = MaterialTheme.typography.titleMedium,
            color = TrialsColors.RedPen
        )
        Text(stringResource(R.string.home_profile_incomplete_note), style = MaterialTheme.typography.bodyMedium)
        InkButton(text = stringResource(R.string.profile_complete_action), onClick = onCompleteProfile)
    }
}

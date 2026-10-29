package com.sai.sports.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.theme.AppColors

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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, AppColors.Warning.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(AppColors.SurfaceHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.User, contentDescription = null, tint = AppColors.Warning, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.home_profile_incomplete),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
            Text(
                stringResource(R.string.home_profile_incomplete_note),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
            InkButton(text = stringResource(R.string.profile_complete_action), onClick = onCompleteProfile)
        }
    }
}

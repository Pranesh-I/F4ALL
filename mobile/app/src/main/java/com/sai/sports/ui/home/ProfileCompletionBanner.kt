package com.sai.sports.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.data.ProfileStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tells an athlete whose profile is not ready for official tests what to do
 * about it — before they walk to a session and find out at the camera.
 *
 * Shows what this phone last knew at once, then asks the server.
 */
@Composable
internal fun ProfileCompletionBanner(athleteId: String?, onCompleteProfile: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProfileStatusStore(context) }
    var missing by remember(athleteId) { mutableStateOf(athleteId?.let(store::missing).orEmpty()) }

    LaunchedEffect(athleteId) {
        if (athleteId == null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { AppServices.api(context).profile() }
        if (result is ApiResult.Success) {
            store.save(result.value)
            missing = result.value.missing
        }
    }

    if (missing.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.home_profile_incomplete), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_profile_incomplete_note), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onCompleteProfile) {
                Text(stringResource(R.string.profile_complete_action))
            }
        }
    }
}

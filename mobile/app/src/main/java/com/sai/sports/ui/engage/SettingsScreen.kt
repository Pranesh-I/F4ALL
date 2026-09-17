package com.sai.sports.ui.engage

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.SectionTitle
import com.sai.sports.ui.common.TitleBar
import com.sai.sports.ui.onboarding.LanguagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { AppServices.api(context) }
    val session = remember { AppServices.session(context) }

    // Null until the server has answered: the switch must show the athlete's
    // real setting, never a default that could mislead them about who sees them.
    var optIn by remember { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        when (val result = withContext(Dispatchers.IO) { api.profile() }) {
            is ApiResult.Success -> optIn = result.value.leaderboardOptIn
            is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.error_load)
        }
    }

    fun setOptIn(value: Boolean) {
        saving = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.updatePreferences(leaderboardOptIn = value) }
            saving = false
            when (result) {
                is ApiResult.Success -> optIn = result.value.leaderboardOptIn
                is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.settings_error_save)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TitleBar(stringResource(R.string.settings_title), onBack)

        SectionTitle(stringResource(R.string.settings_language))
        LanguagePicker(
            selected = LanguageStore.current(context),
            onSelected = { language ->
                LanguageStore.set(context, language)
                scope.launch(Dispatchers.IO) {
                    api.updatePreferences(preferredLanguage = language.tag)
                }
                // Recreate so every screen re-reads its strings.
                (context as? Activity)?.recreate()
            }
        )

        HorizontalDivider()

        SectionTitle(stringResource(R.string.settings_privacy))

        val current = optIn
        if (current == null && error == null) {
            CircularProgressIndicator()
        } else if (current != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = current,
                        enabled = !saving,
                        role = Role.Switch,
                        onValueChange = ::setOptIn
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_show_on_leaderboard), modifier = Modifier.weight(1f))
                // The row handles the toggle, so the whole line is one large
                // touch target and one screen-reader stop.
                Switch(checked = current, onCheckedChange = null, enabled = !saving)
            }
            Text(
                stringResource(
                    if (current) R.string.settings_visible_explainer else R.string.settings_hidden_explainer
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }

        error?.let { ErrorText(stringResource(it)) }

        Text(stringResource(R.string.settings_data_note), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()

        OutlinedButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // Best effort: signing out must work with no signal.
                        runCatching { api.logout(session.current()?.refreshToken) }
                        session.clear()
                    }
                    onLoggedOut()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_sign_out))
        }
    }
}

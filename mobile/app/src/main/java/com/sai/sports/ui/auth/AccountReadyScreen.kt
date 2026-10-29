package com.sai.sports.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.data.ProfileStatusStore
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.Bib
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.SuccessBurst
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The end of creating an account: the athlete's chest number, handed over
 * with a moment of ceremony. It is the number SAI officials see beside their
 * results, so it is worth a second look before going on.
 */
@Composable
fun AccountReadyScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val store = remember { ProfileStatusStore(context) }
    var card by remember(athleteId) { mutableStateOf(athleteId?.let(store::card)) }

    // Just registered: the phone has not cached the profile yet.
    LaunchedEffect(athleteId) {
        val id = athleteId ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { AppServices.api(context).profile() }
        if (result is ApiResult.Success) {
            store.save(result.value)
            card = store.card(id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SuccessBurst()
        Text(
            stringResource(R.string.ready_title),
            style = MaterialTheme.typography.headlineLarge,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            stringResource(R.string.ready_body),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Bib(
            number = athleteId?.let(BibNumber::of) ?: "—",
            name = card?.name,
            detail = card?.region
        )
        Spacer(Modifier.height(8.dp))
        MarigoldButton(
            text = stringResource(R.string.ready_action),
            onClick = onContinue,
            icon = AppIcons.Forward,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

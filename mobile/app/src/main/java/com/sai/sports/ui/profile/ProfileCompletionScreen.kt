package com.sai.sports.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.api.AthleteProfile
import com.sai.sports.api.ConsentPurpose
import com.sai.sports.auth.AppServices
import com.sai.sports.data.ProfileStatusStore
import com.sai.sports.ui.auth.ConsentCard
import com.sai.sports.ui.auth.ConsentRules
import com.sai.sports.ui.auth.REGISTRATION_CONSENT_POINTS
import com.sai.sports.ui.auth.ReferencePhotoStep
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.common.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Walks the athlete through whatever stands between them and an official
 * test, one step at a time, in the order the server lists it: where they
 * live, consent to hold the profile, consent to face verification, the photo.
 *
 * Athletes registered before Sprint 8 land here too — nothing was assumed on
 * their behalf, so they are asked.
 */
@Composable
fun ProfileCompletionScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val statusStore = remember { ProfileStatusStore(context) }

    var profile by remember { mutableStateOf<AthleteProfile?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        when (val result = withContext(Dispatchers.IO) { api.profile() }) {
            is ApiResult.Success -> {
                profile = result.value
                statusStore.save(result.value)
            }
            is ApiResult.Failure -> error = Labels.failure(result.kind, R.string.error_load)
        }
    }

    val current = profile
    val next = current?.missing?.firstOrNull()

    // The photo step is a full screen of its own.
    if (current != null && (next == PHOTO || next == FACE_CONSENT)) {
        ReferencePhotoStep(
            minor = ConsentRules.needsGuardian(current.ageYears),
            faceConsentGiven = FACE_CONSENT !in current.missing,
            onDone = { reload += 1 }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TitleBar(stringResource(R.string.complete_profile_title), onBack)

        when {
            current == null && error == null -> CircularProgressIndicator()
            current == null -> LoadFailed(stringResource(error!!)) { reload += 1 }
            next == null -> {
                Text(stringResource(R.string.complete_profile_done), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_done))
                }
            }
            next == CITY -> WhereStep(current) { updated -> profile = updated; statusStore.save(updated) }
            else -> RegistrationConsentStep(current) { updated -> profile = updated; statusStore.save(updated) }
        }
    }
}

@Composable
private fun WhereStep(profile: AthleteProfile, onSaved: (AthleteProfile) -> Unit) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val scope = rememberCoroutineScope()

    var city by rememberSaveable { mutableStateOf(profile.city.orEmpty()) }
    var place by rememberSaveable { mutableStateOf(profile.place.orEmpty()) }
    var achievements by rememberSaveable { mutableStateOf(profile.achievements.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }

    Text(stringResource(R.string.complete_profile_where), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = city,
        onValueChange = { city = it.take(100) },
        label = { Text(stringResource(R.string.register_city)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = place,
        onValueChange = { place = it.take(100) },
        label = { Text(stringResource(R.string.register_place)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = achievements,
        onValueChange = { achievements = it.take(1000) },
        label = { Text(stringResource(R.string.register_achievements)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
    problem?.let { ErrorText(stringResource(it)) }

    if (busy) {
        CircularProgressIndicator()
    } else {
        Button(
            onClick = {
                if (city.isBlank()) {
                    problem = R.string.register_error_city
                    return@Button
                }
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        api.updateProfile(city = city.trim(), place = place.trim(), achievements = achievements.trim())
                    }
                    busy = false
                    when (result) {
                        is ApiResult.Success -> onSaved(result.value)
                        is ApiResult.Failure -> problem = Labels.failure(result.kind, R.string.error_load)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.action_save)) }
    }
}

@Composable
private fun RegistrationConsentStep(profile: AthleteProfile, onSaved: (AthleteProfile) -> Unit) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val scope = rememberCoroutineScope()
    val minor = ConsentRules.needsGuardian(profile.ageYears)

    var agreed by rememberSaveable { mutableStateOf(false) }
    var guardianName by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }

    Text(stringResource(R.string.complete_profile_consent), style = MaterialTheme.typography.bodyMedium)
    ConsentCard(
        title = R.string.consent_registration_title,
        points = REGISTRATION_CONSENT_POINTS,
        minor = minor,
        agreed = agreed,
        onAgreedChange = { agreed = it },
        guardianName = guardianName,
        onGuardianNameChange = { guardianName = it }
    )
    problem?.let { ErrorText(stringResource(it)) }

    if (busy) {
        CircularProgressIndicator()
    } else {
        Button(
            onClick = {
                val grant = ConsentRules.grant(agreed, minor, guardianName).getOrElse { missing ->
                    problem = (missing as ConsentRules.ConsentMissing).messageRes
                    return@Button
                }
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        api.giveConsent(ConsentPurpose.REGISTRATION, grant)
                    }
                    busy = false
                    when (result) {
                        is ApiResult.Success -> onSaved(result.value)
                        is ApiResult.Failure -> problem = Labels.failure(result.kind, R.string.error_load)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.action_save)) }
    }
}

private const val CITY = "city"
private const val FACE_CONSENT = "face_consent"
private const val PHOTO = "photo"

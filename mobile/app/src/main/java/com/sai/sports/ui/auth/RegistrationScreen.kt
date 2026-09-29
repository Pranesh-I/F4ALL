package com.sai.sports.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Registration
import com.sai.sports.ui.common.SectionTitle
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.data.Regions
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.ScreenTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Creates the athlete's profile, then asks for a registration photo.
 *
 * Date of birth and gender cannot be edited afterwards — they choose the
 * comparison group — so the screen says so before the athlete commits.
 *
 * Nothing is sent without consent to hold it; for an athlete under 18 that
 * consent comes from a named parent or guardian, which the form asks for as
 * soon as the date of birth shows a minor.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RegistrationScreen(
    onRegistered: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { AppServices.api(context) }
    val session = remember { AppServices.session(context) }
    val profileStore = remember { AthleteProfileStore(context) }

    var name by rememberSaveable { mutableStateOf("") }
    var dobEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var gender by rememberSaveable { mutableStateOf<String?>(null) }
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    var height by rememberSaveable { mutableStateOf(profileStore.heightCm()?.toInt()?.toString() ?: "") }
    var weight by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    var achievements by rememberSaveable { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var guardianName by rememberSaveable { mutableStateOf("") }
    var profileCreated by rememberSaveable { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var regionMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<RegistrationRules.Problem?>(null) }

    val dateOfBirth = dobEpochDay?.let(LocalDate::ofEpochDay)
    val minor = dateOfBirth?.let { ConsentRules.needsGuardian(RegistrationRules.ageOn(it)) } ?: true

    if (profileCreated) {
        ReferencePhotoStep(
            minor = minor,
            faceConsentGiven = false,
            initialGuardianName = guardianName,
            onDone = onRegistered,
            onSkip = onRegistered
        )
        return
    }

    fun submit() {
        problem = RegistrationRules.problem(name, dateOfBirth, gender, region, city, height, weight)
        if (problem != null) return
        val consent = ConsentRules.grant(agreed, minor, guardianName).getOrElse { missing ->
            problem = RegistrationRules.Problem((missing as ConsentRules.ConsentMissing).messageRes)
            return
        }

        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                api.register(
                    Registration(
                        name = name.trim(),
                        dateOfBirthIso = dateOfBirth.toString(),
                        gender = gender!!,
                        region = region!!,
                        city = city.trim(),
                        place = place.trim().ifEmpty { null },
                        heightCm = height.toDouble(),
                        weightKg = weight.toDoubleOrNull(),
                        achievements = achievements.trim().ifEmpty { null },
                        consent = consent
                    )
                )
            }

            when (result) {
                is ApiResult.Success -> {
                    withContext(Dispatchers.IO) {
                        // Replace the registering session outright. It names a
                        // phone rather than an athlete, the server has revoked
                        // it, and it cannot upload or submit anything.
                        session.store(result.value.tokens)
                        result.value.profile.heightCm?.let(profileStore::setHeightCm)
                        LanguageStore.current(context)?.let {
                            api.updatePreferences(preferredLanguage = it.tag)
                        }
                    }
                    SyncScheduler.syncNow(context)
                    busy = false
                    profileCreated = true
                }
                is ApiResult.Failure -> {
                    busy = false
                    problem = RegistrationRules.Problem(
                        when (result.kind) {
                            ApiFailure.CONFLICT -> R.string.register_error_exists
                            else -> Labels.failure(result.kind, R.string.register_error_generic)
                        }
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dobEpochDay?.let { it * MILLIS_PER_DAY }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        dobEpochDay = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .toEpochDay()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle(stringResource(R.string.register_title))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(150) },
            label = { Text(stringResource(R.string.register_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                dateOfBirth?.let { stringResource(R.string.register_dob_value, it.toString()) }
                    ?: stringResource(R.string.register_dob_choose)
            )
        }

        Text(stringResource(R.string.register_gender), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RegistrationRules.GENDERS.forEach { value ->
                FilterChip(
                    selected = gender == value,
                    onClick = { gender = value },
                    label = { Text(stringResource(Labels.gender(value))) }
                )
            }
        }

        Box {
            OutlinedButton(onClick = { regionMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(region ?: stringResource(R.string.register_region_choose))
            }
            DropdownMenu(expanded = regionMenuOpen, onDismissRequest = { regionMenuOpen = false }) {
                Regions.ALL.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            region = option
                            regionMenuOpen = false
                        }
                    )
                }
            }
        }

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
            value = height,
            onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
            label = { Text(stringResource(R.string.register_height)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
            label = { Text(stringResource(R.string.register_weight)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = achievements,
            onValueChange = { achievements = it.take(1000) },
            label = { Text(stringResource(R.string.register_achievements)) },
            supportingText = { Text(stringResource(R.string.register_achievements_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )

        Text(stringResource(R.string.register_cohort_note), style = MaterialTheme.typography.bodySmall)

        SectionTitle(stringResource(R.string.consent_title))
        ConsentCard(
            title = R.string.consent_registration_title,
            points = REGISTRATION_CONSENT_POINTS,
            minor = minor,
            agreed = agreed,
            onAgreedChange = { agreed = it },
            guardianName = guardianName,
            onGuardianNameChange = { guardianName = it }
        )

        problem?.let {
            ErrorText(stringResource(it.message, *it.args.toTypedArray()))
        }

        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = ::submit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.register_submit))
            }
        }
    }
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

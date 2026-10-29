package com.sai.sports.ui.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Registration
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.data.Regions
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.ChoiceChip
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.StepBar
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Creating an account, as the athlete counts it: phone, four short steps, a photo. */
object RegistrationSteps {
    const val PHONE = 1
    const val ABOUT = 2
    const val PLACE = 3
    const val BODY = 4
    const val CONSENT = 5
    const val PHOTO = 6
    const val TOTAL = 6
}

/**
 * Creates the athlete's profile one short step at a time, then asks for a
 * registration photo.
 *
 * One question at a time, because a single long form is where people give up
 * on a phone. Each step is checked before moving on, so a mistake is found
 * where it was made.
 *
 * Date of birth and gender cannot be edited afterwards — they choose the
 * comparison group — so the step says so before the athlete commits. Nothing
 * is sent without consent to hold it; for an athlete under 18 that consent
 * comes from a named parent or guardian, which the consent step asks for as
 * soon as the date of birth shows a minor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegistrationScreen(
    onRegistered: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { AppServices.api(context) }
    val session = remember { AppServices.session(context) }
    val profileStore = remember { AthleteProfileStore(context) }

    var step by rememberSaveable { mutableIntStateOf(RegistrationSteps.ABOUT) }
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

    var showDatePicker by remember { mutableStateOf(false) }
    var regionMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<RegistrationRules.Problem?>(null) }

    val dateOfBirth = dobEpochDay?.let(LocalDate::ofEpochDay)
    val minor = dateOfBirth?.let { ConsentRules.needsGuardian(RegistrationRules.ageOn(it)) } ?: true

    if (step == RegistrationSteps.PHOTO) {
        ReferencePhotoStep(
            minor = minor,
            faceConsentGiven = false,
            initialGuardianName = guardianName,
            onDone = onRegistered,
            onSkip = onRegistered,
            registrationStep = RegistrationSteps.PHOTO
        )
        return
    }

    fun advance(check: () -> RegistrationRules.Problem?) {
        problem = check()
        if (problem == null) step += 1
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
                    step = RegistrationSteps.PHOTO
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

    val back: (() -> Unit)? = if (step > RegistrationSteps.ABOUT && !busy) {
        {
            problem = null
            step -= 1
        }
    } else {
        null
    }

    Page(header = { TitleBar(title = "", onBack = back) }, spacing = 18.dp) {
        StepBar(step = step, steps = RegistrationSteps.TOTAL)

        when (step) {
            RegistrationSteps.ABOUT -> {
                StepHeading(R.string.register_about_title, R.string.register_cohort_note)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(150) },
                    label = { Text(stringResource(R.string.register_name)) },
                    singleLine = true,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                PickerField(
                    icon = AppIcons.Calendar,
                    text = dateOfBirth?.let { stringResource(R.string.register_dob_value, it.toString()) }
                        ?: stringResource(R.string.register_dob_choose),
                    filled = dateOfBirth != null,
                    onClick = { showDatePicker = true }
                )
                Text(
                    stringResource(R.string.register_gender),
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextSecondary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RegistrationRules.GENDERS.forEach { value ->
                        ChoiceChip(
                            selected = gender == value,
                            onClick = { gender = value },
                            label = stringResource(Labels.gender(value))
                        )
                    }
                }
            }

            RegistrationSteps.PLACE -> {
                StepHeading(R.string.register_place_title, R.string.register_place_body)
                Box {
                    PickerField(
                        icon = AppIcons.Globe,
                        text = region ?: stringResource(R.string.register_region_choose),
                        filled = region != null,
                        onClick = { regionMenuOpen = true }
                    )
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
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it.take(100) },
                    label = { Text(stringResource(R.string.register_place)) },
                    singleLine = true,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            RegistrationSteps.BODY -> {
                StepHeading(R.string.register_body_title, R.string.register_body_body)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text(stringResource(R.string.register_height)) },
                        singleLine = true,
                        shape = FieldShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text(stringResource(R.string.register_weight)) },
                        singleLine = true,
                        shape = FieldShape,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = achievements,
                    onValueChange = { achievements = it.take(1000) },
                    label = { Text(stringResource(R.string.register_achievements)) },
                    supportingText = { Text(stringResource(R.string.register_achievements_hint)) },
                    minLines = 3,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            RegistrationSteps.CONSENT -> {
                StepHeading(R.string.consent_title, R.string.register_consent_body)
                ConsentCard(
                    title = R.string.consent_registration_title,
                    points = REGISTRATION_CONSENT_POINTS,
                    minor = minor,
                    agreed = agreed,
                    onAgreedChange = { agreed = it },
                    guardianName = guardianName,
                    onGuardianNameChange = { guardianName = it }
                )
            }
        }

        problem?.let {
            ErrorText(stringResource(it.message, *it.args.toTypedArray()))
        }

        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Accent)
            }
        } else {
            MarigoldButton(
                text = stringResource(
                    if (step == RegistrationSteps.CONSENT) R.string.register_submit else R.string.action_continue
                ),
                onClick = {
                    when (step) {
                        RegistrationSteps.ABOUT -> advance { RegistrationRules.aboutProblem(name, dateOfBirth, gender) }
                        RegistrationSteps.PLACE -> advance { RegistrationRules.placeProblem(region, city) }
                        RegistrationSteps.BODY -> advance { RegistrationRules.bodyProblem(height, weight) }
                        else -> submit()
                    }
                },
                icon = if (step == RegistrationSteps.CONSENT) AppIcons.Check else AppIcons.Forward,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StepHeading(@StringRes title: Int, @StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
    }
}

/** A tappable field that opens a picker (a date, a state) instead of a keyboard. */
@Composable
private fun PickerField(icon: ImageVector, text: String, filled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(FieldShape)
            .background(AppColors.SurfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (filled) AppColors.TextPrimary else AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Icon(AppIcons.ChevronDown, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

private val FieldShape = RoundedCornerShape(14.dp)

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

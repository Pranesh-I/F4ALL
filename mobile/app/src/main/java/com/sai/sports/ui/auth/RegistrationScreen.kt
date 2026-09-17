package com.sai.sports.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sai.sports.api.ApiResult
import com.sai.sports.api.Registration
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AthleteProfileStore
import com.sai.sports.data.Regions
import com.sai.sports.sync.SyncScheduler
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
 * benchmark cohort — so the screen says so before the athlete commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var profileCreated by rememberSaveable { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var regionMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val dateOfBirth = dobEpochDay?.let(LocalDate::ofEpochDay)

    if (profileCreated) {
        ReferencePhotoStep(onDone = onRegistered)
        return
    }

    fun submit() {
        error = RegistrationRules.problem(name, dateOfBirth, gender, region, height, weight)
        if (error != null) return

        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                api.register(
                    Registration(
                        name = name.trim(),
                        dateOfBirthIso = dateOfBirth.toString(),
                        gender = gender!!,
                        region = region!!,
                        heightCm = height.toDouble(),
                        weightKg = weight.toDoubleOrNull()
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
                    }
                    SyncScheduler.syncNow(context)
                    busy = false
                    profileCreated = true
                }
                is ApiResult.Failure -> {
                    busy = false
                    error = result.message
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
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
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
        Text("Create your athlete profile", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(150) },
            label = { Text("Full name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(dateOfBirth?.let { "Date of birth: $it" } ?: "Choose date of birth")
        }

        Text("Gender", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RegistrationRules.GENDERS.forEach { (value, label) ->
                FilterChip(
                    selected = gender == value,
                    onClick = { gender = value },
                    label = { Text(label) }
                )
            }
        }

        Box {
            OutlinedButton(onClick = { regionMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(region ?: "Choose state / union territory")
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
            value = height,
            onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
            label = { Text("Height (cm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
            label = { Text("Weight (kg, optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Your date of birth and gender decide which age group your results " +
                "are compared with, and cannot be changed later in the app.",
            style = MaterialTheme.typography.bodySmall
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = ::submit, modifier = Modifier.fillMaxWidth()) {
                Text("Create profile")
            }
        }
    }
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

package com.sai.sports.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.ScreenTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone number, then the code sent to it.
 *
 * Deliberately two steps on one screen rather than two screens: an athlete
 * whose SMS never arrives needs to see their number and a resend button in the
 * same place, not navigate back to find out what they typed.
 */
@Composable
fun LoginScreen(
    onLoggedIn: (registered: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { AppServices.api(context) }
    val session = remember { AppServices.session(context) }

    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var developmentCode by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableIntStateOf(0) }

    LaunchedEffect(resendIn) {
        if (resendIn > 0) {
            delay(1000)
            resendIn -= 1
        }
    }

    fun sendCode() {
        val digits = phone.filter { it.isDigit() }
        if (!LoginRules.isPlausiblePhone(digits)) {
            error = R.string.login_error_phone
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.requestOtp(digits) }
            busy = false
            when (result) {
                is ApiResult.Success -> {
                    codeSent = true
                    resendIn = LoginRules.RESEND_SECONDS
                    developmentCode = result.value.developmentCode
                }
                is ApiResult.Failure -> {
                    error = Labels.failure(result.kind, R.string.login_error_send)
                    result.retryAfterSeconds?.let { resendIn = it }
                }
            }
        }
    }

    fun verify() {
        if (code.length != LoginRules.CODE_LENGTH) {
            error = R.string.login_error_code_length
            return
        }
        busy = true
        error = null
        scope.launch {
            val digits = phone.filter { it.isDigit() }
            val result = withContext(Dispatchers.IO) { api.verifyOtp(digits, code) }
            busy = false
            when (result) {
                is ApiResult.Success -> {
                    withContext(Dispatchers.IO) { session.store(result.value) }
                    if (result.value.registered) {
                        // Anything recorded before signing in can go now.
                        SyncScheduler.syncNow(context)
                        // Remember the language chosen during onboarding on the
                        // account too. Best effort; the phone's choice stands.
                        LanguageStore.current(context)?.let { language ->
                            launch(Dispatchers.IO) {
                                api.updatePreferences(preferredLanguage = language.tag)
                            }
                        }
                    }
                    onLoggedIn(result.value.registered)
                }
                is ApiResult.Failure -> error = when (result.kind) {
                    ApiFailure.INVALID -> R.string.login_error_code_wrong
                    else -> Labels.failure(result.kind, R.string.login_error_code_wrong)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenTitle(stringResource(R.string.login_title))

        Text(stringResource(R.string.login_body), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) },
            label = { Text(stringResource(R.string.login_phone_label)) },
            enabled = !codeSent && !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        if (codeSent) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(LoginRules.CODE_LENGTH) },
                label = { Text(stringResource(R.string.login_code_label, LoginRules.CODE_LENGTH)) },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
        }

        developmentCode?.let {
            Text(stringResource(R.string.login_dev_code, it), style = MaterialTheme.typography.bodySmall)
        }

        error?.let { ErrorText(stringResource(it)) }

        if (busy) {
            CircularProgressIndicator()
        } else if (!codeSent) {
            Button(onClick = ::sendCode, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.login_send_code))
            }
        } else {
            Button(onClick = ::verify, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.login_verify))
            }

            TextButton(onClick = ::sendCode, enabled = resendIn == 0) {
                Text(
                    if (resendIn > 0) stringResource(R.string.login_resend_in, resendIn)
                    else stringResource(R.string.login_resend)
                )
            }

            TextButton(onClick = {
                codeSent = false
                code = ""
                developmentCode = null
                error = null
            }) {
                Text(stringResource(R.string.login_change_number))
            }
        }
    }
}

/** Input rules, kept out of the composable so they are unit-tested. */
object LoginRules {
    const val CODE_LENGTH = 6
    const val RESEND_SECONDS = 60

    /** Ten digits, optionally prefixed with the 91 country code. */
    fun isPlausiblePhone(digits: String): Boolean =
        digits.length == 10 || (digits.length == 12 && digits.startsWith("91"))
}

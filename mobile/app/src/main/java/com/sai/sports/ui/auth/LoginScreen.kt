package com.sai.sports.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.sync.SyncScheduler
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
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
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
            error = "Enter a 10-digit mobile number"
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
                    hint = result.value.developmentCode?.let {
                        "Development server: your code is $it"
                    }
                }
                is ApiResult.Failure -> {
                    error = result.message
                    result.retryAfterSeconds?.let { resendIn = it }
                }
            }
        }
    }

    fun verify() {
        if (code.length != LoginRules.CODE_LENGTH) {
            error = "Enter the ${LoginRules.CODE_LENGTH}-digit code"
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
                    }
                    onLoggedIn(result.value.registered)
                }
                is ApiResult.Failure -> error = result.message
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sign in", style = MaterialTheme.typography.headlineSmall)

        Text(
            "We'll send a code to your phone to confirm it's you.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) },
            label = { Text("Mobile number") },
            enabled = !codeSent && !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        if (codeSent) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(LoginRules.CODE_LENGTH) },
                label = { Text("${LoginRules.CODE_LENGTH}-digit code") },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
        }

        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (busy) {
            CircularProgressIndicator()
        } else if (!codeSent) {
            Button(onClick = ::sendCode, modifier = Modifier.fillMaxWidth()) {
                Text("Send code")
            }
        } else {
            Button(onClick = ::verify, modifier = Modifier.fillMaxWidth()) {
                Text("Verify")
            }

            TextButton(onClick = ::sendCode, enabled = resendIn == 0) {
                Text(if (resendIn > 0) "Resend code in ${resendIn}s" else "Resend code")
            }

            TextButton(onClick = {
                codeSent = false
                code = ""
                hint = null
                error = null
            }) {
                Text("Change number")
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

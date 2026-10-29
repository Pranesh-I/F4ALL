package com.sai.sports.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.sync.PracticeSyncWorker
import com.sai.sports.sync.SyncScheduler
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.StepBar
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which door the athlete came in by. Both verify the phone; only the words differ. */
enum class AuthMode { SIGN_IN, CREATE }

/**
 * Phone number, then the code sent to it.
 *
 * Deliberately two steps on one screen rather than two screens: an athlete
 * whose SMS never arrives needs to see their number and a resend button in the
 * same place, not navigate back to find out what they typed.
 *
 * A new athlete who came in by "Sign in", or a returning one who came in by
 * "Create account", is not turned away: the server says which they are, and
 * the app goes on to registration or home accordingly.
 */
@Composable
fun LoginScreen(
    mode: AuthMode = AuthMode.SIGN_IN,
    onBack: (() -> Unit)? = null,
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
                        // Anything recorded before signing in can go now,
                        // and this account's practice comes to this phone.
                        SyncScheduler.syncNow(context)
                        PracticeSyncWorker.syncSoon(context)
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

    val creating = mode == AuthMode.CREATE

    Page(
        header = { TitleBar(title = "", onBack = onBack) },
        spacing = 20.dp
    ) {
        if (creating) StepBar(step = 1, steps = RegistrationSteps.TOTAL)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when {
                        codeSent -> R.string.login_code_title
                        creating -> R.string.create_phone_title
                        else -> R.string.signin_title
                    }
                ),
                style = MaterialTheme.typography.headlineLarge,
                color = AppColors.TextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                if (codeSent) {
                    stringResource(R.string.login_code_sent, "+91 " + phone.filter { it.isDigit() }.takeLast(10))
                } else {
                    stringResource(if (creating) R.string.create_phone_body else R.string.login_body)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary
            )
        }

        if (!codeSent) {
            PhoneField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() || c == ' ' }.take(14) },
                enabled = !busy
            )
        } else {
            CodeBoxes(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(LoginRules.CODE_LENGTH) },
                enabled = !busy
            )
        }

        developmentCode?.let {
            Text(stringResource(R.string.login_dev_code, it), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }

        error?.let { ErrorText(stringResource(it)) }

        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Accent)
            }
        } else if (!codeSent) {
            MarigoldButton(
                text = stringResource(R.string.login_send_code),
                onClick = ::sendCode,
                icon = AppIcons.Forward,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            MarigoldButton(
                text = stringResource(R.string.login_verify),
                onClick = ::verify,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PlainButton(
                    text = stringResource(R.string.login_change_number),
                    onClick = {
                        codeSent = false
                        code = ""
                        developmentCode = null
                        error = null
                    }
                )
                PlainButton(
                    text = if (resendIn > 0) {
                        stringResource(R.string.login_resend_in, resendIn)
                    } else {
                        stringResource(R.string.login_resend)
                    },
                    onClick = ::sendCode,
                    enabled = resendIn == 0,
                    color = AppColors.Accent
                )
            }
        }
    }
}

/** A mobile number with the country code shown, not typed. */
@Composable
private fun PhoneField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .background(AppColors.SurfaceHigh, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(AppIcons.Phone, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
            Text("+91", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.login_phone_label)) },
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The code as six boxes, filled as it is typed. One text field underneath,
 * so the keyboard, paste and the SMS autofill suggestion all work as usual,
 * and a screen reader hears one field with its label.
 */
@Composable
private fun CodeBoxes(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val focus = remember { FocusRequester() }
    val label = stringResource(R.string.login_code_label, LoginRules.CODE_LENGTH)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(AppColors.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentDescription = label },
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(LoginRules.CODE_LENGTH) { index ->
                    val digit = value.getOrNull(index)?.toString().orEmpty()
                    val current = index == value.length
                    Box(
                        Modifier
                            .weight(1f)
                            .height(60.dp)
                            .background(AppColors.SurfaceHigh, RoundedCornerShape(14.dp))
                            .border(
                                1.5.dp,
                                if (current && enabled) AppColors.Accent else AppColors.SurfaceHigh,
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(digit, style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
                    }
                }
            }
        }
    )
}

/** Input rules, kept out of the composable so they are unit-tested. */
object LoginRules {
    const val CODE_LENGTH = 6
    const val RESEND_SECONDS = 60

    /** Ten digits, optionally prefixed with the 91 country code. */
    fun isPlausiblePhone(digits: String): Boolean =
        digits.length == 10 || (digits.length == 12 && digits.startsWith("91"))
}

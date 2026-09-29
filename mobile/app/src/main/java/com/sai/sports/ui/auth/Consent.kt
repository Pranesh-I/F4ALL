package com.sai.sports.ui.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ConsentGrant

/**
 * The rules for a consent the app asks for.
 *
 * Most athletes are minors, and consent for a minor comes from a parent or
 * guardian, who is named. The server enforces the same rule; this is so the
 * form asks for the right person in the first place.
 */
object ConsentRules {

    /**
     * Which consent wording the app shows. Bump it whenever the text of
     * `consent_*` strings changes, so the server records what was agreed to.
     */
    const val VERSION = "2026-09"

    const val ADULT_AGE_YEARS = 18

    fun needsGuardian(ageYears: Int): Boolean = ageYears < ADULT_AGE_YEARS

    /** The consent as given, or the problem with it. */
    fun grant(agreed: Boolean, minor: Boolean, guardianName: String): Result<ConsentGrant> {
        val name = guardianName.trim()
        return when {
            !agreed -> Result.failure(ConsentMissing(R.string.consent_error_agree))
            minor && name.isEmpty() -> Result.failure(ConsentMissing(R.string.consent_error_guardian))
            else -> Result.success(
                ConsentGrant(
                    version = VERSION,
                    givenBy = if (minor) "guardian" else "self",
                    guardianName = name.takeIf { minor }
                )
            )
        }
    }

    class ConsentMissing(@StringRes val messageRes: Int) : IllegalStateException()
}

/**
 * A consent the athlete — or their guardian — reads and agrees to.
 *
 * [points] are the plain-language things being agreed to, one per line, so
 * the text is short enough to actually be read on a phone.
 */
@Composable
fun ConsentCard(
    @StringRes title: Int,
    points: List<Int>,
    minor: Boolean,
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    guardianName: String,
    onGuardianNameChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            points.forEach { point ->
                Text("• " + stringResource(point), style = MaterialTheme.typography.bodySmall)
            }
            if (minor) {
                Text(stringResource(R.string.consent_guardian_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = guardianName,
                    onValueChange = { onGuardianNameChange(it.take(150)) },
                    label = { Text(stringResource(R.string.consent_guardian_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agreed, onCheckedChange = onAgreedChange)
                Text(
                    stringResource(if (minor) R.string.consent_agree_guardian else R.string.consent_agree_self),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** What agreeing to hold the profile means. */
val REGISTRATION_CONSENT_POINTS = listOf(
    R.string.consent_registration_point_data,
    R.string.consent_registration_point_use,
    R.string.consent_registration_point_share
)

/** What agreeing to face verification means. */
val FACE_CONSENT_POINTS = listOf(
    R.string.consent_face_point_photo,
    R.string.consent_face_point_check,
    R.string.consent_face_point_kept,
    R.string.consent_face_point_withdraw
)

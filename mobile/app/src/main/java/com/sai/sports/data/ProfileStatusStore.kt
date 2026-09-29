package com.sai.sports.data

import android.content.Context
import android.content.SharedPreferences
import com.sai.sports.api.AthleteProfile
import org.json.JSONArray

/**
 * The last known answer to "is this athlete's profile ready for official
 * tests?", per athlete.
 *
 * Kept so the home screen and the pre-test photo check can say what is
 * missing without signal. The server remains the judge: an athlete it
 * disagrees with finds out the moment the phone is online.
 */
class ProfileStatusStore(private val preferences: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    )

    fun save(profile: AthleteProfile) {
        preferences.edit()
            .putString(key(profile.athleteId), JSONArray(profile.missing).toString())
            .apply()
    }

    /** What is missing, or null when this phone has never seen the profile. */
    fun missing(athleteId: String): List<String>? =
        preferences.getString(key(athleteId), null)?.let { value ->
            runCatching {
                val array = JSONArray(value)
                (0 until array.length()).map(array::getString)
            }.getOrNull()
        }

    private fun key(athleteId: String) = "missing.$athleteId"

    private companion object {
        const val PREFERENCES = "profile_status"
    }
}

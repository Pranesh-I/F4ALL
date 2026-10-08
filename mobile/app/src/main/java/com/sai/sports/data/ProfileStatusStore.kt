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
            .putString(nameKey(profile.athleteId), profile.name)
            .putString(regionKey(profile.athleteId), profile.region)
            .apply()
    }

    /**
     * The name and state on the athlete's chest number, as this phone last
     * saw them — so the home screen can show who is signed in with no signal.
     * Null when the phone has never loaded the profile.
     */
    fun card(athleteId: String): AthleteCard? {
        val name = preferences.getString(nameKey(athleteId), null) ?: return null
        return AthleteCard(name = name, region = preferences.getString(regionKey(athleteId), null))
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
    private fun nameKey(athleteId: String) = "name.$athleteId"
    private fun regionKey(athleteId: String) = "region.$athleteId"

    private companion object {
        const val PREFERENCES = "profile_status"
    }
}

/** Who a chest number belongs to. */
data class AthleteCard(val name: String, val region: String?)

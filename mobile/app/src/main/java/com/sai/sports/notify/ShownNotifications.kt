package com.sai.sports.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Which inbox notifications this phone has already shown, per athlete.
 *
 * Kept per athlete so a shared phone never confuses two accounts' inboxes,
 * and capped so it cannot grow forever. Reading a notification is recorded on
 * the server; this only stops the phone buzzing twice for the same one.
 */
class ShownNotifications(private val preferences: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    )

    fun shown(athleteId: String): Set<String> =
        preferences.getString(key(athleteId), null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    /** Remembers [ids] as shown, keeping only the most recent [CAP]. */
    fun record(athleteId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val existing = preferences.getString(key(athleteId), null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val kept = (existing + ids).distinct().takeLast(CAP)
        preferences.edit { putString(key(athleteId), kept.joinToString(SEPARATOR)) }
    }

    private fun key(athleteId: String) = "shown_$athleteId"

    companion object {
        private const val FILE = "shown_notifications"
        private const val SEPARATOR = ","
        const val CAP = 300
    }
}

package com.sai.sports.data

import android.content.Context

/**
 * Holds the athlete's standing height, which the vertical jump calibration
 * cannot work without.
 *
 * INTERIM. Sprint 7 collects height during real registration and stores it
 * against the athlete's server profile; this exists so Sprint 3 can calibrate
 * without waiting four sprints. When registration lands, replace the backing
 * store and leave the call sites alone.
 *
 * Self-reported height is also a genuine accuracy limit on jump measurement,
 * not just a placeholder concern — an athlete who rounds 163cm up to 170cm
 * shifts every jump they record by about 4%.
 */
class AthleteProfileStore(
    context: Context
) {

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun heightCm(): Double? {
        val stored = preferences.getFloat(KEY_HEIGHT_CM, 0f)
        return if (stored <= 0f) null else stored.toDouble()
    }

    fun setHeightCm(heightCm: Double) {
        preferences.edit()
            .putFloat(KEY_HEIGHT_CM, heightCm.toFloat())
            .apply()
    }

    fun hasHeight(): Boolean = heightCm() != null

    companion object {

        private const val PREFERENCES_NAME = "athlete_profile"
        private const val KEY_HEIGHT_CM = "height_cm"

        /** Bounds for the entry field — catches typos, not edge cases. */
        const val MIN_HEIGHT_CM = 100.0
        const val MAX_HEIGHT_CM = 230.0

        fun isPlausible(heightCm: Double): Boolean =
            heightCm in MIN_HEIGHT_CM..MAX_HEIGHT_CM
    }
}

package com.sai.sports.ui.onboarding

import android.content.Context

/** Whether this phone has been through the first-run introduction. */
object OnboardingStore {

    private const val PREFERENCES = "onboarding"
    private const val KEY_DONE = "completed_v1"

    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markComplete(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }
}

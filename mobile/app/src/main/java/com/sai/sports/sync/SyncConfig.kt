package com.sai.sports.sync

import android.content.Context

/**
 * Where uploads are sent.
 *
 * The backend does not exist yet — Sprint 5 builds it — so this is a
 * configurable placeholder rather than a hardcoded production URL. Until then
 * the upload path is verified against MockWebServer in the unit tests and can
 * be pointed at a local FastAPI instance by overriding the stored value.
 *
 * Sprint 5 should replace this with a build-variant setting (debug -> staging,
 * release -> production) so a release build cannot possibly be pointed at a
 * developer machine.
 */
object SyncConfig {

    private const val PREFERENCES_NAME = "sync_config"
    private const val KEY_BASE_URL = "base_url"

    /**
     * Emulator uses 10.0.2.2 to reach host localhost.
     * Physical devices over USB use 127.0.0.1 (paired with `adb reverse tcp:8000 tcp:8000`).
     */
    private val isEmulator: Boolean
        get() = android.os.Build.FINGERPRINT.startsWith("generic")
            || android.os.Build.MODEL.contains("google_sdk")
            || android.os.Build.MODEL.contains("Emulator")
            || android.os.Build.HARDWARE.contains("goldfish")
            || android.os.Build.HARDWARE.contains("ranchu")

    val DEFAULT_BASE_URL: String
        get() = if (isEmulator) "http://10.0.2.2:8010" else "http://127.0.0.1:8010"

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, DEFAULT_BASE_URL)
            ?: DEFAULT_BASE_URL

    fun setBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, url.trimEnd('/'))
            .apply()
    }
}

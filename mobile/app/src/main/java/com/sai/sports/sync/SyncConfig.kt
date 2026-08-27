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
     * The Android emulator's alias for the host machine's loopback. A physical
     * test device needs the developer's LAN address instead, set via
     * [setBaseUrl].
     */
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"

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

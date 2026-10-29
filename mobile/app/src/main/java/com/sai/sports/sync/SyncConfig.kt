package com.sai.sports.sync

import android.content.Context
import com.sai.sports.BuildConfig

/**
 * Where the app talks to.
 *
 * A **release** build talks only to the HTTPS address fixed at build time
 * (`BuildConfig.API_BASE_URL`, from the `f4all.apiUrl` Gradle property): a
 * stored override is ignored, so nobody can point an athlete's phone at
 * another server, and the network security config refuses plain HTTP anyway.
 *
 * A **debug** build keeps the developer defaults — the emulator's 10.0.2.2 or
 * a USB-connected phone's 127.0.0.1 (with `adb reverse tcp:8010 tcp:8010`) —
 * and can be pointed elsewhere with [setBaseUrl].
 */
object SyncConfig {

    private const val PREFERENCES_NAME = "sync_config"
    private const val KEY_BASE_URL = "base_url"

    /**
     * Emulator uses 10.0.2.2 to reach host localhost.
     * Physical devices over USB use 127.0.0.1 (paired with `adb reverse tcp:8010 tcp:8010`).
     */
    private val isEmulator: Boolean
        get() = android.os.Build.FINGERPRINT.startsWith("generic")
            || android.os.Build.MODEL.contains("google_sdk")
            || android.os.Build.MODEL.contains("Emulator")
            || android.os.Build.HARDWARE.contains("goldfish")
            || android.os.Build.HARDWARE.contains("ranchu")

    val DEFAULT_BASE_URL: String
        get() = if (isEmulator) "http://10.0.2.2:8010" else "http://127.0.0.1:8010"

    fun baseUrl(context: Context): String = resolve(
        allowCustom = BuildConfig.ALLOW_CUSTOM_SERVER,
        releaseUrl = BuildConfig.API_BASE_URL,
        stored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, null),
        developerDefault = DEFAULT_BASE_URL
    )

    /** Kept free of Android so the release rule is unit-tested. */
    fun resolve(allowCustom: Boolean, releaseUrl: String, stored: String?, developerDefault: String): String =
        if (!allowCustom) releaseUrl else stored ?: developerDefault

    /** Debug builds only; a release build ignores what is stored. */
    fun setBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, url.trimEnd('/'))
            .apply()
    }
}

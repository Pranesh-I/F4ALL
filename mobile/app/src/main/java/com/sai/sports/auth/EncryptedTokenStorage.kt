package com.sai.sports.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tokens at rest, encrypted with a key held in the Android Keystore.
 *
 * A refresh token lives on the phone for months and mints sessions on a
 * minor's account. Plain SharedPreferences would hand it to anything that can
 * read the app's data directory — a rooted phone, an adb backup, a careless
 * file manager.
 *
 * The file is excluded from cloud backup and device transfer (see
 * `backup_rules.xml` and `data_extraction_rules.xml`). The Keystore key does not
 * travel with a backup, so a restored copy would be undecryptable ciphertext —
 * and a crash on first launch after a phone upgrade.
 */
@Suppress("DEPRECATION") // security-crypto is deprecated upstream but still the supported path here.
@android.annotation.SuppressLint("ApplySharedPref") // commit() is deliberate; see save().
class EncryptedTokenStorage(context: Context) : TokenStorage {

    private val appContext = context.applicationContext

    private val preferences: SharedPreferences? by lazy { open() }

    override fun load(): StoredSession? {
        val prefs = preferences ?: return null
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return StoredSession(
            accessToken = access,
            refreshToken = refresh,
            accessExpiresAtMs = prefs.getLong(KEY_EXPIRES, 0L),
            athleteId = prefs.getString(KEY_ATHLETE, null)
        )
    }

    override fun save(session: StoredSession) {
        preferences?.edit()
            ?.putString(KEY_ACCESS, session.accessToken)
            ?.putString(KEY_REFRESH, session.refreshToken)
            ?.putLong(KEY_EXPIRES, session.accessExpiresAtMs)
            ?.putString(KEY_ATHLETE, session.athleteId)
            // commit, not apply: losing a rotated refresh token to a process
            // death would leave the phone holding one the server already
            // retired, and the next refresh would be treated as token theft.
            ?.commit()
    }

    override fun clear() {
        preferences?.edit()?.clear()?.commit()
    }

    private fun open(): SharedPreferences? = try {
        create()
    } catch (error: Exception) {
        // A corrupted keyset — seen after some OEM backup restores — cannot be
        // repaired. Discard it and start clean: the athlete logs in again,
        // which is far better than an app that crashes on every launch.
        Log.w(TAG, "Encrypted session unreadable; resetting", error)
        appContext.deleteSharedPreferences(FILE_NAME)
        runCatching { create() }.getOrNull()
    }

    private fun create(): SharedPreferences {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val TAG = "EncryptedTokenStorage"

        /** Referenced by name in the backup exclusion rules — keep in sync. */
        const val FILE_NAME = "f4all_session"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "access_expires_at_ms"
        private const val KEY_ATHLETE = "athlete_id"
    }
}

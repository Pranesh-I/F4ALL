package com.sai.sports.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The photo check an athlete took before an official test.
 *
 * One check covers the tests of one sitting: an athlete doing four tests in a
 * session is asked once, not four times. After [IdentityPassStore.VALID_FOR_MS]
 * the next official test asks again.
 */
data class IdentityPass(
    /** The server's check, when the photo was checked online. */
    val checkId: String?,
    /** A photo taken with no signal, relative to filesDir, checked when the phone syncs. */
    val photoPath: String?,
    /** The server matched the photo to the registration photo. */
    val confirmed: Boolean,
    val takenAtWallMs: Long,
    val takenAtElapsedMs: Long
)

/**
 * The current [IdentityPass] per athlete and session.
 *
 * Keyed by athlete as well as session: on a shared phone, one athlete's check
 * must never cover another athlete's test.
 */
class IdentityPassStore(
    private val preferences: SharedPreferences,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    )

    fun save(athleteId: String, sessionId: String, pass: IdentityPass) {
        preferences.edit().putString(key(athleteId, sessionId), encode(pass)).apply()
    }

    /** The pass still in force for this athlete and session, or null. */
    fun current(athleteId: String, sessionId: String): IdentityPass? {
        val pass = preferences.getString(key(athleteId, sessionId), null)?.let(::decode)
            ?: return null
        return pass.takeIf { isValid(it, wallClock(), elapsedClock()) }
    }

    /** A pass stamped with the time now. */
    fun stamp(checkId: String?, photoPath: String?, confirmed: Boolean) = IdentityPass(
        checkId = checkId,
        photoPath = photoPath,
        confirmed = confirmed,
        takenAtWallMs = wallClock(),
        takenAtElapsedMs = elapsedClock()
    )

    companion object {
        private const val PREFERENCES = "identity_passes"

        /** How long one photo check covers further official tests. */
        const val VALID_FOR_MS = 30 * 60 * 1000L

        /**
         * Measured on the uptime clock, which the athlete cannot wind back;
         * after a reboot uptime restarts, and the wall clock is all there is.
         */
        fun isValid(pass: IdentityPass, wallNowMs: Long, elapsedNowMs: Long): Boolean {
            val age = if (elapsedNowMs >= pass.takenAtElapsedMs) {
                elapsedNowMs - pass.takenAtElapsedMs
            } else {
                wallNowMs - pass.takenAtWallMs
            }
            return age in 0..VALID_FOR_MS
        }

        private fun key(athleteId: String, sessionId: String) = "pass.$athleteId.$sessionId"

        fun encode(pass: IdentityPass): String = JSONObject()
            .putOpt("check_id", pass.checkId)
            .putOpt("photo_path", pass.photoPath)
            .put("confirmed", pass.confirmed)
            .put("taken_at_wall_ms", pass.takenAtWallMs)
            .put("taken_at_elapsed_ms", pass.takenAtElapsedMs)
            .toString()

        fun decode(value: String): IdentityPass? = runCatching {
            val json = JSONObject(value)
            IdentityPass(
                checkId = json.optString("check_id").takeIf { json.has("check_id") },
                photoPath = json.optString("photo_path").takeIf { json.has("photo_path") },
                confirmed = json.getBoolean("confirmed"),
                takenAtWallMs = json.getLong("taken_at_wall_ms"),
                takenAtElapsedMs = json.getLong("taken_at_elapsed_ms")
            )
        }.getOrNull()
    }
}

/** Photos taken offline for a check, kept in app-private storage until sent. */
object IdentityPhotos {
    const val DIRECTORY = "identity"

    /** Deleted this long after being taken if no recorded test is waiting on them. */
    const val ORPHAN_AFTER_MS = 24 * 60 * 60 * 1000L

    /** Saves [jpeg]; returns its path relative to filesDir. */
    fun save(filesDir: File, jpeg: ByteArray): String {
        val directory = File(filesDir, DIRECTORY).apply { mkdirs() }
        val name = "${UUID.randomUUID()}.jpg"
        File(directory, name).writeBytes(jpeg)
        return "$DIRECTORY/$name"
    }
}

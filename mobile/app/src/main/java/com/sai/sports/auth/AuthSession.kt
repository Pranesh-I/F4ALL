package com.sai.sports.auth

import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.TokenPair

/** Where tokens live between app launches. */
interface TokenStorage {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    /** Wall-clock millis after which the access token must not be sent. */
    val accessExpiresAtMs: Long,
    /** Null while the phone is verified but no profile exists yet. */
    val athleteId: String?
) {
    val isRegistered: Boolean get() = athleteId != null
}

/**
 * The athlete's login, and the one place that decides whether it is still good.
 *
 * Pure JVM: storage, clock and the refresh call are injected, so the rules
 * below — which are what keep an offline-first app from either leaking stale
 * tokens or logging people out for no reason — are unit-tested.
 *
 * ## The two failure modes this is built to avoid
 *
 * **Logging an athlete out because they had no signal.** A refresh that fails
 * with a network error says nothing about the session. The tokens are kept and
 * the next attempt tries again. Only an explicit 401 from the server — the
 * refresh token really is dead — ends the session.
 *
 * **Sending a token that is about to expire.** An upload chunk that leaves
 * with thirty seconds of life left can arrive after expiry on a slow link, so
 * tokens are refreshed a margin before they actually lapse.
 *
 * ## Locking
 *
 * Two locks, always taken in the order refresh -> state. The refresh lock is
 * held across the network call so concurrent callers refresh exactly once. The
 * state lock is only ever held briefly, so the UI reading [isLoggedIn] never
 * waits behind a refresh on a slow connection.
 */
class AuthSession(
    private val storage: TokenStorage,
    private val refreshCall: (refreshToken: String) -> ApiResult<TokenPair>,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val stateLock = Any()
    private val refreshLock = Any()

    @Volatile
    private var cached: StoredSession? = null

    @Volatile
    private var loaded = false

    fun current(): StoredSession? {
        if (loaded) return cached
        return synchronized(stateLock) {
            if (!loaded) {
                cached = runCatching { storage.load() }.getOrNull()
                loaded = true
            }
            cached
        }
    }

    val isLoggedIn: Boolean get() = current() != null

    val isRegistered: Boolean get() = current()?.isRegistered == true

    fun store(pair: TokenPair) = synchronized(stateLock) {
        write(
            StoredSession(
                accessToken = pair.accessToken,
                refreshToken = pair.refreshToken,
                accessExpiresAtMs = clock() + pair.expiresInSeconds * 1000,
                athleteId = pair.athleteId
            )
        )
    }

    fun clear() = synchronized(stateLock) {
        runCatching { storage.clear() }
        cached = null
        loaded = true
    }

    /**
     * A usable access token, refreshing first when needed. Blocking — call off
     * the main thread.
     *
     * Serialized so a sync worker and a screen that notice expiry at the same
     * moment refresh once. Two concurrent refreshes would present the same
     * refresh token twice, which the server treats as theft and answers by
     * revoking the whole session.
     */
    fun accessToken(): String? {
        synchronized(refreshLock) {
            return refreshIfNeeded()
        }
    }

    private fun refreshIfNeeded(): String? {
        val session = current() ?: return null

        if (clock() < session.accessExpiresAtMs - REFRESH_MARGIN_MS) {
            return session.accessToken
        }

        return when (val refreshed = refreshCall(session.refreshToken)) {
            is ApiResult.Success -> synchronized(stateLock) {
                val pair = refreshed.value
                val latest = current()

                // A fresh login landed while the refresh was in flight; that
                // session is newer than anything this refresh produced.
                if (latest == null || latest.refreshToken != session.refreshToken) {
                    return latest?.accessToken
                }

                write(
                    StoredSession(
                        accessToken = pair.accessToken,
                        refreshToken = pair.refreshToken,
                        accessExpiresAtMs = clock() + pair.expiresInSeconds * 1000,
                        // A registration completed on this device is not
                        // undone by a refresh that raced it.
                        athleteId = pair.athleteId ?: latest.athleteId
                    )
                )
                pair.accessToken
            }

            is ApiResult.Failure -> {
                if (refreshed.kind == ApiFailure.UNAUTHORIZED) {
                    synchronized(stateLock) {
                        // Only end the session this refresh was about — not one
                        // the athlete started while it was running.
                        if (current()?.refreshToken == session.refreshToken) {
                            clear()
                        }
                    }
                }
                // Anything else — no signal, a server hiccup — leaves the
                // session intact. The caller gets no token for now and tries
                // again later; the athlete is not logged out by a bad network.
                null
            }
        }
    }

    private fun write(session: StoredSession) {
        storage.save(session)
        cached = session
        loaded = true
    }

    companion object {
        /** Refresh this long before expiry, so a slow request does not outlive its token. */
        const val REFRESH_MARGIN_MS = 2 * 60 * 1000L
    }
}

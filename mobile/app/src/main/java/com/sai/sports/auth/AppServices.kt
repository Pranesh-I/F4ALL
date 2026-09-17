package com.sai.sports.auth

import android.content.Context
import com.sai.sports.api.F4allApi
import com.sai.sports.sync.SyncConfig

/**
 * Process-wide instances of the session and the API client.
 *
 * There must be exactly one [AuthSession] per process. Two would each decide
 * independently that the access token needs refreshing, both present the same
 * refresh token, and the server — correctly — reads the second presentation as
 * a stolen token and revokes the athlete's session.
 */
object AppServices {

    @Volatile
    private var session: AuthSession? = null

    fun session(context: Context): AuthSession =
        session ?: synchronized(this) {
            session ?: run {
                val appContext = context.applicationContext
                // The refresh call itself is unauthenticated, so this client
                // needs no token provider — and must not have one, or a refresh
                // would recurse into itself.
                val refreshApi = F4allApi(baseUrl = SyncConfig.baseUrl(appContext))
                AuthSession(
                    storage = EncryptedTokenStorage(appContext),
                    refreshCall = refreshApi::refresh
                ).also { session = it }
            }
        }

    /** An API client that authenticates as the logged-in athlete. Blocking calls. */
    fun api(context: Context): F4allApi {
        val appContext = context.applicationContext
        val authSession = session(appContext)
        return F4allApi(
            baseUrl = SyncConfig.baseUrl(appContext),
            accessToken = authSession::accessToken
        )
    }
}

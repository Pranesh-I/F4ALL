package com.sai.sports.auth

import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.TokenPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthSessionTest {

    private class MemoryStorage(var stored: StoredSession? = null) : TokenStorage {
        override fun load() = stored
        override fun save(session: StoredSession) { stored = session }
        override fun clear() { stored = null }
    }

    private var now = 1_000_000L

    private fun pair(access: String, refresh: String, athleteId: String? = "athlete-1") =
        TokenPair(access, refresh, expiresInSeconds = 3600, athleteId = athleteId, registered = athleteId != null)

    private fun session(
        storage: TokenStorage = MemoryStorage(),
        refresh: (String) -> ApiResult<TokenPair> = { error("refresh should not be called") }
    ) = AuthSession(storage, refresh, clock = { now })

    @Test
    fun `a fresh token is returned without refreshing`() {
        val auth = session()
        auth.store(pair("access-1", "refresh-1"))

        assertEquals("access-1", auth.accessToken())
    }

    @Test
    fun `a token near expiry is refreshed before it is sent`() {
        val storage = MemoryStorage()
        val auth = session(storage) { ApiResult.Success(pair("access-2", "refresh-2")) }
        auth.store(pair("access-1", "refresh-1"))

        // Inside the safety margin, not yet expired.
        now += 3600_000 - AuthSession.REFRESH_MARGIN_MS + 1

        assertEquals("access-2", auth.accessToken())
        assertEquals("refresh-2", storage.stored?.refreshToken)
    }

    @Test
    fun `no signal during refresh does not log the athlete out`() {
        val auth = session { ApiResult.Failure(ApiFailure.NETWORK, "offline") }
        auth.store(pair("access-1", "refresh-1"))
        now += 4000_000

        assertNull(auth.accessToken())
        assertTrue("A bad network must never end the session", auth.isLoggedIn)
    }

    @Test
    fun `a server error during refresh does not log the athlete out`() {
        val auth = session { ApiResult.Failure(ApiFailure.SERVER, "boom") }
        auth.store(pair("access-1", "refresh-1"))
        now += 4000_000

        assertNull(auth.accessToken())
        assertTrue(auth.isLoggedIn)
    }

    @Test
    fun `a rejected refresh token ends the session`() {
        val storage = MemoryStorage()
        val auth = session(storage) { ApiResult.Failure(ApiFailure.UNAUTHORIZED, "revoked") }
        auth.store(pair("access-1", "refresh-1"))
        now += 4000_000

        assertNull(auth.accessToken())
        assertFalse(auth.isLoggedIn)
        assertNull(storage.stored)
    }

    @Test
    fun `concurrent callers refresh exactly once`() {
        // Two refreshes would present the same refresh token twice, which the
        // server treats as theft and answers by revoking the whole session.
        val calls = AtomicInteger()
        val auth = session {
            calls.incrementAndGet()
            Thread.sleep(50)
            ApiResult.Success(pair("access-2", "refresh-2"))
        }
        auth.store(pair("access-1", "refresh-1"))
        now += 4000_000

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map {
            pool.submit<String?> { start.await(); auth.accessToken() }
        }
        start.countDown()

        results.forEach { assertEquals("access-2", it.get(5, TimeUnit.SECONDS)) }
        pool.shutdown()
        assertEquals(1, calls.get())
    }

    @Test
    fun `registration state follows the stored athlete id`() {
        val auth = session()
        auth.store(pair("a", "r", athleteId = null))
        assertTrue(auth.isLoggedIn)
        assertFalse(auth.isRegistered)

        auth.store(pair("b", "s", athleteId = "athlete-9"))
        assertTrue(auth.isRegistered)
    }

    @Test
    fun `a session survives a restart through storage`() {
        val storage = MemoryStorage()
        session(storage).store(pair("access-1", "refresh-1"))

        val restarted = session(storage)

        assertNotNull(restarted.current())
        assertEquals("access-1", restarted.accessToken())
    }

    @Test
    fun `unreadable storage reads as logged out rather than crashing`() {
        val broken = object : TokenStorage {
            override fun load(): StoredSession? = throw IllegalStateException("keyset corrupt")
            override fun save(session: StoredSession) = Unit
            override fun clear() = Unit
        }

        assertFalse(session(broken).isLoggedIn)
    }

    @Test
    fun `signing out clears the stored tokens`() {
        val storage = MemoryStorage()
        val auth = session(storage)
        auth.store(pair("access-1", "refresh-1"))

        auth.clear()

        assertFalse(auth.isLoggedIn)
        assertNull(storage.stored)
    }
}

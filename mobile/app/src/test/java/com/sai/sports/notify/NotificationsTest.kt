package com.sai.sports.notify

import com.sai.sports.api.ApiResult
import com.sai.sports.api.AppNotification
import com.sai.sports.api.F4allApi
import com.sai.sports.testing.MemoryPreferences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sprint 17: what the phone shows, where it leads, and what it asks the server. */
class NotificationsTest {

    private lateinit var server: MockWebServer
    private lateinit var api: F4allApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = F4allApi(baseUrl = server.url("/").toString(), accessToken = { "token" })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun note(
        id: String,
        event: String = "submission_approved",
        createdAtMs: Long = NOW,
        data: Map<String, String> = mapOf("result_id" to "r-$id", "test_type" to "SIT_UPS")
    ) = AppNotification(id, event, "server title", "server body", data, createdAtMs, read = false)

    // -- what it says ------------------------------------------------------

    @Test
    fun `every event the server sends has the app's own wording`() {
        listOf(
            "session_opened", "session_ending_soon", "submission_received", "verification_completed",
            "submission_flagged", "submission_approved", "submission_rejected", "resubmission_requested"
        ).forEach { assertNotNull(it, NotificationText.strings(it)) }
    }

    @Test
    fun `an event this app does not know falls back to the server's text`() {
        assertNull(NotificationText.strings("a_future_event"))
    }

    // -- where it leads ----------------------------------------------------

    @Test
    fun `a result notification opens the result, a session one the home screen`() {
        assertEquals(NotificationText.Destination.Result("r-1"), NotificationText.destination(note("1")))
        assertEquals(
            NotificationText.Destination.Home,
            NotificationText.destination(note("2", "session_opened", data = mapOf("session_id" to "s-1")))
        )
        assertEquals(
            NotificationText.Destination.Inbox,
            NotificationText.destination(note("3", "a_future_event", data = emptyMap()))
        )
    }

    // -- what is posted ----------------------------------------------------

    @Test
    fun `only new, recent notifications are posted, newest first and a few at a time`() {
        val unread = listOf(
            note("old", createdAtMs = NOW - 4 * DAY),
            note("shown", createdAtMs = NOW - 60_000),
        ) + (1..7).map { note("n$it", createdAtMs = NOW - it * 60_000L) }

        val posted = NotificationText.toPost(unread, alreadyShown = setOf("shown"), nowMs = NOW)

        assertEquals(listOf("n1", "n2", "n3", "n4", "n5"), posted.map { it.id })
    }

    @Test
    fun `shown notifications are remembered per athlete, never shared`() {
        val shown = ShownNotifications(MemoryPreferences())
        shown.record("asha", listOf("a1", "a2"))
        shown.record("ravi", listOf("r1"))

        assertEquals(setOf("a1", "a2"), shown.shown("asha"))
        assertEquals(setOf("r1"), shown.shown("ravi"))
        assertTrue(shown.shown("someone-else").isEmpty())
    }

    @Test
    fun `the shown list does not grow forever`() {
        val shown = ShownNotifications(MemoryPreferences())
        shown.record("asha", (1..ShownNotifications.CAP + 50).map { "id$it" })

        val kept = shown.shown("asha")
        assertEquals(ShownNotifications.CAP, kept.size)
        assertTrue("id${ShownNotifications.CAP + 50}" in kept)
        assertFalse("id1" in kept)
    }

    // -- the API -----------------------------------------------------------

    @Test
    fun `the inbox is read with its unread count and what each one opens`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"notification_id":"n-1","event_type":"submission_approved",
                   "title":"Sit-ups: approved","body":"Official now.",
                   "data":{"result_id":"r-9","test_type":"SIT_UPS","event":"submission_approved"},
                   "created_at":"2026-10-13T06:00:00Z","read_at":null}],
                   "unread_count":3,"next_before":"2026-10-13T06:00:00Z"}"""
            )
        )

        val page = (api.notifications(unreadOnly = true, limit = 20) as ApiResult.Success).value

        assertEquals("/api/athletes/me/notifications?limit=20&unread_only=true", server.takeRequest().path)
        assertEquals(3, page.unreadCount)
        val item = page.items.single()
        assertEquals("r-9", item.data["result_id"])
        assertFalse(item.read)
        assertEquals("2026-10-13T06:00:00Z", page.nextBefore)
    }

    @Test
    fun `marking read sends the ids, or all`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"unread_count":2}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"unread_count":0}"""))

        assertEquals(2, (api.markNotificationsRead(listOf("n-1")) as ApiResult.Success).value)
        val first = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("n-1", first.getJSONArray("notification_ids").getString(0))
        assertFalse(first.getBoolean("all"))

        api.markNotificationsRead(all = true)
        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getBoolean("all"))
    }

    @Test
    fun `a device is registered and removed by its token`() {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(api.registerDevice("fcm-token-1") is ApiResult.Success)
        val registered = server.takeRequest()
        assertEquals("/api/athletes/me/devices", registered.path)
        assertEquals("fcm-token-1", JSONObject(registered.body.readUtf8()).getString("token"))

        assertTrue(api.unregisterDevice("fcm:token/1") is ApiResult.Success)
        val removed = server.takeRequest()
        assertEquals("DELETE", removed.method)
        assertEquals("/api/athletes/me/devices/fcm%3Atoken%2F1", removed.path)
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}

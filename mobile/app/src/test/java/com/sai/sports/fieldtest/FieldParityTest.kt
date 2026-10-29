package com.sai.sports.fieldtest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone scores every shared fixture exactly as the server does.
 *
 * The traces in `backend/tests/fixtures/field` are generated, and their expected
 * results written, by the Python scorer (`test_the_shared_fixtures_match_the_python_scorer`).
 * If the two drift, the server would re-score an honest athlete's run to a
 * different time from the one the phone showed them, and flag them for it.
 */
class FieldParityTest {

    private val fixtures: List<JSONObject> =
        File(FIXTURE_DIRECTORY).listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { JSONObject(it.readText()) }

    @Test
    fun `the fixtures are there`() {
        assertTrue("no field fixtures found in $FIXTURE_DIRECTORY", fixtures.size >= 10)
    }

    @Test
    fun `every fixture scores as the server scored it`() {
        for (fixture in fixtures) {
            val name = fixture.getString("name")
            val expected = fixture.getJSONObject("expected")
            val motion = rows(fixture.getJSONArray("motion")).map(::motion)
            val result = when (fixture.getString("test")) {
                "SHUTTLE_RUN" -> ShuttleRunAnalyzer.score(motion)
                else -> EnduranceRunAnalyzer.score(rows(fixture.getJSONArray("fixes")).map(::fix), motion)
            }

            assertEquals(name, expected.getString("outcome"), result.outcome.name)
            assertEquals(name, expected.optString("invalid").takeUnless { expected.isNull("invalid") }, result.invalid)
            assertEquals(name, expected.getDouble("score"), result.score, 0.0)

            val events = rows(expected.getJSONArray("events"))
            assertEquals("$name events", events.size, result.events.size)
            events.zip(result.events).forEach { (want, got) ->
                assertEquals("$name event time", want.getLong(0), got.tMs)
                assertEquals("$name event", want.getString(1), got.label)
                assertEquals("$name event detail", want.getString(2), got.detail)
            }

            val metrics = expected.getJSONObject("metrics")
            if (metrics.has("turns")) assertEquals("$name turns", metrics.getInt("turns"), result.turns)
            if (metrics.has("leg_ms")) {
                val legs = metrics.getJSONArray("leg_ms")
                assertEquals("$name legs", (0 until legs.length()).map { legs.getLong(it) }, result.legMs)
            }
            if (metrics.has("distance_m")) {
                assertEquals("$name distance", metrics.getDouble("distance_m"), result.distanceM!!, 0.11)
            }
            if (metrics.has("gps_jumps")) assertEquals("$name jumps", metrics.getInt("gps_jumps"), result.gpsJumps)
        }
    }

    private fun rows(array: JSONArray): List<JSONArray> = (0 until array.length()).map { array.getJSONArray(it) }

    private fun motion(row: JSONArray) = Motion(
        tMs = row.getLong(0),
        ax = row.getDouble(1), ay = row.getDouble(2), az = row.getDouble(3),
        gx = if (row.length() > 4) row.getDouble(4) else 0.0,
        gy = if (row.length() > 5) row.getDouble(5) else 0.0,
        gz = if (row.length() > 6) row.getDouble(6) else 0.0
    )

    private fun fix(row: JSONArray) = Fix(row.getLong(0), row.getDouble(1), row.getDouble(2), row.getDouble(3))

    private companion object {
        const val FIXTURE_DIRECTORY = "../../backend/tests/fixtures/field"
    }
}

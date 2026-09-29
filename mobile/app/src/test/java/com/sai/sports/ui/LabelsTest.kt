package com.sai.sports.ui

import com.sai.sports.R
import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiFailure
import com.sai.sports.sync.SyncStatus
import com.sai.sports.ui.common.Labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LabelsTest {

    @Test
    fun `every analyzer reason has a translation`() {
        // Read the analyzers' own source, so a reason added there without a
        // translation here fails the build instead of reaching an athlete in
        // English.
        val sources = listOf(
            "SitUpAnalyzer.kt",
            "VerticalJumpAnalyzer.kt",
            "RepExerciseAnalyzer.kt",
            "SquatAnalyzer.kt",
            "PushUpAnalyzer.kt",
            "BicepCurlAnalyzer.kt",
            "LungeAnalyzer.kt"
        ).map {
            File("src/main/java/com/sai/sports/analyzer/$it").readText()
        }
        val reasons = sources.flatMap { source ->
            // `reason = "..."`, and the rep analyzers' `noStartReason = "..."`
            // which is split across two lines.
            Regex("""[rR]eason\s*=\s*"([^"]+)"""").findAll(source).map { it.groupValues[1] } +
                Regex("""fail\(\s*[a-zA-Z.]+,\s*"([^"]+)"""").findAll(source).map { it.groupValues[1] }
        }

        assertTrue("Expected to find analyzer reasons", reasons.size >= 13)
        reasons.forEach { reason ->
            assertNotEquals(
                "No translation for analyzer reason: $reason",
                R.string.invalid_generic,
                Labels.invalidReason(reason)
            )
        }
    }

    @Test
    fun `each rep test's missing start position gets its own advice`() {
        assertEquals(
            R.string.invalid_no_start_standing,
            Labels.invalidReason("Start position never detected — stand tall, side-on to the camera, before starting")
        )
        assertEquals(
            R.string.invalid_no_start_plank,
            Labels.invalidReason("Start position never detected — hold a straight-arm plank, side-on to the camera")
        )
        assertEquals(
            R.string.invalid_no_start_curl,
            Labels.invalidReason("Start position never detected — stand facing the camera with your arms straight")
        )
        // The sit-up reason still reaches the sit-up advice.
        assertEquals(
            R.string.invalid_no_start,
            Labels.invalidReason("Start position never detected — lie back fully before starting")
        )
    }

    @Test
    fun `an unknown or missing reason falls back to generic advice`() {
        assertEquals(R.string.invalid_generic, Labels.invalidReason(null))
        assertEquals(R.string.invalid_generic, Labels.invalidReason("Something new"))
    }

    @Test
    fun `every server code the app can receive is translated`() {
        listOf("processing", "verified", "flagged", "approved", "rejected", "pending_sync").forEach {
            assertNotEquals(it, R.string.status_unknown, Labels.resultStatus(it))
        }
        listOf("first_test", "first_verified", "personal_best", "all_tests",
            "streak_2_weeks", "streak_4_weeks", "streak_8_weeks").forEach {
            assertNotEquals(it, R.string.badge_unknown, Labels.badgeTitle(it))
            assertNotEquals(it, R.string.badge_unknown, Labels.badgeDescription(it))
        }
        listOf("approved", "rejected", "requested_resubmission").forEach {
            assertNotEquals(it, R.string.review_other, Labels.reviewAction(it))
        }
        TestType.entries.forEach { assertNotEquals(R.string.test_unknown, Labels.testName(it.name)) }
        SyncStatus.entries.forEach { Labels.syncStatus(it) }
    }

    @Test
    fun `failures the athlete cannot fix get plain advice`() {
        assertEquals(R.string.error_network, Labels.failure(ApiFailure.NETWORK, R.string.error_load))
        assertEquals(R.string.error_session_expired, Labels.failure(ApiFailure.UNAUTHORIZED, R.string.error_load))
        assertEquals(R.string.error_load, Labels.failure(ApiFailure.INVALID, R.string.error_load))
    }

    @Test
    fun `attempt events are summarised as counts`() {
        val events = listOf(
            "rep_counted", "rep_counted", "rep_rejected_partial", "side_locked", "tracking_lost",
            "rep_rejected_form", "form_warning", "rep_rejected_wrong_arm", "rep_rejected_wrong_arm"
        ).map { AnalyzerEvent(0, it) }

        val summary = Labels.summarise(events)

        assertEquals(2, summary.repsCounted)
        assertEquals(1, summary.partialReps)
        assertEquals(1, summary.trackingLost)
        assertEquals(0, summary.jumpsMeasured)
        assertEquals(1, summary.formRejectedReps)
        assertEquals(1, summary.formWarnings)
        assertEquals(2, summary.wrongArmReps)
    }

    @Test
    fun `cohorts from the server are split into gender and ages`() {
        assertEquals("female" to "14-15", Labels.parseCohort("female, 14-15"))
        assertEquals("all" to "all ages", Labels.parseCohort("all, all ages"))
        assertEquals("" to "", Labels.parseCohort(""))
    }
}

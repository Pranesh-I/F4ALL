package com.sai.sports.ui

import com.sai.sports.api.AthleteProfile
import com.sai.sports.data.AthleteCard
import com.sai.sports.data.ProfileStatusStore
import com.sai.sports.testing.MemoryPreferences
import com.sai.sports.ui.kit.BibNumber
import com.sai.sports.ui.kit.StepState
import com.sai.sports.ui.profile.ResultPresentation
import com.sai.sports.ui.profile.ResultPresentation.Writing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The design rules: chest numbers and how a score is written. */
class TrialsDesignRulesTest {

    @Test
    fun `the chest number is the dashboard's short id, upper-cased and split in two`() {
        // The dashboard shows an athlete as the first eight characters of their id.
        assertEquals("3F9C·21AB", BibNumber.of("3f9c21ab-0d4e-4c6a-9a51-7b2f0c1d2e3f"))
        assertEquals("ABCD", BibNumber.of("abcd"))
        assertEquals("", BibNumber.of(""))
    }

    @Test
    fun `the phone's count is pencil until SAI has measured it`() {
        assertEquals(Writing.PENCIL, ResultPresentation.writing("uploaded", 18.0, null, null))
        assertEquals(Writing.PENCIL, ResultPresentation.writing("processing", 18.0, null, null))
    }

    @Test
    fun `SAI's measurement is pen, and corrects the pencil when they differ`() {
        assertEquals(Writing.PEN, ResultPresentation.writing("verified", 18.0, 18.0, null))
        assertEquals(Writing.CORRECTED, ResultPresentation.writing("verified", 18.0, 17.0, null))
        assertEquals(Writing.CORRECTED, ResultPresentation.writing("flagged", 18.0, 16.0, null))
        // No phone score to cross out: just the pen.
        assertEquals(Writing.PEN, ResultPresentation.writing("verified", null, 17.0, null))
    }

    @Test
    fun `only an official's approval earns the stamp, and a refusal is struck through`() {
        assertEquals(Writing.STAMPED, ResultPresentation.writing("approved", 18.0, 17.0, 17.0))
        assertEquals(Writing.STRUCK, ResultPresentation.writing("rejected", 18.0, 17.0, null))
        // A rejection is never stamped, whatever score came with it.
        assertEquals(Writing.STRUCK, ResultPresentation.writing("rejected", 18.0, 17.0, 17.0))
    }

    @Test
    fun `the slip's steps follow the server status`() {
        assertEquals(StepState.Active, ResultPresentation.checkStep("uploaded"))
        assertEquals(StepState.Active, ResultPresentation.checkStep("processing"))
        listOf("verified", "flagged", "approved", "rejected", "pending_sync").forEach {
            assertEquals(it, StepState.Done, ResultPresentation.checkStep(it))
        }

        assertEquals(StepState.Waiting, ResultPresentation.approvalStep("uploaded"))
        assertEquals(StepState.Active, ResultPresentation.approvalStep("verified"))
        assertEquals(StepState.Active, ResultPresentation.approvalStep("flagged"))
        assertEquals(StepState.Done, ResultPresentation.approvalStep("approved"))
        assertEquals(StepState.Refused, ResultPresentation.approvalStep("rejected"))
        assertEquals(StepState.Refused, ResultPresentation.approvalStep("pending_sync"))
    }

    @Test
    fun `the chest number's name and state are remembered per athlete for offline use`() {
        val store = ProfileStatusStore(MemoryPreferences())
        assertNull("Never seen", store.card("asha"))

        store.save(profile("asha", "Asha K", "Kerala"))
        store.save(profile("ravi", "Ravi T", "Bihar"))

        assertEquals(AthleteCard("Asha K", "Kerala"), store.card("asha"))
        assertEquals(AthleteCard("Ravi T", "Bihar"), store.card("ravi"))
    }

    private fun profile(id: String, name: String, region: String) = AthleteProfile(
        athleteId = id, name = name, ageYears = 15, gender = "female", region = region,
        heightCm = 150.0, weightKg = null, hasReferencePhoto = false
    )
}

package com.sai.sports.ui

import com.sai.sports.ui.auth.LoginRules
import com.sai.sports.ui.auth.PhotoPreparation
import com.sai.sports.ui.auth.RegistrationRules
import com.sai.sports.ui.profile.ResultPresentation
import com.sai.sports.ui.profile.ordinal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Sprint7RulesTest {

    private val today = LocalDate.of(2026, 9, 17)

    private fun problem(
        name: String = "Asha",
        dob: LocalDate? = LocalDate.of(2010, 5, 1),
        gender: String? = "female",
        region: String? = "Kerala",
        height: String = "160",
        weight: String = ""
    ) = RegistrationRules.problem(name, dob, gender, region, height, weight, today)

    @Test
    fun `phone numbers accept ten digits or the country code form`() {
        assertTrue(LoginRules.isPlausiblePhone("9876543210"))
        assertTrue(LoginRules.isPlausiblePhone("919876543210"))
        assertFalse(LoginRules.isPlausiblePhone("98765"))
        assertFalse(LoginRules.isPlausiblePhone("449876543210"))
    }

    @Test
    fun `a complete registration has no problem`() {
        assertNull(problem())
    }

    @Test
    fun `each missing field is reported`() {
        assertNotNull(problem(name = " "))
        assertNotNull(problem(dob = null))
        assertNotNull(problem(gender = null))
        assertNotNull(problem(region = null))
        assertNotNull(problem(height = ""))
    }

    @Test
    fun `age limits match the server`() {
        assertNotNull(problem(dob = today.minusYears(8)))
        assertNull(problem(dob = today.minusYears(9)))
        assertNull(problem(dob = today.minusYears(40)))
        assertNotNull(problem(dob = today.minusYears(41)))
        assertNotNull(problem(dob = today.plusDays(1)))
    }

    @Test
    fun `height is required because jumps cannot be measured without it`() {
        assertNotNull(problem(height = "abc"))
        assertNotNull(problem(height = "20"))
        assertNull(problem(height = "172.5"))
    }

    @Test
    fun `weight is optional but must be sensible when given`() {
        assertNull(problem(weight = ""))
        assertNull(problem(weight = "55"))
        assertNotNull(problem(weight = "5"))
    }

    @Test
    fun `a machine score is never labelled official`() {
        assertEquals("Official score", ResultPresentation.headline(30.0, 28.0, 28.0).second)
        assertTrue(ResultPresentation.headline(30.0, 28.0, null).second.contains("not yet official"))
        assertTrue(ResultPresentation.headline(30.0, null, null).second.contains("provisional"))
        assertEquals(28.0, ResultPresentation.headline(30.0, 28.0, null).first)
    }

    @Test
    fun `verified is described as awaiting approval`() {
        assertTrue(ResultPresentation.statusLabel("verified").contains("awaiting official approval"))
    }

    @Test
    fun `scores format without spurious decimals`() {
        assertEquals("24 reps", ResultPresentation.formatScore(24.0, "reps"))
        assertEquals("41.5 cm", ResultPresentation.formatScore(41.5, "cm"))
        assertEquals("—", ResultPresentation.formatScore(null, "cm"))
    }

    @Test
    fun `ordinals are correct including the teens`() {
        assertEquals("51st", ordinal(51))
        assertEquals("62nd", ordinal(62))
        assertEquals("73rd", ordinal(73))
        assertEquals("11th", ordinal(11))
        assertEquals("12th", ordinal(12))
        assertEquals("90th", ordinal(90))
    }

    @Test
    fun `photos are subsampled but never below the target size`() {
        assertEquals(1, PhotoPreparation.sampleSizeFor(800, 600, 1024))
        assertEquals(2, PhotoPreparation.sampleSizeFor(4000, 3000, 1024))
        // 4000 / 4 = 1000 would fall below the target, so stop at 2.
        assertEquals(2, PhotoPreparation.sampleSizeFor(4000, 3000, 1024))
        // 8192 / 8 = 1024 still meets the target exactly.
        assertEquals(8, PhotoPreparation.sampleSizeFor(8192, 6000, 1024))
    }
}

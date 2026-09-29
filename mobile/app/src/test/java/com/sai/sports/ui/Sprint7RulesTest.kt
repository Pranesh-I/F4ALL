package com.sai.sports.ui

import com.sai.sports.R
import com.sai.sports.ui.auth.LoginRules
import com.sai.sports.ui.auth.PhotoPreparation
import com.sai.sports.ui.auth.RegistrationRules
import com.sai.sports.ui.profile.ResultPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        city: String = "Kochi",
        height: String = "160",
        weight: String = ""
    ) = RegistrationRules.problem(name, dob, gender, region, city, height, weight, today)

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
    fun `each missing field is reported with its own message`() {
        assertEquals(R.string.register_error_name, problem(name = " ")?.message)
        assertEquals(R.string.register_error_dob, problem(dob = null)?.message)
        assertEquals(R.string.register_error_gender, problem(gender = null)?.message)
        assertEquals(R.string.register_error_region, problem(region = null)?.message)
        assertEquals(R.string.register_error_city, problem(city = " ")?.message)
        assertEquals(R.string.register_error_height, problem(height = "")?.message)
    }

    @Test
    fun `age limits match the server and carry their bounds`() {
        val tooYoung = problem(dob = today.minusYears(8))
        assertEquals(R.string.register_error_age, tooYoung?.message)
        assertEquals(listOf<Any>(9, 40), tooYoung?.args)

        assertNull(problem(dob = today.minusYears(9)))
        assertNull(problem(dob = today.minusYears(40)))
        assertEquals(R.string.register_error_age, problem(dob = today.minusYears(41))?.message)
        assertEquals(R.string.register_error_dob_future, problem(dob = today.plusDays(1))?.message)
    }

    @Test
    fun `height is required because jumps cannot be measured without it`() {
        assertEquals(R.string.register_error_height, problem(height = "abc")?.message)
        assertEquals(R.string.register_error_height_range, problem(height = "20")?.message)
        assertNull(problem(height = "172.5"))
    }

    @Test
    fun `weight is optional but must be sensible when given`() {
        assertNull(problem(weight = ""))
        assertNull(problem(weight = "55"))
        assertEquals(R.string.register_error_weight_range, problem(weight = "5")?.message)
    }

    @Test
    fun `a machine score is never labelled official`() {
        assertEquals(28.0 to R.string.score_official, ResultPresentation.headline(30.0, 28.0, 28.0))
        assertEquals(28.0 to R.string.score_server, ResultPresentation.headline(30.0, 28.0, null))
        assertEquals(30.0 to R.string.score_provisional, ResultPresentation.headline(30.0, null, null))
    }

    @Test
    fun `numbers use the same digits in every language`() {
        assertEquals("24", ResultPresentation.formatNumber(24.0))
        assertEquals("41.5", ResultPresentation.formatNumber(41.5))
        assertEquals("—", ResultPresentation.formatNumber(null))
    }

    @Test
    fun `photos are subsampled but never below the target size`() {
        assertEquals(1, PhotoPreparation.sampleSizeFor(800, 600, 1024))
        // 4000 / 4 = 1000 would fall below the target, so stop at 2.
        assertEquals(2, PhotoPreparation.sampleSizeFor(4000, 3000, 1024))
        // 8192 / 8 = 1024 still meets the target exactly.
        assertEquals(8, PhotoPreparation.sampleSizeFor(8192, 6000, 1024))
    }
}

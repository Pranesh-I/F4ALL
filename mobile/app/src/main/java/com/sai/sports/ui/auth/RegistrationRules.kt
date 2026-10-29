package com.sai.sports.ui.auth

import androidx.annotation.StringRes
import com.sai.sports.R
import java.time.LocalDate
import java.time.Period

/**
 * Registration validation, mirroring the server's rules so an athlete finds out
 * about a typo before spending data on a request that will be refused.
 *
 * The server still validates everything; this is a courtesy, not a guard.
 * Problems are string resources with arguments, so they read in the athlete's
 * language.
 */
object RegistrationRules {

    const val MIN_AGE_YEARS = 9
    const val MAX_AGE_YEARS = 40

    const val MIN_HEIGHT_CM = 100
    const val MAX_HEIGHT_CM = 230
    const val MIN_WEIGHT_KG = 15
    const val MAX_WEIGHT_KG = 200

    val GENDERS = listOf("male", "female", "other")

    data class Problem(@StringRes val message: Int, val args: List<Any> = emptyList())

    fun ageOn(dateOfBirth: LocalDate, today: LocalDate = LocalDate.now()): Int =
        Period.between(dateOfBirth, today).years

    /** Step "About you": name, date of birth, gender. */
    fun aboutProblem(
        name: String,
        dateOfBirth: LocalDate?,
        gender: String?,
        today: LocalDate = LocalDate.now()
    ): Problem? {
        if (name.isBlank()) return Problem(R.string.register_error_name)
        if (dateOfBirth == null) return Problem(R.string.register_error_dob)
        if (dateOfBirth.isAfter(today)) return Problem(R.string.register_error_dob_future)

        val age = ageOn(dateOfBirth, today)
        if (age < MIN_AGE_YEARS || age > MAX_AGE_YEARS) {
            return Problem(R.string.register_error_age, listOf(MIN_AGE_YEARS, MAX_AGE_YEARS))
        }
        if (gender == null) return Problem(R.string.register_error_gender)
        return null
    }

    /** Step "Where you live": state and city. */
    fun placeProblem(region: String?, city: String): Problem? {
        if (region == null) return Problem(R.string.register_error_region)
        if (city.isBlank()) return Problem(R.string.register_error_city)
        return null
    }

    /** Step "Height and weight". */
    fun bodyProblem(heightCm: String, weightKg: String): Problem? {
        // Required, not optional: vertical jump cannot be measured without it,
        // and asking later means a jump test that cannot be scored.
        val height = heightCm.toDoubleOrNull() ?: return Problem(R.string.register_error_height)
        if (height < MIN_HEIGHT_CM || height > MAX_HEIGHT_CM) {
            return Problem(R.string.register_error_height_range, listOf(MIN_HEIGHT_CM, MAX_HEIGHT_CM))
        }

        if (weightKg.isNotBlank()) {
            val weight = weightKg.toDoubleOrNull() ?: return Problem(R.string.register_error_weight)
            if (weight < MIN_WEIGHT_KG || weight > MAX_WEIGHT_KG) {
                return Problem(R.string.register_error_weight_range, listOf(MIN_WEIGHT_KG, MAX_WEIGHT_KG))
            }
        }
        return null
    }

    /** The whole form, in the order the steps ask for it. */
    fun problem(
        name: String,
        dateOfBirth: LocalDate?,
        gender: String?,
        region: String?,
        city: String,
        heightCm: String,
        weightKg: String,
        today: LocalDate = LocalDate.now()
    ): Problem? =
        aboutProblem(name, dateOfBirth, gender, today)
            ?: placeProblem(region, city)
            ?: bodyProblem(heightCm, weightKg)
}

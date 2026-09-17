package com.sai.sports.ui.auth

import java.time.LocalDate
import java.time.Period

/**
 * Registration validation, mirroring the server's rules so an athlete finds out
 * about a typo before spending data on a request that will be refused.
 *
 * The server still validates everything; this is a courtesy, not a guard.
 */
object RegistrationRules {

    const val MIN_AGE_YEARS = 9
    const val MAX_AGE_YEARS = 40

    val GENDERS = listOf("male" to "Male", "female" to "Female", "other" to "Other")

    fun ageOn(dateOfBirth: LocalDate, today: LocalDate = LocalDate.now()): Int =
        Period.between(dateOfBirth, today).years

    /** Returns a message for the athlete, or null when everything is valid. */
    fun problem(
        name: String,
        dateOfBirth: LocalDate?,
        gender: String?,
        region: String?,
        heightCm: String,
        weightKg: String,
        today: LocalDate = LocalDate.now()
    ): String? {
        if (name.isBlank()) return "Enter your name"
        if (dateOfBirth == null) return "Choose your date of birth"
        if (dateOfBirth.isAfter(today)) return "Date of birth cannot be in the future"

        val age = ageOn(dateOfBirth, today)
        if (age < MIN_AGE_YEARS || age > MAX_AGE_YEARS) {
            return "Athletes aged $MIN_AGE_YEARS to $MAX_AGE_YEARS can register"
        }

        if (gender == null) return "Choose a gender"
        if (region == null) return "Choose your state or union territory"

        // Height is required, not optional: vertical jump cannot be measured
        // without it, and asking later means a jump test that cannot be scored.
        val height = heightCm.toDoubleOrNull() ?: return "Enter your height in cm"
        if (height < 100 || height > 230) return "Height should be between 100 and 230 cm"

        if (weightKg.isNotBlank()) {
            val weight = weightKg.toDoubleOrNull() ?: return "Weight must be a number"
            if (weight < 15 || weight > 200) return "Weight should be between 15 and 200 kg"
        }

        return null
    }
}

package com.sai.sports.data

import com.sai.sports.R
import com.sai.sports.api.ApiFailure
import com.sai.sports.api.ApiResult
import com.sai.sports.api.AthleteProfile
import com.sai.sports.api.IdentityCheckResult
import com.sai.sports.sync.IdentityUploadPolicy
import com.sai.sports.testing.MemoryPreferences
import com.sai.sports.ui.auth.ConsentRules
import com.sai.sports.ui.identity.IdentityCheckFlow
import com.sai.sports.ui.identity.IdentityStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 8 on the phone: consent, the photo check before an official test,
 * and the pass that lets one check cover a sitting.
 */
class IdentityModeTest {

    private val minute = 60_000L

    // -----------------------------------------------------------------
    // Consent
    // -----------------------------------------------------------------

    @Test
    fun `a minor's consent is a named guardian's`() {
        assertTrue(ConsentRules.needsGuardian(17))
        assertFalse(ConsentRules.needsGuardian(18))

        val unnamed = ConsentRules.grant(agreed = true, minor = true, guardianName = "  ")
        assertEquals(
            R.string.consent_error_guardian,
            (unnamed.exceptionOrNull() as ConsentRules.ConsentMissing).messageRes
        )

        val given = ConsentRules.grant(agreed = true, minor = true, guardianName = " R. Kumar ").getOrThrow()
        assertEquals("guardian", given.givenBy)
        assertEquals("R. Kumar", given.guardianName)
        assertEquals(ConsentRules.VERSION, given.version)
    }

    @Test
    fun `an adult consents for themselves, and nobody without agreeing`() {
        val adult = ConsentRules.grant(agreed = true, minor = false, guardianName = "ignored").getOrThrow()
        assertEquals("self", adult.givenBy)
        assertNull(adult.guardianName)

        val refused = ConsentRules.grant(agreed = false, minor = false, guardianName = "")
        assertEquals(
            R.string.consent_error_agree,
            (refused.exceptionOrNull() as ConsentRules.ConsentMissing).messageRes
        )
    }

    // -----------------------------------------------------------------
    // The photo check: retry, then a person decides
    // -----------------------------------------------------------------

    private fun result(outcome: String, id: String = "c-$outcome") =
        ApiResult.Success(IdentityCheckResult(id, outcome, remainingThisHour = 5))

    private fun failure(kind: ApiFailure) = ApiResult.Failure(kind, "x")

    @Test
    fun `a match starts the test`() {
        assertEquals(IdentityStep.Passed("c-match"), IdentityCheckFlow().onResponse(result("match")))
    }

    @Test
    fun `a failed photo says why, and after three tries the athlete may continue`() {
        val flow = IdentityCheckFlow()

        val first = flow.onResponse(result("no_face", "c1")) as IdentityStep.TryAgain
        assertEquals(IdentityStep.Reason.NO_FACE, first.reason)
        assertFalse(first.mayContinue)

        val second = flow.onResponse(result("no_match", "c2")) as IdentityStep.TryAgain
        assertEquals(IdentityStep.Reason.NO_MATCH, second.reason)
        assertFalse(second.mayContinue)

        val third = flow.onResponse(result("no_match", "c3")) as IdentityStep.TryAgain
        assertTrue("Never locked out", third.mayContinue)
        assertEquals("The last check travels with the attempt", "c3", third.lastCheckId)
    }

    @Test
    fun `no signal keeps the photo for later rather than stopping the test`() {
        assertEquals(IdentityStep.CheckLater, IdentityCheckFlow().onResponse(failure(ApiFailure.NETWORK)))
        assertEquals(IdentityStep.CheckLater, IdentityCheckFlow().onResponse(failure(ApiFailure.SERVER)))
    }

    @Test
    fun `a check that cannot run lets the athlete continue`() {
        assertEquals(IdentityStep.CouldNotCheck("c-unavailable"), IdentityCheckFlow().onResponse(result("unavailable")))
        // An outcome a newer server invents is not a crash, and not a pass.
        assertEquals(IdentityStep.CouldNotCheck("c-new"), IdentityCheckFlow().onResponse(result("new")))

        val flow = IdentityCheckFlow()
        flow.onResponse(result("no_face", "c1"))
        assertEquals(IdentityStep.CouldNotCheck("c1"), flow.onResponse(failure(ApiFailure.RATE_LIMITED)))
    }

    @Test
    fun `missing consent or photo sends the athlete to complete the profile`() {
        assertEquals(IdentityStep.NeedsProfile, IdentityCheckFlow().onResponse(failure(ApiFailure.CONFLICT)))
        assertEquals(IdentityStep.Failed, IdentityCheckFlow().onResponse(failure(ApiFailure.INVALID)))
    }

    // -----------------------------------------------------------------
    // One check per sitting
    // -----------------------------------------------------------------

    private var wall = 1_000_000L
    private var elapsed = 5_000L

    private fun store(preferences: MemoryPreferences = MemoryPreferences()) =
        IdentityPassStore(preferences, wallClock = { wall }, elapsedClock = { elapsed })

    @Test
    fun `a pass covers the sitting and then expires`() {
        val store = store()
        store.save("asha", "s1", store.stamp("c1", null, confirmed = true))

        elapsed += 29 * minute
        assertEquals("c1", store.current("asha", "s1")?.checkId)

        elapsed += 2 * minute
        assertNull(store.current("asha", "s1"))
    }

    @Test
    fun `winding the calendar back does not stretch a pass`() {
        val store = store()
        store.save("asha", "s1", store.stamp("c1", null, confirmed = true))

        wall -= 24 * 60 * minute
        elapsed += 31 * minute
        assertNull(store.current("asha", "s1"))
    }

    @Test
    fun `after a reboot the wall clock decides`() {
        val store = store()
        store.save("asha", "s1", store.stamp("c1", null, confirmed = true))

        elapsed = 100 // uptime restarted
        wall += 10 * minute
        assertEquals("c1", store.current("asha", "s1")?.checkId)

        wall += 25 * minute
        assertNull(store.current("asha", "s1"))
    }

    @Test
    fun `one athlete's check never covers another's test, or another session`() {
        val preferences = MemoryPreferences()
        val store = store(preferences)
        store.save("asha", "s1", store.stamp("c1", null, confirmed = true))

        assertNull(store.current("ravi", "s1"))
        assertNull(store.current("asha", "s2"))
    }

    @Test
    fun `a pass survives storage exactly, with or without a check id`() {
        val offline = IdentityPass(null, "identity/p.jpg", confirmed = false, takenAtWallMs = 7, takenAtElapsedMs = 9)
        val online = IdentityPass("c1", null, confirmed = true, takenAtWallMs = 7, takenAtElapsedMs = 9)

        assertEquals(offline, IdentityPassStore.decode(IdentityPassStore.encode(offline)))
        assertEquals(online, IdentityPassStore.decode(IdentityPassStore.encode(online)))
        assertNull(IdentityPassStore.decode("not json"))
    }

    // -----------------------------------------------------------------
    // An offline photo, checked at sync
    // -----------------------------------------------------------------

    @Test
    fun `any answer is attached, whatever it concluded`() {
        assertEquals(
            IdentityUploadPolicy.Decision.Attach("c-no_match"),
            IdentityUploadPolicy.decide(result("no_match"))
        )
    }

    @Test
    fun `a passing problem keeps the photo for the next sync`() {
        listOf(ApiFailure.NETWORK, ApiFailure.SERVER, ApiFailure.RATE_LIMITED, ApiFailure.UNAUTHORIZED).forEach {
            assertEquals(it.name, IdentityUploadPolicy.Decision.RetryLater, IdentityUploadPolicy.decide(failure(it)))
        }
    }

    @Test
    fun `a check that can never happen does not hold the test back`() {
        listOf(ApiFailure.CONFLICT, ApiFailure.INVALID, ApiFailure.FORBIDDEN).forEach {
            assertEquals(it.name, IdentityUploadPolicy.Decision.SubmitWithout, IdentityUploadPolicy.decide(failure(it)))
        }
    }

    // -----------------------------------------------------------------
    // What the phone remembers about the profile
    // -----------------------------------------------------------------

    @Test
    fun `profile status is remembered per athlete`() {
        val store = ProfileStatusStore(MemoryPreferences())
        assertNull("Never seen", store.missing("asha"))

        store.save(profile("asha", missing = listOf("face_consent", "photo")))
        store.save(profile("ravi", missing = emptyList()))

        assertEquals(listOf("face_consent", "photo"), store.missing("asha"))
        assertEquals(emptyList<String>(), store.missing("ravi"))
    }

    private fun profile(id: String, missing: List<String>) = AthleteProfile(
        athleteId = id, name = "A", ageYears = 15, gender = "female", region = "Kerala",
        heightCm = 150.0, weightKg = null, hasReferencePhoto = false, missing = missing
    )
}

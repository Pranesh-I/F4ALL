package com.sai.sports.data

import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.AnalyzerResult
import com.sai.sports.analyzer.AttemptStatus
import com.sai.sports.analyzer.RepFixtures
import com.sai.sports.analyzer.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PracticeModeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun attempt(
        id: String,
        score: Double,
        recordedAtMs: Long,
        testType: TestType = TestType.SQUATS,
        mode: AttemptMode = AttemptMode.PRACTICE,
        scored: Boolean = true,
        athleteId: String? = "asha"
    ) = Attempt(
        id = id,
        testType = testType,
        videoFileName = "$id.mp4",
        recordedAtMs = recordedAtMs,
        mode = mode,
        athleteId = athleteId,
        result = if (scored) {
            AnalyzerResult(
                testType = testType,
                score = score,
                unit = testType.unit,
                status = AttemptStatus.COMPLETE,
                confidence = 0.9,
                framesAnalyzed = 100,
                framesRejected = 0,
                events = listOf(AnalyzerEvent(recordedAtMs, "rep_counted"))
            )
        } else {
            AnalyzerResult.invalid(testType, "No usable pose data")
        }
    )

    // -----------------------------------------------------------------
    // Practice never becomes an official submission
    // -----------------------------------------------------------------

    @Test
    fun `practice attempts are never uploadable, however good`() {
        assertFalse(UploadEligibility.isUploadable(attempt("p", 30.0, 1, mode = AttemptMode.PRACTICE)))
        assertTrue(UploadEligibility.isUploadable(attempt("o", 30.0, 1, mode = AttemptMode.OFFICIAL)))
        assertFalse(UploadEligibility.isUploadable(attempt("x", 0.0, 1, mode = AttemptMode.OFFICIAL, scored = false)))
    }

    @Test
    fun `an official attempt with no owner is never uploaded`() {
        // It could only go up as whoever is signed in — possibly someone else.
        assertFalse(
            UploadEligibility.isUploadable(attempt("o", 30.0, 1, mode = AttemptMode.OFFICIAL, athleteId = null))
        )
    }

    @Test
    fun `each athlete lists only their own attempts on a shared phone`() {
        val store = AttemptStore(folder.root)
        store.save(attempt("mine", 1.0, 1_000, athleteId = "asha"), emptyList())
        store.save(attempt("friend", 1.0, 2_000, athleteId = "ravi"), emptyList())
        store.save(attempt("legacy", 1.0, 3_000, athleteId = null), emptyList())

        assertEquals(listOf("mine"), store.listAttempts("asha").map { it.id })
        assertEquals(listOf("friend"), store.listAttempts("ravi").map { it.id })
    }

    @Test
    fun `ownership and account status survive a save and load`() {
        val store = AttemptStore(folder.root)
        store.save(attempt("a", 1.0, 1_000).copy(savedToAccount = true), emptyList())

        val loaded = store.load("a")!!
        assertEquals("asha", loaded.athleteId)
        assertTrue(loaded.savedToAccount)
    }

    // -----------------------------------------------------------------
    // Personal bests and progress
    // -----------------------------------------------------------------

    @Test
    fun `the personal best is the best scored practice attempt at that test`() {
        val all = listOf(
            attempt("a", 10.0, 1),
            attempt("b", 14.0, 2),
            attempt("c", 12.0, 3),
            attempt("d", 99.0, 4, mode = AttemptMode.OFFICIAL),
            attempt("e", 0.0, 5, scored = false),
            attempt("f", 50.0, 6, testType = TestType.LUNGES)
        )

        assertEquals("b", PracticeStats.personalBest(all, TestType.SQUATS)?.id)
        assertEquals("f", PracticeStats.personalBest(all, TestType.LUNGES)?.id)
        assertNull(PracticeStats.personalBest(all, TestType.PUSH_UPS))
    }

    @Test
    fun `the first scored attempt is the best to beat`() {
        val first = attempt("a", 8.0, 1)

        val comparison = PracticeStats.compare(first, listOf(first))!!

        assertTrue(comparison.isFirstScored)
        assertTrue(comparison.isPersonalBest)
        assertNull(comparison.changeFromPrevious)
    }

    @Test
    fun `a better score is a new best and shows the gain on last time`() {
        val history = listOf(attempt("a", 10.0, 1), attempt("b", 12.0, 2))
        val latest = attempt("c", 15.0, 3)

        val comparison = PracticeStats.compare(latest, history + latest)!!

        assertTrue(comparison.isPersonalBest)
        assertFalse(comparison.isFirstScored)
        assertEquals(12.0, comparison.previousBest!!, 0.0)
        assertEquals(3.0, comparison.changeFromPrevious!!, 0.0)
    }

    @Test
    fun `equalling the best is not a new best`() {
        val history = listOf(attempt("a", 12.0, 1))
        val latest = attempt("b", 12.0, 2)

        val comparison = PracticeStats.compare(latest, history + latest)!!

        assertFalse(comparison.isPersonalBest)
        assertEquals(0.0, comparison.changeFromPrevious!!, 0.0)
    }

    @Test
    fun `a worse attempt compares with the last scored one, skipping unscored ones`() {
        val history = listOf(
            attempt("a", 20.0, 1),
            attempt("b", 14.0, 2),
            attempt("c", 0.0, 3, scored = false)
        )
        val latest = attempt("d", 11.0, 4)

        val comparison = PracticeStats.compare(latest, history + latest)!!

        assertFalse(comparison.isPersonalBest)
        assertEquals(20.0, comparison.previousBest!!, 0.0)
        assertEquals(-3.0, comparison.changeFromPrevious!!, 0.0)
    }

    @Test
    fun `looking back at an old attempt compares it only with what came before it`() {
        val all = listOf(attempt("a", 10.0, 1), attempt("b", 15.0, 2), attempt("c", 20.0, 3))

        val comparison = PracticeStats.compare(all[1], all)!!

        assertTrue("It was a best when it was set", comparison.isPersonalBest)
        assertEquals(5.0, comparison.changeFromPrevious!!, 0.0)
    }

    @Test
    fun `official and unscored attempts get no practice comparison`() {
        assertNull(PracticeStats.compare(attempt("o", 30.0, 1, mode = AttemptMode.OFFICIAL), emptyList()))
        assertNull(PracticeStats.compare(attempt("x", 0.0, 1, scored = false), emptyList()))
    }

    @Test
    fun `the practice home lists every test with its count and best`() {
        val all = listOf(
            attempt("a", 10.0, 1),
            attempt("b", 0.0, 2, scored = false),
            attempt("c", 30.0, 3, testType = TestType.VERTICAL_JUMP)
        )

        val records = PracticeStats.records(all).associateBy { it.testType }

        assertEquals(TestType.entries.size, records.size)
        assertEquals(2, records.getValue(TestType.SQUATS).attempts)
        assertEquals("a", records.getValue(TestType.SQUATS).best?.id)
        assertEquals(0, records.getValue(TestType.PUSH_UPS).attempts)
        assertNull(records.getValue(TestType.PUSH_UPS).best)
    }

    @Test
    fun `practice history is newest first and practice only`() {
        val all = listOf(
            attempt("old", 1.0, 1),
            attempt("new", 1.0, 9),
            attempt("official", 1.0, 5, mode = AttemptMode.OFFICIAL)
        )

        assertEquals(listOf("new", "old"), PracticeStats.practiceAttempts(all).map { it.id })
    }

    // -----------------------------------------------------------------
    // Storage
    // -----------------------------------------------------------------

    @Test
    fun `the mode survives a save and load`() {
        val store = AttemptStore(folder.root)
        val practice = attempt("test_1", 12.0, 1_000)

        store.save(practice, RepFixtures.run(1, RepFixtures.squat()))

        assertEquals(AttemptMode.PRACTICE, store.load("test_1")!!.mode)
    }

    @Test
    fun `attempts saved before modes existed read as official`() {
        // They were all uploaded, so calling them practice would rewrite history.
        val store = AttemptStore(folder.root)
        store.save(attempt("legacy", 12.0, 1_000, mode = AttemptMode.OFFICIAL), emptyList())
        val file = folder.root.resolve("attempts/legacy.json")
        file.writeText(file.readText().replace(Regex(""","mode":"[A-Z]+""""), ""))

        assertEquals(AttemptMode.OFFICIAL, store.load("legacy")!!.mode)
    }

    @Test
    fun `a practice video can be dropped while its result and skeleton stay`() {
        val store = AttemptStore(folder.root)
        val practice = attempt("test_2", 12.0, 1_000)
        store.save(practice, RepFixtures.run(1, RepFixtures.squat()))
        val video = store.videoFile(practice).apply { writeText("video bytes") }

        assertTrue(store.deleteVideo(practice))

        assertFalse(video.exists())
        assertNotNull(store.load("test_2"))
        assertTrue(store.loadSequence("test_2").isNotEmpty())
        assertTrue("Deleting twice is fine", store.deleteVideo(practice))
    }

    @Test
    fun `attempts list newest recorded first`() {
        val store = AttemptStore(folder.root)
        store.save(attempt("b", 1.0, 2_000), emptyList())
        store.save(attempt("a", 1.0, 1_000), emptyList())
        store.save(attempt("c", 1.0, 3_000), emptyList())

        assertEquals(listOf("c", "b", "a"), store.listAttempts().map { it.id })
    }
}

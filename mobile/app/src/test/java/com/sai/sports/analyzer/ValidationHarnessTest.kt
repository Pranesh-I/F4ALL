package com.sai.sports.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the Sprint 3 accuracy validation against reference recordings.
 *
 * The reference videos are a Sprint 0 deliverable that has not been collected
 * yet, so on a clean checkout this test SKIPS rather than fails — a red build
 * for missing data nobody has recorded yet trains people to ignore red builds.
 * The moment sequences appear in `docs/reference-videos/`, it starts enforcing
 * the accuracy targets, and Sprint 3's Definition of Done is not met until it
 * runs green with 15-20 sequences behind it.
 */
class ValidationHarnessTest {

    private val referenceDirectory = ValidationHarness.defaultReferenceDirectory()

    @Test
    fun `analyzers meet the accuracy targets on reference data`() {

        val cases = ValidationHarness.loadCases(referenceDirectory)

        assumeTrue(
            "No reference data in ${referenceDirectory.absolutePath} — " +
                "collect reference videos and their ground truth to enforce this.",
            cases.isNotEmpty()
        )

        val report = ValidationHarness.run(referenceDirectory)

        ValidationHarness.writeReport(
            report = report,
            destination = File("build/reports/validation/sprint3-accuracy.csv")
        )

        println(ValidationHarness.summarize(report))

        assertTrue(
            "Nothing was evaluated:\n${report.skipped.joinToString("\n")}",
            report.evaluated > 0
        )

        val failures = report.outcomes.filterNot { it.withinTarget }

        assertTrue(
            buildString {
                appendLine("${failures.size} of ${report.evaluated} sequences missed the target:")
                failures.forEach {
                    appendLine(
                        "  ${it.case.sequenceFile}: expected ${it.case.expected}, " +
                            "got ${it.result.score} (${it.result.status})"
                    )
                }
            },
            failures.isEmpty()
        )
    }

    @Test
    fun `harness reports a miss rather than silently passing an unscored attempt`() {

        // Guards the harness itself: an INVALID result must count as a failure
        // against ground truth, not be quietly excluded from the denominator.
        val temporaryDirectory = createTempDirectory()

        try {
            val sequences = File(temporaryDirectory, ValidationHarness.SEQUENCES_DIR)
            sequences.mkdirs()

            // A recording of someone standing still, labelled as a 30cm jump.
            File(sequences, "still.csv").writeText(
                PoseSequenceCodec.encode(
                    frames = PoseFixtures.standing(0, frameCount = 40),
                    testName = "Vertical Jump"
                )
            )

            File(temporaryDirectory, ValidationHarness.GROUND_TRUTH_FILE).writeText(
                "sequence,test,expected,height_cm\nstill.csv,VERTICAL_JUMP,30,170\n"
            )

            val report = ValidationHarness.run(temporaryDirectory)

            assertEquals(1, report.evaluated)
            assertEquals(0, report.withinTarget)

        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    @Test
    fun `harness scores a known-good synthetic sequence within target`() {

        val temporaryDirectory = createTempDirectory()

        try {
            val sequences = File(temporaryDirectory, ValidationHarness.SEQUENCES_DIR)
            sequences.mkdirs()

            val frames = mutableListOf<PoseFrame>()
            frames += PoseFixtures.hold(0, 155.0, 10)

            var cursor = 10 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
            repeat(4) {
                val rep = PoseFixtures.sitUpRep(cursor, ascentFrames = 40, descentFrames = 40)
                frames += rep
                cursor = rep.last().timestampMs + PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
                frames += PoseFixtures.hold(cursor, 155.0, 8)
                cursor += 8 * PoseFixtures.DEFAULT_FRAME_INTERVAL_MS
            }

            File(sequences, "situps.csv").writeText(
                PoseSequenceCodec.encode(frames, "Sit-ups")
            )

            File(temporaryDirectory, ValidationHarness.GROUND_TRUTH_FILE).writeText(
                "sequence,test,expected,height_cm\nsitups.csv,SIT_UPS,4,\n"
            )

            val report = ValidationHarness.run(temporaryDirectory)

            assertEquals(1, report.evaluated)
            assertEquals(1, report.withinTarget)
            assertEquals(0.0, report.meanAbsoluteError, 0.001)

        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    @Test
    fun `jump case without a height is skipped rather than guessed`() {

        val temporaryDirectory = createTempDirectory()

        try {
            val sequences = File(temporaryDirectory, ValidationHarness.SEQUENCES_DIR)
            sequences.mkdirs()

            File(sequences, "jump.csv").writeText(
                PoseSequenceCodec.encode(
                    frames = PoseFixtures.standing(0),
                    testName = "Vertical Jump"
                )
            )

            File(temporaryDirectory, ValidationHarness.GROUND_TRUTH_FILE).writeText(
                "sequence,test,expected,height_cm\njump.csv,VERTICAL_JUMP,30,\n"
            )

            val report = ValidationHarness.run(temporaryDirectory)

            assertEquals(0, report.evaluated)
            assertEquals(1, report.skipped.size)
            assertTrue(report.skipped.first().contains("height_cm"))

        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File =
        File.createTempFile("f4all-validation", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
}

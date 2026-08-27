package com.sai.sports.analyzer

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Batch-scores recorded pose sequences against manual ground truth.
 *
 * This is the instrument Sprint 3's Definition of Done is measured with. It
 * runs the SAME analyzer classes the app runs — replaying a saved sequence, not
 * a reimplementation — so a threshold change can be evaluated across the whole
 * reference set in seconds, without a phone or a volunteer.
 *
 * ## Getting reference data in
 *
 *  1. Record the reference videos (a Sprint 0 task that is still open).
 *  2. For each one, manually count the reps or measure the jump height.
 *  3. Capture the pose sequence for each video. Recording a test in the app
 *     writes `sequences/<attempt>.csv` into app storage; pull those with
 *     `adb pull`.
 *  4. Put the CSVs in `docs/reference-videos/sequences/` and describe them in
 *     `docs/reference-videos/ground-truth.csv`:
 *
 *         sequence,test,expected,height_cm
 *         situp_01.csv,SIT_UPS,12,
 *         jump_01.csv,VERTICAL_JUMP,38.5,172
 *
 *  5. Run: `gradlew :app:testDebugUnitTest --tests "*ValidationHarnessTest*"`
 *
 * The report lands in `app/build/reports/validation/sprint3-accuracy.csv`.
 */
object ValidationHarness {

    data class Case(
        val sequenceFile: String,
        val testType: TestType,
        val expected: Double,
        val athleteHeightCm: Double?
    )

    data class Outcome(
        val case: Case,
        val result: AnalyzerResult,
        val error: Double,
        val withinTarget: Boolean
    )

    data class Report(
        val outcomes: List<Outcome>,
        val skipped: List<String>
    ) {

        val evaluated: Int get() = outcomes.size

        val withinTarget: Int get() = outcomes.count { it.withinTarget }

        val meanAbsoluteError: Double
            get() = if (outcomes.isEmpty()) 0.0
            else outcomes.sumOf { abs(it.error) } / outcomes.size

        fun meanAbsoluteErrorFor(testType: TestType): Double {
            val subset = outcomes.filter { it.case.testType == testType }
            return if (subset.isEmpty()) 0.0
            else subset.sumOf { abs(it.error) } / subset.size
        }

        fun passRate(): Double =
            if (outcomes.isEmpty()) 0.0
            else withinTarget.toDouble() / outcomes.size
    }

    /**
     * Reads the ground-truth manifest. Returns an empty list when there is no
     * reference data yet, which is the current state of the repository.
     */
    fun loadCases(referenceDirectory: File): List<Case> {

        val manifest = File(referenceDirectory, GROUND_TRUTH_FILE)

        if (!manifest.exists()) {
            return emptyList()
        }

        return manifest.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filterNot { it.startsWith("sequence,") }
            .mapNotNull { line ->

                val cells = line.split(',')
                if (cells.size < 3) return@mapNotNull null

                val testType = runCatching {
                    TestType.valueOf(cells[1].trim().uppercase(Locale.US))
                }.getOrNull() ?: return@mapNotNull null

                val expected = cells[2].trim().toDoubleOrNull() ?: return@mapNotNull null

                Case(
                    sequenceFile = cells[0].trim(),
                    testType = testType,
                    expected = expected,
                    athleteHeightCm = cells.getOrNull(3)?.trim()?.toDoubleOrNull()
                )
            }
    }

    fun run(referenceDirectory: File): Report {

        val outcomes = mutableListOf<Outcome>()
        val skipped = mutableListOf<String>()

        for (case in loadCases(referenceDirectory)) {

            val sequenceFile = File(
                File(referenceDirectory, SEQUENCES_DIR),
                case.sequenceFile
            )

            if (!sequenceFile.exists()) {
                skipped += "${case.sequenceFile}: file not found"
                continue
            }

            if (case.testType == TestType.VERTICAL_JUMP && case.athleteHeightCm == null) {
                // Jump height cannot be computed without the athlete's height,
                // and guessing one would produce a confidently wrong number.
                skipped += "${case.sequenceFile}: height_cm missing for a jump"
                continue
            }

            val frames = PoseSequenceCodec.decode(sequenceFile.readText())

            if (frames.isEmpty()) {
                skipped += "${case.sequenceFile}: no frames decoded"
                continue
            }

            val analyzer: TestAnalyzer = when (case.testType) {
                TestType.SIT_UPS -> SitUpAnalyzer()
                TestType.VERTICAL_JUMP -> VerticalJumpAnalyzer(case.athleteHeightCm!!)
            }

            val result = analyzer.analyzeSequence(frames)

            val error = result.score - case.expected

            val tolerance = when (case.testType) {
                TestType.SIT_UPS -> AnalyzerThresholds.TARGET_SITUP_TOLERANCE_REPS.toDouble()
                TestType.VERTICAL_JUMP -> AnalyzerThresholds.TARGET_JUMP_TOLERANCE_CM
            }

            outcomes += Outcome(
                case = case,
                result = result,
                error = error,
                // An unscored attempt is a failure against ground truth, not a
                // free pass — the athlete did the test and got no number.
                withinTarget = result.isUsable && abs(error) <= tolerance
            )
        }

        return Report(outcomes = outcomes, skipped = skipped)
    }

    fun writeReport(report: Report, destination: File) {

        destination.parentFile?.mkdirs()

        val builder = StringBuilder()

        builder.append(
            "sequence,test,expected,actual,error,within_target,status," +
                "confidence,frames_analyzed,frames_rejected,invalid_reason\n"
        )

        report.outcomes.forEach { outcome ->
            builder.append(outcome.case.sequenceFile).append(',')
            builder.append(outcome.case.testType.name).append(',')
            builder.append(format(outcome.case.expected)).append(',')
            builder.append(format(outcome.result.score)).append(',')
            builder.append(format(outcome.error)).append(',')
            builder.append(outcome.withinTarget).append(',')
            builder.append(outcome.result.status.name).append(',')
            builder.append(format(outcome.result.confidence)).append(',')
            builder.append(outcome.result.framesAnalyzed).append(',')
            builder.append(outcome.result.framesRejected).append(',')
            builder.append(
                outcome.result.invalidReason?.replace(',', ';') ?: ""
            )
            builder.append('\n')
        }

        destination.writeText(builder.toString())
    }

    fun summarize(report: Report): String {

        if (report.evaluated == 0) {
            return "No reference sequences evaluated."
        }

        return buildString {
            appendLine("Sequences evaluated : ${report.evaluated}")
            appendLine(
                "Within target       : ${report.withinTarget}/${report.evaluated} " +
                    "(${(report.passRate() * 100).toInt()}%)"
            )
            appendLine(
                "Mean abs error      : sit-ups " +
                    "${format(report.meanAbsoluteErrorFor(TestType.SIT_UPS))} reps, " +
                    "jump ${format(report.meanAbsoluteErrorFor(TestType.VERTICAL_JUMP))} cm"
            )
            if (report.skipped.isNotEmpty()) {
                appendLine("Skipped:")
                report.skipped.forEach { appendLine("  - $it") }
            }
        }
    }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    const val GROUND_TRUTH_FILE = "ground-truth.csv"
    const val SEQUENCES_DIR = "sequences"

    /**
     * Unit tests run with the module directory as the working directory, so the
     * repo-root reference folder is two levels up. Override with
     * `-Df4all.referenceDir=<path>`.
     */
    fun defaultReferenceDirectory(): File =
        System.getProperty("f4all.referenceDir")
            ?.let { File(it) }
            ?: File("../../docs/reference-videos")
}

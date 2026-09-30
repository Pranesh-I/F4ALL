package com.sai.sports.ui.common

import androidx.annotation.StringRes
import com.sai.sports.R
import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.FormIssue
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiFailure
import com.sai.sports.coach.CoachCue
import com.sai.sports.sync.SyncStatus

/**
 * Every code the app receives, mapped to a translatable string.
 *
 * The server and the analyzers speak in stable codes and English diagnostics.
 * Nothing the athlete reads comes from them directly — an athlete reading Tamil
 * must not be shown "score_discrepancy" or a Python error message. Kept free of
 * Context so the mappings are unit-tested.
 */
object Labels {

    @StringRes
    fun testName(type: TestType): Int = when (type) {
        TestType.SQUATS -> R.string.test_squats
        TestType.PUSH_UPS -> R.string.test_push_ups
        TestType.BICEP_CURLS -> R.string.test_bicep_curls
        TestType.LUNGES -> R.string.test_lunges
        TestType.SIT_UPS -> R.string.test_sit_ups
        TestType.VERTICAL_JUMP -> R.string.test_vertical_jump
    }

    /**
     * What a coaching cue says, on screen and aloud. [CoachCue.Counted]'s
     * string takes the count as its argument. Depth advice names the movement,
     * because "go deeper" means something different for a curl and a squat.
     */
    @StringRes
    fun cue(cue: CoachCue, testType: TestType): Int = when (cue) {
        is CoachCue.Counted -> R.string.cue_good_rep
        is CoachCue.Fault -> formIssue(cue.issue)
        CoachCue.GoDeeper -> when (testType) {
            TestType.SQUATS -> R.string.cue_depth_squat
            TestType.PUSH_UPS -> R.string.cue_depth_pushup
            TestType.BICEP_CURLS -> R.string.cue_depth_curl
            TestType.LUNGES -> R.string.cue_depth_lunge
            TestType.SIT_UPS -> R.string.cue_depth_situp
            TestType.VERTICAL_JUMP -> R.string.cue_depth_generic
        }
        CoachCue.TooFast -> R.string.cue_too_fast
        CoachCue.WrongArm -> R.string.cue_wrong_arm
        CoachCue.StartPosition -> R.string.cue_start_position
    }

    @StringRes
    fun formIssue(issue: FormIssue): Int = when (issue) {
        FormIssue.TORSO_LEAN -> R.string.cue_torso_lean
        FormIssue.HIPS_SAGGING -> R.string.cue_hips_sagging
        FormIssue.HIPS_PIKED -> R.string.cue_hips_piked
        FormIssue.NOT_IN_PLANK -> R.string.cue_not_in_plank
        FormIssue.ELBOW_FLARE -> R.string.cue_elbow_flare
        FormIssue.STANCE_TOO_NARROW -> R.string.cue_stance_too_narrow
        FormIssue.KNEE_PAST_TOES -> R.string.cue_knee_past_toes
        FormIssue.FEET_MOVED -> R.string.cue_feet_moved
        FormIssue.BODY_SWING -> R.string.cue_body_swing
    }

    @StringRes
    fun testName(code: String): Int =
        runCatching { testName(TestType.valueOf(code)) }.getOrDefault(R.string.test_unknown)

    @StringRes
    fun unit(unit: String): Int = when (unit) {
        "reps" -> R.string.unit_reps
        "cm" -> R.string.unit_cm
        "seconds" -> R.string.unit_seconds
        else -> R.string.unit_blank
    }

    @StringRes
    fun resultStatus(status: String): Int = when (status) {
        // Queued for a worker vs claimed by one: the same wait to the athlete.
        "uploaded", "processing" -> R.string.status_processing
        "verified" -> R.string.status_verified
        "flagged" -> R.string.status_flagged
        "approved" -> R.string.status_approved
        "rejected" -> R.string.status_rejected
        "pending_sync" -> R.string.status_resubmission
        else -> R.string.status_unknown
    }

    @StringRes
    fun syncStatus(status: SyncStatus): Int = when (status) {
        SyncStatus.RECORDED -> R.string.sync_recorded
        SyncStatus.COMPRESSING -> R.string.sync_compressing
        SyncStatus.COMPRESSED -> R.string.sync_compressed
        SyncStatus.QUEUED -> R.string.sync_queued
        SyncStatus.UPLOADING -> R.string.sync_uploading
        SyncStatus.SYNCED -> R.string.sync_synced
        SyncStatus.FAILED -> R.string.sync_failed
    }

    @StringRes
    fun reviewAction(action: String): Int = when (action) {
        "approved" -> R.string.review_approved
        "rejected" -> R.string.review_rejected
        "requested_resubmission" -> R.string.review_resubmission
        else -> R.string.review_other
    }

    @StringRes
    fun benchmarkBand(band: String): Int = when (band) {
        "top_10" -> R.string.band_top_10
        "top_25" -> R.string.band_top_25
        "above_average" -> R.string.band_above_average
        else -> R.string.band_below_average
    }

    @StringRes
    fun gender(code: String): Int = when (code) {
        "male" -> R.string.gender_male
        "female" -> R.string.gender_female
        "other" -> R.string.gender_other
        else -> R.string.gender_all
    }

    @StringRes
    fun badgeTitle(code: String): Int = when (code) {
        "first_test" -> R.string.badge_first_test
        "first_verified" -> R.string.badge_first_verified
        "personal_best" -> R.string.badge_personal_best
        "all_tests" -> R.string.badge_all_tests
        "streak_2_weeks" -> R.string.badge_streak_2
        "streak_4_weeks" -> R.string.badge_streak_4
        "streak_8_weeks" -> R.string.badge_streak_8
        else -> R.string.badge_unknown
    }

    @StringRes
    fun badgeDescription(code: String): Int = when (code) {
        "first_test" -> R.string.badge_first_test_desc
        "first_verified" -> R.string.badge_first_verified_desc
        "personal_best" -> R.string.badge_personal_best_desc
        "all_tests" -> R.string.badge_all_tests_desc
        "streak_2_weeks", "streak_4_weeks", "streak_8_weeks" -> R.string.badge_streak_desc
        else -> R.string.badge_unknown
    }

    /** A failure the athlete can act on, in their language. */
    @StringRes
    fun failure(kind: ApiFailure, @StringRes otherwise: Int): Int = when (kind) {
        ApiFailure.NETWORK -> R.string.error_network
        ApiFailure.SERVER -> R.string.error_server
        ApiFailure.RATE_LIMITED -> R.string.error_rate_limited
        ApiFailure.UNAUTHORIZED -> R.string.error_session_expired
        else -> otherwise
    }

    /**
     * The analyzers' reasons for not scoring, translated.
     *
     * Matched on stable prefixes of the analyzer's English message.
     * `AnalyzerReasonCoverageTest` reads the analyzer sources and fails if any
     * reason there has no translation here.
     */
    @StringRes
    fun invalidReason(reason: String?): Int {
        val text = reason ?: return R.string.invalid_generic
        return INVALID_REASONS.entries.firstOrNull { text.startsWith(it.key) }?.value
            ?: R.string.invalid_generic
    }

    val INVALID_REASONS: Map<String, Int> = linkedMapOf(
        "No usable pose data" to R.string.invalid_no_pose,
        // The specific start-position reasons must precede the sit-up one:
        // lookup takes the first matching prefix.
        "Start position never detected — stand tall" to R.string.invalid_no_start_standing,
        "Start position never detected — hold a straight-arm plank" to R.string.invalid_no_start_plank,
        "Start position never detected — stand facing" to R.string.invalid_no_start_curl,
        "Start position never detected" to R.string.invalid_no_start,
        "Lost track of you mid-jump" to R.string.invalid_lost_track,
        "No clean landing detected" to R.string.invalid_no_landing,
        "Could not set a standing reference" to R.string.invalid_no_reference,
        "Recording stopped mid-jump" to R.string.invalid_stopped_mid_jump,
        "No jump detected" to R.string.invalid_no_jump,
        "You or the camera moved" to R.string.invalid_moved
    )

    /** Counts of what happened during an attempt, for a plain-language summary. */
    data class AttemptSummary(
        val repsCounted: Int,
        val partialReps: Int,
        val tooFastReps: Int,
        val jumpsMeasured: Int,
        val jumpsRejected: Int,
        val trackingLost: Int,
        val formRejectedReps: Int = 0,
        val formWarnings: Int = 0,
        val wrongArmReps: Int = 0
    )

    fun summarise(events: List<AnalyzerEvent>): AttemptSummary {
        fun count(label: String) = events.count { it.label == label }
        return AttemptSummary(
            repsCounted = count("rep_counted"),
            partialReps = count("rep_rejected_partial"),
            tooFastReps = count("rep_rejected_too_fast"),
            jumpsMeasured = count("jump_measured"),
            jumpsRejected = count("jump_rejected"),
            trackingLost = count("tracking_lost"),
            formRejectedReps = count("rep_rejected_form"),
            formWarnings = count("form_warning"),
            wrongArmReps = count("rep_rejected_wrong_arm")
        )
    }

    /** "female, 14-15" from the server -> gender code and age range. */
    fun parseCohort(cohort: String): Pair<String, String> {
        val parts = cohort.split(",").map { it.trim() }
        return (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
    }
}

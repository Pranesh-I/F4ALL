package com.sai.sports.fieldtest

/**
 * Tests scored from motion sensors and GPS instead of pose: the shuttle run
 * and the endurance run. Kept apart from the pose [com.sai.sports.analyzer.TestType]
 * so nothing in the camera pipeline has to know they exist.
 */
enum class FieldTestType(val unit: String) {
    SHUTTLE_RUN("seconds"),
    ENDURANCE_RUN("seconds");

    /** Timed: the lowest time is the best. */
    val higherIsBetter: Boolean get() = false

    companion object {
        fun fromCode(code: String?): FieldTestType? = entries.firstOrNull { it.name == code }
    }
}

enum class FieldOutcome { COMPLETE, INVALID }

/**
 * One inertial sample: acceleration (m/s², gravity included) and rotation rate
 * (rad/s, zero where the phone has no gyroscope), in the phone's axes. [tMs]
 * is milliseconds from the GO beep; samples before it are negative.
 */
data class Motion(
    val tMs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double = 0.0,
    val gy: Double = 0.0,
    val gz: Double = 0.0
)

data class Fix(val tMs: Long, val lat: Double, val lon: Double, val accuracyM: Double)

/** What was found, for the athlete and the reviewer: start, moving, turn, finish, split. */
data class FieldEvent(val tMs: Long, val label: String, val detail: String = "")

/**
 * A scored field test. [invalid] is a stable code (see [FieldInvalid]) the app
 * translates; [score] is seconds, 0 when the trace could not be scored.
 */
data class FieldResult(
    val testType: FieldTestType,
    val outcome: FieldOutcome,
    val score: Double,
    val invalid: String? = null,
    val events: List<FieldEvent> = emptyList(),
    val turns: Int? = null,
    val legMs: List<Long>? = null,
    val distanceM: Double? = null,
    val paceSecondsPerKm: Double? = null,
    val cadenceSpm: Double? = null,
    val gpsJumps: Int? = null
) {
    companion object {
        fun invalid(
            testType: FieldTestType,
            reason: String,
            events: List<FieldEvent> = emptyList(),
            turns: Int? = null,
            legMs: List<Long>? = null,
            distanceM: Double? = null,
            gpsJumps: Int? = null,
            paceSecondsPerKm: Double? = null,
            cadenceSpm: Double? = null
        ) = FieldResult(
            testType, FieldOutcome.INVALID, 0.0, reason, events,
            turns = turns, legMs = legMs, distanceM = distanceM, gpsJumps = gpsJumps,
            paceSecondsPerKm = paceSecondsPerKm, cadenceSpm = cadenceSpm
        )
    }
}

/** Why a trace could not be scored. The same codes as the server's `Invalid`. */
object FieldInvalid {
    const val NOT_ENOUGH_DATA = "not_enough_data"
    const val LOW_SAMPLE_RATE = "low_sample_rate"
    const val SENSOR_GAP = "sensor_gap"
    const val MOVED_BEFORE_START = "moved_before_start"
    const val WRONG_TURN_COUNT = "wrong_turn_count"
    const val LEG_TOO_FAST = "leg_too_fast"
    const val TOO_FAST = "too_fast"
    const val NO_FINISH = "no_finish"
    const val NO_START_FIX = "no_start_fix"
    const val GPS_WEAK = "gps_weak"
    const val GPS_UNRELIABLE = "gps_unreliable"
    const val GPS_GAP = "gps_gap"
    const val DISTANCE_NOT_REACHED = "distance_not_reached"
    const val NOT_ON_FOOT = "not_on_foot"

    val ALL = listOf(
        NOT_ENOUGH_DATA, LOW_SAMPLE_RATE, SENSOR_GAP, MOVED_BEFORE_START, WRONG_TURN_COUNT,
        LEG_TOO_FAST, TOO_FAST, NO_FINISH, NO_START_FIX, GPS_WEAK, GPS_UNRELIABLE, GPS_GAP,
        DISTANCE_NOT_REACHED, NOT_ON_FOOT
    )
}

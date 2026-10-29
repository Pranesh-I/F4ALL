package com.sai.sports.fieldtest

import com.sai.sports.fieldtest.FieldThresholds as T
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Endurance run, 600 m, from GPS — the distance in the Khelo India battery.
 *
 * A line-for-line port of `backend/app/field_tests/endurance.py`; the shared
 * fixtures (`FieldParityTest`) hold the two to the same answers.
 *
 * The track is the evidence: fixes worse than 25 m are ignored, jumps faster
 * than a person can move are GPS error, jitter under 3 m is not distance, the
 * finish is interpolated at 600 m, and the accelerometer must show steps — a
 * bicycle or a bus carries the phone the distance without any.
 */
object EnduranceRunAnalyzer {

    private const val EARTH_RADIUS_M = 6371000.0
    private const val SPLIT_METRES = 100.0

    fun score(inputFixes: List<Fix>, inputMotion: List<Motion>): FieldResult {
        val test = FieldTestType.ENDURANCE_RUN
        val fixes = inputFixes.sortedBy { it.tMs }
        val motion = inputMotion.sortedBy { it.tMs }

        val accepted = mutableListOf<Fix>()
        var considered = 0
        var jumps = 0
        for (fix in fixes) {
            if (fix.accuracyM > T.ENDURANCE_MAX_ACCURACY_M) continue
            considered += 1
            if (accepted.isEmpty()) {
                accepted += fix
                continue
            }
            val last = accepted.last()
            val seconds = (fix.tMs - last.tMs) / 1000.0
            if (seconds <= 0.0) continue
            if (distanceM(last, fix) / seconds > T.ENDURANCE_JUMP_SPEED_MPS) {
                jumps += 1
                continue
            }
            accepted += fix
        }

        if (considered < 10) {
            // Fixes arrived but too few were accurate enough: indoors, or no sky.
            val reason = if (fixes.size >= 10) FieldInvalid.GPS_WEAK else FieldInvalid.NOT_ENOUGH_DATA
            return FieldResult.invalid(test, reason, gpsJumps = jumps)
        }
        if (jumps > T.ENDURANCE_MAX_JUMPS || jumps.toDouble() / considered > T.ENDURANCE_MAX_JUMP_SHARE) {
            return FieldResult.invalid(test, FieldInvalid.GPS_UNRELIABLE, gpsJumps = jumps)
        }

        val anchorIndex = accepted.indexOfFirst { it.tMs >= -T.ENDURANCE_START_FIX_MS }
        if (anchorIndex < 0 || accepted[anchorIndex].tMs > T.ENDURANCE_START_FIX_MS) {
            return FieldResult.invalid(test, FieldInvalid.NO_START_FIX, gpsJumps = jumps)
        }

        val events = mutableListOf(FieldEvent(0, "start"))
        var pivot = accepted[anchorIndex]
        var total = 0.0
        var nextSplit = SPLIT_METRES
        var finish: Long? = null
        var previousT = pivot.tMs
        for (fix in accepted.subList(anchorIndex + 1, accepted.size)) {
            if (fix.tMs - previousT > T.ENDURANCE_MAX_FIX_GAP_MS) {
                return FieldResult.invalid(
                    test, FieldInvalid.GPS_GAP, events, distanceM = round1(total), gpsJumps = jumps
                )
            }
            previousT = fix.tMs
            val segment = distanceM(pivot, fix)
            if (segment < T.ENDURANCE_MIN_SEGMENT_M) continue
            val before = total
            total += segment
            while (nextSplit <= total && nextSplit <= T.ENDURANCE_TARGET_METRES) {
                val at = pivot.tMs + (fix.tMs - pivot.tMs) * (nextSplit - before) / segment
                if (nextSplit < T.ENDURANCE_TARGET_METRES) {
                    events += FieldEvent(roundMs(at), "split", nextSplit.toInt().toString())
                }
                nextSplit += SPLIT_METRES
            }
            if (total >= T.ENDURANCE_TARGET_METRES) {
                finish = roundMs(
                    pivot.tMs + (fix.tMs - pivot.tMs) * (T.ENDURANCE_TARGET_METRES - before) / segment
                )
                break
            }
            pivot = fix
        }

        val distance = round1(min(total, T.ENDURANCE_TARGET_METRES))
        if (finish == null) {
            return FieldResult.invalid(
                test, FieldInvalid.DISTANCE_NOT_REACHED, events, distanceM = distance, gpsJumps = jumps
            )
        }
        events += FieldEvent(finish, "finish")

        val seconds = finish / 1000.0
        val pace = round1(seconds * 1000.0 / T.ENDURANCE_TARGET_METRES)
        if (seconds <= 0.0 || T.ENDURANCE_TARGET_METRES / seconds > T.ENDURANCE_MAX_AVERAGE_SPEED_MPS) {
            return FieldResult.invalid(
                test, FieldInvalid.TOO_FAST, events, distanceM = distance, gpsJumps = jumps,
                paceSecondsPerKm = pace
            )
        }

        val (stepping, cadence) = stepping(motion, finish)
        if (stepping < T.ENDURANCE_MIN_STEPPING_SHARE) {
            return FieldResult.invalid(
                test, FieldInvalid.NOT_ON_FOOT, events, distanceM = distance, gpsJumps = jumps,
                paceSecondsPerKm = pace, cadenceSpm = cadence
            )
        }

        return FieldResult(
            testType = test,
            outcome = FieldOutcome.COMPLETE,
            score = Math.floorDiv(finish + 50, 100L) / 10.0,
            events = events,
            distanceM = distance,
            paceSecondsPerKm = pace,
            cadenceSpm = cadence,
            gpsJumps = jumps
        )
    }

    /** Great-circle distance (haversine), written as the Python is. */
    fun distanceM(a: Fix, b: Fix): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val halfLat = sin(dLat / 2.0)
        val halfLon = sin(dLon / 2.0)
        val h = halfLat * halfLat + cos(lat1) * cos(lat2) * halfLon * halfLon
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(min(1.0, h)))
    }

    /** Distance covered so far, for the live screen during a run. */
    fun distanceSoFar(fixes: List<Fix>): Double {
        var total = 0.0
        var pivot: Fix? = null
        for (fix in fixes.sortedBy { it.tMs }) {
            if (fix.accuracyM > T.ENDURANCE_MAX_ACCURACY_M) continue
            val from = pivot
            if (from == null) {
                pivot = fix
                continue
            }
            val segment = distanceM(from, fix)
            if (segment < T.ENDURANCE_MIN_SEGMENT_M) continue
            val seconds = (fix.tMs - from.tMs) / 1000.0
            if (seconds > 0.0 && segment / seconds > T.ENDURANCE_JUMP_SPEED_MPS) continue
            total += segment
            pivot = fix
        }
        return total
    }

    /** (share of windows with a stepping cadence, average cadence in steps/min). */
    private fun stepping(motion: List<Motion>, finish: Long): Pair<Double, Double> {
        val during = motion.filter { it.tMs in 0..finish }
        if (during.size < 3) return 0.0 to 0.0
        val sizes = during.map(ShuttleRunAnalyzer::magnitude)
        val steps = mutableListOf<Long>()
        for (i in 1 until during.size - 1) {
            val peak = sizes[i] > sizes[i - 1] && sizes[i] >= sizes[i + 1]
            if (!peak || sizes[i] <= T.GRAVITY + T.ENDURANCE_STEP_PEAK) continue
            if (steps.isNotEmpty() && during[i].tMs - steps.last() < T.ENDURANCE_STEP_MIN_GAP_MS) continue
            steps += during[i].tMs
        }

        var windows = 0
        var stepping = 0
        var start = 0L
        while (start + T.ENDURANCE_CADENCE_WINDOW_MS <= finish) {
            val end = start + T.ENDURANCE_CADENCE_WINDOW_MS
            val count = steps.count { it in start until end }
            val cadence = count * 60000.0 / T.ENDURANCE_CADENCE_WINDOW_MS
            windows += 1
            if (cadence >= T.ENDURANCE_MIN_CADENCE) stepping += 1
            start = end
        }
        if (windows == 0) return 0.0 to 0.0
        return stepping.toDouble() / windows to round1(steps.size * 60000.0 / finish)
    }

    private fun roundMs(value: Double): Long = floor(value + 0.5).toLong()

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
}

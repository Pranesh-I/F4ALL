package com.sai.sports.fieldtest

import com.sai.sports.fieldtest.FieldThresholds as T
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shuttle run, 4 x 10 m, from the phone's motion sensors.
 *
 * A line-for-line port of `backend/app/field_tests/shuttle.py`; the shared
 * fixtures (`FieldParityTest`) hold the two to the same answers, so the server
 * re-scoring an athlete's trace gets the time the phone showed them.
 *
 * The protocol the instructions state: the phone worn at the waist (or held
 * firmly), standing still until the GO beep, four lengths with three turns,
 * then run through the line, stop within a couple of steps and stand still.
 * Turns are rotation about gravity, so how the phone is carried does not
 * matter; the finish is when the athlete becomes still, less the steps it
 * takes to stop.
 */
object ShuttleRunAnalyzer {

    private const val RADIANS_TO_DEGREES = 180.0 / Math.PI

    fun score(input: List<Motion>): FieldResult {
        val test = FieldTestType.SHUTTLE_RUN
        val samples = input.sortedBy { it.tMs }
        if (samples.size < 50 || samples.first().tMs >= 0 || samples.last().tMs <= 0) {
            return FieldResult.invalid(test, FieldInvalid.NOT_ENOUGH_DATA)
        }

        val gaps = samples.zipWithNext { a, b -> b.tMs - a.tMs }.sorted()
        if (gaps[gaps.size / 2] > T.SHUTTLE_MAX_MEDIAN_GAP_MS) {
            return FieldResult.invalid(test, FieldInvalid.LOW_SAMPLE_RATE)
        }
        if (gaps.last() > T.SHUTTLE_MAX_GAP_MS) {
            return FieldResult.invalid(test, FieldInvalid.SENSOR_GAP)
        }

        val before = samples.filter { it.tMs >= -T.SHUTTLE_PRESTART_MS && it.tMs < 0 }
        if (before.size < 10) return FieldResult.invalid(test, FieldInvalid.NOT_ENOUGH_DATA)
        if (std(before.map(::magnitude)) > T.SHUTTLE_STILL_STD) {
            return FieldResult.invalid(test, FieldInvalid.MOVED_BEFORE_START)
        }

        val after = samples.filter { it.tMs >= 0 }
        val turns = turns(before, after)
        val events = mutableListOf(FieldEvent(0, "start"))
        firstMoving(after)?.let { events += FieldEvent(it, "moving") }

        if (turns.size < T.SHUTTLE_TURNS) {
            return FieldResult.invalid(
                test, FieldInvalid.WRONG_TURN_COUNT, events + turnEvents(turns), turns = turns.size
            )
        }

        val stop = stillnessAfter(after, turns[T.SHUTTLE_TURNS - 1] + T.SHUTTLE_MIN_LEG_MS)
            ?: return FieldResult.invalid(
                test, FieldInvalid.NO_FINISH, events + turnEvents(turns), turns = turns.size
            )
        val finish = stop - T.SHUTTLE_FINISH_ALLOWANCE_MS

        val counted = turns.filter { it < stop }
        events += turnEvents(counted)
        if (counted.size != T.SHUTTLE_TURNS) {
            return FieldResult.invalid(test, FieldInvalid.WRONG_TURN_COUNT, events, turns = counted.size)
        }

        val marks = listOf(0L) + counted + finish
        val legs = marks.zipWithNext { a, b -> b - a }
        events += FieldEvent(finish, "finish")

        fun invalid(reason: String) =
            FieldResult.invalid(test, reason, events, turns = counted.size, legMs = legs)
        if (finish > T.SHUTTLE_MAX_TOTAL_MS) return invalid(FieldInvalid.NO_FINISH)
        if (legs.any { it < T.SHUTTLE_MIN_LEG_MS }) return invalid(FieldInvalid.LEG_TOO_FAST)
        if (finish < T.SHUTTLE_MIN_TOTAL_MS) return invalid(FieldInvalid.TOO_FAST)

        return FieldResult(
            testType = test,
            outcome = FieldOutcome.COMPLETE,
            score = Math.floorDiv(finish + 5, 10L) / 100.0,
            events = events,
            turns = counted.size,
            legMs = legs
        )
    }

    fun magnitude(sample: Motion): Double =
        sqrt(sample.ax * sample.ax + sample.ay * sample.ay + sample.az * sample.az)

    /** Times (ms) at which the body had turned far enough to count a turn. */
    private fun turns(before: List<Motion>, after: List<Motion>): List<Long> {
        val count = before.size.toDouble()
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (s in before) sx += s.ax
        for (s in before) sy += s.ay
        for (s in before) sz += s.az
        var g0 = sx / count
        var g1 = sy / count
        var g2 = sz / count

        val window = ArrayDeque<Pair<Long, Double>>()
        var turned = 0.0
        val turns = mutableListOf<Long>()
        var previous = after.first().tMs
        for (sample in after) {
            val dt = (sample.tMs - previous).toDouble()
            previous = sample.tMs
            val alpha = dt / (T.SHUTTLE_GRAVITY_TIME_CONSTANT_MS + dt)
            g0 += alpha * (sample.ax - g0)
            g1 += alpha * (sample.ay - g1)
            g2 += alpha * (sample.az - g2)
            val size = sqrt(g0 * g0 + g1 * g1 + g2 * g2)
            if (size == 0.0) continue
            val yaw = (sample.gx * g0 + sample.gy * g1 + sample.gz * g2) / size
            val step = yaw * (dt / 1000.0) * RADIANS_TO_DEGREES

            // The rest of a turn just counted is not the start of the next one.
            if (turns.isNotEmpty() && sample.tMs - turns.last() < T.SHUTTLE_TURN_GAP_MS) continue

            window.addLast(sample.tMs to step)
            turned += step
            while (window.isNotEmpty() && window.first().first < sample.tMs - T.SHUTTLE_TURN_WINDOW_MS) {
                turned -= window.removeFirst().second
            }

            if (abs(turned) >= T.SHUTTLE_TURN_DEGREES) {
                turns += sample.tMs
                window.clear()
                turned = 0.0
            }
        }
        return turns
    }

    /** |acceleration| spread over the stop window ending at [index]; null before the trace reaches back that far. */
    private fun windowedStd(after: List<Motion>, index: Int): Double? {
        val end = after[index].tMs
        val start = end - T.SHUTTLE_STOP_WINDOW_MS
        if (after.first().tMs > start) return null
        val values = mutableListOf<Double>()
        var j = index
        while (j >= 0 && after[j].tMs >= start) {
            values += magnitude(after[j])
            j -= 1
        }
        return std(values)
    }

    private fun firstMoving(after: List<Motion>): Long? {
        for (index in after.indices) {
            val spread = windowedStd(after, index)
            if (spread != null && spread > T.SHUTTLE_STILL_STD) return after[index].tMs
        }
        return null
    }

    /** When the athlete became still (and stayed still), at or after [earliest]. */
    private fun stillnessAfter(after: List<Motion>, earliest: Long): Long? {
        for (index in after.indices) {
            val sample = after[index]
            if (sample.tMs < earliest) continue
            val spread = windowedStd(after, index)
            if (spread == null || spread >= T.SHUTTLE_STILL_STD) continue
            val onset = sample.tMs - T.SHUTTLE_STOP_WINDOW_MS
            val holdUntil = sample.tMs + T.SHUTTLE_STOP_HOLD_MS
            if (after.last().tMs < holdUntil) return null
            var still = true
            var j = index + 1
            while (j < after.size && after[j].tMs <= holdUntil) {
                val later = windowedStd(after, j)
                if (later == null || later >= T.SHUTTLE_STILL_STD) {
                    still = false
                    break
                }
                j += 1
            }
            if (still) return onset
        }
        return null
    }

    private fun turnEvents(turns: List<Long>): List<FieldEvent> =
        turns.mapIndexed { index, t -> FieldEvent(t, "turn", (index + 1).toString()) }

    private fun std(values: List<Double>): Double {
        val count = values.size.toDouble()
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / count
        var squares = 0.0
        for (v in values) squares += (v - mean) * (v - mean)
        return sqrt(squares / count)
    }
}

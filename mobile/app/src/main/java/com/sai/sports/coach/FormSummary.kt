package com.sai.sports.coach

import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.FormIssue

/**
 * The form-quality verdict on a whole attempt.
 *
 * Built from the analyzer's stored trace, so it can be recomputed for any saved
 * attempt — nothing extra is persisted, and an attempt recorded before this
 * existed gets a summary too.
 */
data class FormSummary(
    /** Every rep the athlete tried: counted or refused for any reason. */
    val attemptedReps: Int,
    /** Counted reps with no form warning. */
    val goodReps: Int,
    /** What went wrong most often, most frequent first. */
    val toWorkOn: List<CoachCue>
) {

    /** 0..100 — the share of attempted reps done with good form. */
    val scorePercent: Int
        get() = if (attemptedReps == 0) 0 else goodReps * 100 / attemptedReps

    companion object {

        private const val MAX_ADVICE = 2

        /** Null when the attempt had no reps to judge — a jump, or nothing recorded. */
        fun from(events: List<AnalyzerEvent>): FormSummary? {

            val counted = events.count { it.label == "rep_counted" }
            val refused = events.count {
                it.label == "rep_rejected_partial" ||
                    it.label == "rep_rejected_too_fast" ||
                    it.label == "rep_rejected_form" ||
                    it.label == "rep_rejected_wrong_arm"
            }
            val attempted = counted + refused
            if (attempted == 0) return null

            // Warnings share their rep's timestamp, so distinct timestamps are
            // the number of counted reps that had at least one.
            val repsWithWarnings = events
                .filter { it.label == "form_warning" }
                .map { it.timestampMs }
                .distinct()
                .size

            val tally = mutableListOf<CoachCue>()
            events.forEach { event ->
                when (event.label) {
                    "rep_rejected_partial" -> tally += CoachCue.GoDeeper
                    "rep_rejected_too_fast" -> tally += CoachCue.TooFast
                    "rep_rejected_wrong_arm" -> tally += CoachCue.WrongArm
                    "rep_rejected_form", "form_warning" ->
                        event.detail.split(",").mapNotNull { FormIssue.fromCode(it) }
                            .forEach { tally += CoachCue.Fault(it) }
                }
            }

            val toWorkOn = tally
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<CoachCue, Int>> { it.value }
                        .thenByDescending { it.key.tone.rank }
                        .thenBy { tally.indexOf(it.key) }
                )
                .take(MAX_ADVICE)
                .map { it.key }

            return FormSummary(
                attemptedReps = attempted,
                goodReps = (counted - repsWithWarnings).coerceAtLeast(0),
                toWorkOn = toWorkOn
            )
        }
    }
}

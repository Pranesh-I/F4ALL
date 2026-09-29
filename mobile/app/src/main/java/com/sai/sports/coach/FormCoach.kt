package com.sai.sports.coach

import com.sai.sports.analyzer.AnalyzerEvent
import com.sai.sports.analyzer.FormIssue

/** How a cue should look and how urgently it should be said. */
enum class CueTone(val rank: Int) {
    POSITIVE(0),
    INFO(1),
    WARNING(2),
    FAULT(3)
}

/**
 * One thing the coach can tell the athlete.
 *
 * Data, not text: the capture screen and the voice both render it in the
 * athlete's language. [key] is the cue's identity for cooldowns — two cues with
 * the same key are the same advice said twice.
 */
sealed interface CoachCue {

    val tone: CueTone
    val key: String

    /** A rep counted. Spoken as its number, so an athlete who cannot see the screen keeps count. */
    data class Counted(val count: Int) : CoachCue {
        override val tone = CueTone.POSITIVE
        override val key = "counted"
    }

    /** A form fault: red when it cost the rep, amber when it is a coaching point. */
    data class Fault(val issue: FormIssue) : CoachCue {
        override val tone = if (issue.rejectsRep) CueTone.FAULT else CueTone.WARNING
        override val key = "issue.${issue.code}"
    }

    /** The rep turned back before reaching depth. */
    data object GoDeeper : CoachCue {
        override val tone = CueTone.FAULT
        override val key = "depth"
    }

    data object TooFast : CoachCue {
        override val tone = CueTone.WARNING
        override val key = "too_fast"
    }

    data object WrongArm : CoachCue {
        override val tone = CueTone.FAULT
        override val key = "wrong_arm"
    }

    /** Nothing can count until the start position is held. */
    data object StartPosition : CoachCue {
        override val tone = CueTone.INFO
        override val key = "start"
    }
}

/** What to show now, and what to say now (possibly nothing). */
data class CoachUpdate(
    val display: CoachCue?,
    val speak: List<CoachCue>
)

/**
 * Turns what the analyzer sees into feedback an athlete can act on mid-set.
 *
 * Two inputs, two kinds of feedback:
 *
 *  - Live faults ([FormIssue]s on the current frame) give advice *during* the
 *    rep, while it can still be fixed. A fault must persist for [onsetMs]
 *    before it is shown — one frame of a misplaced hip is the pose model, and
 *    an athlete told to fix something they are not doing stops listening.
 *  - Rep outcomes (the analyzer's trace events) give the verdict *after* the
 *    rep: the count for a good one, the reason for one that did not count.
 *
 * Voice is rationed separately from the screen. The screen can show a cue
 * every frame; a voice repeating "keep your chest up" every second is noise.
 * Each cue has a cooldown ([repeatCooldownMs], longer for the start prompt),
 * and non-count utterances keep [minUtteranceGapMs] apart. Rep counts are
 * exempt from both: each is a new number, never a repeat.
 *
 * Pure and clock-free — driven by frame timestamps — so it runs on the JVM in
 * tests exactly as it runs on the phone. Not thread-safe: call it from the
 * pose thread only.
 */
class FormCoach(
    private val onsetMs: Long = ISSUE_ONSET_MS,
    private val displayHoldMs: Long = DISPLAY_HOLD_MS,
    private val repeatCooldownMs: Long = REPEAT_COOLDOWN_MS,
    private val startPromptDelayMs: Long = START_PROMPT_DELAY_MS,
    private val startPromptCooldownMs: Long = START_PROMPT_COOLDOWN_MS,
    private val minUtteranceGapMs: Long = MIN_UTTERANCE_GAP_MS
) {

    private var firstFrameMs: Long? = null
    private var repsCounted = 0

    /** When each live fault was first seen in its current unbroken run. */
    private val faultSince = mutableMapOf<FormIssue, Long>()

    private var displayed: CoachCue? = null
    private var displayedAtMs = 0L

    private val lastSpokenMs = mutableMapOf<String, Long>()
    private var lastUtteranceMs: Long? = null

    fun update(
        timestampMs: Long,
        ready: Boolean,
        liveIssues: Set<FormIssue>,
        newEvents: List<AnalyzerEvent>
    ): CoachUpdate {

        val firstFrame = firstFrameMs ?: timestampMs.also { firstFrameMs = it }
        val speak = mutableListOf<CoachCue>()

        trackFaults(timestampMs, if (ready) liveIssues else emptySet())

        val outcome = outcomeOf(newEvents)

        when {
            outcome != null -> {
                show(outcome.display, timestampMs, force = true)
                outcome.speak.forEach { offer(it, timestampMs, speak) }
            }

            !ready -> {
                if (timestampMs - firstFrame >= startPromptDelayMs) {
                    show(CoachCue.StartPosition, timestampMs, force = false)
                    offer(CoachCue.StartPosition, timestampMs, speak)
                }
            }

            else -> {
                persistentFault(timestampMs)?.let { fault ->
                    if (show(fault, timestampMs, force = false)) {
                        offer(fault, timestampMs, speak)
                    }
                }
            }
        }

        if (displayed != null && timestampMs - displayedAtMs > displayHoldMs) {
            displayed = null
        }

        if (speak.isNotEmpty()) {
            lastUtteranceMs = timestampMs
            speak.forEach { lastSpokenMs[it.key] = timestampMs }
        }

        return CoachUpdate(display = displayed, speak = speak)
    }

    private fun trackFaults(timestampMs: Long, issues: Set<FormIssue>) {
        faultSince.keys.retainAll(issues)
        issues.forEach { faultSince.putIfAbsent(it, timestampMs) }
    }

    /** The most serious fault that has persisted past the onset delay. */
    private fun persistentFault(timestampMs: Long): CoachCue.Fault? =
        faultSince
            .filter { (_, since) -> timestampMs - since >= onsetMs }
            .keys
            .sortedWith(compareByDescending<FormIssue> { it.rejectsRep }.thenBy { it.ordinal })
            .firstOrNull()
            ?.let { CoachCue.Fault(it) }

    private class Outcome(val display: CoachCue, val speak: List<CoachCue>)

    /**
     * The verdict on the rep(s) judged this frame. A counted rep carries its
     * warnings as `form_warning` events at the same timestamp; the count is
     * still spoken, the warning is what is shown.
     */
    private fun outcomeOf(events: List<AnalyzerEvent>): Outcome? {

        var outcome: Outcome? = null

        events.forEachIndexed { index, event ->
            outcome = when (event.label) {

                "rep_counted" -> {
                    repsCounted++
                    val counted = CoachCue.Counted(repsCounted)
                    val warning = events.drop(index + 1)
                        .takeWhile { it.label == "form_warning" && it.timestampMs == event.timestampMs }
                        .firstNotNullOfOrNull { FormIssue.fromCode(it.detail) }
                        ?.let { CoachCue.Fault(it) }
                    if (warning == null) Outcome(counted, listOf(counted))
                    else Outcome(warning, listOf(counted, warning))
                }

                "rep_rejected_partial" -> Outcome(CoachCue.GoDeeper, listOf(CoachCue.GoDeeper))

                "rep_rejected_too_fast" -> Outcome(CoachCue.TooFast, listOf(CoachCue.TooFast))

                "rep_rejected_wrong_arm" -> Outcome(CoachCue.WrongArm, listOf(CoachCue.WrongArm))

                "rep_rejected_form" -> {
                    val issues = event.detail.split(",").mapNotNull { FormIssue.fromCode(it) }
                    val cause = issues.firstOrNull { it.rejectsRep } ?: issues.firstOrNull()
                    cause?.let { CoachCue.Fault(it) }?.let { Outcome(it, listOf(it)) } ?: outcome
                }

                else -> outcome
            }
        }

        return outcome
    }

    /**
     * Puts [cue] on screen unless something more serious is still showing.
     * Returns whether it is now the displayed cue.
     */
    private fun show(cue: CoachCue, timestampMs: Long, force: Boolean): Boolean {
        val current = displayed
        val currentExpired = current == null || timestampMs - displayedAtMs > displayHoldMs
        if (force || currentExpired || current == cue || cue.tone.rank >= current!!.tone.rank) {
            displayed = cue
            displayedAtMs = timestampMs
            return true
        }
        return false
    }

    /** Adds [cue] to this frame's speech if its cooldown and the utterance gap allow. */
    private fun offer(cue: CoachCue, timestampMs: Long, speak: MutableList<CoachCue>) {

        if (cue is CoachCue.Counted) {
            speak += cue
            return
        }

        val cooldown =
            if (cue == CoachCue.StartPosition) startPromptCooldownMs else repeatCooldownMs
        val lastSaid = lastSpokenMs[cue.key]
        if (lastSaid != null && timestampMs - lastSaid < cooldown) return

        // A count said on this same frame is not a reason to hold advice back.
        val lastUtterance = lastUtteranceMs
        if (speak.none { it is CoachCue.Counted } &&
            lastUtterance != null && timestampMs - lastUtterance < minUtteranceGapMs
        ) {
            return
        }

        speak += cue
    }

    fun reset() {
        firstFrameMs = null
        repsCounted = 0
        faultSince.clear()
        displayed = null
        displayedAtMs = 0L
        lastSpokenMs.clear()
        lastUtteranceMs = null
    }

    companion object {
        /** A live fault must hold this long before it is shown or said. */
        const val ISSUE_ONSET_MS = 400L

        /** How long a cue stays on screen after it was last refreshed. */
        const val DISPLAY_HOLD_MS = 1_500L

        /** The same spoken advice is not repeated within this window. */
        const val REPEAT_COOLDOWN_MS = 5_000L

        /** Grace period before telling an athlete to get into position. */
        const val START_PROMPT_DELAY_MS = 3_000L
        const val START_PROMPT_COOLDOWN_MS = 8_000L

        /** Minimum spacing between spoken cues, so advice never talks over itself. */
        const val MIN_UTTERANCE_GAP_MS = 1_500L
    }
}

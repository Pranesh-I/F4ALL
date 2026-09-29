package com.sai.sports.data

import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ActiveSessions
import com.sai.sports.api.AssessmentSessionInfo
import com.sai.sports.data.local.SessionAttemptRow

/** Where one test in a session stands for this athlete. */
enum class SessionTestState {
    /** May be recorded now. */
    AVAILABLE,
    /** An official asked for it again; may be recorded now. */
    RESUBMIT,
    /** Recorded on this phone and waiting for signal to reach SAI. */
    WAITING_TO_SEND,
    /** SAI has it. */
    SUBMITTED;

    val canRecord: Boolean get() = this == AVAILABLE || this == RESUBMIT
}

data class SessionTestView(val testType: TestType, val state: SessionTestState)

/** Whether an official attempt may be recorded right now, and if not, why. */
enum class SessionGate {
    OPEN,
    /** The session is not in this phone's list — never fetched, or withdrawn. */
    UNKNOWN,
    CLOSED,
    WAITING_TO_SEND,
    SUBMITTED
}

data class SessionView(val session: AssessmentSessionInfo, val tests: List<SessionTestView>)

/**
 * The session list as fetched, plus the two clock readings needed to judge
 * windows later without trusting the phone's calendar.
 */
data class CachedSessions(
    val sessions: ActiveSessions,
    /** Phone wall clock at the fetch. */
    val fetchedAtWallMs: Long,
    /** Phone uptime clock at the fetch. */
    val fetchedAtElapsedMs: Long
)

/**
 * The rules deciding what an athlete may record for a session — pure, so each
 * one is unit-tested.
 *
 * Two sources are combined. The server knows what has reached SAI; the phone
 * knows what it has recorded but not yet sent. Either one alone would let an
 * athlete record a second official attempt: the server list goes stale the
 * moment the phone loses signal, and the phone forgets nothing about tests it
 * sent from another phone.
 */
object SessionAvailability {

    /**
     * The server's "now", carried forward from the fetch.
     *
     * The uptime clock cannot be changed by the athlete, so it is used when it
     * can be; it resets on reboot, in which case the wall clock (offset by the
     * difference seen at the fetch) is the fallback.
     */
    fun serverNow(cached: CachedSessions, wallNowMs: Long, elapsedNowMs: Long): Long {
        val sinceFetch =
            if (elapsedNowMs >= cached.fetchedAtElapsedMs) elapsedNowMs - cached.fetchedAtElapsedMs
            else wallNowMs - cached.fetchedAtWallMs
        return cached.sessions.serverTimeMs + sinceFetch
    }

    fun isOpen(session: AssessmentSessionInfo, serverNowMs: Long): Boolean =
        serverNowMs >= session.startsAtMs && serverNowMs < session.endsAtMs

    /** Sessions still open, each with its tests' states. Tests this app version does not know are left out. */
    fun views(
        cached: CachedSessions,
        local: List<SessionAttemptRow>,
        wallNowMs: Long,
        elapsedNowMs: Long
    ): List<SessionView> {
        val now = serverNow(cached, wallNowMs, elapsedNowMs)
        return cached.sessions.sessions
            .filter { isOpen(it, now) }
            .map { session ->
                SessionView(
                    session = session,
                    tests = session.tests.mapNotNull { test ->
                        val type = TestType.entries.firstOrNull { it.name == test.testType }
                            ?: return@mapNotNull null
                        SessionTestView(type, stateOf(session.id, test.testType, cached, local))
                    }
                )
            }
    }

    fun stateOf(
        sessionId: String,
        testType: String,
        cached: CachedSessions?,
        local: List<SessionAttemptRow>
    ): SessionTestState {

        val mine = local.filter { it.sessionId == sessionId && it.testType == testType }
        val server = cached?.sessions?.sessions
            ?.firstOrNull { it.id == sessionId }
            ?.tests?.firstOrNull { it.testType == testType }

        return when {
            // Recorded here and not yet accepted by SAI.
            mine.any { it.resultId == null } -> SessionTestState.WAITING_TO_SEND
            // An official sent it back: the one delivered earlier no longer holds the slot.
            server?.resultStatus == RESUBMISSION_REQUESTED -> SessionTestState.RESUBMIT
            server?.submitted == true || mine.isNotEmpty() -> SessionTestState.SUBMITTED
            else -> SessionTestState.AVAILABLE
        }
    }

    /**
     * Checked when the capture screen opens for an official attempt — before
     * the athlete does anything — so a second attempt is refused up front
     * rather than recorded and then rejected by SAI.
     */
    fun gate(
        sessionId: String,
        testType: String,
        cached: CachedSessions?,
        local: List<SessionAttemptRow>,
        wallNowMs: Long,
        elapsedNowMs: Long
    ): SessionGate {
        val session = cached?.sessions?.sessions?.firstOrNull { it.id == sessionId }
            ?: return SessionGate.UNKNOWN
        if (session.tests.none { it.testType == testType }) return SessionGate.UNKNOWN
        if (!isOpen(session, serverNow(cached, wallNowMs, elapsedNowMs))) return SessionGate.CLOSED
        return when (stateOf(sessionId, testType, cached, local)) {
            SessionTestState.WAITING_TO_SEND -> SessionGate.WAITING_TO_SEND
            SessionTestState.SUBMITTED -> SessionGate.SUBMITTED
            SessionTestState.AVAILABLE, SessionTestState.RESUBMIT -> SessionGate.OPEN
        }
    }

    private const val RESUBMISSION_REQUESTED = "pending_sync"
}

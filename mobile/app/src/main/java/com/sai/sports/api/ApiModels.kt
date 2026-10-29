package com.sai.sports.api

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(
        val kind: ApiFailure,
        val message: String,
        val retryAfterSeconds: Int? = null
    ) : ApiResult<Nothing>
}

enum class ApiFailure {
    NETWORK,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INVALID,
    RATE_LIMITED,
    SERVER;

    /** Worth trying again later without the athlete changing anything. */
    val isTransient: Boolean
        get() = this == NETWORK || this == RATE_LIMITED || this == SERVER

    companion object {
        fun forStatus(code: Int): ApiFailure = when (code) {
            401 -> UNAUTHORIZED
            403 -> FORBIDDEN
            404 -> NOT_FOUND
            409 -> CONFLICT
            429 -> RATE_LIMITED
            in 500..599 -> SERVER
            else -> INVALID
        }
    }
}

data class OtpRequested(
    /** Only a development server echoes this; production always returns null. */
    val developmentCode: String?
)

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val athleteId: String?,
    val registered: Boolean
)

data class Registration(
    val name: String,
    val dateOfBirthIso: String,
    val gender: String,
    /** State or union territory. */
    val region: String,
    val city: String,
    val place: String?,
    val heightCm: Double?,
    val weightKg: Double?,
    val achievements: String?,
    /** Consent to hold the profile; face-photo consent is asked with the photo. */
    val consent: ConsentGrant
)

/** A consent as the athlete — or, for a minor, their guardian — gave it. */
data class ConsentGrant(
    /** Which wording was shown; see `ConsentText.VERSION`. */
    val version: String,
    /** "self" or "guardian". */
    val givenBy: String,
    val guardianName: String?
)

object ConsentPurpose {
    const val REGISTRATION = "registration"
    const val FACE_VERIFICATION = "face_verification"
}

/** What the photo check before an official test concluded. */
data class IdentityCheckResult(
    val checkId: String,
    /** "match", "no_match", "no_face" or "unavailable". */
    val outcome: String,
    val remainingThisHour: Int
) {
    val matched: Boolean get() = outcome == MATCH

    companion object {
        const val MATCH = "match"
        const val NO_MATCH = "no_match"
        const val NO_FACE = "no_face"
        const val UNAVAILABLE = "unavailable"
    }
}

data class AthleteProfile(
    val athleteId: String,
    val name: String,
    val ageYears: Int,
    val gender: String,
    val region: String,
    val heightCm: Double?,
    val weightKg: Double?,
    val hasReferencePhoto: Boolean,
    /** Hidden from other athletes unless they switched this on. */
    val leaderboardOptIn: Boolean = false,
    val preferredLanguage: String = "en",
    val city: String? = null,
    val place: String? = null,
    val achievements: String? = null,
    /** Purposes with a consent in force; see [ConsentPurpose]. */
    val consents: List<String> = emptyList(),
    /**
     * What still stands between the athlete and an official test, in the
     * order to ask for it: "city", "registration_consent", "face_consent",
     * "photo". Empty when the profile is complete.
     */
    val missing: List<String> = emptyList()
) {
    val complete: Boolean get() = missing.isEmpty()
}

data class Registered(
    val profile: AthleteProfile,
    val tokens: TokenPair
)

data class PersonalBest(
    val testType: String,
    val unit: String,
    val score: Double,
    /** Approved by an official, rather than only measured by the server. */
    val official: Boolean,
    /** The result that set it; null from a server older than Sprint 15. */
    val resultId: String? = null
)

/** One official result in the athlete's history. */
data class HistoryItem(
    val resultId: String,
    val testType: String,
    val unit: String,
    val status: String,
    val provisionalScore: Double?,
    val serverScore: Double?,
    val finalScore: Double?,
    /** The server's timestamp as sent, which is also the paging cursor. */
    val createdAt: String,
    val createdAtMs: Long,
    /** The assessment session it was submitted to, if any. */
    val sessionId: String? = null,
    val sessionName: String? = null
)

/** A page of official history, newest first. */
data class HistoryPage(
    val items: List<HistoryItem>,
    /** Pass back to fetch the next, older page; null on the last one. */
    val nextBefore: String?
)

data class AthleteSummary(
    val profile: AthleteProfile,
    val personalBests: List<PersonalBest>,
    val history: List<HistoryItem>
)

data class Benchmark(
    val band: String,
    val label: String,
    /** Null below the median, where the norm table cannot support a number. */
    val percentile: Int?,
    val nextTarget: Double?,
    val cohort: String,
    val unit: String,
    val source: String,
    /** True when the norms are placeholders rather than official SAI standards. */
    val provisional: Boolean,
    /** The state whose own norms were used; null for all-India norms. */
    val region: String? = null
)

/** The server's form verdict on the reps it counted. */
data class ServerForm(
    /** 0..100, the share of attempted reps with no fault: the same measure as the phone's FormSummary. */
    val score: Int,
    /** Null for a result checked before the breakdown was recorded. */
    val faults: List<ServerFormFault>?
)

data class ServerFormFault(
    /** A FormIssue name, e.g. "KNEE_PAST_TOES". */
    val code: String,
    val repsAffected: Int
)

data class ReviewNote(
    val action: String,
    val notes: String?
)

data class ServerResult(
    val resultId: String,
    val testType: String,
    val status: String,
    val unit: String,
    val provisionalScore: Double?,
    val serverScore: Double?,
    val finalScore: Double?,
    val benchmark: Benchmark?,
    val benchmarkUnavailable: String?,
    val review: ReviewNote?,
    val createdAtMs: Long? = null,
    val sessionName: String? = null,
    /** Null until SAI has measured the video, and always for a jump. */
    val serverForm: ServerForm? = null,
    /** The analyzer's reason when SAI could not score the video at all. */
    val serverInvalidReason: String? = null,
    /** The athlete's best SAI-trusted score at this test; may be this result. */
    val personalBest: PersonalBest? = null,
    /** The athlete's attempt at this test before this one, whatever became of it. */
    val previousAttempt: HistoryItem? = null
)

data class Badge(
    /** Stable code, translated on the phone — never display text from the server. */
    val code: String,
    val earned: Boolean,
    val progress: Int,
    val target: Int
)

data class BadgeSummary(
    val badges: List<Badge>,
    val currentStreakWeeks: Int,
    val longestStreakWeeks: Int
)

data class LeaderboardEntry(
    val rank: Int,
    /** First name and last initial only. */
    val displayName: String,
    val region: String,
    val score: Double,
    val isYou: Boolean
)

data class YourStanding(
    val rank: Int,
    val score: Double,
    val visibleToOthers: Boolean
)

data class Leaderboard(
    val testType: String,
    val unit: String,
    val region: String?,
    val cohort: String,
    val entries: List<LeaderboardEntry>,
    val you: YourStanding?,
    val totalRanked: Int,
    /** "14-15", or null for all ages. */
    val ageBand: String? = null,
    val sessionName: String? = null,
    /** This page's position within [totalRanked]. */
    val offset: Int = 0
) {
    val hasMore: Boolean get() = offset + entries.size < totalRanked
}

/** Which board to show; every field is a filter the server applies. */
data class LeaderboardQuery(
    val testType: String,
    /** [PLACE_MY_STATE], [PLACE_INDIA], or the name of any state. */
    val place: String = PLACE_MY_STATE,
    /** [AGE_MINE], [AGE_ALL] or a band such as "12-13". */
    val ageBand: String = AGE_MINE,
    val sessionId: String? = null
) {
    companion object {
        const val PLACE_MY_STATE = "my_state"
        const val PLACE_INDIA = "india"
        const val AGE_MINE = "mine"
        const val AGE_ALL = "all"
    }
}

/** What the leaderboard's filters can offer this athlete. */
data class LeaderboardOptions(
    val ageBands: List<String>,
    val yourAgeBand: String?,
    val yourRegion: String,
    /** Sessions the athlete took part in, newest first. */
    val sessions: List<LeaderboardSession>
)

data class LeaderboardSession(val id: String, val name: String)

/**
 * A practice attempt as it travels to and from the athlete's account.
 *
 * Result and analyzer trace only: practice videos and pose sequences never
 * leave the phone that recorded them.
 */
data class PracticeUpload(
    val clientAttemptId: String,
    val testType: String,
    val score: Double,
    val unit: String,
    val status: String,
    val confidence: Double,
    val invalidReason: String?,
    val recordedAtMs: Long,
    val events: List<PracticeEvent>
)

data class PracticeEvent(
    val timestampMs: Long,
    val label: String,
    val detail: String
)

/** What the server says is open to this athlete right now. */
data class ActiveSessions(
    /** The server's clock when it answered, so session windows are judged on its time, not the phone's. */
    val serverTimeMs: Long,
    val sessions: List<AssessmentSessionInfo>
)

/** An official assessment window SAI has opened. */
data class AssessmentSessionInfo(
    val id: String,
    val name: String,
    val description: String?,
    val rules: String?,
    val startsAtMs: Long,
    val endsAtMs: Long,
    val tests: List<SessionTest>
)

data class SessionTest(
    val testType: String,
    val unit: String,
    /** A live official submission exists on the server. */
    val submitted: Boolean,
    /** The latest result's status; "pending_sync" means an official asked for a resubmission. */
    val resultStatus: String?
)

/** One notification from the athlete's inbox (Sprint 17). */
data class AppNotification(
    val id: String,
    /** Stable code; the app phrases it in the athlete's language. */
    val eventType: String,
    /** The server's rendering, used only for an event this app does not know. */
    val title: String,
    val body: String,
    /** result_id / session_id / session_name / test_type. */
    val data: Map<String, String>,
    val createdAtMs: Long,
    val read: Boolean
)

data class NotificationPage(
    val items: List<AppNotification>,
    val unreadCount: Int,
    /** Pass back to fetch the next, older page; null on the last one. */
    val nextBefore: String?
)

/** SAI's answer to a field-test submission, decided as it arrives (Sprint 18). */
data class FieldSubmission(
    val resultId: String,
    /** verified / flagged / rejected. */
    val status: String,
    val serverScore: Double?,
    /** Why SAI could not score the trace, as a stable code. */
    val invalidReason: String?
)

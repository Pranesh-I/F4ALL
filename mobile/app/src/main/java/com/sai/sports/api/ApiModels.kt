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
    val region: String,
    val heightCm: Double?,
    val weightKg: Double?
)

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
    val preferredLanguage: String = "en"
)

data class Registered(
    val profile: AthleteProfile,
    val tokens: TokenPair
)

data class PersonalBest(
    val testType: String,
    val unit: String,
    val score: Double,
    /** Approved by an official, rather than only measured by the server. */
    val official: Boolean
)

data class HistoryItem(
    val resultId: String,
    val testType: String,
    val unit: String,
    val status: String,
    val provisionalScore: Double?,
    val serverScore: Double?,
    val finalScore: Double?,
    val createdAt: String
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
    val provisional: Boolean
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
    val review: ReviewNote?
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
    val totalRanked: Int
)

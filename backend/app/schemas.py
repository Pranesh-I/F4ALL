"""Pydantic request/response models.

Field names match docs/openapi.yaml exactly — the mobile client in Sprint 4 is
already coded against that contract, so a rename here is a wire break there.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Generic
# ---------------------------------------------------------------------------


class MessageResponse(BaseModel):
    message: str


class HealthResponse(BaseModel):
    status: str
    environment: str
    database: str
    storage: str


class ReadinessResponse(BaseModel):
    ready: bool
    database: bool
    broker: bool
    detail: str | None = None


# ---------------------------------------------------------------------------
# Auth (Sprint 7 implements the real flow; these keep the contract honest)
# ---------------------------------------------------------------------------


class RequestOtpRequest(BaseModel):
    phone: str = Field(min_length=6, max_length=20)


class VerifyOtpRequest(BaseModel):
    phone: str = Field(min_length=6, max_length=20)
    otp: str = Field(min_length=4, max_length=8)


class AuthResponse(BaseModel):
    access_token: str
    athlete_id: uuid.UUID


# ---------------------------------------------------------------------------
# Video upload
# ---------------------------------------------------------------------------


class UploadInitRequest(BaseModel):
    test_type: str
    file_size_bytes: int = Field(gt=0)
    checksum_sha256: str = Field(min_length=64, max_length=64)
    chunk_size_bytes: int | None = None
    content_type: str = "video/mp4"


class UploadSessionResponse(BaseModel):
    upload_id: str
    chunk_size_bytes: int
    received_chunks: list[int]
    expires_at: datetime | None = None


class UploadChunkResponse(BaseModel):
    received_chunks_count: int
    next_expected_index: int | None = None


class UploadCompleteResponse(BaseModel):
    video_id: uuid.UUID
    checksum_verified: bool


# ---------------------------------------------------------------------------
# Test submission
# ---------------------------------------------------------------------------


class SubmitTestRequest(BaseModel):
    test_id: str
    provisional_score: float
    video_id: uuid.UUID | None = None

    # The assessment session this official attempt was made for, and when the
    # phone recorded it. The recording, not the upload, must fall inside the
    # session: phones record offline and send days later.
    session_id: uuid.UUID | None = None
    recorded_at_ms: int | None = Field(default=None, gt=0)

    # The photo check the athlete took before recording an official test.
    identity_check_id: uuid.UUID | None = None

    # Vertical jump cannot be re-scored server-side without the athlete's
    # standing height. Until Sprint 7 stores it on the profile, the client
    # sends it with the submission; the server prefers the profile value when
    # one exists, because a client-supplied height is a client-supplied input.
    athlete_height_cm: float | None = None


class SubmitTestResponse(BaseModel):
    result_id: uuid.UUID
    status: str


# ---------------------------------------------------------------------------
# Results
# ---------------------------------------------------------------------------


class FlagResponse(BaseModel):
    reason: str
    detail: str | None = None
    severity: str
    source: str
    created_at: datetime
    resolution: str | None = None
    resolved_at: datetime | None = None


class LatestReview(BaseModel):
    """The most recent official decision, as the athlete is allowed to see it."""

    action: str
    notes: str | None = None
    created_at: datetime


class TestResultResponse(BaseModel):
    result_id: uuid.UUID
    test_type: str
    status: str
    provisional_score: float | None = None
    server_score: float | None = None
    final_score: float | None = None
    unit: str
    verified_at: datetime | None = None
    flags: list[FlagResponse] = Field(default_factory=list)
    latest_review: LatestReview | None = None


# ---------------------------------------------------------------------------
# Dashboard (Sprint 8)
# ---------------------------------------------------------------------------


class OfficialLoginRequest(BaseModel):
    email: str = Field(min_length=3, max_length=150)
    password: str = Field(min_length=1, max_length=256)


class OfficialProfileResponse(BaseModel):
    official_id: uuid.UUID
    name: str
    email: str
    role: str
    region: str | None


class OfficialTokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    official: OfficialProfileResponse


class ReviewItemResponse(BaseModel):
    result_id: uuid.UUID
    athlete_name: str
    region: str
    test_type: str
    status: str
    provisional_score: float | None
    server_score: float | None
    flag_count: int
    created_at: datetime

    # Highest unresolved flag severity, so the queue can be triaged at a glance.
    max_severity: str | None = None
    attempt_number: int = 1
    unit: str = ""


class ReviewQueuePage(BaseModel):
    items: list[ReviewItemResponse]
    total: int
    limit: int
    offset: int


class FaceVerificationResponse(BaseModel):
    status: str
    similarity_score: float | None
    verified_at: datetime | None


class ReviewHistoryItem(BaseModel):
    action: str
    notes: str | None
    official_name: str
    created_at: datetime


class ReviewDetailResponse(BaseModel):
    result_id: uuid.UUID
    athlete_name: str
    region: str
    test_type: str
    status: str
    provisional_score: float | None
    server_score: float | None
    unit: str
    video_url: str | None
    flags: list[FlagResponse]
    created_at: datetime
    verified_at: datetime | None

    final_score: float | None = None
    attempt_number: int = 1
    athlete_id: uuid.UUID | None = None
    athlete_age_years: int | None = None
    athlete_gender: str | None = None
    athlete_height_cm: float | None = None
    video_duration_seconds: float | None = None

    # Short-lived signed URL; null when no photo is on file.
    reference_photo_url: str | None = None
    face_verification: FaceVerificationResponse | None = None
    # The photo check before recording: match | no_match | no_face |
    # unavailable, or None when none was taken.
    identity_check: str | None = None
    has_pose_sequence: bool = False
    benchmark: BenchmarkComparisonResponse | None = None
    review_history: list[ReviewHistoryItem] = Field(default_factory=list)

    # Which actions this official may take on this result right now.
    allowed_actions: list[str] = Field(default_factory=list)


class ReviewActionRequest(BaseModel):
    action: str
    notes: str | None = Field(default=None, max_length=2000)

    # Only for approving a result the server could not score. The reviewer has
    # watched the video and states the number; it is recorded in the audit
    # trail as theirs, never passed off as a measurement.
    final_score: float | None = Field(default=None, ge=0, le=10000)


class ReviewActionResponse(BaseModel):
    result_id: uuid.UUID
    action: str
    status: str
    message: str


class LeaderboardEntry(BaseModel):
    rank: int
    athlete_id: uuid.UUID
    athlete_name: str
    region: str
    gender: str
    age_years: int
    score: float
    unit: str
    achieved_at: datetime


class LeaderboardResponse(BaseModel):
    test_type: str
    unit: str
    higher_is_better: bool
    entries: list[LeaderboardEntry]


class DashboardStats(BaseModel):
    by_status: dict[str, int]
    flagged_high_severity: int
    breaching_sla: int


# ---------------------------------------------------------------------------
# Auth (Sprint 7)
# ---------------------------------------------------------------------------


class RequestOtpResponse(BaseModel):
    message: str
    expires_at: datetime

    # Echoed only on a development server so the flow can be walked without an
    # SMS gateway. Always null in production.
    development_code: str | None = None


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int

    # Null when the phone verified successfully but no profile exists yet. The
    # client uses this to decide between the home screen and registration, and
    # the token is already valid so registration itself is authenticated.
    athlete_id: uuid.UUID | None = None
    registered: bool = True


class RefreshRequest(BaseModel):
    refresh_token: str


class LogoutRequest(BaseModel):
    refresh_token: str | None = None
    all_devices: bool = False


# ---------------------------------------------------------------------------
# Athlete profile (Sprint 7)
# ---------------------------------------------------------------------------


class ConsentGrant(BaseModel):
    # The version of the consent wording the app showed.
    version: str = Field(min_length=1, max_length=20)
    # "self", or "guardian" — required for athletes under 18.
    given_by: str
    guardian_name: str | None = Field(default=None, max_length=150)


class ConsentRequest(ConsentGrant):
    purpose: str


class AthleteRegistrationRequest(BaseModel):
    name: str = Field(min_length=1, max_length=150)
    dob: date
    gender: str
    # State or union territory.
    region: str = Field(min_length=1, max_length=100)
    city: str = Field(min_length=1, max_length=100)
    place: str | None = Field(default=None, max_length=100)
    height_cm: float | None = Field(default=None, gt=50, lt=260)
    weight_kg: float | None = Field(default=None, gt=10, lt=250)
    achievements: str | None = Field(default=None, max_length=1000)
    # Consent to hold the profile. Face-photo consent is separate and given
    # when the photo is taken.
    consent: ConsentGrant


class RegistrationResponse(BaseModel):
    profile: AthleteProfileResponse
    # A fresh athlete session. The registering token that authorised this call
    # names a phone, not an athlete, and is revoked by registration.
    tokens: TokenResponse


class AthleteProfileUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=150)
    region: str | None = Field(default=None, min_length=1, max_length=100)
    city: str | None = Field(default=None, min_length=1, max_length=100)
    # Empty string clears the optional ones.
    place: str | None = Field(default=None, max_length=100)
    achievements: str | None = Field(default=None, max_length=1000)
    height_cm: float | None = Field(default=None, gt=50, lt=260)
    weight_kg: float | None = Field(default=None, gt=10, lt=250)
    leaderboard_opt_in: bool | None = None
    preferred_language: str | None = Field(default=None, max_length=8)


class AthleteProfileResponse(BaseModel):
    athlete_id: uuid.UUID
    name: str
    dob: date
    age_years: int
    gender: str
    region: str
    phone: str
    height_cm: float | None
    weight_kg: float | None
    has_reference_photo: bool
    leaderboard_opt_in: bool = False
    preferred_language: str = "en"
    city: str | None = None
    place: str | None = None
    achievements: str | None = None
    # Purposes with a consent in force: "registration", "face_verification".
    consents: list[str] = []
    # What is still needed before official tests: any of "city",
    # "registration_consent", "face_consent", "photo". Empty when complete.
    missing: list[str] = []


class IdentityCheckResponse(BaseModel):
    check_id: uuid.UUID
    # match | no_match | no_face | unavailable
    outcome: str
    # Checks left this hour, so the app can say how many retakes remain.
    remaining_this_hour: int


class PersonalBest(BaseModel):
    test_type: str
    unit: str
    score: float
    achieved_at: datetime

    # Whether this best comes from an official's approval or is still only the
    # server's measurement. The two must never look the same to an athlete.
    official: bool


class BenchmarkComparisonResponse(BaseModel):
    band: str
    label: str
    percentile: int | None = None
    percentile_50: float
    percentile_75: float
    percentile_90: float
    next_target: float | None = None
    cohort: str
    unit: str

    # Where the norms came from, and whether they are a placeholder. Carried
    # all the way to the athlete: a percentile against invented numbers is
    # misinformation, and the caveat is what stops it being presented as fact.
    source: str
    provisional: bool


class BenchmarkUnavailable(BaseModel):
    reason: str


class ResultWithBenchmarkResponse(TestResultResponse):
    benchmark: BenchmarkComparisonResponse | None = None
    benchmark_unavailable: str | None = None


class TestHistoryItem(BaseModel):
    result_id: uuid.UUID
    test_type: str
    unit: str
    status: str
    provisional_score: float | None
    server_score: float | None
    final_score: float | None
    created_at: datetime
    # The assessment session an official attempt belonged to.
    session_id: uuid.UUID | None = None


class VerificationStatusResponse(BaseModel):
    result_id: uuid.UUID
    test_type: str
    unit: str
    status: str
    provisional_score: float | None = None
    server_score: float | None = None
    final_score: float | None = None
    submitted_at: datetime
    verified_at: datetime | None = None
    # While still in `processing`: how long it has waited, against the target.
    waiting_seconds: int | None = None
    sla_seconds: int
    overdue: bool = False

    # Officials only; null for the athlete. The flags describe the cheat
    # checks, which are not explained to the person they are checking.
    verification_error: str | None = None
    flags: list[FlagResponse] | None = None
    identity_check: str | None = None
    face_check: str | None = None


class TestHistoryPage(BaseModel):
    items: list[TestHistoryItem] = Field(default_factory=list)
    # Pass as `before` to fetch the next, older page; null on the last page.
    next_before: datetime | None = None


class AthleteSummaryResponse(BaseModel):
    profile: AthleteProfileResponse
    personal_bests: list[PersonalBest] = Field(default_factory=list)
    history: list[TestHistoryItem] = Field(default_factory=list)
    total_tests: int = 0


# ---------------------------------------------------------------------------
# Sprint 9: badges and athlete leaderboards
# ---------------------------------------------------------------------------


class BadgeResponse(BaseModel):
    # Stable code; the app translates it. Titles are not sent from the server
    # because the athlete may be reading in Hindi, Tamil or Bengali.
    code: str
    earned: bool
    earned_at: datetime | None = None
    progress: int = 0
    target: int = 1


class BadgesResponse(BaseModel):
    badges: list[BadgeResponse]
    current_streak_weeks: int
    longest_streak_weeks: int


class AthleteLeaderboardEntry(BaseModel):
    rank: int
    # First name and last initial only. Most athletes are minors; a full name,
    # age and region together identify a child.
    display_name: str
    region: str
    score: float
    is_you: bool = False


class YourStanding(BaseModel):
    rank: int
    score: float
    # Whether other athletes can see this entry. The athlete always sees their
    # own position; opting in only controls whether anyone else does.
    visible_to_others: bool


class AthleteLeaderboardResponse(BaseModel):
    test_type: str
    unit: str
    scope: str
    region: str | None
    cohort: str
    entries: list[AthleteLeaderboardEntry]
    you: YourStanding | None = None
    total_ranked: int


ReviewDetailResponse.model_rebuild()


# ---------------------------------------------------------------------------
# Practice (private to the athlete; never an official result)
# ---------------------------------------------------------------------------

# A generous ceiling on one attempt's trace. A long set produces a few hundred
# events; this stops one request from storing megabytes.
MAX_PRACTICE_EVENTS = 2000


class PracticeEvent(BaseModel):
    timestamp_ms: int = Field(ge=0)
    label: str = Field(min_length=1, max_length=64)
    detail: str = Field(default="", max_length=200)


class PracticeAttemptIn(BaseModel):
    test_type: str = Field(min_length=1, max_length=50)
    score: float = Field(ge=0, le=10_000, allow_inf_nan=False)
    unit: str = Field(min_length=1, max_length=20)
    status: str = Field(pattern="^(COMPLETE|INVALID)$")
    confidence: float = Field(default=0, ge=0, le=1, allow_inf_nan=False)
    invalid_reason: str | None = Field(default=None, max_length=300)
    recorded_at_ms: int = Field(gt=0)
    events: list[PracticeEvent] = Field(
        default_factory=list, max_length=MAX_PRACTICE_EVENTS
    )


class PracticeAttemptOut(PracticeAttemptIn):
    client_attempt_id: str


class PracticeHistoryResponse(BaseModel):
    attempts: list[PracticeAttemptOut] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Assessment sessions
# ---------------------------------------------------------------------------


class SessionTestStatus(BaseModel):
    test_type: str
    unit: str
    # A live official submission already exists; the athlete may not submit again.
    submitted: bool
    # Status of the athlete's latest result for this test in this session, if
    # any. "pending_sync" means an official asked for a resubmission.
    result_status: str | None = None


class ActiveSessionResponse(BaseModel):
    session_id: uuid.UUID
    name: str
    description: str | None
    rules: str | None
    starts_at: datetime
    ends_at: datetime
    tests: list[SessionTestStatus]


class ActiveSessionsResponse(BaseModel):
    # The phone compares session windows against this, not its own clock.
    server_time: datetime
    sessions: list[ActiveSessionResponse] = Field(default_factory=list)


class AssessmentSessionCreate(BaseModel):
    name: str = Field(min_length=1, max_length=150)
    description: str | None = Field(default=None, max_length=2000)
    rules: str | None = Field(default=None, max_length=4000)
    starts_at: datetime
    ends_at: datetime
    # Created switched off unless asked: a session appears to athletes only
    # when someone deliberately opens it.
    enabled: bool = False
    allowed_tests: list[str] = Field(min_length=1, max_length=20)
    region: str | None = Field(default=None, max_length=100)


class AssessmentSessionUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=150)
    description: str | None = Field(default=None, max_length=2000)
    rules: str | None = Field(default=None, max_length=4000)
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    enabled: bool | None = None
    allowed_tests: list[str] | None = Field(default=None, min_length=1, max_length=20)
    region: str | None = Field(default=None, max_length=100)
    # Explicit, because "region": null in a patch is otherwise ambiguous with
    # "not provided".
    clear_region: bool = False


class AssessmentSessionResponse(BaseModel):
    id: uuid.UUID
    name: str
    description: str | None
    rules: str | None
    starts_at: datetime
    ends_at: datetime
    enabled: bool
    allowed_tests: list[str]
    region: str | None
    status: str
    submission_count: int
    created_at: datetime
    updated_at: datetime

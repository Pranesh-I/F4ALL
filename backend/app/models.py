"""SQLAlchemy models implementing docs/db-schema-v1.md.

Portability note: columns use ``sqlalchemy.Uuid`` and ``sqlalchemy.Enum`` rather
than the postgresql dialect types. Postgres is the production database, but the
test suite runs against SQLite in-memory, and a schema that can only be created
on Postgres means the tests need a running container to say anything at all.
"""

from __future__ import annotations

import enum
import uuid
from datetime import UTC, datetime

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy import (
    Enum as SAEnum,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.sql import text


def _utcnow() -> datetime:
    return datetime.now(UTC)


def _new_uuid() -> uuid.UUID:
    return uuid.uuid4()



def enum_column(enum_class, length: int = 40):
    """Non-native enum column that persists the member VALUE.

    ``native_enum=False`` keeps this portable: Postgres would otherwise create a
    real ENUM type that SQLite cannot reproduce, and the test suite runs on
    SQLite. ``values_callable`` persists ``.value`` rather than ``.name``, which
    is what the CHECK constraints in docs/db-schema-v1.md are written against —
    and what ``FaceVerificationStatus.passed = "pass"`` depends on, since its
    name and value deliberately differ ("pass" is a Python keyword).
    """
    return SAEnum(
        enum_class,
        native_enum=False,
        length=length,
        values_callable=lambda enum: [member.value for member in enum],
    )


class Base(DeclarativeBase):
    pass


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow, nullable=False
    )


# ---------------------------------------------------------------------------
# Enumerations
# ---------------------------------------------------------------------------


class Gender(str, enum.Enum):
    male = "male"
    female = "female"
    other = "other"


class TestResultStatus(str, enum.Enum):
    """Lifecycle of a submitted test.

    ``verified`` means the server re-scored it and agreed with the device.
    ``approved`` means a human official signed it off. They are deliberately
    distinct: the system's credibility rests on a machine result never being
    presented as an official one.

    The machine half of the lifecycle (Sprint 11):

        uploaded -> processing -> verified | flagged | rejected

    ``uploaded`` is a stored video waiting for a worker; ``processing`` means a
    worker has claimed it. Before the submission exists, the upload itself is
    tracked on ``upload_sessions`` (created, then chunks arriving, then
    completed). ``rejected`` is set by the machine only for a submission that
    cannot be verified by anyone — no video, a file that is not a video — and
    by an official after review. Anything about the athlete's performance is
    ``flagged`` for a human, never rejected by the machine.
    """

    pending_sync = "pending_sync"
    uploaded = "uploaded"
    processing = "processing"
    verified = "verified"
    flagged = "flagged"
    approved = "approved"
    rejected = "rejected"


# Submitted but without a machine verdict yet: queued, or claimed by a worker.
# What the SLA is measured against and what re-queueing picks up.
AWAITING_VERIFICATION = frozenset(
    {TestResultStatus.uploaded, TestResultStatus.processing}
)


class FlagSource(str, enum.Enum):
    auto = "auto"
    manual = "manual"


class FlagSeverity(str, enum.Enum):
    low = "low"
    medium = "medium"
    high = "high"


class FlagResolution(str, enum.Enum):
    confirmed = "confirmed"
    dismissed = "dismissed"


class FaceVerificationStatus(str, enum.Enum):
    passed = "pass"
    failed = "fail"
    manual_review = "manual_review"


class OfficialRole(str, enum.Enum):
    sai_admin = "sai_admin"
    regional_reviewer = "regional_reviewer"


class ReviewAction(str, enum.Enum):
    approved = "approved"
    rejected = "rejected"
    requested_resubmission = "requested_resubmission"
    # Sprint 14: a reviewer raises a concern without deciding. The result goes
    # (or stays) `flagged` with a manual flag, and remains open for a decision.
    flagged = "flagged"


# Actions that settle a result. `flagged` does not: it asks for another look.
FINAL_REVIEW_ACTIONS = frozenset(
    {ReviewAction.approved, ReviewAction.rejected, ReviewAction.requested_resubmission}
)


class ReviewReason(str, enum.Enum):
    """Why a reviewer rejected, asked for a resubmission, or flagged (Sprint 14).

    Stored on the audit row beside the free-text notes, so decisions can be
    counted and audited by cause rather than read one note at a time.
    """

    identity_mismatch = "identity_mismatch"
    multiple_people = "multiple_people"
    invalid_video = "invalid_video"
    score_discrepancy = "score_discrepancy"
    technical_issue = "technical_issue"
    form_issue = "form_issue"
    duplicate_submission = "duplicate_submission"
    other = "other"


class ConsentPurpose(str, enum.Enum):
    # Holding the profile and test results at all.
    registration = "registration"
    # Keeping a face photo and comparing faces against it. Separate, because it
    # is biometric processing an athlete may refuse while still practising.
    face_verification = "face_verification"


class ConsentGiver(str, enum.Enum):
    self = "self"
    # A parent or guardian, required for anyone under 18.
    guardian = "guardian"


class IdentityCheckOutcome(str, enum.Enum):
    match = "match"
    no_match = "no_match"
    # No face in the photo the athlete just took — retake, not a mismatch.
    no_face = "no_face"
    # The comparison could not run (no models, unreadable registration photo).
    unavailable = "unavailable"


# ---------------------------------------------------------------------------
# Tables
# ---------------------------------------------------------------------------


class Athlete(TimestampMixin, Base):
    __tablename__ = "athletes"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    name: Mapped[str] = mapped_column(String(150), nullable=False)
    dob: Mapped[datetime] = mapped_column(Date, nullable=False)
    gender: Mapped[Gender] = mapped_column(enum_column(Gender, 20), nullable=False)
    region: Mapped[str] = mapped_column(String(100), nullable=False)
    height_cm: Mapped[float | None] = mapped_column(Numeric(5, 2), nullable=True)
    weight_kg: Mapped[float | None] = mapped_column(Numeric(5, 2), nullable=True)
    phone: Mapped[str] = mapped_column(String(20), nullable=False, unique=True)

    # Where the athlete lives, below the state level `region` records. Null for
    # athletes registered before Sprint 8 until they complete their profile.
    city: Mapped[str | None] = mapped_column(String(100), nullable=True)
    place: Mapped[str | None] = mapped_column(String(100), nullable=True)

    # Free text in the athlete's own words: district meets, school teams.
    achievements: Mapped[str | None] = mapped_column(Text, nullable=True)

    # Captured once at registration; Sprint 6 compares test-video faces to it.
    # Encrypted by the application before storage (keys ending ".enc"); see
    # services/identity_crypto.py.
    reference_face_key: Mapped[str | None] = mapped_column(String(500), nullable=True)

    # Off unless the athlete turns it on. Most athletes are minors, and a public
    # ranking with their name on it is not something to opt them into by default.
    # Officials see every approved result regardless; this governs only what
    # other athletes can see.
    leaderboard_opt_in: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False
    )

    # BCP-47 language the athlete chose in the app. Kept server-side so anything
    # sent to them later (SMS, notifications) can use the same language.
    preferred_language: Mapped[str] = mapped_column(
        String(8), nullable=False, default="en"
    )

    results: Mapped[list[TestResult]] = relationship(back_populates="athlete")

    __table_args__ = (
        CheckConstraint(
            "gender IN ('male','female','other')", name="ck_athletes_gender"
        ),
    )


class Test(Base):
    __tablename__ = "tests"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    name: Mapped[str] = mapped_column(String(100), nullable=False, unique=True)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    unit: Mapped[str] = mapped_column(String(30), nullable=False)

    # Stable machine key the mobile app sends (SIT_UPS / VERTICAL_JUMP).
    # `name` is a human label and will be translated in Sprint 9; keying the
    # verification pipeline off a translatable string would be a bug waiting.
    code: Mapped[str] = mapped_column(String(40), nullable=False, unique=True)

    # False for timed tests, where a lower number is the better performance.
    # It lives on the test rather than being passed in by each caller because
    # every caller getting it right is not a property anything enforces — and
    # getting it wrong tells the fastest athletes they are the slowest.
    higher_is_better: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=True
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )


class TestResult(TimestampMixin, Base):
    __tablename__ = "test_results"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    athlete_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("athletes.id"), nullable=False
    )
    test_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("tests.id"), nullable=False
    )
    attempt_number: Mapped[int] = mapped_column(Integer, nullable=False, default=1)

    # The assessment session this official attempt was made for. Null for
    # submissions from before sessions existed.
    session_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("assessment_sessions.id"), nullable=True
    )

    # The pre-test identity check the athlete took before recording. Several
    # tests recorded in one sitting share one check.
    identity_check_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("identity_checks.id"), nullable=True
    )

    # The three scores are kept apart on purpose. Collapsing them would destroy
    # the audit trail that lets a reviewer see the device and the server
    # disagreed, which is the entire basis for trusting the final number.
    provisional_score: Mapped[float | None] = mapped_column(
        Numeric(10, 2), nullable=True
    )
    server_score: Mapped[float | None] = mapped_column(Numeric(10, 2), nullable=True)
    final_score: Mapped[float | None] = mapped_column(Numeric(10, 2), nullable=True)

    status: Mapped[TestResultStatus] = mapped_column(
        enum_column(TestResultStatus, 30),
        nullable=False,
        default=TestResultStatus.pending_sync,
    )

    # Set when the verification job finishes, for SLA reporting. This is the
    # processing-completed time; the API also exposes it under that name.
    verified_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    verification_error: Mapped[str | None] = mapped_column(Text, nullable=True)

    # --- Sprint 11: enough to reproduce the machine verdict later ----------
    # When a worker claimed the job, and how long the verification itself took
    # (queue wait is created_at -> processing_started_at).
    processing_started_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    processing_duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)

    # Machine-readable code for the deciding check (verification/finalization.py).
    verification_reason: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # The machine's own verdict — verified, flagged or rejected — written once
    # by the worker and never by a reviewer (Sprint 14). `status` moves on when
    # an official decides; this keeps what the automated check concluded.
    verification_verdict: Mapped[str | None] = mapped_column(String(20), nullable=True)
    pipeline_version: Mapped[str | None] = mapped_column(String(40), nullable=True)

    # What the phone claimed and what the server measured, as snapshots, plus
    # every check the verdict was built from. Separate from the score columns
    # above, which stay the queryable numbers.
    mobile_result: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    server_result: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    verification_checks: Mapped[list | None] = mapped_column(JSON, nullable=True)

    # Duplicate-processing guard. Every run claims the result under a fresh id,
    # and only the run holding the latest claim may write the verdict, so a
    # redelivered or re-queued job cannot add a second set of flags.
    verification_attempts: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0
    )
    verification_run_id: Mapped[str | None] = mapped_column(String(64), nullable=True)

    athlete: Mapped[Athlete] = relationship(back_populates="results")
    test: Mapped[Test] = relationship()
    videos: Mapped[list[Video]] = relationship(back_populates="test_result")
    flags: Mapped[list[Flag]] = relationship(back_populates="test_result")

    __table_args__ = (
        UniqueConstraint(
            "athlete_id", "test_id", "attempt_number", name="uq_result_attempt"
        ),
        Index("ix_test_results_status", "status"),
        # One live official submission per athlete, session and test — enforced
        # by the database, so two submissions racing in cannot both land. A
        # result sent back for resubmission (pending_sync) no longer holds the
        # slot, which is what lets the athlete submit again.
        Index(
            "uq_session_submission",
            "athlete_id",
            "session_id",
            "test_id",
            unique=True,
            postgresql_where=text(
                "session_id IS NOT NULL AND status <> 'pending_sync'"
            ),
            sqlite_where=text("session_id IS NOT NULL AND status <> 'pending_sync'"),
        ),
        CheckConstraint(
            "status IN ('pending_sync','uploaded','processing','verified','flagged',"
            "'approved','rejected')",
            name="ck_test_results_status",
        ),
    )


class Video(Base):
    __tablename__ = "videos"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    test_result_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("test_results.id"), nullable=True
    )
    s3_key: Mapped[str] = mapped_column(String(500), nullable=False)

    # Computed on the device over the exact uploaded bytes and re-computed here
    # after reassembly. A mismatch means the video the server holds is not the
    # one that was recorded.
    checksum_sha256: Mapped[str] = mapped_column(String(64), nullable=False)

    file_size_bytes: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    duration_seconds: Mapped[float | None] = mapped_column(
        Numeric(10, 2), nullable=True
    )
    sensor_telemetry_key: Mapped[str | None] = mapped_column(String(500), nullable=True)

    # The landmarks the SERVER extracted, written during verification. This is
    # what the dashboard draws over the video: a reviewer needs to see what the
    # measurement saw, and the device's landmarks are the ones not to trust.
    pose_sequence_key: Mapped[str | None] = mapped_column(String(500), nullable=True)

    # 16 sampled 16x16 greyscale frames, written during verification, so a
    # later submission of the same footage can be recognised
    # (verification/cheat/duplicates.py). Too coarse to show a face.
    fingerprint: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    test_result: Mapped[TestResult | None] = relationship(back_populates="videos")

    __table_args__ = (
        Index("ix_videos_test_result", "test_result_id"),
        # Duplicate-submission lookups search every video by its bytes' hash.
        Index("ix_videos_checksum", "checksum_sha256"),
    )


class Flag(Base):
    __tablename__ = "flags"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    test_result_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("test_results.id"), nullable=False
    )
    source: Mapped[FlagSource] = mapped_column(
        enum_column(FlagSource, 20), nullable=False
    )

    # Stores WHY, not just that. A dashboard reviewer given "flagged" with no
    # reason cannot do anything useful with it. `reason` is the flag type (a
    # stable code); `detail` is the reviewer-facing explanation.
    reason: Mapped[str] = mapped_column(String(255), nullable=False)
    detail: Mapped[str | None] = mapped_column(Text, nullable=True)

    # The measurements behind the flag and the limit they crossed (Sprint 12):
    # where in the video, how much, against what. Null for flags raised before
    # it existed, and for manual flags. Officials only — it names thresholds.
    evidence: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    severity: Mapped[FlagSeverity] = mapped_column(
        enum_column(FlagSeverity, 20), nullable=False
    )
    resolved_by: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("officials.id"), nullable=True
    )
    resolution: Mapped[FlagResolution | None] = mapped_column(
        enum_column(FlagResolution, 30), nullable=True
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )
    resolved_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    test_result: Mapped[TestResult] = relationship(back_populates="flags")

    __table_args__ = (Index("ix_flags_test_result", "test_result_id"),)


class FaceVerification(Base):
    __tablename__ = "face_verifications"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    test_result_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("test_results.id"), nullable=False
    )
    verification_status: Mapped[FaceVerificationStatus] = mapped_column(
        enum_column(FaceVerificationStatus, 20), nullable=False
    )
    similarity_score: Mapped[float | None] = mapped_column(Numeric(5, 4), nullable=True)
    verified_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )


class Official(Base):
    __tablename__ = "officials"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    name: Mapped[str] = mapped_column(String(150), nullable=False)
    email: Mapped[str] = mapped_column(String(150), nullable=False, unique=True)
    role: Mapped[OfficialRole] = mapped_column(
        enum_column(OfficialRole, 30), nullable=False
    )

    # Null for sai_admin, who sees every region.
    region: Mapped[str | None] = mapped_column(String(100), nullable=True)

    # scrypt, via the standard library. Null means the account cannot sign in
    # until an administrator sets a password — never "any password works".
    password_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)

    # Officials can approve results that decide a child's selection. An account
    # that can be guessed at indefinitely is not acceptable for that, so failed
    # sign-ins lock it for a period.
    failed_login_attempts: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0
    )
    locked_until: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    # Deactivated rather than deleted: review_actions references this row, and
    # the audit trail must still say who approved what after they leave.
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )


class ReviewActionRecord(Base):
    """Append-only audit trail. Rows are never updated or deleted."""

    __tablename__ = "review_actions"
    __table_args__ = (Index("ix_review_actions_test_result", "test_result_id"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    test_result_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("test_results.id"), nullable=False
    )
    official_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("officials.id"), nullable=False
    )
    action: Mapped[ReviewAction] = mapped_column(
        enum_column(ReviewAction, 30), nullable=False
    )
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Sprint 14. A ReviewReason value; null on rows from before it existed and
    # on approvals, which need no reason.
    reason: Mapped[str | None] = mapped_column(String(40), nullable=True)
    # The result's status either side of this action. Null on older rows.
    previous_status: Mapped[str | None] = mapped_column(String(30), nullable=True)
    new_status: Mapped[str | None] = mapped_column(String(30), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )


class Benchmark(Base):
    __tablename__ = "benchmarks"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    test_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("tests.id"), nullable=False
    )
    gender: Mapped[str] = mapped_column(String(20), nullable=False)
    age_min: Mapped[int] = mapped_column(Integer, nullable=False)
    age_max: Mapped[int] = mapped_column(Integer, nullable=False)
    percentile_50: Mapped[float] = mapped_column(Numeric(10, 2), nullable=False)
    percentile_75: Mapped[float] = mapped_column(Numeric(10, 2), nullable=False)
    percentile_90: Mapped[float] = mapped_column(Numeric(10, 2), nullable=False)

    # Where these numbers came from. Not in db-schema-v1, and added because the
    # alternative is a table of authoritative-looking numbers with no way to
    # tell an official SAI norm from a placeholder. This value is surfaced all
    # the way to the athlete, who is being told something about themselves.
    source: Mapped[str] = mapped_column(String(255), nullable=False, default="")

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    __table_args__ = (
        # Without this, two overlapping rows would make the cohort an athlete
        # is measured against depend on row order.
        UniqueConstraint(
            "test_id", "gender", "age_min", "age_max", name="uq_benchmark_cohort"
        ),
        Index("ix_benchmarks_lookup", "test_id", "gender"),
    )


class UploadSession(Base):
    """Server-side state for one resumable upload.

    Not in db-schema-v1: the schema predates the chunked upload protocol, which
    Sprint 4 had to define because the original API contract referenced a
    ``video_id`` with no endpoint that produced one.

    ``received_chunks`` is the authoritative record of what actually landed. The
    mobile client asks this table what to re-send rather than trusting its own
    memory of what it sent, which is what makes a killed upload resumable
    instead of restartable.
    """

    __tablename__ = "upload_sessions"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    athlete_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("athletes.id"), nullable=True
    )
    test_code: Mapped[str] = mapped_column(String(40), nullable=False)
    file_size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    chunk_size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    total_chunks: Mapped[int] = mapped_column(Integer, nullable=False)
    checksum_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    content_type: Mapped[str] = mapped_column(String(100), nullable=False)

    # Comma-separated chunk indices. A JSON/array column would be tidier but
    # would not work identically on SQLite and Postgres, and this list is short.
    received_chunks: Mapped[str] = mapped_column(Text, nullable=False, default="")

    staging_path: Mapped[str] = mapped_column(String(500), nullable=False)
    completed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    video_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("videos.id"), nullable=True
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )

    def received_set(self) -> set[int]:
        if not self.received_chunks:
            return set()
        return {int(part) for part in self.received_chunks.split(",") if part}

    def set_received(self, indices: set[int]) -> None:
        self.received_chunks = ",".join(str(index) for index in sorted(indices))

    def is_complete(self) -> bool:
        return len(self.received_set()) == self.total_chunks


class OtpChallenge(Base):
    """One outstanding OTP for one phone number.

    The code is stored as an HMAC, never in plaintext. A six-digit code is
    trivially brute-forced offline whatever the hash, so the hash is not what
    protects it — `attempts` and `expires_at` are. What the HMAC does buy is
    that a leaked database dump does not hand the reader a set of live,
    ready-to-use login codes for real phone numbers.

    Rows are kept after use rather than deleted: `consumed_at` and `attempts`
    are the only evidence available if someone later asks whether an account was
    brute-forced.
    """

    __tablename__ = "otp_challenges"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    phone: Mapped[str] = mapped_column(String(20), nullable=False)
    code_hash: Mapped[str] = mapped_column(String(64), nullable=False)

    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    consumed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    __table_args__ = (
        # Every lookup is "the live challenges for this phone, newest first".
        Index("ix_otp_challenges_phone_created", "phone", "created_at"),
    )


class RefreshToken(Base):
    """A long-lived token that can mint new access tokens.

    Stored as a SHA-256 of the token, so the table cannot be used to
    impersonate anyone. Unlike the OTP this is a 256-bit random value, so a
    plain digest is enough — there is nothing to brute-force.

    Refresh tokens exist because the alternative on this product is worse. An
    athlete in a village with intermittent connectivity cannot be asked to
    re-do an SMS round trip every week, and the answer to that must not be an
    access token with a one-year expiry that can never be withdrawn.
    """

    __tablename__ = "refresh_tokens"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    token_hash: Mapped[str] = mapped_column(
        String(64), nullable=False, unique=True, index=True
    )
    subject_id: Mapped[uuid.UUID] = mapped_column(Uuid, nullable=False)

    # "athlete", "registering", or an OfficialRole value. Not a foreign key,
    # because the subject may be in either of two tables — or, while
    # registering, in neither.
    subject_role: Mapped[str] = mapped_column(String(30), nullable=False)

    # Only set for the "registering" role, where the subject is a phone number
    # that has verified an OTP but has no profile yet. Stored so a rotation
    # can reproduce the claim from the server's own record rather than copying
    # it out of the token being presented.
    subject_phone: Mapped[str | None] = mapped_column(String(20), nullable=True)

    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    revoked_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    # Set when this token is exchanged, pointing at what replaced it. Rotation
    # means a stolen refresh token stops working the moment the real device
    # uses its own, and reuse of a rotated token is detectable.
    replaced_by: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )
    last_used_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    __table_args__ = (Index("ix_refresh_tokens_subject", "subject_id"),)


class PracticeAttempt(TimestampMixin, Base):
    """One practice attempt, kept so an athlete's practice follows their account.

    Deliberately a separate table from ``test_results``. Practice is the
    athlete's own: nothing official reads this table — no verification, no
    review queue, no benchmark, leaderboard or badge — and only the athlete who
    recorded an attempt can ever read it back. Keeping it apart makes that a
    property of the schema rather than a filter someone must remember to add.

    No video is stored; practice videos never leave the phone. ``events`` is
    the on-device analyzer's trace (rep outcomes and form faults), which is
    what the app rebuilds history, personal bests and form feedback from.
    """

    __tablename__ = "practice_attempts"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    athlete_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("athletes.id", ondelete="CASCADE"), nullable=False
    )

    # The phone's own id for the attempt. Unique per athlete, which is what makes
    # an upload retried after a lost response an update rather than a duplicate.
    client_attempt_id: Mapped[str] = mapped_column(String(64), nullable=False)

    test_code: Mapped[str] = mapped_column(String(50), nullable=False)
    score: Mapped[float] = mapped_column(Numeric(10, 2), nullable=False)
    unit: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    confidence: Mapped[float] = mapped_column(Numeric(4, 3), nullable=False, default=0)
    invalid_reason: Mapped[str | None] = mapped_column(String(300), nullable=True)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    events: Mapped[list] = mapped_column(JSON, nullable=False, default=list)

    __table_args__ = (
        UniqueConstraint(
            "athlete_id", "client_attempt_id", name="uq_practice_attempts_client_id"
        ),
        Index("ix_practice_attempts_athlete_recorded", "athlete_id", "recorded_at"),
        CheckConstraint(
            "status IN ('COMPLETE','INVALID')", name="ck_practice_attempts_status"
        ),
    )


class AssessmentSession(TimestampMixin, Base):
    """An official assessment window, created by SAI.

    Athletes see a session only while it is enabled and open, and — when
    ``region`` is set — only if they are registered in that region. Within it
    they may make one official submission per test in ``allowed_tests``.

    Whether a session is scheduled, active or ended is computed from the clock
    rather than stored, so it can never be left stale by a missed job.
    """

    __tablename__ = "assessment_sessions"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    name: Mapped[str] = mapped_column(String(150), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)

    # Shown to the athlete before they start: anything particular to this
    # session (where to film, what to wear, who to contact).
    rules: Mapped[str | None] = mapped_column(Text, nullable=True)

    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    # Test codes (SQUATS, VERTICAL_JUMP, ...), in the order they are shown.
    allowed_tests: Mapped[list] = mapped_column(JSON, nullable=False, default=list)

    # Null: open to every region.
    region: Mapped[str | None] = mapped_column(String(100), nullable=True)

    created_by: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("officials.id"), nullable=True
    )
    updated_by: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("officials.id"), nullable=True
    )

    __table_args__ = (
        CheckConstraint("ends_at > starts_at", name="ck_assessment_sessions_window"),
        Index("ix_assessment_sessions_window", "enabled", "starts_at", "ends_at"),
    )


class AthleteConsent(Base):
    """A consent an athlete — or their guardian — gave, and when it ended.

    Rows are never updated except to record withdrawal, so the history of what
    was agreed to, under which version of the wording, survives.
    """

    __tablename__ = "athlete_consents"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    athlete_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("athletes.id"), nullable=False
    )
    purpose: Mapped[ConsentPurpose] = mapped_column(
        enum_column(ConsentPurpose, 40), nullable=False
    )
    # Which wording the athlete was shown. Changing the text means a new
    # version, and consent to an old one says nothing about the new.
    version: Mapped[str] = mapped_column(String(20), nullable=False)
    given_by: Mapped[ConsentGiver] = mapped_column(
        enum_column(ConsentGiver, 20), nullable=False
    )
    guardian_name: Mapped[str | None] = mapped_column(String(150), nullable=True)
    given_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )
    withdrawn_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    __table_args__ = (
        Index("ix_athlete_consents_athlete_purpose", "athlete_id", "purpose"),
        CheckConstraint(
            "given_by <> 'guardian' OR guardian_name IS NOT NULL",
            name="ck_athlete_consents_guardian_named",
        ),
    )


class IdentityCheck(Base):
    """One comparison of a photo taken before a test against the registration photo.

    Only the outcome is kept. The photo itself is compared in memory and
    discarded, so nothing here is biometric data.
    """

    __tablename__ = "identity_checks"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=_new_uuid)
    athlete_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("athletes.id"), nullable=False
    )
    session_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("assessment_sessions.id"), nullable=True
    )
    outcome: Mapped[IdentityCheckOutcome] = mapped_column(
        enum_column(IdentityCheckOutcome, 20), nullable=False
    )
    similarity: Mapped[float | None] = mapped_column(Numeric(5, 4), nullable=True)
    # Why the check could not run, for `unavailable`.
    reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    # When the phone took the photo; a check made offline is sent later.
    captured_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    __table_args__ = (
        Index("ix_identity_checks_athlete_created", "athlete_id", "created_at"),
    )

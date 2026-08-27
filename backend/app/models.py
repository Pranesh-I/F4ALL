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
    """

    pending_sync = "pending_sync"
    processing = "processing"
    verified = "verified"
    flagged = "flagged"
    approved = "approved"
    rejected = "rejected"


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

    # Captured once at registration; Sprint 6 compares test-video faces to it.
    reference_face_key: Mapped[str | None] = mapped_column(String(500), nullable=True)

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

    # Set when the verification job finishes, for SLA reporting.
    verified_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    verification_error: Mapped[str | None] = mapped_column(Text, nullable=True)

    athlete: Mapped[Athlete] = relationship(back_populates="results")
    test: Mapped[Test] = relationship()
    videos: Mapped[list[Video]] = relationship(back_populates="test_result")
    flags: Mapped[list[Flag]] = relationship(back_populates="test_result")

    __table_args__ = (
        UniqueConstraint(
            "athlete_id", "test_id", "attempt_number", name="uq_result_attempt"
        ),
        Index("ix_test_results_status", "status"),
        CheckConstraint(
            "status IN ('pending_sync','processing','verified','flagged',"
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

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )

    test_result: Mapped[TestResult | None] = relationship(back_populates="videos")

    __table_args__ = (Index("ix_videos_test_result", "test_result_id"),)


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
    # reason cannot do anything useful with it.
    reason: Mapped[str] = mapped_column(String(255), nullable=False)
    detail: Mapped[str | None] = mapped_column(Text, nullable=True)

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

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
    )


class ReviewActionRecord(Base):
    """Append-only audit trail. Rows are never updated or deleted."""

    __tablename__ = "review_actions"

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
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, nullable=False
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

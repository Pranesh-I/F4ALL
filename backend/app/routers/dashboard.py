"""Official dashboard endpoints.

Every query here is region-scoped in SQL, not in the UI. A regional reviewer
must never reach another region's athletes by editing a URL, and "not found"
is the answer for anything outside their region — confirming that a result
exists elsewhere is itself a leak.
"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import case, false, func, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    Athlete,
    FaceVerification,
    Flag,
    FlagResolution,
    Official,
    OfficialRole,
    ReviewAction,
    ReviewActionRecord,
    Test,
    TestResult,
    TestResultStatus,
)
from ..schemas import (
    DashboardStats,
    FaceVerificationResponse,
    FlagResponse,
    LeaderboardEntry,
    LeaderboardResponse,
    ReviewActionRequest,
    ReviewActionResponse,
    ReviewDetailResponse,
    ReviewHistoryItem,
    ReviewItemResponse,
)
from ..security import current_official
from ..services import benchmarks as benchmark_service
from ..storage import get_storage
from .tests_submit import _benchmark_for

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/dashboard", tags=["Dashboard"])

# Results an official can still decide. Once approved or rejected, a result is
# settled; changing it again would need its own, more deliberate workflow.
DECIDABLE = {
    TestResultStatus.flagged,
    TestResultStatus.verified,
    TestResultStatus.processing,
}

SEVERITY_RANK = {"high": 3, "medium": 2, "low": 1}


def _is_admin(official: Official | None) -> bool:
    return official is not None and _value(official.role) == OfficialRole.sai_admin.value


def _scope(query, official: Official | None):
    """Restrict a query joined to Athlete to what this official may see.

    Fails CLOSED. A regional reviewer whose region was never set sees nothing
    — the previous version of this filter returned everything in that case,
    which turned a half-provisioned account into a national one.
    """
    if official is None or _is_admin(official):
        return query
    if not official.region:
        return query.where(false())
    return query.where(Athlete.region == official.region)


def _max_severity_expression():
    """Highest unresolved flag severity per result, as a sortable number."""
    rank = case(
        (Flag.severity == "high", 3),
        (Flag.severity == "medium", 2),
        (Flag.severity == "low", 1),
        else_=0,
    )
    return (
        select(func.coalesce(func.max(rank), 0))
        .where(Flag.test_result_id == TestResult.id)
        .where(Flag.resolved_at.is_(None))
        .correlate(TestResult)
        .scalar_subquery()
    )


@router.get("/reviews", response_model=list[ReviewItemResponse])
def review_queue(
    response: Response,
    status_filter: str | None = Query(default=None, alias="status"),
    test_type: str | None = Query(default=None),
    region: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    """The work queue, most urgent first.

    Ordered by highest unresolved flag severity, then oldest first — a
    high-severity flag should not wait behind a week of low ones, and within a
    severity nobody should be starved by newer submissions.

    The total count is in `X-Total-Count` so the body stays the list the
    contract already describes.
    """
    severity = _max_severity_expression().label("max_severity")

    query = (
        select(TestResult, Athlete, Test, severity)
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .join(Test, Test.id == TestResult.test_id)
    )

    if status_filter:
        statuses = [part.strip() for part in status_filter.split(",") if part.strip()]
        valid = {member.value for member in TestResultStatus}
        unknown = [value for value in statuses if value not in valid]
        if unknown:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY, f"Unknown status: {unknown[0]}"
            )
        query = query.where(TestResult.status.in_(statuses))
    else:
        # Default view is work that needs a human.
        query = query.where(
            TestResult.status.in_([TestResultStatus.flagged, TestResultStatus.processing])
        )

    if test_type:
        query = query.where(Test.code == test_type.upper())

    if region:
        query = query.where(Athlete.region == region)

    query = _scope(query, official)

    total = db.execute(
        select(func.count()).select_from(query.order_by(None).subquery())
    ).scalar_one()
    response.headers["X-Total-Count"] = str(total)

    rows = db.execute(
        query.order_by(severity.desc(), TestResult.created_at.asc())
        .limit(limit)
        .offset(offset)
    ).all()

    names = {rank: name for name, rank in SEVERITY_RANK.items()}

    return [
        ReviewItemResponse(
            result_id=result.id,
            athlete_name=athlete.name,
            region=athlete.region,
            test_type=test.code,
            status=_value(result.status),
            provisional_score=_as_float(result.provisional_score),
            server_score=_as_float(result.server_score),
            flag_count=len(result.flags),
            created_at=result.created_at,
            max_severity=names.get(int(max_rank or 0)),
            attempt_number=result.attempt_number,
            unit=test.unit,
        )
        for result, athlete, test, max_rank in rows
    ]


@router.get("/reviews/{result_id}", response_model=ReviewDetailResponse)
def review_detail(
    result_id: uuid.UUID,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    official: Official | None = Depends(current_official),
):
    result, athlete, test = _load_scoped(db, result_id, official)
    storage = get_storage(settings)

    video = next(iter(result.videos), None)

    # Signed and short-lived. These recordings show minors; a durable public
    # URL would be a data-protection incident waiting to happen.
    video_url = storage.signed_url(video.s3_key) if video is not None else None

    reference_photo_url = (
        storage.signed_url(athlete.reference_face_key)
        if athlete.reference_face_key
        else None
    )

    face = db.execute(
        select(FaceVerification)
        .where(FaceVerification.test_result_id == result.id)
        .order_by(FaceVerification.created_at.desc())
    ).scalars().first()

    history_rows = db.execute(
        select(ReviewActionRecord, Official)
        .join(Official, Official.id == ReviewActionRecord.official_id)
        .where(ReviewActionRecord.test_result_id == result.id)
        .order_by(ReviewActionRecord.created_at.asc())
    ).all()

    benchmark, _ = _benchmark_for(db, result, test, athlete)

    return ReviewDetailResponse(
        result_id=result.id,
        athlete_name=athlete.name,
        region=athlete.region,
        test_type=test.code,
        status=_value(result.status),
        provisional_score=_as_float(result.provisional_score),
        server_score=_as_float(result.server_score),
        final_score=_as_float(result.final_score),
        unit=test.unit,
        video_url=video_url,
        flags=[_flag(flag) for flag in _ordered_flags(result.flags)],
        created_at=result.created_at,
        verified_at=result.verified_at,
        attempt_number=result.attempt_number,
        athlete_id=athlete.id,
        athlete_age_years=benchmark_service.age_on(athlete.dob),
        athlete_gender=_value(athlete.gender),
        athlete_height_cm=_as_float(athlete.height_cm),
        video_duration_seconds=_as_float(video.duration_seconds) if video else None,
        reference_photo_url=reference_photo_url,
        face_verification=(
            FaceVerificationResponse(
                status=_value(face.verification_status),
                similarity_score=_as_float(face.similarity_score),
                verified_at=face.verified_at,
            )
            if face is not None
            else None
        ),
        has_pose_sequence=bool(video is not None and video.pose_sequence_key),
        benchmark=benchmark,
        review_history=[
            ReviewHistoryItem(
                action=_value(record.action),
                notes=record.notes,
                official_name=reviewer.name,
                created_at=record.created_at,
            )
            for record, reviewer in history_rows
        ],
        allowed_actions=_allowed_actions(result, official),
    )


@router.get("/reviews/{result_id}/pose")
def review_pose_sequence(
    result_id: uuid.UUID,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    official: Official | None = Depends(current_official),
):
    """The landmarks the server extracted, for the skeleton overlay.

    Served through the API rather than as a public file so region scoping
    applies to it exactly as it does to the video.
    """
    result, _, _ = _load_scoped(db, result_id, official)
    video = next(iter(result.videos), None)

    if video is None or not video.pose_sequence_key:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No pose sequence for this result")

    import tempfile
    from pathlib import Path

    with tempfile.TemporaryDirectory(prefix="f4all-pose-") as temporary:
        destination = Path(temporary) / "pose.json"
        try:
            get_storage(settings).fetch_to(video.pose_sequence_key, destination)
        except (FileNotFoundError, OSError) as exc:
            raise HTTPException(
                status.HTTP_404_NOT_FOUND, "Pose sequence is not available"
            ) from exc
        payload = json.loads(destination.read_text(encoding="utf-8"))

    return payload


@router.post("/reviews/{result_id}/action", response_model=ReviewActionResponse)
def review_action(
    result_id: uuid.UUID,
    payload: ReviewActionRequest,
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    try:
        action = ReviewAction(payload.action)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unknown action '{payload.action}'",
        ) from exc

    if official is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Review actions require an identified official for the audit trail",
        )

    result, _, _ = _load_scoped(db, result_id, official)
    notes = (payload.notes or "").strip() or None

    if result.status not in DECIDABLE:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            f"This result is already {_value(result.status)} and cannot be changed here",
        )

    if action is not ReviewAction.approved and notes is None:
        # The athlete sees these notes. A rejection or a request to record
        # again with no reason leaves a teenager with no idea what to fix.
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "Explain the decision in the notes — the athlete will see them",
        )

    now = datetime.now(UTC)

    if action is ReviewAction.approved:
        if result.server_score is not None:
            # The official number is the server's measurement. Approval is what
            # makes it official; it does not get to substitute a different one.
            result.final_score = result.server_score
        elif payload.final_score is not None:
            if notes is None:
                raise HTTPException(
                    status.HTTP_422_UNPROCESSABLE_ENTITY,
                    "A score entered by hand needs a note saying how it was determined",
                )
            result.final_score = payload.final_score
        else:
            # Never silently promote the phone's number. That would make the
            # one unverified measurement in the system the official one.
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "The server could not score this video. Enter the score you "
                "determined from the recording, or reject it.",
            )
        result.status = TestResultStatus.approved

    elif action is ReviewAction.rejected:
        result.status = TestResultStatus.rejected
        result.final_score = None

    else:
        # `pending_sync` is the schema's state for "waiting on the athlete".
        # The athlete app reads it, with the review notes, as a request to
        # record the test again.
        result.status = TestResultStatus.pending_sync
        result.final_score = None

    recorded_notes = notes
    if action is ReviewAction.approved and result.server_score is None:
        recorded_notes = f"[Score entered by reviewer: {payload.final_score}] {notes}"

    db.add(
        ReviewActionRecord(
            test_result_id=result.id,
            official_id=official.id,
            action=action,
            notes=recorded_notes,
            created_at=now,
        )
    )

    for flag in result.flags:
        if flag.resolved_at is None:
            flag.resolved_by = official.id
            flag.resolved_at = now
            flag.resolution = (
                FlagResolution.confirmed
                if action is ReviewAction.rejected
                else FlagResolution.dismissed
            )
            db.add(flag)

    db.add(result)
    db.commit()

    logger.info(
        "Official %s %s result %s", official.id, action.value, result.id
    )

    return ReviewActionResponse(
        result_id=result.id,
        action=action.value,
        status=_value(result.status),
        message=f"Submission {action.value.replace('_', ' ')}",
    )


@router.get("/leaderboard", response_model=LeaderboardResponse)
def leaderboard(
    test_type: str = Query(...),
    region: str | None = Query(default=None),
    gender: str | None = Query(default=None),
    age_min: int | None = Query(default=None, ge=0, le=100),
    age_max: int | None = Query(default=None, ge=0, le=100),
    limit: int = Query(default=50, ge=1, le=200),
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    """Top performers from APPROVED results only.

    A ranking built on unreviewed numbers would put a flagged or tampered
    submission at the top of a list officials use to spot talent.
    """
    test = db.execute(
        select(Test).where(Test.code == test_type.upper())
    ).scalar_one_or_none()
    if test is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"Unknown test '{test_type}'")

    query = (
        select(TestResult, Athlete)
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .where(TestResult.test_id == test.id)
        .where(TestResult.status == TestResultStatus.approved)
        .where(TestResult.final_score.is_not(None))
    )

    if region:
        query = query.where(Athlete.region == region)
    if gender:
        query = query.where(Athlete.gender == gender.lower())

    today = datetime.now(UTC).date()
    if age_min is not None:
        # Born on or before the date they turned age_min.
        query = query.where(Athlete.dob <= _years_before(today, age_min))
    if age_max is not None:
        # Born after the date they would have turned age_max + 1.
        query = query.where(Athlete.dob > _years_before(today, age_max + 1))

    query = _scope(query, official)

    # Best result per athlete. One athlete with ten strong attempts must not
    # occupy ten places.
    best: dict[uuid.UUID, tuple[TestResult, Athlete]] = {}
    for result, athlete in db.execute(query).all():
        score = float(result.final_score)
        current = best.get(athlete.id)
        if current is None or _better(
            score, float(current[0].final_score), test.higher_is_better
        ):
            best[athlete.id] = (result, athlete)

    ranked = sorted(
        best.values(),
        key=lambda pair: (
            -float(pair[0].final_score)
            if test.higher_is_better
            else float(pair[0].final_score),
            pair[0].verified_at or pair[0].created_at,
        ),
    )[:limit]

    return LeaderboardResponse(
        test_type=test.code,
        unit=test.unit,
        higher_is_better=test.higher_is_better,
        entries=[
            LeaderboardEntry(
                rank=index + 1,
                athlete_id=athlete.id,
                athlete_name=athlete.name,
                region=athlete.region,
                gender=_value(athlete.gender),
                age_years=benchmark_service.age_on(athlete.dob),
                score=float(result.final_score),
                unit=test.unit,
                achieved_at=result.verified_at or result.created_at,
            )
            for index, (result, athlete) in enumerate(ranked)
        ],
    )


@router.get("/stats", response_model=DashboardStats)
def stats(
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    official: Official | None = Depends(current_official),
):
    """Counts for the dashboard header, scoped like everything else."""
    base = select(TestResult.status, func.count()).join(
        Athlete, Athlete.id == TestResult.athlete_id
    )
    rows = db.execute(_scope(base, official).group_by(TestResult.status)).all()
    by_status = {member.value: 0 for member in TestResultStatus}
    for status_value, count in rows:
        by_status[_value(status_value)] = count

    high = db.execute(
        _scope(
            select(func.count(func.distinct(TestResult.id)))
            .select_from(TestResult)
            .join(Athlete, Athlete.id == TestResult.athlete_id)
            .join(Flag, Flag.test_result_id == TestResult.id)
            .where(TestResult.status == TestResultStatus.flagged)
            .where(Flag.severity == "high")
            .where(Flag.resolved_at.is_(None)),
            official,
        )
    ).scalar_one()

    cutoff = datetime.now(UTC) - timedelta(seconds=settings.verification_sla_seconds)
    breaching = db.execute(
        _scope(
            select(func.count())
            .select_from(TestResult)
            .join(Athlete, Athlete.id == TestResult.athlete_id)
            .where(TestResult.status == TestResultStatus.processing)
            .where(TestResult.created_at < cutoff),
            official,
        )
    ).scalar_one()

    return DashboardStats(
        by_status=by_status, flagged_high_severity=high, breaching_sla=breaching
    )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _load_scoped(
    db: Session, result_id: uuid.UUID, official: Official | None
) -> tuple[TestResult, Athlete, Test]:
    row = db.execute(
        _scope(
            select(TestResult, Athlete, Test)
            .join(Athlete, Athlete.id == TestResult.athlete_id)
            .join(Test, Test.id == TestResult.test_id)
            .where(TestResult.id == result_id),
            official,
        )
    ).first()

    if row is None:
        # 404 for "does not exist" and "not in your region" alike.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Result not found")

    return row[0], row[1], row[2]


def _allowed_actions(result: TestResult, official: Official | None) -> list[str]:
    if official is None or result.status not in DECIDABLE:
        return []
    return [member.value for member in ReviewAction]


def _ordered_flags(flags: list[Flag]) -> list[Flag]:
    return sorted(
        flags,
        key=lambda flag: (
            flag.resolved_at is not None,
            -SEVERITY_RANK.get(_value(flag.severity), 0),
            flag.created_at,
        ),
    )


def _flag(flag: Flag) -> FlagResponse:
    return FlagResponse(
        reason=flag.reason,
        detail=flag.detail,
        severity=_value(flag.severity),
        source=_value(flag.source),
        created_at=flag.created_at,
        resolution=_value(flag.resolution) if flag.resolution is not None else None,
        resolved_at=flag.resolved_at,
    )


def _better(candidate: float, current: float, higher_is_better: bool) -> bool:
    return candidate > current if higher_is_better else candidate < current


def _years_before(day, years: int):
    try:
        return day.replace(year=day.year - years)
    except ValueError:
        # 29 February in a non-leap target year.
        return day.replace(year=day.year - years, day=28)


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)


def _as_float(value) -> float | None:
    return float(value) if value is not None else None

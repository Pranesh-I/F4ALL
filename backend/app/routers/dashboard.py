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
from sqlalchemy import and_, case, false, func, not_, or_, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    AWAITING_VERIFICATION,
    FINAL_REVIEW_ACTIONS,
    AssessmentSession,
    Athlete,
    FaceVerification,
    Flag,
    FlagResolution,
    FlagSeverity,
    FlagSource,
    IdentityCheck,
    Official,
    OfficialRole,
    ReviewAction,
    ReviewActionRecord,
    ReviewReason,
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
    SubmissionItemResponse,
    TestInfoResponse,
)
from ..security import current_official
from ..services import benchmarks as benchmark_service
from ..storage import get_storage
from ..verification.analyzers import TestType
from .media import identity_photo_url
from .tests_submit import _benchmark_for

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/dashboard", tags=["Dashboard"])

SEVERITY_RANK = {"high": 3, "medium": 2, "low": 1}

# Where a result stands for a reviewer, derived from its status, the machine's
# reason and whether an official has acted. Computed here, never in the UI.
REVIEW_STATUSES = (
    "awaiting_upload",
    "awaiting_verification",
    "needs_review",
    "awaiting_approval",
    "invalid",
    "approved",
    "rejected",
    "resubmission_requested",
)


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


@router.get("/reviews", response_model=list[SubmissionItemResponse])
def review_queue(
    response: Response,
    status_filter: str | None = Query(default=None, alias="status"),
    review_status_filter: str | None = Query(
        default=None,
        alias="review_status",
        description="Comma-separated: " + ", ".join(REVIEW_STATUSES),
    ),
    test_type: str | None = Query(default=None),
    region: str | None = Query(default=None),
    session_id: uuid.UUID | None = Query(default=None),
    athlete: str | None = Query(
        default=None, max_length=150, description="Athlete id, or part of the name"
    ),
    flags: str | None = Query(
        default=None, description="open | none | low | medium | high (at least)"
    ),
    submitted_from: datetime | None = Query(default=None),
    submitted_to: datetime | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
    official: Official = Depends(current_official),
):
    """The work queue, most urgent first.

    Ordered by highest unresolved flag severity, then oldest first — a
    high-severity flag should not wait behind a week of low ones, and within a
    severity nobody should be starved by newer submissions.

    With no status filter it shows work waiting for a human decision:
    ``needs_review`` (flagged) and ``invalid`` (the machine could not verify
    it and nobody has asked for a new recording yet). Results still being
    verified are not decidable, so they are not in the default view.

    The total count is in `X-Total-Count` so the body stays a plain list.
    """
    return _list_results(
        db,
        response,
        official,
        statuses=status_filter,
        review_statuses=review_status_filter,
        default_review_statuses=None if status_filter else ["needs_review", "invalid"],
        test_type=test_type,
        region=region,
        session_id=session_id,
        athlete=athlete,
        flags=flags,
        submitted_from=submitted_from,
        submitted_to=submitted_to,
        order="priority",
        limit=limit,
        offset=offset,
    )


@router.get("/submissions", response_model=list[SubmissionItemResponse])
def submissions(
    response: Response,
    status_filter: str | None = Query(default=None, alias="status"),
    review_status_filter: str | None = Query(
        default=None,
        alias="review_status",
        description="Comma-separated: " + ", ".join(REVIEW_STATUSES),
    ),
    test_type: str | None = Query(default=None),
    region: str | None = Query(default=None),
    session_id: uuid.UUID | None = Query(default=None),
    athlete: str | None = Query(
        default=None, max_length=150, description="Athlete id, or part of the name"
    ),
    flags: str | None = Query(
        default=None, description="open | none | low | medium | high (at least)"
    ),
    submitted_from: datetime | None = Query(default=None),
    submitted_to: datetime | None = Query(default=None),
    limit: int = Query(default=25, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
    official: Official = Depends(current_official),
):
    """Every submission this official may see, newest first (Sprint 13).

    The review queue answers "what needs a decision, most urgent first"; this
    answers "what has been submitted" — any status unless filtered. Both take
    the same filters and are region-scoped and paged the same way, with the
    total in `X-Total-Count`.
    """
    return _list_results(
        db,
        response,
        official,
        statuses=status_filter,
        review_statuses=review_status_filter,
        default_review_statuses=None,
        test_type=test_type,
        region=region,
        session_id=session_id,
        athlete=athlete,
        flags=flags,
        submitted_from=submitted_from,
        submitted_to=submitted_to,
        order="newest",
        limit=limit,
        offset=offset,
    )


def _list_results(
    db: Session,
    response: Response,
    official: Official,
    *,
    statuses: str | None,
    review_statuses: str | None,
    default_review_statuses: list[str] | None,
    test_type: str | None,
    region: str | None,
    session_id: uuid.UUID | None,
    athlete: str | None,
    flags: str | None,
    submitted_from: datetime | None,
    submitted_to: datetime | None,
    order: str,
    limit: int,
    offset: int,
) -> list[SubmissionItemResponse]:
    """One query for a page of results with everything a row shows.

    Flag counts, the highest open severity and whether anyone has reviewed a
    result are correlated subqueries in the same SELECT, so a page of 25 is
    one round trip rather than one per row.
    """
    severity = _max_severity_expression().label("max_severity")
    flag_count = _flag_count_expression().label("flag_count")
    open_flags = _flag_count_expression(open_only=True).label("open_flags")
    reviews = _review_count_expression().label("review_count")

    query = (
        select(
            TestResult, Athlete, Test, AssessmentSession,
            severity, flag_count, open_flags, reviews,
        )
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .join(Test, Test.id == TestResult.test_id)
        .outerjoin(AssessmentSession, AssessmentSession.id == TestResult.session_id)
    )

    parsed_statuses = _parse_result_statuses(statuses)
    if parsed_statuses:
        query = query.where(TestResult.status.in_(parsed_statuses))

    wanted = _parse_review_statuses(review_statuses) or default_review_statuses or []
    if wanted:
        query = query.where(
            or_(*(_review_status_condition(name, reviews) for name in wanted))
        )

    if test_type:
        query = query.where(Test.code == test_type.upper())
    if region:
        query = query.where(Athlete.region == region)
    if session_id is not None:
        query = query.where(TestResult.session_id == session_id)
    if athlete and athlete.strip():
        query = query.where(_athlete_condition(athlete.strip()))
    if flags:
        query = query.where(_flag_condition(flags, severity))
    if submitted_from is not None:
        query = query.where(TestResult.created_at >= _utc(submitted_from))
    if submitted_to is not None:
        query = query.where(TestResult.created_at < _utc(submitted_to))

    query = _scope(query, official)

    total = db.execute(
        select(func.count()).select_from(query.order_by(None).subquery())
    ).scalar_one()
    response.headers["X-Total-Count"] = str(total)

    ordering = (
        (severity.desc(), TestResult.created_at.asc())
        if order == "priority"
        else (TestResult.created_at.desc(), TestResult.id)
    )
    rows = db.execute(query.order_by(*ordering).limit(limit).offset(offset)).all()

    return [
        SubmissionItemResponse(
            result_id=result.id,
            athlete_id=athlete_row.id,
            athlete_name=athlete_row.name,
            region=athlete_row.region,
            test_type=test.code,
            status=_value(result.status),
            provisional_score=_as_float(result.provisional_score),
            server_score=_as_float(result.server_score),
            final_score=_as_float(result.final_score),
            flag_count=int(total_flags or 0),
            open_flag_count=int(unresolved or 0),
            created_at=result.created_at,
            verified_at=result.verified_at,
            max_severity=_SEVERITY_NAMES.get(int(max_rank or 0)),
            attempt_number=result.attempt_number,
            unit=test.unit,
            verification_reason=result.verification_reason,
            verification_verdict=result.verification_verdict,
            review_status=review_status(
                result.status, result.verification_reason, reviewed=bool(review_count)
            ),
            session_id=session.id if session is not None else None,
            session_name=session.name if session is not None else None,
        )
        for (
            result, athlete_row, test, session,
            max_rank, total_flags, unresolved, review_count,
        ) in rows
    ]


@router.get("/tests", response_model=list[TestInfoResponse])
def tests_catalog(
    db: Session = Depends(get_db),
    _official: Official = Depends(current_official),
):
    """The tests the backend can assess, in battery order.

    Driven by the scorer registry (`TestType`) — the same list session
    creation validates against — so the dashboard never offers a test the
    server cannot score. Names come from the `tests` table where it has been
    seeded (`python -m app.cli seed`).
    """
    seeded = {test.code: test for test in db.execute(select(Test)).scalars()}
    catalog = []
    for member in TestType:
        row = seeded.get(member.value)
        catalog.append(
            TestInfoResponse(
                code=member.value,
                name=row.name if row else member.value.replace("_", " ").title(),
                unit=member.unit,
                higher_is_better=row.higher_is_better if row else True,
            )
        )
    return catalog


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

    # Decrypted by the media router, never by the bucket.
    reference_photo_url = (
        identity_photo_url(settings, athlete.reference_face_key)
        if athlete.reference_face_key
        else None
    )

    identity_check = (
        db.get(IdentityCheck, result.identity_check_id)
        if result.identity_check_id is not None
        else None
    )

    face = db.execute(
        select(FaceVerification)
        .where(FaceVerification.test_result_id == result.id)
        .order_by(FaceVerification.created_at.desc())
    ).scalars().first()

    history_rows = _history(db, result.id)

    benchmark, _ = _benchmark_for(db, result, test, athlete)

    session = (
        db.get(AssessmentSession, result.session_id)
        if result.session_id is not None
        else None
    )

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
        identity_check=_value(identity_check.outcome) if identity_check else None,
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
                official_id=reviewer.id,
                reason=record.reason,
                previous_status=record.previous_status,
                new_status=record.new_status,
            )
            for record, reviewer in history_rows
        ],
        allowed_actions=_allowed_actions(
            result, official, reviewed=bool(history_rows)
        ),
        session_id=session.id if session is not None else None,
        session_name=session.name if session is not None else None,
        review_status=review_status(
            result.status, result.verification_reason, reviewed=bool(history_rows)
        ),
        verification_verdict=result.verification_verdict,
        verification_reason=result.verification_reason,
        review_version=len(history_rows),
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
    official: Official = Depends(current_official),
):
    """Record an official's decision, or a concern (`flagged`).

    What may happen from each state is decided here and only here
    (`_allowed_actions`); the dashboard shows what this allows. Every action
    appends one audit row with the reviewer, the reason, and the status either
    side. The automated evidence (scores, snapshots, checks, flags and the
    machine verdict) is never changed by a decision.
    """
    try:
        action = ReviewAction(payload.action)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unknown action '{payload.action}'",
        ) from exc

    # Scoped and locked in one statement. A second reviewer deciding the same
    # result at the same moment waits here, then sees what the first left.
    result, _, _ = _load_scoped(db, result_id, official, lock=True)

    history = _history(db, result.id)
    version = len(history)
    if payload.expected_version is not None and payload.expected_version != version:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "This submission changed while you had it open: "
            f"{_describe_latest(result, history)}. Reload it before deciding.",
        )

    allowed = _allowed_actions(result, official, reviewed=bool(history))
    if action.value not in allowed:
        raise HTTPException(status.HTTP_409_CONFLICT, _refusal(result, action, allowed))

    reason = _parse_reason(payload.reason, required=action is not ReviewAction.approved)
    notes = (payload.notes or "").strip() or None

    explained = (ReviewAction.rejected, ReviewAction.requested_resubmission)
    if action in explained and not notes:
        # The athlete sees these notes. A rejection or a request to record
        # again with no reason leaves a teenager with no idea what to fix.
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "Explain the decision in the notes — the athlete will see them",
        )
    if action is ReviewAction.flagged and not notes:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "Say what concerns you in the notes — the next reviewer will read them",
        )

    severity = (
        _parse_severity(payload.severity) if action is ReviewAction.flagged else None
    )
    now = datetime.now(UTC)
    previous = _value(result.status)

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

    elif action is ReviewAction.requested_resubmission:
        # `pending_sync` is the schema's state for "waiting on the athlete".
        # The athlete app reads it, with the review notes, as a request to
        # record the test again; the session slot is freed for that.
        result.status = TestResultStatus.pending_sync
        result.final_score = None

    else:
        # A concern, not a decision: the result is (or stays) flagged, with
        # the reviewer's flag beside the automatic ones, and stays decidable.
        result.status = TestResultStatus.flagged
        db.add(
            Flag(
                test_result_id=result.id,
                source=FlagSource.manual,
                reason=reason,
                detail=notes,
                severity=severity,
                evidence={
                    "signal": "reviewer",
                    "raised_by": str(official.id),
                    "review_reason": reason,
                },
                created_at=now,
            )
        )

    recorded_notes = notes
    if action is ReviewAction.approved and result.server_score is None:
        recorded_notes = f"[Score entered by reviewer: {payload.final_score}] {notes}"

    db.add(
        ReviewActionRecord(
            test_result_id=result.id,
            official_id=official.id,
            action=action,
            notes=recorded_notes,
            reason=reason,
            previous_status=previous,
            new_status=_value(result.status),
            created_at=now,
        )
    )

    if action in FINAL_REVIEW_ACTIONS:
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
        "Official %s %s result %s (%s -> %s, reason %s)",
        official.id, action.value, result.id, previous, _value(result.status), reason,
    )

    return ReviewActionResponse(
        result_id=result.id,
        action=action.value,
        status=_value(result.status),
        message=_ACTION_MESSAGES[action],
        review_status=review_status(
            result.status, result.verification_reason, reviewed=True
        ),
        review_version=version + 1,
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
            .where(TestResult.status.in_(AWAITING_VERIFICATION))
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


def _parse_result_statuses(status_filter: str | None) -> list[str]:
    """`?status=flagged,verified` as a list, refusing values that do not exist."""
    if not status_filter:
        return []
    statuses = [part.strip() for part in status_filter.split(",") if part.strip()]
    valid = {member.value for member in TestResultStatus}
    unknown = [value for value in statuses if value not in valid]
    if unknown:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, f"Unknown status: {unknown[0]}"
        )
    return statuses


_SEVERITY_NAMES = {rank: name for name, rank in SEVERITY_RANK.items()}


# ---------------------------------------------------------------------------
# Review state (Sprint 14)
# ---------------------------------------------------------------------------

# What a reviewer may do from each state:
#
#   verified | flagged                   approve, reject, resubmission, flag
#   rejected by the machine, unreviewed  reject (confirm) or resubmission
#   uploaded | processing                nothing yet: the machine verdict first
#   approved | rejected | pending_sync   settled
REVIEWABLE = frozenset({TestResultStatus.verified, TestResultStatus.flagged})
_ALL_ACTIONS = [
    ReviewAction.approved.value,
    ReviewAction.rejected.value,
    ReviewAction.requested_resubmission.value,
    ReviewAction.flagged.value,
]
_MACHINE_REJECTION_ACTIONS = [
    ReviewAction.rejected.value,
    ReviewAction.requested_resubmission.value,
]

_ACTION_MESSAGES = {
    ReviewAction.approved: "Submission approved. Its score is now official.",
    ReviewAction.rejected: "Submission rejected.",
    ReviewAction.requested_resubmission: (
        "Resubmission requested. The athlete can record this test again."
    ),
    ReviewAction.flagged: "Submission flagged for further review.",
}


def review_status(
    status_value, verification_reason: str | None, *, reviewed: bool
) -> str:
    current = TestResultStatus(_value(status_value))
    if current in AWAITING_VERIFICATION:
        return "awaiting_verification"
    if current is TestResultStatus.flagged:
        return "needs_review"
    if current is TestResultStatus.verified:
        return "awaiting_approval"
    if current is TestResultStatus.approved:
        return "approved"
    if current is TestResultStatus.rejected:
        return "invalid" if verification_reason and not reviewed else "rejected"
    # pending_sync: waiting on the athlete, either for a first upload or for
    # the new recording an official asked for.
    return "resubmission_requested" if reviewed else "awaiting_upload"


def _review_status_condition(name: str, review_count):
    """The SQL twin of review_status(), for filtering."""
    reviewed = review_count > 0
    conditions = {
        "awaiting_verification": TestResult.status.in_(AWAITING_VERIFICATION),
        "needs_review": TestResult.status == TestResultStatus.flagged,
        "awaiting_approval": TestResult.status == TestResultStatus.verified,
        "approved": TestResult.status == TestResultStatus.approved,
        "invalid": and_(
            TestResult.status == TestResultStatus.rejected,
            TestResult.verification_reason.is_not(None),
            not_(reviewed),
        ),
        "rejected": and_(
            TestResult.status == TestResultStatus.rejected,
            or_(reviewed, TestResult.verification_reason.is_(None)),
        ),
        "resubmission_requested": and_(
            TestResult.status == TestResultStatus.pending_sync, reviewed
        ),
        "awaiting_upload": and_(
            TestResult.status == TestResultStatus.pending_sync, not_(reviewed)
        ),
    }
    return conditions[name]


def _parse_review_statuses(value: str | None) -> list[str]:
    if not value:
        return []
    names = [part.strip() for part in value.split(",") if part.strip()]
    unknown = [name for name in names if name not in REVIEW_STATUSES]
    if unknown:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, f"Unknown review status: {unknown[0]}"
        )
    return names


def _flag_count_expression(*, open_only: bool = False):
    query = select(func.count(Flag.id)).where(Flag.test_result_id == TestResult.id)
    if open_only:
        query = query.where(Flag.resolved_at.is_(None))
    return query.correlate(TestResult).scalar_subquery()


def _review_count_expression():
    return (
        select(func.count(ReviewActionRecord.id))
        .where(ReviewActionRecord.test_result_id == TestResult.id)
        .correlate(TestResult)
        .scalar_subquery()
    )


def _athlete_condition(value: str):
    """An athlete id, or a case-insensitive part of the name."""
    try:
        return Athlete.id == uuid.UUID(value)
    except ValueError:
        return Athlete.name.icontains(value, autoescape=True)


def _flag_condition(value: str, severity):
    choice = value.strip().lower()
    if choice == "open":
        return severity >= 1
    if choice == "none":
        return severity == 0
    if choice in SEVERITY_RANK:
        return severity >= SEVERITY_RANK[choice]
    raise HTTPException(
        status.HTTP_422_UNPROCESSABLE_ENTITY,
        "flags must be one of: open, none, low, medium, high",
    )


def _utc(moment: datetime) -> datetime:
    """Stored times are UTC; a filter sent with any offset is compared as UTC."""
    if moment.tzinfo is None:
        return moment.replace(tzinfo=UTC)
    return moment.astimezone(UTC)


def _parse_reason(value: str | None, *, required: bool) -> str | None:
    cleaned = (value or "").strip().lower() or None
    if cleaned is None:
        if required:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "Choose a reason for this decision",
            )
        return None
    try:
        return ReviewReason(cleaned).value
    except ValueError as exc:
        allowed = ", ".join(member.value for member in ReviewReason)
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            f"Unknown reason '{value}'. Use one of: {allowed}",
        ) from exc


def _parse_severity(value: str | None) -> FlagSeverity:
    try:
        return FlagSeverity((value or "medium").strip().lower())
    except ValueError as exc:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, "severity must be low, medium or high"
        ) from exc


def _history(
    db: Session, result_id: uuid.UUID
) -> list[tuple[ReviewActionRecord, Official]]:
    return list(
        db.execute(
            select(ReviewActionRecord, Official)
            .join(Official, Official.id == ReviewActionRecord.official_id)
            .where(ReviewActionRecord.test_result_id == result_id)
            .order_by(ReviewActionRecord.created_at.asc())
        ).all()
    )


def _describe_latest(result: TestResult, history) -> str:
    if not history:
        return f"it is now {_value(result.status)}"
    record, reviewer = history[-1]
    return (
        f"{reviewer.name} recorded '{_value(record.action).replace('_', ' ')}' "
        f"at {_aware(record.created_at):%Y-%m-%d %H:%M} UTC, "
        f"and it is now {_value(result.status)}"
    )


def _refusal(result: TestResult, action: ReviewAction, allowed: list[str]) -> str:
    current = result.status
    if current in AWAITING_VERIFICATION:
        return (
            "Verification has not finished yet. Decide once the server's verdict "
            "is in, or re-run verification"
        )
    if allowed:
        return (
            "The server could not verify this recording, so it can only be "
            "rejected or sent back for a new recording"
        )
    return f"This result is already {_value(current)} and cannot be changed here"


def _aware(moment: datetime) -> datetime:
    return moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)


def _load_scoped(
    db: Session, result_id: uuid.UUID, official: Official | None, *, lock: bool = False
) -> tuple[TestResult, Athlete, Test]:
    query = _scope(
        select(TestResult, Athlete, Test)
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .join(Test, Test.id == TestResult.test_id)
        .where(TestResult.id == result_id),
        official,
    )
    if lock:
        # Only the result row, and re-read even if this session holds it, so
        # the checks that follow see what a concurrent decision committed.
        query = query.with_for_update(of=TestResult).execution_options(
            populate_existing=True
        )
    row = db.execute(query).first()

    if row is None:
        # 404 for "does not exist" and "not in your region" alike.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Result not found")

    return row[0], row[1], row[2]


def _allowed_actions(
    result: TestResult, official: Official | None, *, reviewed: bool = False
) -> list[str]:
    if official is None:
        return []
    if result.status in REVIEWABLE:
        return list(_ALL_ACTIONS)
    if (
        result.status is TestResultStatus.rejected
        and result.verification_reason is not None
        and not reviewed
    ):
        # A machine rejection (no video, not a readable video) nobody has
        # reviewed: the athlete can only record again if an official asks.
        return list(_MACHINE_REJECTION_ACTIONS)
    return []


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
    """A flag as officials see it: with its evidence and review state."""
    resolution = _value(flag.resolution) if flag.resolution is not None else None
    return FlagResponse(
        reason=flag.reason,
        detail=flag.detail,
        severity=_value(flag.severity),
        source=_value(flag.source),
        created_at=flag.created_at,
        resolution=resolution,
        resolved_at=flag.resolved_at,
        flag_id=flag.id,
        status=resolution or "open",
        evidence=flag.evidence,
        reviewed_by=flag.resolved_by,
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

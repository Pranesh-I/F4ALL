"""Test submission and result retrieval."""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    AssessmentSession,
    Athlete,
    IdentityCheck,
    ReviewActionRecord,
    Test,
    TestResult,
    TestResultStatus,
    UploadSession,
    Video,
)
from ..schemas import (
    BenchmarkComparisonResponse,
    FlagResponse,
    LatestReview,
    ResultWithBenchmarkResponse,
    SubmitTestRequest,
    SubmitTestResponse,
)
from ..security import current_athlete
from ..services import benchmarks as benchmark_service
from ..services import sessions as session_rules

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["Tests"])


def _resolve_test(db: Session, identifier: str) -> Test | None:
    """Accept either the test's UUID or its stable code (SIT_UPS / ...).

    The mobile app knows test types by code; a dashboard or seeded client may
    use the id. Rejecting one of them would be a needless wire break.
    """
    try:
        as_uuid = uuid.UUID(identifier)
    except (TypeError, ValueError):
        as_uuid = None

    if as_uuid is not None:
        found = db.get(Test, as_uuid)
        if found:
            return found

    return db.execute(
        select(Test).where(Test.code == identifier.upper())
    ).scalar_one_or_none()


@router.post(
    "/tests/submit",
    response_model=SubmitTestResponse,
    status_code=status.HTTP_201_CREATED,
)
def submit_test(
    payload: SubmitTestRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    athlete: Athlete | None = Depends(current_athlete),
):
    test = _resolve_test(db, payload.test_id)
    if test is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unknown test '{payload.test_id}'",
        )

    athlete_id = athlete.id if athlete else None
    if athlete_id is None:
        # Development path only; production requires a token, so this cannot be
        # reached there.
        athlete_id = _development_athlete(db).id

    video: Video | None = None
    if payload.video_id is not None:
        video = db.get(Video, payload.video_id)
        if video is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Unknown video_id",
            )

        _assert_video_owner(db, video, athlete)

        if video.test_result_id is not None:
            # Idempotent on the video. A phone on a bad network routinely loses
            # the response to a request the server already committed, and its
            # retry must return the same result rather than creating a second
            # attempt and verifying the same video twice.
            existing = db.get(TestResult, video.test_result_id)
            if existing is not None:
                if existing.athlete_id != athlete_id:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="This video has already been submitted",
                    )
                return SubmitTestResponse(
                    result_id=existing.id, status=_value(existing.status)
                )

    session = _session_for(db, payload, test, athlete_id, settings)
    identity_check = _identity_check_for(db, payload, athlete_id)

    attempt_number = (
        db.execute(
            select(func.coalesce(func.max(TestResult.attempt_number), 0)).where(
                TestResult.athlete_id == athlete_id,
                TestResult.test_id == test.id,
            )
        ).scalar_one()
        + 1
    )

    result = TestResult(
        athlete_id=athlete_id,
        test_id=test.id,
        session_id=session.id if session else None,
        identity_check_id=identity_check.id if identity_check else None,
        attempt_number=attempt_number,
        provisional_score=payload.provisional_score,
        # processing, never verified. The device's number is provisional until
        # the server has independently re-scored the video.
        status=TestResultStatus.processing,
    )
    db.add(result)
    db.flush()

    if video is not None:
        video.test_result_id = result.id
        db.add(video)

    try:
        db.commit()
    except IntegrityError:
        # The session's one-submission slot was taken by a request that
        # committed between our check and this insert. The database index is
        # the final word on it.
        db.rollback()
        if session is None:
            raise
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=ALREADY_SUBMITTED
        ) from None
    db.refresh(result)

    if video is not None:
        _enqueue_verification(
            result_id=str(result.id),
            athlete_height_cm=_height_for(athlete, payload),
            settings=settings,
        )
    else:
        logger.warning(
            "Result %s submitted with no video; it cannot be verified", result.id
        )

    return SubmitTestResponse(result_id=result.id, status=result.status.value)


ALREADY_SUBMITTED = "You have already submitted this test for this assessment session"


def _identity_check_for(
    db: Session, payload: SubmitTestRequest, athlete_id
) -> IdentityCheck | None:
    """The photo check this attempt names — only ever the athlete's own.

    Whether it matched is not judged here: an attempt without a match still
    lands, and verification routes it to a reviewer.
    """
    if payload.identity_check_id is None:
        return None
    check = db.get(IdentityCheck, payload.identity_check_id)
    if check is None or check.athlete_id != athlete_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Unknown identity check",
        )
    return check


def _session_for(
    db: Session,
    payload: SubmitTestRequest,
    test: Test,
    athlete_id: uuid.UUID,
    settings: Settings,
) -> AssessmentSession | None:
    """The session this official attempt counts towards, after checking it may.

    Runs after the idempotent-retry return above, so a phone repeating a
    submission that already landed still gets its result back instead of
    being told it has already submitted.
    """
    if payload.session_id is None:
        if settings.sessions_required:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Official tests must be submitted within an assessment session",
            )
        return None

    session = db.get(AssessmentSession, payload.session_id)
    athlete = db.get(Athlete, athlete_id)
    if session is None or athlete is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Unknown assessment session",
        )

    recorded_at = (
        datetime.fromtimestamp(payload.recorded_at_ms / 1000, tz=UTC)
        if payload.recorded_at_ms is not None
        else None
    )
    problem = session_rules.submission_problem(
        session,
        athlete,
        test.code,
        recorded_at,
        now=datetime.now(UTC),
        grace=timedelta(hours=settings.session_submission_grace_hours),
    )
    if problem is not None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=problem)

    already = db.execute(
        select(func.count())
        .select_from(TestResult)
        .where(
            TestResult.athlete_id == athlete_id,
            TestResult.session_id == session.id,
            TestResult.test_id == test.id,
            # An official's request to resubmit frees the slot.
            TestResult.status != TestResultStatus.pending_sync,
        )
    ).scalar_one()
    if already:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=ALREADY_SUBMITTED
        )

    return session


def _assert_video_owner(db: Session, video: Video, athlete: Athlete | None) -> None:
    """A video can only be submitted by the athlete who uploaded it.

    Ownership is recorded on the upload session. Without this check any athlete
    holding another's video id could claim that recording as their own test.
    It answers exactly as for an id that does not exist, so the endpoint cannot
    be used to confirm whose videos exist.
    """
    if athlete is None:
        return

    session = db.execute(
        select(UploadSession).where(UploadSession.video_id == video.id)
    ).scalar_one_or_none()

    if session is not None and session.athlete_id not in (None, athlete.id):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown video_id"
        )


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)


def _height_for(athlete: Athlete | None, payload: SubmitTestRequest) -> float | None:
    """Profile height wins over the client-supplied value.

    A height sent by the client is a client-supplied input to a measurement the
    server is supposed to verify independently; when a registered value exists,
    that is the one to trust.
    """
    if athlete is not None and athlete.height_cm is not None:
        return float(athlete.height_cm)
    return payload.athlete_height_cm


def _enqueue_verification(
    *, result_id: str, athlete_height_cm: float | None, settings: Settings
) -> None:
    """Hand the result to Celery, or run nothing if the broker is unreachable.

    A broker outage must not lose the submission. The row stays in `processing`
    and the reconciliation command (`python -m app.cli reverify-pending`) picks
    it up, rather than the athlete's test silently disappearing.
    """
    try:
        from ..tasks import verify_test_result

        # retry=False is essential: this runs inside the athlete's HTTP request.
        # With Celery's default retry behaviour an unreachable broker blocks the
        # request instead of raising, so a Redis outage would hang every
        # submission rather than degrading to "verify it later".
        verify_test_result.apply_async(
            args=[result_id, athlete_height_cm], retry=False
        )
        logger.info("Queued verification for result %s", result_id)
    except Exception:  # pragma: no cover - depends on broker availability
        logger.exception(
            "Could not queue verification for %s; it stays in processing and "
            "will be picked up by `python -m app.cli reverify-pending`",
            result_id,
        )


def _development_athlete(db: Session) -> Athlete:
    """A placeholder athlete so the pipeline is exercisable before Sprint 7."""
    from datetime import date

    existing = db.execute(
        select(Athlete).where(Athlete.phone == "0000000000")
    ).scalar_one_or_none()

    if existing:
        return existing

    athlete = Athlete(
        name="Development Athlete",
        dob=date(2005, 1, 1),
        gender="male",
        region="Development",
        phone="0000000000",
        height_cm=170,
    )
    db.add(athlete)
    db.commit()
    db.refresh(athlete)
    return athlete


@router.get("/results/{result_id}", response_model=ResultWithBenchmarkResponse)
def get_result(
    result_id: uuid.UUID,
    db: Session = Depends(get_db),
    athlete: Athlete | None = Depends(current_athlete),
):
    result = db.get(TestResult, result_id)
    if result is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Result not found"
        )

    # An athlete may only read their own results.
    if athlete is not None and result.athlete_id != athlete.id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Result not found"
        )

    test = db.get(Test, result.test_id)
    comparison, unavailable = _benchmark_for(db, result, test, athlete)

    latest = db.execute(
        select(ReviewActionRecord)
        .where(ReviewActionRecord.test_result_id == result.id)
        .order_by(ReviewActionRecord.created_at.desc())
    ).scalars().first()

    return ResultWithBenchmarkResponse(
        benchmark=comparison,
        benchmark_unavailable=unavailable,
        # The official's decision and reason, reflected back to the athlete.
        # Which official made it is deliberately not included.
        latest_review=(
            LatestReview(
                action=_value(latest.action),
                notes=latest.notes,
                created_at=latest.created_at,
            )
            if latest is not None
            else None
        ),
        result_id=result.id,
        test_type=test.code if test else "UNKNOWN",
        status=result.status.value
        if hasattr(result.status, "value")
        else str(result.status),
        provisional_score=(
            float(result.provisional_score)
            if result.provisional_score is not None
            else None
        ),
        server_score=(
            float(result.server_score) if result.server_score is not None else None
        ),
        final_score=(
            float(result.final_score) if result.final_score is not None else None
        ),
        unit=test.unit if test else "",
        verified_at=result.verified_at,
        flags=[
            FlagResponse(
                reason=flag.reason,
                detail=flag.detail,
                severity=flag.severity
                if isinstance(flag.severity, str)
                else flag.severity.value,
                source=flag.source
                if isinstance(flag.source, str)
                else flag.source.value,
                created_at=flag.created_at,
            )
            for flag in result.flags
        ],
    )


def _benchmark_for(
    db: Session,
    result: TestResult,
    test: Test | None,
    athlete: Athlete | None,
) -> tuple[BenchmarkComparisonResponse | None, str | None]:
    """Where this result stands against the athlete's cohort.

    Only computed from a score the SERVER stands behind. Benchmarking a
    provisional on-device number would tell an athlete they placed in the top
    25% on the strength of a measurement that has not been verified — and the
    whole design of this system rests on those two never looking the same.

    Returns (comparison, reason_it_is_unavailable); exactly one is non-None.
    """
    if test is None:
        return None, None

    if athlete is None:
        athlete = db.get(Athlete, result.athlete_id)
    if athlete is None:
        return None, None

    score = result.final_score if result.final_score is not None else result.server_score
    if score is None:
        return None, "This result has not been verified by SAI yet"

    age = benchmark_service.age_on(athlete.dob)
    benchmark = benchmark_service.find_benchmark(
        db, test_id=test.id, gender=athlete.gender, age_years=age
    )

    if benchmark is None:
        return None, (
            "No benchmark cohort is defined for this athlete's age and gender yet"
        )

    comparison = benchmark_service.compare(
        benchmark,
        float(score),
        age_years=age,
        unit=test.unit,
        # From the test row: for timed tests a lower score is the better one.
        higher_is_better=test.higher_is_better,
    )

    return (
        BenchmarkComparisonResponse(
            band=comparison.band.value,
            label=comparison.label,
            percentile=comparison.percentile,
            percentile_50=comparison.percentile_50,
            percentile_75=comparison.percentile_75,
            percentile_90=comparison.percentile_90,
            next_target=comparison.next_target,
            cohort=comparison.cohort,
            unit=comparison.unit,
            source=comparison.source,
            provisional=comparison.provisional,
        ),
        None,
    )

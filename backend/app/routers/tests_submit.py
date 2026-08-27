"""Test submission and result retrieval."""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    Athlete,
    Test,
    TestResult,
    TestResultStatus,
    Video,
)
from ..schemas import (
    FlagResponse,
    SubmitTestRequest,
    SubmitTestResponse,
    TestResultResponse,
)
from ..security import current_athlete

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

    video: Video | None = None
    if payload.video_id is not None:
        video = db.get(Video, payload.video_id)
        if video is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Unknown video_id",
            )

    athlete_id = athlete.id if athlete else None
    if athlete_id is None:
        # Development path only; production requires a token, so this cannot be
        # reached there.
        athlete_id = _development_athlete(db).id

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

    db.commit()
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


@router.get("/results/{result_id}", response_model=TestResultResponse)
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

    return TestResultResponse(
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

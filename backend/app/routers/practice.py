"""An athlete's practice history, stored against their account.

Practice is the athlete's own. These endpoints exist so it follows them from
one phone to the next — and for nothing else:

* Only ``/api/athletes/me/...`` routes, authenticated as the athlete. There is
  no route by athlete id, so there is no id to guess and no way to read someone
  else's practice.
* Nothing official reads ``practice_attempts``. A practice score is never
  verified, reviewed, benchmarked, ranked or badged; the phone scored it and
  the athlete is the only audience.
* No video. Practice videos never leave the phone.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Path, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import Athlete, PracticeAttempt
from ..schemas import PracticeAttemptIn, PracticeAttemptOut, PracticeHistoryResponse
from ..security import require_athlete
from ..verification.analyzers import TestType

router = APIRouter(prefix="/api/athletes/me/practice", tags=["Practice"])

# The phone's ids are "test_<epoch millis>"; allow a little room for change.
CLIENT_ID_PATTERN = r"^[A-Za-z0-9_\-]{1,64}$"

# Recording times outside this window are a broken clock, not a real attempt.
EARLIEST_RECORDING = datetime(2024, 1, 1, tzinfo=UTC)
FUTURE_TOLERANCE = timedelta(days=1)

DEFAULT_PAGE = 200
MAX_PAGE = 500


@router.put("/{client_attempt_id}", response_model=PracticeAttemptOut)
def save_practice_attempt(
    payload: PracticeAttemptIn,
    client_attempt_id: str = Path(pattern=CLIENT_ID_PATTERN),
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Create or replace one practice attempt.

    PUT on the phone's own id makes this idempotent: an upload repeated after a
    lost response overwrites the same row instead of adding a second attempt.
    """
    try:
        test_type = TestType(payload.test_type)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unknown test type: {payload.test_type}",
        ) from None

    if payload.unit != test_type.unit:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{test_type.value} is scored in {test_type.unit}",
        )

    recorded_at = datetime.fromtimestamp(payload.recorded_at_ms / 1000, tz=UTC)
    if not EARLIEST_RECORDING <= recorded_at <= datetime.now(UTC) + FUTURE_TOLERANCE:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Recording time is outside the accepted range",
        )

    row = db.execute(
        select(PracticeAttempt).where(
            PracticeAttempt.athlete_id == athlete.id,
            PracticeAttempt.client_attempt_id == client_attempt_id,
        )
    ).scalar_one_or_none()

    if row is None:
        row = PracticeAttempt(athlete_id=athlete.id, client_attempt_id=client_attempt_id)

    row.test_code = test_type.value
    row.score = payload.score
    row.unit = payload.unit
    row.status = payload.status
    row.confidence = payload.confidence
    row.invalid_reason = payload.invalid_reason
    row.recorded_at = recorded_at
    row.events = [event.model_dump() for event in payload.events]

    db.add(row)
    db.commit()

    return _out(row)


@router.get("", response_model=PracticeHistoryResponse)
def my_practice_history(
    before_ms: int | None = Query(default=None, gt=0),
    limit: int = Query(default=DEFAULT_PAGE, ge=1, le=MAX_PAGE),
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """The calling athlete's practice, newest first.

    Page backwards with ``before_ms`` set to the oldest ``recorded_at_ms`` seen.
    """
    query = select(PracticeAttempt).where(PracticeAttempt.athlete_id == athlete.id)

    if before_ms is not None:
        query = query.where(
            PracticeAttempt.recorded_at < datetime.fromtimestamp(before_ms / 1000, tz=UTC)
        )

    rows = db.execute(
        query.order_by(PracticeAttempt.recorded_at.desc()).limit(limit)
    ).scalars()

    return PracticeHistoryResponse(attempts=[_out(row) for row in rows])


def _out(row: PracticeAttempt) -> PracticeAttemptOut:
    recorded_at = row.recorded_at
    # SQLite hands back naive datetimes; they were stored as UTC.
    if recorded_at.tzinfo is None:
        recorded_at = recorded_at.replace(tzinfo=UTC)

    return PracticeAttemptOut(
        client_attempt_id=row.client_attempt_id,
        test_type=row.test_code,
        score=float(row.score),
        unit=row.unit,
        status=row.status,
        confidence=float(row.confidence),
        invalid_reason=row.invalid_reason,
        recorded_at_ms=math.floor(recorded_at.timestamp() * 1000),
        events=row.events or [],
    )

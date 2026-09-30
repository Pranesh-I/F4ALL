"""Where a submission is in server-side verification, and re-running it.

Two readers of the same status:

* **The athlete who submitted it** sees the state and the timing — whether it
  is still being checked, and whether that is taking longer than it should.
  Not the flags: those describe the cheat heuristics, and explaining to a
  would-be cheater which check fired is how the checks stop working.
* **An official** (region-scoped, as everywhere on the dashboard) sees all of
  it: flags, verification errors, the identity and face checks.

Re-running is for a submission stuck awaiting verification (`uploaded` or
`processing`) — the broker was down, a worker died. It is not a way to re-roll
a verdict: once verification has finished, the answer belongs to the reviewer,
not to a second machine run. A re-run racing a live one is safe: only the
latest claim may write a verdict (tasks.py).
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    AWAITING_VERIFICATION,
    Athlete,
    FaceVerification,
    IdentityCheck,
    Official,
    OfficialRole,
    Test,
    TestResult,
    TestResultStatus,
)
from ..schemas import (
    VerificationCheckResponse,
    VerificationComparison,
    VerificationStatusResponse,
)
from ..security import (
    OFFICIAL_ROLES,
    _bearer_token,
    current_athlete,
    current_official,
    decode_token,
)
from . import tests_submit
from .dashboard import _flag, _load_scoped, _ordered_flags

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/verification", tags=["Verification"])


class Caller:
    """Whoever is asking: an athlete, an official, or (development only) nobody."""

    def __init__(self, athlete: Athlete | None, official: Official | None):
        self.athlete = athlete
        self.official = official

    @property
    def is_official(self) -> bool:
        # Only a signed-in official. An anonymous caller under the development
        # auth bypass used to get the official view of every submission.
        return self.official is not None


def caller(
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Caller:
    token = _bearer_token(request)
    if token is not None and decode_token(token, settings).get("role") in OFFICIAL_ROLES:
        return Caller(None, current_official(request, db, settings))
    return Caller(current_athlete(request, db, settings), None)


@router.get("/{result_id}", response_model=VerificationStatusResponse)
def verification_status(
    result_id: uuid.UUID,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    who: Caller = Depends(caller),
):
    result, test = _load_for(db, result_id, who)
    return _status(db, result, test, settings, official_view=who.is_official)


@router.post(
    "/{result_id}/process",
    response_model=VerificationStatusResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
def reprocess(
    result_id: uuid.UUID,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    official: Official | None = Depends(current_official),
):
    """Queue verification again for a submission still awaiting a verdict."""
    if official is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            "Re-running verification requires an identified official",
        )
    if _value(official.role) != OfficialRole.sai_admin.value:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN, "Only an SAI admin can re-run verification"
        )

    result, _, test = _load_scoped(db, result_id, official)

    if result.status not in AWAITING_VERIFICATION:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "Only a submission still waiting for verification can be re-run; "
            "a finished one is decided by review",
        )
    if not result.videos:
        raise HTTPException(
            status.HTTP_409_CONFLICT, "This submission has no video to verify"
        )

    logger.info("Official %s re-queued verification of %s", official.id, result.id)
    tests_submit._enqueue_verification(
        result_id=str(result.id),
        athlete_height_cm=None,
        settings=settings,
    )
    return _status(db, result, test, settings, official_view=True)


# ---------------------------------------------------------------------------


def _load_for(db: Session, result_id: uuid.UUID, who: Caller) -> tuple[TestResult, Test]:
    if who.is_official:
        result, _, test = _load_scoped(db, result_id, who.official)
        return result, test

    if who.athlete is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Authentication required")

    row = db.execute(
        select(TestResult, Test)
        .join(Test, Test.id == TestResult.test_id)
        .where(TestResult.id == result_id)
    ).first()
    # Someone else's submission is "not found", not "forbidden".
    if row is None or row[0].athlete_id != who.athlete.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Result not found")
    return row[0], row[1]


def _status(
    db: Session,
    result: TestResult,
    test: Test,
    settings: Settings,
    *,
    official_view: bool,
) -> VerificationStatusResponse:
    submitted_at = _aware(result.created_at)
    waiting = result.status in AWAITING_VERIFICATION
    waiting_seconds = (
        int((datetime.now(UTC) - submitted_at).total_seconds()) if waiting else None
    )

    response = VerificationStatusResponse(
        result_id=result.id,
        test_type=test.code,
        unit=test.unit,
        status=_value(result.status),
        provisional_score=_as_float(result.provisional_score),
        server_score=_as_float(result.server_score),
        final_score=_as_float(result.final_score),
        submitted_at=submitted_at,
        verified_at=_aware(result.verified_at) if result.verified_at else None,
        waiting_seconds=waiting_seconds,
        sla_seconds=settings.verification_sla_seconds,
        overdue=waiting_seconds is not None
        and waiting_seconds > settings.verification_sla_seconds,
        processing_started_at=(
            _aware(result.processing_started_at)
            if result.processing_started_at
            else None
        ),
        processing_completed_at=(
            _aware(result.verified_at) if result.verified_at else None
        ),
        processing_duration_ms=result.processing_duration_ms,
        comparison=_comparison(result, official_view=official_view),
    )

    if not official_view:
        # A rejection names what to fix; any other reason describes a check.
        if result.status is TestResultStatus.rejected:
            response.verification_reason = result.verification_reason
        return response

    response.verification_reason = result.verification_reason
    response.pipeline_version = result.pipeline_version
    response.verification_attempts = result.verification_attempts
    response.checks = (
        [VerificationCheckResponse(**check) for check in result.verification_checks]
        if result.verification_checks
        else None
    )
    response.mobile_result = result.mobile_result
    response.server_result = result.server_result
    response.integrity = (result.server_result or {}).get("integrity")

    identity = (
        db.get(IdentityCheck, result.identity_check_id)
        if result.identity_check_id is not None
        else None
    )
    face = db.execute(
        select(FaceVerification)
        .where(FaceVerification.test_result_id == result.id)
        .order_by(FaceVerification.created_at.desc())
    ).scalars().first()

    response.verification_error = result.verification_error
    response.flags = [_flag(flag) for flag in _ordered_flags(result.flags)]
    response.identity_check = _value(identity.outcome) if identity else None
    response.face_check = _value(face.verification_status) if face else None
    return response


def _comparison(
    result: TestResult, *, official_view: bool
) -> VerificationComparison | None:
    """Mobile beside server, from the snapshots stored with the verdict."""
    mobile = result.mobile_result or {}
    server = result.server_result or {}
    if not mobile and not server:
        return None

    compared = server.get("comparison") or {}
    return VerificationComparison(
        mobile_rep_count=mobile.get("rep_count"),
        server_rep_count=server.get("rep_count"),
        mobile_measurement=mobile.get("measurement"),
        server_measurement=server.get("measurement"),
        mobile_form_score=mobile.get("form_score"),
        server_form_score=server.get("form_score"),
        difference=compared.get("difference") if official_view else None,
        tolerance=compared.get("tolerance") if official_view else None,
    )


def _aware(value: datetime) -> datetime:
    # SQLite hands back naive datetimes; everything stored is UTC.
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)


def _as_float(value) -> float | None:
    return float(value) if value is not None else None

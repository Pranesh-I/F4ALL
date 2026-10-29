"""Submitting a field test: the shuttle run and the endurance run (Sprint 18).

The phone sends its score with the raw sensor/GPS trace. Unlike a video, the
trace is small and is checked straight away, in the request: the athlete gets
the server's answer — verified, flagged for a reviewer, or rejected with a
reason — before the phone lets go of the attempt.

Everything after that is shared with the video tests: the same result row,
session rules, identity check, review by an official, benchmarks, leaderboards,
badges and notifications.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..field_tests import FieldTest
from ..field_tests import verify as field_verify
from ..models import (
    Athlete,
    Flag,
    FlagSeverity,
    FlagSource,
    Test,
    TestResult,
    TestResultStatus,
)
from ..schemas import FieldTestSubmit, FieldTestSubmitResponse
from ..security import require_athlete
from ..storage import get_storage
from ..verification import finalization
from .tests_submit import ALREADY_SUBMITTED, _identity_check_for, _session_for

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/field-tests", tags=["Field tests"])

STATUSES = {
    "verified": TestResultStatus.verified,
    "flagged": TestResultStatus.flagged,
    "rejected": TestResultStatus.rejected,
}


@router.post(
    "/submit",
    response_model=FieldTestSubmitResponse,
    status_code=status.HTTP_201_CREATED,
)
def submit_field_test(
    payload: FieldTestSubmit,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    athlete: Athlete = Depends(require_athlete),
):
    try:
        field_test = FieldTest(payload.test_type.strip().upper())
    except ValueError:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            f"{payload.test_type} is not a field test",
        ) from None
    test = db.execute(
        select(Test).where(Test.code == field_test.value)
    ).scalar_one_or_none()
    if test is None:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND, f"{field_test.value} is not set up"
        )

    # A retry after a lost response: return what was created the first time.
    existing = db.execute(
        select(TestResult)
        .where(TestResult.athlete_id == athlete.id)
        .where(TestResult.client_attempt_id == payload.client_attempt_id)
    ).scalar_one_or_none()
    if existing is not None:
        return _response(existing)

    try:
        motion, fixes = field_verify.parse_trace(
            field_test, payload.motion, payload.fixes
        )
    except ValueError as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc

    session = _session_for(db, payload, test, athlete.id, settings)
    identity_check = _identity_check_for(db, payload, athlete.id)

    canonical = field_verify.trace_bytes(field_test, payload.motion, payload.fixes)
    key = field_verify.telemetry_key(canonical)
    earlier = db.execute(
        select(TestResult.id).where(TestResult.telemetry_key == key).limit(1)
    ).scalar_one_or_none()

    result = field_verify.score(field_test, motion, fixes)
    verdict = field_verify.decide(
        field_test,
        result,
        device_score=payload.provisional_score,
        duplicate_of=str(earlier) if earlier else None,
    )

    attempt_number = (
        db.execute(
            select(func.coalesce(func.max(TestResult.attempt_number), 0)).where(
                TestResult.athlete_id == athlete.id, TestResult.test_id == test.id
            )
        ).scalar_one()
        + 1
    )
    now = datetime.now(UTC)
    row = TestResult(
        athlete_id=athlete.id,
        test_id=test.id,
        session_id=session.id if session else None,
        identity_check_id=identity_check.id if identity_check else None,
        attempt_number=attempt_number,
        client_attempt_id=payload.client_attempt_id,
        telemetry_key=key,
        provisional_score=payload.provisional_score,
        server_score=result.score if verdict.status != "rejected" else None,
        status=STATUSES[verdict.status],
        verification_verdict=verdict.status,
        verification_reason=verdict.reason,
        verification_checks=verdict.checks,
        pipeline_version=field_verify.PIPELINE_VERSION,
        mobile_result=finalization.mobile_snapshot(
            test_code=test.code,
            unit=test.unit,
            provisional_score=payload.provisional_score,
            form_score=None,
        ),
        server_result=field_verify.server_snapshot(
            verdict, field_test, fixes, len(motion)
        ),
        processing_started_at=now,
        verified_at=now,
        processing_duration_ms=0,
    )
    db.add(row)
    db.flush()
    for reason, detail, severity, evidence in verdict.flags:
        db.add(
            Flag(
                test_result_id=row.id,
                source=FlagSource.auto,
                reason=reason,
                detail=detail,
                severity=FlagSeverity(severity),
                evidence=evidence,
            )
        )

    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        # Either the same attempt arrived twice at once, or the session's one
        # slot was taken a moment ago.
        again = db.execute(
            select(TestResult)
            .where(TestResult.athlete_id == athlete.id)
            .where(TestResult.client_attempt_id == payload.client_attempt_id)
        ).scalar_one_or_none()
        if again is not None:
            return _response(again)
        raise HTTPException(status.HTTP_409_CONFLICT, ALREADY_SUBMITTED) from None

    # The trace is kept for the reviewer. Storing it must not lose the result,
    # which is already decided and committed.
    try:
        storage = get_storage(settings)
        if not storage.exists(key):
            storage.store_bytes(
                key, field_verify.compress(canonical), content_type="application/gzip"
            )
    except Exception:
        logger.exception("Could not store the trace for result %s", row.id)

    logger.info("Field test %s for %s: %s", field_test.value, athlete.id, verdict.status)
    db.refresh(row)
    return _response(row)


def _response(row: TestResult) -> FieldTestSubmitResponse:
    snapshot = row.server_result or {}
    return FieldTestSubmitResponse(
        result_id=row.id,
        status=row.status.value if hasattr(row.status, "value") else str(row.status),
        server_score=float(row.server_score) if row.server_score is not None else None,
        invalid_reason=snapshot.get("invalid_reason"),
    )

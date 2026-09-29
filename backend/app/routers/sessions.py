"""Assessment sessions: SAI opens a window, athletes submit within it.

Two audiences, two routers:

* ``/api/sessions`` — the athlete's view: the sessions open to them right now,
  and for each test whether they have already made their one submission.
* ``/api/dashboard/sessions`` — SAI's control: create, edit, open, close.
  Any official may look; only an SAI admin may change anything, because a
  session decides who can submit official results.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import (
    AssessmentSession,
    Athlete,
    Official,
    OfficialRole,
    Test,
    TestResult,
    TestResultStatus,
)
from ..regions import canonical_region
from ..schemas import (
    ActiveSessionResponse,
    ActiveSessionsResponse,
    AssessmentSessionCreate,
    AssessmentSessionResponse,
    AssessmentSessionUpdate,
    MessageResponse,
    SessionTestStatus,
)
from ..security import current_official, require_athlete
from ..services import sessions as rules
from ..verification.analyzers import TestType

router = APIRouter(prefix="/api/sessions", tags=["Sessions"])
admin_router = APIRouter(prefix="/api/dashboard/sessions", tags=["Dashboard"])


def _now() -> datetime:
    return datetime.now(UTC)


# ---------------------------------------------------------------------------
# Athlete
# ---------------------------------------------------------------------------


@router.get("/active", response_model=ActiveSessionsResponse)
def active_sessions(
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Sessions open to this athlete now, with what they have already submitted."""
    now = _now()

    candidates = db.execute(
        select(AssessmentSession)
        .where(AssessmentSession.enabled.is_(True))
        .order_by(AssessmentSession.ends_at)
    ).scalars()
    visible = [
        session for session in candidates if rules.visible_to(session, athlete, now)
    ]

    if not visible:
        return ActiveSessionsResponse(server_time=now)

    results = db.execute(
        select(TestResult, Test.code)
        .join(Test, Test.id == TestResult.test_id)
        .where(
            TestResult.athlete_id == athlete.id,
            TestResult.session_id.in_([session.id for session in visible]),
        )
        .order_by(TestResult.created_at)
    ).all()

    # Latest result per (session, test); later rows overwrite earlier ones.
    latest: dict[tuple[uuid.UUID, str], TestResult] = {}
    for result, code in results:
        latest[(result.session_id, code)] = result

    sessions = []
    for session in visible:
        tests = []
        for code in session.allowed_tests:
            result = latest.get((session.id, code))
            result_status = _value(result.status) if result else None
            tests.append(
                SessionTestStatus(
                    test_type=code,
                    unit=_unit(code),
                    submitted=result is not None
                    and result_status != TestResultStatus.pending_sync.value,
                    result_status=result_status,
                )
            )
        sessions.append(
            ActiveSessionResponse(
                session_id=session.id,
                name=session.name,
                description=session.description,
                rules=session.rules,
                starts_at=rules.aware(session.starts_at),
                ends_at=rules.aware(session.ends_at),
                tests=tests,
            )
        )

    return ActiveSessionsResponse(server_time=now, sessions=sessions)


# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------


@admin_router.get("", response_model=list[AssessmentSessionResponse])
def list_sessions(
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    """Every session, newest window first. A regional reviewer sees national
    sessions and their own region's."""
    query = select(AssessmentSession).order_by(AssessmentSession.starts_at.desc())
    if official is not None and not _is_admin(official):
        query = query.where(
            (AssessmentSession.region.is_(None))
            | (AssessmentSession.region == official.region)
        )
    sessions = list(db.execute(query).scalars())
    counts = _submission_counts(db, [session.id for session in sessions])
    return [_response(session, counts.get(session.id, 0)) for session in sessions]


@admin_router.post(
    "", response_model=AssessmentSessionResponse, status_code=status.HTTP_201_CREATED
)
def create_session(
    payload: AssessmentSessionCreate,
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    admin = _require_admin(official)

    session = AssessmentSession(
        name=payload.name.strip(),
        description=_clean(payload.description),
        rules=_clean(payload.rules),
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        enabled=payload.enabled,
        allowed_tests=_validated_tests(payload.allowed_tests),
        region=_validated_region(payload.region),
        created_by=admin.id,
        updated_by=admin.id,
    )
    _check_window(session)

    db.add(session)
    db.commit()
    db.refresh(session)
    return _response(session, 0)


@admin_router.patch("/{session_id}", response_model=AssessmentSessionResponse)
def update_session(
    session_id: uuid.UUID,
    payload: AssessmentSessionUpdate,
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    """Edit a session, including opening or closing it (``enabled``)."""
    admin = _require_admin(official)
    session = _load(db, session_id)
    submissions = _submission_counts(db, [session.id]).get(session.id, 0)

    if payload.name is not None:
        session.name = payload.name.strip()
    if payload.description is not None:
        session.description = _clean(payload.description)
    if payload.rules is not None:
        session.rules = _clean(payload.rules)
    if payload.starts_at is not None:
        session.starts_at = payload.starts_at
    if payload.ends_at is not None:
        session.ends_at = payload.ends_at
    if payload.enabled is not None:
        session.enabled = payload.enabled
    if payload.clear_region:
        session.region = None
    elif payload.region is not None:
        session.region = _validated_region(payload.region)

    if payload.allowed_tests is not None:
        tests = _validated_tests(payload.allowed_tests)
        # Removing a test that athletes already submitted to would orphan
        # those official results from the session that defined them.
        removed = set(session.allowed_tests) - set(tests)
        if submissions and removed and _has_submissions_for(db, session.id, removed):
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                "Athletes have already submitted to a test being removed",
            )
        session.allowed_tests = tests

    _check_window(session)
    session.updated_by = admin.id

    db.add(session)
    db.commit()
    db.refresh(session)
    return _response(session, submissions)


@admin_router.delete("/{session_id}", response_model=MessageResponse)
def delete_session(
    session_id: uuid.UUID,
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    """Delete a session nobody has submitted to. One with submissions is part
    of the official record: close it (``enabled: false``) instead."""
    _require_admin(official)
    session = _load(db, session_id)

    if _submission_counts(db, [session.id]).get(session.id, 0):
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "Athletes have submitted to this session; disable it instead of deleting it",
        )

    db.delete(session)
    db.commit()
    return MessageResponse(message="Session deleted")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _is_admin(official: Official) -> bool:
    return _value(official.role) == OfficialRole.sai_admin.value


def _require_admin(official: Official | None) -> Official:
    if official is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            "Changing a session requires an identified official for the audit trail",
        )
    if not _is_admin(official):
        raise HTTPException(
            status.HTTP_403_FORBIDDEN, "Only an SAI admin can manage sessions"
        )
    return official


def _load(db: Session, session_id: uuid.UUID) -> AssessmentSession:
    session = db.get(AssessmentSession, session_id)
    if session is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Session not found")
    return session


def _validated_tests(codes: list[str]) -> list[str]:
    known = {test.value for test in TestType}
    cleaned: list[str] = []
    for code in codes:
        normalised = code.strip().upper()
        if normalised not in known:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY, f"Unknown test type: {code}"
            )
        if normalised not in cleaned:
            cleaned.append(normalised)
    return cleaned


def _validated_region(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    region = canonical_region(value)
    if region is None:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "Region must be an Indian state or union territory",
        )
    return region


def _check_window(session: AssessmentSession) -> None:
    if rules.aware(session.ends_at) <= rules.aware(session.starts_at):
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, "A session must end after it starts"
        )


def _submission_counts(db: Session, session_ids: list[uuid.UUID]) -> dict[uuid.UUID, int]:
    if not session_ids:
        return {}
    rows = db.execute(
        select(TestResult.session_id, func.count())
        .where(TestResult.session_id.in_(session_ids))
        .group_by(TestResult.session_id)
    ).all()
    return {session_id: count for session_id, count in rows}


def _has_submissions_for(db: Session, session_id: uuid.UUID, codes: set[str]) -> bool:
    return (
        db.execute(
            select(func.count())
            .select_from(TestResult)
            .join(Test, Test.id == TestResult.test_id)
            .where(TestResult.session_id == session_id, Test.code.in_(codes))
        ).scalar_one()
        > 0
    )


def _response(session: AssessmentSession, submissions: int) -> AssessmentSessionResponse:
    return AssessmentSessionResponse(
        id=session.id,
        name=session.name,
        description=session.description,
        rules=session.rules,
        starts_at=rules.aware(session.starts_at),
        ends_at=rules.aware(session.ends_at),
        enabled=session.enabled,
        allowed_tests=list(session.allowed_tests),
        region=session.region,
        status=rules.status_of(session, _now()).value,
        submission_count=submissions,
        created_at=rules.aware(session.created_at),
        updated_at=rules.aware(session.updated_at),
    )


def _unit(code: str) -> str:
    try:
        return TestType(code).unit
    except ValueError:
        return "reps"


def _clean(value: str | None) -> str | None:
    if value is None:
        return None
    stripped = value.strip()
    return stripped or None


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)

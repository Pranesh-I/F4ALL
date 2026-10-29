"""Finding what athletes should be told, from the state of the database.

Nothing in the submission, verification or review code calls this. The
notifier compares what the database says now with what it has already
recorded, so:

* a notification problem cannot fail a submission or a review — the code that
  does those never waits on it;
* every event is found the same way however the state changed (the API, the
  Celery worker, an official on the dashboard, a command-line repair);
* finding the same event twice is harmless: each has a ``dedupe_key``, unique
  in the database, and is stored once.

Result events come from a result's *current* status. A result that went from
uploaded to verified between two looks produces "checked by SAI" and not also
"received": the athlete is told where it stands, not every step it passed.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import Settings
from ..models import (
    AssessmentSession,
    Athlete,
    Notification,
    NotifierState,
    Test,
    TestResult,
    TestResultStatus,
)
from .messages import Event, render

logger = logging.getLogger(__name__)

RESULTS_CURSOR = "results"
# Each look re-reads this far behind the last one, so a change committed while
# the previous look was running is not missed. Dedupe keys absorb the overlap.
OVERLAP = timedelta(minutes=5)
KEY_BATCH = 500

RESULT_EVENTS: dict[TestResultStatus, tuple[str, str]] = {
    # status: (event, dedupe-key prefix)
    TestResultStatus.uploaded: (Event.SUBMISSION_RECEIVED, "received"),
    TestResultStatus.processing: (Event.SUBMISSION_RECEIVED, "received"),
    TestResultStatus.verified: (Event.VERIFICATION_COMPLETED, "verified"),
    TestResultStatus.flagged: (Event.SUBMISSION_FLAGGED, "flagged"),
    TestResultStatus.approved: (Event.SUBMISSION_APPROVED, "approved"),
    TestResultStatus.rejected: (Event.SUBMISSION_REJECTED, "rejected"),
    TestResultStatus.pending_sync: (Event.RESUBMISSION_REQUESTED, "resubmission"),
}


@dataclass(frozen=True)
class Draft:
    athlete_id: uuid.UUID
    language: str | None
    event: str
    dedupe_key: str
    data: dict = field(default_factory=dict)
    test_code: str | None = None
    session_name: str | None = None


def scan(db: Session, settings: Settings, now: datetime | None = None) -> int:
    """Find new events and store them as pending notifications. Returns how many."""
    now = now or datetime.now(UTC)
    since = _cursor(db, settings, now) - OVERLAP
    drafts = result_events(db, since) + session_events(db, settings, now)
    created = enqueue(db, drafts, now)
    _set_cursor(db, now)
    db.commit()
    return created


def result_events(db: Session, since: datetime) -> list[Draft]:
    """One event per result changed since ``since``, from its current status."""
    rows = db.execute(
        select(TestResult, Athlete.preferred_language, Test.code, AssessmentSession.name)
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .join(Test, Test.id == TestResult.test_id)
        .outerjoin(AssessmentSession, AssessmentSession.id == TestResult.session_id)
        .where(TestResult.updated_at >= since)
    ).all()

    drafts = []
    for result, language, code, session_name in rows:
        status = result.status
        status = (
            status if isinstance(status, TestResultStatus) else TestResultStatus(status)
        )
        event, prefix = RESULT_EVENTS[status]
        drafts.append(
            Draft(
                athlete_id=result.athlete_id,
                language=language,
                event=event,
                dedupe_key=f"{prefix}:{result.id}",
                data={"result_id": str(result.id), "test_type": code},
                test_code=code,
                session_name=session_name,
            )
        )
    return drafts


def session_events(db: Session, settings: Settings, now: datetime) -> list[Draft]:
    """ "Session open", then "closing soon", for every athlete the session is open
    to who still has tests to record in it."""
    sessions = (
        db.execute(
            select(AssessmentSession)
            .where(AssessmentSession.enabled.is_(True))
            .where(AssessmentSession.starts_at <= now)
            .where(AssessmentSession.ends_at > now)
        )
        .scalars()
        .all()
    )

    drafts: list[Draft] = []
    for session in sessions:
        athletes = select(Athlete.id, Athlete.preferred_language)
        if session.region:
            athletes = athletes.where(Athlete.region == session.region)
        done = _athletes_finished(db, session)
        # Someone who has already recorded every test needs neither message.
        eligible = [row for row in db.execute(athletes).all() if row[0] not in done]

        data = {"session_id": str(session.id), "session_name": session.name}
        drafts += [
            Draft(
                athlete_id=athlete_id,
                language=language,
                event=Event.SESSION_OPENED,
                dedupe_key=f"session_opened:{session.id}:{athlete_id}",
                data=data,
                session_name=session.name,
            )
            for athlete_id, language in eligible
        ]

        if now < _ending_soon_from(session, settings):
            continue
        drafts += [
            Draft(
                athlete_id=athlete_id,
                language=language,
                event=Event.SESSION_ENDING_SOON,
                dedupe_key=f"session_ending:{session.id}:{athlete_id}",
                data=data,
                session_name=session.name,
            )
            for athlete_id, language in eligible
        ]
    return drafts


def enqueue(db: Session, drafts: list[Draft], now: datetime) -> int:
    """Store the drafts not stored already. Safe to call with repeats."""
    unique = {draft.dedupe_key: draft for draft in drafts}
    keys = list(unique)
    existing: set[str] = set()
    for start in range(0, len(keys), KEY_BATCH):
        batch = keys[start : start + KEY_BATCH]
        existing.update(
            db.execute(
                select(Notification.dedupe_key).where(Notification.dedupe_key.in_(batch))
            ).scalars()
        )

    created = 0
    for key in keys:
        if key in existing:
            continue
        draft = unique[key]
        message = render(
            draft.event,
            draft.language,
            test_code=draft.test_code,
            session=draft.session_name,
        )
        row = Notification(
            athlete_id=draft.athlete_id,
            event_type=draft.event,
            dedupe_key=key,
            title=message.title,
            body=message.body,
            data={**draft.data, "event": draft.event},
            language=message.language,
            created_at=now,
            next_attempt_at=now,
            delivery_status="pending",
        )
        # A second notifier racing this one may have stored it a moment ago;
        # the unique key makes that a skipped row, not a duplicate.
        try:
            with db.begin_nested():
                db.add(row)
            created += 1
        except IntegrityError:
            logger.info("Notification %s was stored by another notifier", key)
    return created


def _ending_soon_from(session: AssessmentSession, settings: Settings) -> datetime:
    """When "closing soon" goes out: a set time before the close, or halfway
    through a session too short for that to mean anything."""
    starts, ends = _aware(session.starts_at), _aware(session.ends_at)
    window = min(timedelta(hours=settings.session_ending_soon_hours), (ends - starts) / 2)
    return ends - window


def _athletes_finished(db: Session, session: AssessmentSession) -> set[uuid.UUID]:
    """Athletes with a live submission for every test the session allows."""
    wanted = len(set(session.allowed_tests or []))
    rows = db.execute(
        select(TestResult.athlete_id)
        .where(TestResult.session_id == session.id)
        .where(TestResult.status != TestResultStatus.pending_sync)
        .group_by(TestResult.athlete_id)
        .having(func.count(func.distinct(TestResult.test_id)) >= wanted)
    ).scalars()
    return set(rows)


def _cursor(db: Session, settings: Settings, now: datetime) -> datetime:
    state = db.get(NotifierState, RESULTS_CURSOR)
    if state is None:
        return now - timedelta(hours=settings.notifier_first_run_lookback_hours)
    return _aware(state.value)


def _set_cursor(db: Session, now: datetime) -> None:
    state = db.get(NotifierState, RESULTS_CURSOR)
    if state is None:
        db.add(NotifierState(name=RESULTS_CURSOR, value=now))
    else:
        state.value = now


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=UTC)

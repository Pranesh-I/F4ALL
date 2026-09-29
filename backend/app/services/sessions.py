"""The rules of an assessment session, as pure functions.

Kept apart from the routers so every rule — who sees a session, what may be
submitted to it, and when — is tested directly, and so the athlete endpoint and
the submission endpoint cannot drift into disagreeing about them.
"""

from __future__ import annotations

import enum
from datetime import UTC, datetime, timedelta

from ..models import AssessmentSession, Athlete


class SessionStatus(str, enum.Enum):
    DISABLED = "disabled"
    SCHEDULED = "scheduled"
    ACTIVE = "active"
    ENDED = "ended"


def aware(moment: datetime) -> datetime:
    """SQLite returns naive datetimes; everything here is stored as UTC."""
    return moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)


def status_of(session: AssessmentSession, now: datetime) -> SessionStatus:
    if not session.enabled:
        return SessionStatus.DISABLED
    if now < aware(session.starts_at):
        return SessionStatus.SCHEDULED
    if now >= aware(session.ends_at):
        return SessionStatus.ENDED
    return SessionStatus.ACTIVE


def open_to(session: AssessmentSession, athlete: Athlete) -> bool:
    """A regional session is only for athletes registered in that region."""
    return session.region is None or session.region == athlete.region


def visible_to(session: AssessmentSession, athlete: Athlete, now: datetime) -> bool:
    return status_of(session, now) is SessionStatus.ACTIVE and open_to(session, athlete)


def submission_problem(
    session: AssessmentSession,
    athlete: Athlete,
    test_code: str,
    recorded_at: datetime | None,
    now: datetime,
    grace: timedelta,
) -> str | None:
    """Why an official attempt cannot be submitted to ``session``, or None.

    Offline-first: what must fall inside the session is the *recording*, not
    the upload. A phone that recorded during the session and found signal two
    days later is exactly the athlete this platform is for. The grace period
    bounds how late that upload may be; the recording time itself is checked
    against the window and against the server's clock.
    """
    if not session.enabled:
        return "This assessment session is not open"

    if not open_to(session, athlete):
        return "This assessment session is not open to your region"

    if test_code not in (session.allowed_tests or []):
        return "This test is not part of the assessment session"

    starts_at = aware(session.starts_at)
    ends_at = aware(session.ends_at)

    if now > ends_at + grace:
        return "The assessment session has closed"

    # Without a recording time only the arrival can be checked, so it must
    # arrive while the session is open. The phone always sends one.
    moment = aware(recorded_at) if recorded_at is not None else now
    if moment > now + CLOCK_TOLERANCE:
        return "The recording time is in the future"
    if moment < starts_at or moment >= ends_at:
        return "The attempt was not recorded during the assessment session"

    return None


# A phone's clock can run a little fast; beyond this it is not a clock error.
CLOCK_TOLERANCE = timedelta(minutes=10)

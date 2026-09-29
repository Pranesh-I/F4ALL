"""Consent: what an athlete, or their guardian, has agreed to.

Two separate purposes (see `ConsentPurpose`): holding the profile at all, and
keeping a face photo to compare against. The second is biometric processing,
and an athlete may refuse it and still practise; they cannot take official
tests without it, because those need the identity check.

Most athletes are minors. India's DPDP Act asks for a parent's consent for
anyone under 18, so a minor's consent must be given by a named guardian.
"""

from __future__ import annotations

from datetime import UTC, date, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Athlete, AthleteConsent, ConsentGiver, ConsentPurpose
from .benchmarks import age_on

ADULT_AGE_YEARS = 18


class ConsentProblem(ValueError):
    """A consent that cannot be accepted as given; the message is for the athlete."""


def parse_purpose(value: str) -> ConsentPurpose:
    try:
        return ConsentPurpose(value)
    except ValueError as exc:
        allowed = ", ".join(member.value for member in ConsentPurpose)
        raise ConsentProblem(f"Consent purpose must be one of: {allowed}") from exc


def validated_giver(
    dob: date, given_by: str, guardian_name: str | None
) -> tuple[ConsentGiver, str | None]:
    try:
        giver = ConsentGiver(given_by)
    except ValueError as exc:
        raise ConsentProblem("Consent must be given by 'self' or 'guardian'") from exc

    name = (guardian_name or "").strip() or None

    if age_on(dob) < ADULT_AGE_YEARS and giver is not ConsentGiver.guardian:
        raise ConsentProblem(
            "Athletes under 18 need a parent or guardian to give consent"
        )
    if giver is ConsentGiver.guardian and name is None:
        raise ConsentProblem("Give the name of the parent or guardian consenting")

    return giver, name if giver is ConsentGiver.guardian else None


def active(db: Session, athlete_id) -> dict[ConsentPurpose, AthleteConsent]:
    rows = db.execute(
        select(AthleteConsent).where(
            AthleteConsent.athlete_id == athlete_id,
            AthleteConsent.withdrawn_at.is_(None),
        )
    ).scalars()
    return {row.purpose: row for row in rows}


def has(db: Session, athlete_id, purpose: ConsentPurpose) -> bool:
    return purpose in active(db, athlete_id)


def grant(
    db: Session,
    athlete: Athlete,
    purpose: ConsentPurpose,
    *,
    version: str,
    given_by: str,
    guardian_name: str | None,
) -> AthleteConsent:
    """Record a consent, superseding any earlier one for the same purpose.

    Adds to the session without committing.
    """
    giver, name = validated_giver(athlete.dob, given_by, guardian_name)

    now = datetime.now(UTC)
    previous = active(db, athlete.id).get(purpose)
    if previous is not None:
        previous.withdrawn_at = now
        db.add(previous)

    consent = AthleteConsent(
        athlete_id=athlete.id,
        purpose=purpose,
        version=version.strip(),
        given_by=giver,
        guardian_name=name,
        given_at=now,
    )
    db.add(consent)
    return consent


def withdraw(db: Session, athlete_id, purpose: ConsentPurpose) -> bool:
    """End the consent in force for [purpose]; False when there was none."""
    current = active(db, athlete_id).get(purpose)
    if current is None:
        return False
    current.withdrawn_at = datetime.now(UTC)
    db.add(current)
    return True


def missing_for_official_tests(
    athlete: Athlete, purposes: set[ConsentPurpose]
) -> list[str]:
    """What still stands between this athlete and an official test, in the
    order the app asks for it."""
    missing = []
    if not athlete.city:
        missing.append("city")
    if ConsentPurpose.registration not in purposes:
        missing.append("registration_consent")
    if ConsentPurpose.face_verification not in purposes:
        missing.append("face_consent")
    if not athlete.reference_face_key:
        missing.append("photo")
    return missing

"""Athlete registration and profile.

Registration is the point at which two things the rest of the system depends on
finally exist:

* **A stored height.** Vertical jump cannot be re-scored server-side without it.
  Until now the phone sent it with each submission, which meant a client-supplied
  number was calibrating an official measurement. From here the profile is the
  source and the submission's copy is ignored.
* **A registration photo.** Sprint 6's identity check has been reporting "no
  registration photo on file" for every submission because there was nothing to
  compare against.
"""

from __future__ import annotations

import logging
import uuid
from datetime import date

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import Athlete, Gender, Test, TestResult, TestResultStatus
from ..regions import canonical_region
from ..schemas import (
    AthleteProfileResponse,
    AthleteProfileUpdate,
    AthleteRegistrationRequest,
    AthleteSummaryResponse,
    MessageResponse,
    PersonalBest,
    RegistrationResponse,
    TestHistoryItem,
    TokenResponse,
)
from ..security import phone_subject, registration_claim, require_athlete
from ..services import tokens as token_service
from ..services.benchmarks import age_on
from ..storage import get_storage

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/athletes", tags=["Athletes"])

# Youngest and oldest the platform will register. The lower bound matches the
# Khelo India assessment's own floor; the upper one is a sanity check, not a
# selection rule.
MIN_AGE_YEARS = 9
MAX_AGE_YEARS = 40

MAX_PHOTO_BYTES = 5 * 1024 * 1024
ALLOWED_PHOTO_TYPES = {"image/jpeg", "image/png", "image/webp"}


@router.post(
    "/register",
    response_model=RegistrationResponse,
    status_code=status.HTTP_201_CREATED,
)
def register(
    payload: AthleteRegistrationRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    phone: str = Depends(registration_claim),
):
    """Create a profile for a phone number that has verified an OTP.

    The phone comes from the token, never from the body — see
    `security.registration_claim`.
    """
    existing = db.execute(
        select(Athlete).where(Athlete.phone == phone)
    ).scalar_one_or_none()

    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An athlete is already registered with this phone number",
        )

    age = _validated_age(payload.dob)
    gender = _validated_gender(payload.gender)
    region = _validated_region(payload.region)

    athlete = Athlete(
        id=uuid.uuid4(),
        name=payload.name.strip(),
        dob=payload.dob,
        gender=gender,
        region=region,
        phone=phone,
        height_cm=payload.height_cm,
        weight_kg=payload.weight_kg,
    )
    db.add(athlete)
    db.commit()

    logger.info("Registered athlete %s in %s", athlete.id, athlete.region)

    # The registering session names the phone, not this athlete, so it cannot
    # upload or submit anything. Retire it and hand back a real athlete session
    # — otherwise the first request after registering fails as "unknown athlete".
    token_service.revoke_all_for_subject(db, phone_subject(phone))
    pair = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )

    return RegistrationResponse(
        profile=_profile(athlete, age),
        tokens=TokenResponse(
            access_token=pair.access_token,
            refresh_token=pair.refresh_token,
            expires_in=pair.expires_in,
            athlete_id=athlete.id,
            registered=True,
        ),
    )


@router.get("/me", response_model=AthleteProfileResponse)
def my_profile(athlete: Athlete = Depends(require_athlete)):
    return _profile(athlete, age_on(athlete.dob))


@router.patch("/me", response_model=AthleteProfileResponse)
def update_my_profile(
    payload: AthleteProfileUpdate,
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Update the mutable parts of a profile.

    Date of birth and gender are deliberately not editable here. Both select the
    benchmark cohort an athlete is judged against, so making them
    self-service — after results exist — would turn the comparison into
    something an athlete can shop around. Correcting them is a support action
    with an audit trail, which Sprint 8's dashboard is the right place for.
    """
    if payload.name is not None:
        athlete.name = payload.name.strip()
    if payload.region is not None:
        athlete.region = _validated_region(payload.region)
    if payload.height_cm is not None:
        athlete.height_cm = payload.height_cm
    if payload.weight_kg is not None:
        athlete.weight_kg = payload.weight_kg

    db.add(athlete)
    db.commit()

    return _profile(athlete, age_on(athlete.dob))


@router.post("/me/photo", response_model=MessageResponse)
async def upload_reference_photo(
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    athlete: Athlete = Depends(require_athlete),
):
    """Store the registration photo Sprint 6's identity check compares against.

    Kept in the same private, signed-URL-only storage as the videos. This is a
    photograph of a child's face held by a government platform; there is no
    version of this that belongs on a public URL.
    """
    if file.content_type not in ALLOWED_PHOTO_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"Photo must be one of: {', '.join(sorted(ALLOWED_PHOTO_TYPES))}",
        )

    content = await file.read()

    if not content:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Photo is empty"
        )

    if len(content) > MAX_PHOTO_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Photo must be under {MAX_PHOTO_BYTES // (1024 * 1024)}MB",
        )

    storage = get_storage(settings)
    key = f"reference-faces/{athlete.id}/{uuid.uuid4().hex}.jpg"
    storage.store_bytes(key, content, content_type=file.content_type)

    previous = athlete.reference_face_key
    athlete.reference_face_key = key
    db.add(athlete)
    db.commit()

    if previous and previous != key:
        # Retaining superseded face photos indefinitely would keep biometric
        # data nobody has a use for. Failure to delete must not fail the
        # request, though — the new photo is already stored and usable.
        try:
            storage.delete(previous)
        except Exception:
            logger.warning("Could not delete superseded photo %s", previous)

    return MessageResponse(message="Registration photo saved")


@router.get("/me/summary", response_model=AthleteSummaryResponse)
def my_summary(
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Profile, test history and personal bests."""
    rows = db.execute(
        select(TestResult, Test)
        .join(Test, Test.id == TestResult.test_id)
        .where(TestResult.athlete_id == athlete.id)
        .order_by(TestResult.created_at.desc())
    ).all()

    history = [
        TestHistoryItem(
            result_id=result.id,
            test_type=test.code,
            unit=test.unit,
            status=_value(result.status),
            provisional_score=_as_float(result.provisional_score),
            server_score=_as_float(result.server_score),
            final_score=_as_float(result.final_score),
            created_at=result.created_at,
        )
        for result, test in rows
    ]

    return AthleteSummaryResponse(
        profile=_profile(athlete, age_on(athlete.dob)),
        personal_bests=_personal_bests(rows),
        history=history,
        total_tests=len(history),
    )


def _personal_bests(rows) -> list[PersonalBest]:
    """Best score per test type.

    Only counts results the server has actually stood behind — a provisional
    score the phone reported is not an achievement, and a rejected or flagged
    attempt is certainly not one. `official` distinguishes an approved result
    from a merely verified one, because the athlete must never be shown a
    machine number in the place where an official one belongs.
    """
    best: dict[str, PersonalBest] = {}

    for result, test in rows:
        approved = result.status is TestResultStatus.approved
        verified = result.status is TestResultStatus.verified

        if approved and result.final_score is not None:
            score, official = float(result.final_score), True
        elif verified and result.server_score is not None:
            score, official = float(result.server_score), False
        else:
            continue

        current = best.get(test.code)
        if current is None or score > current.score:
            best[test.code] = PersonalBest(
                test_type=test.code,
                unit=test.unit,
                score=score,
                achieved_at=result.verified_at or result.created_at,
                official=official,
            )

    return sorted(best.values(), key=lambda item: item.test_type)


def _validated_age(dob: date) -> int:
    if dob > date.today():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Date of birth cannot be in the future",
        )

    age = age_on(dob)

    if age < MIN_AGE_YEARS or age > MAX_AGE_YEARS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"This platform registers athletes aged {MIN_AGE_YEARS} to "
                f"{MAX_AGE_YEARS}. The date of birth given works out to {age}."
            ),
        )

    return age


def _validated_region(value: str) -> str:
    region = canonical_region(value)
    if region is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Region must be an Indian state or union territory",
        )
    return region


def _validated_gender(value: str) -> Gender:
    try:
        return Gender(value.lower())
    except ValueError as exc:
        allowed = ", ".join(member.value for member in Gender)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Gender must be one of: {allowed}",
        ) from exc


def _profile(athlete: Athlete, age: int) -> AthleteProfileResponse:
    return AthleteProfileResponse(
        athlete_id=athlete.id,
        name=athlete.name,
        dob=athlete.dob,
        age_years=age,
        gender=_value(athlete.gender),
        region=athlete.region,
        phone=athlete.phone,
        height_cm=_as_float(athlete.height_cm),
        weight_kg=_as_float(athlete.weight_kg),
        has_reference_photo=bool(athlete.reference_face_key),
    )


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)


def _as_float(value) -> float | None:
    return float(value) if value is not None else None

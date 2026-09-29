"""The photo check an athlete takes before an official test.

The athlete takes a photo of their face; it is compared with the registration
photo and the outcome returned at once, so the app can offer a retake with
guidance ("face the light", "take off the cap") rather than a silent failure.

The outcome never blocks anything by itself. An athlete who cannot get a match
after retrying may still record, and the attempt reaches a reviewer with the
reason attached (see `verification/cheat/identity.py`). The photo is compared in
memory and discarded; only the outcome is kept.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    UploadFile,
    status,
)
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    AssessmentSession,
    Athlete,
    ConsentPurpose,
    IdentityCheck,
    IdentityCheckOutcome,
)
from ..schemas import IdentityCheckResponse
from ..security import require_athlete
from ..services import consents as consent_service
from ..services import identity_crypto
from ..storage import get_storage
from ..verification.cheat import face

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/athletes/me", tags=["Identity"])

MODELS_DIR = Path(__file__).resolve().parent.parent.parent / "models"

MAX_PHOTO_BYTES = 5 * 1024 * 1024
ALLOWED_PHOTO_TYPES = {"image/jpeg", "image/png", "image/webp"}

NO_PHOTO = "Add a registration photo before taking an official test"
NO_CONSENT = "Consent to face verification is needed before the photo check"


Comparison = tuple[IdentityCheckOutcome, float | None, str | None]


def compare(photo: bytes, reference: bytes) -> Comparison:
    """Outcome, similarity and (for `unavailable`) why. Replaced in tests."""
    try:
        comparison = face.compare_photos(photo, reference, MODELS_DIR)
    except face.NoFaceInPhoto as exc:
        return IdentityCheckOutcome.no_face, None, str(exc)
    except face.FaceCheckUnavailable as exc:
        return IdentityCheckOutcome.unavailable, None, str(exc)

    if comparison.matches:
        outcome = IdentityCheckOutcome.match
    else:
        outcome = IdentityCheckOutcome.no_match
    return outcome, comparison.similarity, None


@router.post(
    "/identity-checks",
    response_model=IdentityCheckResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_identity_check(
    file: UploadFile = File(...),
    captured_at_ms: int | None = Form(default=None),
    session_id: uuid.UUID | None = Form(default=None),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    athlete: Athlete = Depends(require_athlete),
):
    if not consent_service.has(db, athlete.id, ConsentPurpose.face_verification):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NO_CONSENT)
    if not athlete.reference_face_key:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NO_PHOTO)

    if file.content_type not in ALLOWED_PHOTO_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"Photo must be one of: {', '.join(sorted(ALLOWED_PHOTO_TYPES))}",
        )

    if session_id is not None and db.get(AssessmentSession, session_id) is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Unknown assessment session",
        )

    now = datetime.now(UTC)
    used = _checks_in_last_hour(db, athlete.id, now)
    if used >= settings.identity_checks_per_hour:
        # Enough for honest retakes; a cap stops anyone tuning a photo against
        # the matcher by trial and error.
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many photo checks. Please wait before trying again.",
            headers={"Retry-After": "3600"},
        )

    photo = await file.read()
    if not photo:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Photo is empty"
        )
    if len(photo) > MAX_PHOTO_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Photo must be under {MAX_PHOTO_BYTES // (1024 * 1024)}MB",
        )

    try:
        reference = identity_crypto.load_photo(
            get_storage(settings), settings, athlete.reference_face_key
        )
    except Exception as exc:
        logger.error("Could not read registration photo for %s: %s", athlete.id, exc)
        outcome, similarity, reason = (
            IdentityCheckOutcome.unavailable,
            None,
            "Registration photo could not be read",
        )
    else:
        outcome, similarity, reason = compare(photo, reference)

    # Neither photo outlives this request; only what the comparison concluded.
    del photo

    check = IdentityCheck(
        athlete_id=athlete.id,
        session_id=session_id,
        outcome=outcome,
        similarity=similarity,
        reason=reason,
        captured_at=_captured_at(captured_at_ms, now),
    )
    db.add(check)
    db.commit()

    return IdentityCheckResponse(
        check_id=check.id,
        outcome=outcome.value,
        remaining_this_hour=max(0, settings.identity_checks_per_hour - used - 1),
    )


def _checks_in_last_hour(db: Session, athlete_id, now: datetime) -> int:
    return db.execute(
        select(func.count(IdentityCheck.id)).where(
            IdentityCheck.athlete_id == athlete_id,
            IdentityCheck.created_at >= now - timedelta(hours=1),
        )
    ).scalar_one()


def _captured_at(captured_at_ms: int | None, now: datetime) -> datetime | None:
    """When the phone says the photo was taken; nonsense is dropped, not stored."""
    if captured_at_ms is None or captured_at_ms <= 0:
        return None
    captured = datetime.fromtimestamp(captured_at_ms / 1000, tz=UTC)
    if captured > now + timedelta(minutes=10):
        return None
    return captured

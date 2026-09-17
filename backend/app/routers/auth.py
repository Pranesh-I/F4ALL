"""Phone OTP authentication.

## The enumeration rule

`request-otp` behaves identically whether or not the number belongs to a
registered athlete, and `verify-otp` succeeds for an unknown number too —
returning a valid token with `registered: false` so the client can send the
caller into registration.

The alternative, refusing an unregistered number, turns this endpoint into an
oracle for "does this person have an account on the SAI talent platform". That
is a question about minors that anyone with a phone number could ask, and it
costs nothing to refuse to answer it.

It also happens to be the better flow: registration is then an authenticated
call made by someone who has already proved they hold the phone.
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import Athlete
from ..schemas import (
    LogoutRequest,
    MessageResponse,
    RefreshRequest,
    RequestOtpRequest,
    RequestOtpResponse,
    TokenResponse,
    VerifyOtpRequest,
)
from ..security import (
    REGISTERING_ROLE,
    decode_token,
    phone_subject,
    require_athlete,
)
from ..services import tokens as token_service
from ..services.otp import OtpDeliveryUnavailable, OtpService, RateLimited
from ..services.sms import SmsError

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/auth", tags=["Authentication"])


@router.post("/request-otp", response_model=RequestOtpResponse)
def request_otp(
    payload: RequestOtpRequest,
    response: Response,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    phone = _normalise(payload.phone)
    service = OtpService(db, settings)

    try:
        issued = service.request(phone)
    except RateLimited as exc:
        # Retry-After is the difference between a client that backs off and one
        # that hammers the endpoint until the athlete gives up.
        response.headers["Retry-After"] = str(exc.retry_after_seconds)
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=str(exc),
            headers={"Retry-After": str(exc.retry_after_seconds)},
        ) from exc
    except (OtpDeliveryUnavailable, SmsError) as exc:
        logger.error("OTP delivery failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Could not send the verification code. Please try again shortly.",
        ) from exc

    return RequestOtpResponse(
        message="Verification code sent",
        expires_at=issued.expires_at,
        development_code=issued.development_code,
    )


@router.post("/verify-otp", response_model=TokenResponse)
def verify_otp(
    payload: VerifyOtpRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    phone = _normalise(payload.phone)

    if not OtpService(db, settings).verify(phone, payload.otp):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid or expired code",
        )

    athlete = db.execute(
        select(Athlete).where(Athlete.phone == phone)
    ).scalar_one_or_none()

    if athlete is None:
        # A verified phone with no profile. The token's subject is the phone
        # rather than an athlete id, and only /api/athletes/register accepts it.
        pair = token_service.issue_token_pair(
            db,
            subject_id=phone_subject(phone),
            role=REGISTERING_ROLE,
            settings=settings,
            claims={"phone": phone},
        )
        return TokenResponse(
            access_token=pair.access_token,
            refresh_token=pair.refresh_token,
            expires_in=pair.expires_in,
            athlete_id=None,
            registered=False,
        )

    pair = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )

    return TokenResponse(
        access_token=pair.access_token,
        refresh_token=pair.refresh_token,
        expires_in=pair.expires_in,
        athlete_id=athlete.id,
        registered=True,
    )


@router.post("/refresh", response_model=TokenResponse)
def refresh(
    payload: RefreshRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    try:
        pair = token_service.rotate_refresh_token(db, payload.refresh_token, settings)
    except token_service.InvalidRefreshToken as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)
        ) from exc

    # The rotated token keeps the subject of the one it replaced. A registering
    # session is revoked at registration, so it never reaches here afterwards.
    subject_id = _subject_of(pair.access_token, settings)
    athlete = (
        db.get(Athlete, subject_id) if subject_id is not None else None
    )

    return TokenResponse(
        access_token=pair.access_token,
        refresh_token=pair.refresh_token,
        expires_in=pair.expires_in,
        athlete_id=athlete.id if athlete else None,
        registered=athlete is not None,
    )


@router.post("/logout", response_model=MessageResponse)
def logout(
    payload: LogoutRequest,
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Revoke refresh tokens.

    `all_devices` exists because a phone in this context is often shared, sold
    on, or lost, and the athlete may have no way to reach the device that still
    holds a token.
    """
    if payload.all_devices:
        revoked = token_service.revoke_all_for_subject(db, athlete.id)
        return MessageResponse(message=f"Signed out of {revoked} device(s)")

    if payload.refresh_token:
        token_service.revoke_refresh_token(db, payload.refresh_token)

    # Always the same message. Whether a particular token was live is not
    # information a caller needs, and reporting it distinguishes a valid token
    # from an invalid one for anyone probing.
    return MessageResponse(message="Signed out")


def _normalise(phone: str) -> str:
    """Strip formatting so one number cannot hold several challenge streams.

    Without this, "+91 99999 99999" and "9999999999" are different rows, and the
    per-phone rate limit counts each separately — which is a rate limit that can
    be walked straight around by adding a space.
    """
    cleaned = "".join(character for character in phone if character.isdigit())

    # Indian numbers are ten digits; the country code is optional on input and
    # dropped so both forms collapse to one identity.
    if len(cleaned) == 12 and cleaned.startswith("91"):
        cleaned = cleaned[2:]

    return cleaned


def _subject_of(access_token: str, settings: Settings) -> uuid.UUID | None:
    try:
        return uuid.UUID(str(decode_token(access_token, settings).get("sub")))
    except (TypeError, ValueError):
        return None

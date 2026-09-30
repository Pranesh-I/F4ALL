"""Sign-in for SAI officials.

Email and password, not phone OTP. Officials sign in from office machines,
and the accounts are provisioned by an administrator (`app.cli create-official`)
rather than self-registered — nobody should be able to make themselves a
reviewer of children's results.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import Official, RefreshToken
from ..schemas import (
    LogoutRequest,
    MessageResponse,
    OfficialLoginRequest,
    OfficialProfileResponse,
    OfficialTokenResponse,
    RefreshRequest,
)
from ..security import OFFICIAL_ROLES, current_official
from ..services import tokens as token_service
from ..services.passwords import DUMMY_HASH, verify_password

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/dashboard/auth", tags=["Dashboard auth"])

MAX_FAILED_ATTEMPTS = 5
LOCKOUT_MINUTES = 15

INVALID_CREDENTIALS = "Incorrect email or password"


@router.post("/login", response_model=OfficialTokenResponse)
def login(
    payload: OfficialLoginRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    now = datetime.now(UTC)
    email = payload.email.strip().lower()

    official = db.execute(
        select(Official).where(func.lower(Official.email) == email)
    ).scalar_one_or_none()

    if official is None:
        # Spend the same scrypt time as a real check, so response timing does
        # not reveal which email addresses belong to officials.
        verify_password(payload.password, DUMMY_HASH)
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, INVALID_CREDENTIALS)

    locked_until = _aware(official.locked_until)
    if locked_until is not None and locked_until > now:
        # Checked BEFORE the password, and the password is not evaluated at
        # all while locked — otherwise a locked account still answers "right"
        # or "wrong" to anyone who keeps guessing.
        minutes = max(1, int((locked_until - now).total_seconds() // 60) + 1)
        raise HTTPException(
            status.HTTP_423_LOCKED,
            f"Too many failed sign-ins. Try again in {minutes} minute(s).",
        )

    if not official.is_active or not verify_password(
        payload.password, official.password_hash
    ):
        if official.is_active:
            official.failed_login_attempts += 1
            if official.failed_login_attempts >= MAX_FAILED_ATTEMPTS:
                official.locked_until = now + timedelta(minutes=LOCKOUT_MINUTES)
                official.failed_login_attempts = 0
                logger.warning(
                    "Official account %s locked after failed sign-ins", official.id
                )
            db.add(official)
            db.commit()
        # Same message for a wrong password and a deactivated account: whether
        # an account was disabled is not something to tell a stranger.
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, INVALID_CREDENTIALS)

    official.failed_login_attempts = 0
    official.locked_until = None
    db.add(official)
    db.commit()

    return _token_response(db, official, settings)


@router.post("/refresh", response_model=OfficialTokenResponse)
def refresh(
    payload: RefreshRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    # Peek at the stored role before rotating. An athlete refresh token must
    # not be exchangeable for anything on the dashboard.
    record = db.execute(
        select(RefreshToken).where(
            RefreshToken.token_hash
            == token_service.hash_refresh_token(payload.refresh_token)
        )
    ).scalar_one_or_none()

    if record is None or record.subject_role not in OFFICIAL_ROLES:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid refresh token")

    official = db.get(Official, record.subject_id)
    if official is None or not official.is_active:
        token_service.revoke_all_for_subject(db, record.subject_id)
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Account is not active")

    try:
        pair = token_service.rotate_refresh_token(db, payload.refresh_token, settings)
    except token_service.InvalidRefreshToken as exc:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, str(exc)) from exc

    return OfficialTokenResponse(
        access_token=pair.access_token,
        refresh_token=pair.refresh_token,
        expires_in=pair.expires_in,
        official=official_profile(official),
    )


@router.get("/me", response_model=OfficialProfileResponse)
def me(official: Official = Depends(current_official)):
    return official_profile(official)


@router.post("/logout", response_model=MessageResponse)
def logout(
    payload: LogoutRequest,
    db: Session = Depends(get_db),
    official: Official = Depends(current_official),
):
    """Revoke this browser's refresh token, or every one the official holds.

    Clearing the tab's storage alone leaves a refresh token that still works
    for 90 days if it was copied, and these accounts approve children's
    results. The access token lapses on its own within the hour.
    """
    if payload.all_devices:
        revoked = token_service.revoke_all_for_subject(db, official.id)
        return MessageResponse(message=f"Signed out of {revoked} session(s)")

    if payload.refresh_token:
        record = db.execute(
            select(RefreshToken).where(
                RefreshToken.token_hash
                == token_service.hash_refresh_token(payload.refresh_token)
            )
        ).scalar_one_or_none()
        # Only this official's own token; someone else's is left alone.
        if record is not None and record.subject_id == official.id:
            token_service.revoke_refresh_token(db, payload.refresh_token)

    # Same answer whether or not the token was live, as for athletes.
    return MessageResponse(message="Signed out")


def _token_response(
    db: Session, official: Official, settings: Settings
) -> OfficialTokenResponse:
    pair = token_service.issue_token_pair(
        db,
        subject_id=official.id,
        role=_value(official.role),
        settings=settings,
    )
    return OfficialTokenResponse(
        access_token=pair.access_token,
        refresh_token=pair.refresh_token,
        expires_in=pair.expires_in,
        official=official_profile(official),
    )


def official_profile(official: Official) -> OfficialProfileResponse:
    return OfficialProfileResponse(
        official_id=official.id,
        name=official.name,
        email=official.email,
        role=_value(official.role),
        region=official.region,
    )


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)


def _aware(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)

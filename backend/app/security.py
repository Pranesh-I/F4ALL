"""Authentication.

Sprint 7 owns the real OTP flow. What exists here is the verification half: the
middleware that reads a bearer token, validates it, and resolves the athlete.
Building it now means every endpoint is written against a real dependency rather
than being retrofitted later, which is how auth gaps get shipped.

``allow_unauthenticated`` lets the mobile app talk to a dev server before tokens
exist. It fails closed — ``Settings.unauthenticated_allowed`` returns False for
any production or staging environment regardless of how the flag is set.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta

import jwt
from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import Settings, get_settings
from .database import get_db
from .models import Athlete, Official

logger = logging.getLogger(__name__)

BEARER_PREFIX = "bearer "


# A token issued to a phone that verified an OTP but has no profile yet. It is
# accepted by exactly one endpoint — athlete registration — and nowhere else.
REGISTERING_ROLE = "registering"

OFFICIAL_ROLES = {"sai_admin", "regional_reviewer"}


def phone_subject(phone: str) -> uuid.UUID:
    """A stable token subject for a verified phone that has no profile yet.

    Derived from the number so the same phone always yields the same subject,
    which is what lets registration find and revoke the registering session.
    """
    return uuid.uuid5(uuid.NAMESPACE_OID, f"f4all-phone:{phone}")


def create_access_token(
    subject: str,
    settings: Settings,
    *,
    role: str = "athlete",
    claims: dict | None = None,
) -> str:
    now = datetime.now(UTC)
    payload = {
        "sub": subject,
        "role": role,
        "iat": now,
        "exp": now + timedelta(minutes=settings.jwt_expiry_minutes),
    }
    if claims:
        payload.update(claims)
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_token(token: str, settings: Settings) -> dict:
    try:
        return jwt.decode(
            token, settings.jwt_secret, algorithms=[settings.jwt_algorithm]
        )
    except jwt.ExpiredSignatureError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Token expired"
        ) from exc
    except jwt.InvalidTokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token"
        ) from exc


def _bearer_token(request: Request) -> str | None:
    header = request.headers.get("Authorization", "")
    if header.lower().startswith(BEARER_PREFIX):
        return header[len(BEARER_PREFIX):].strip()
    return None


def current_athlete(
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Athlete | None:
    """Resolve the calling athlete.

    Returns None only when unauthenticated access is permitted, which is never
    the case in a deployed environment. Endpoints that must have an athlete use
    [require_athlete] instead.
    """
    token = _bearer_token(request)

    if token is None:
        if settings.unauthenticated_allowed:
            return None
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
        )

    payload = decode_token(token, settings)
    subject = payload.get("sub")

    try:
        athlete_id = uuid.UUID(str(subject))
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token subject"
        ) from exc

    athlete = db.get(Athlete, athlete_id)
    if athlete is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Unknown athlete"
        )
    return athlete


def require_athlete(
    athlete: Athlete | None = Depends(current_athlete),
) -> Athlete:
    if athlete is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
        )
    return athlete


def current_official(
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> Official | None:
    """Resolve the calling official for dashboard endpoints.

    Region scoping in Sprint 8 depends on this returning the right person: a
    regional reviewer must never see another region's athletes.
    """
    token = _bearer_token(request)

    if token is None:
        if settings.unauthenticated_allowed:
            return None
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
        )

    payload = decode_token(token, settings)

    if payload.get("role") not in OFFICIAL_ROLES:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dashboard access requires an official account",
        )

    try:
        official_id = uuid.UUID(str(payload.get("sub")))
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token subject"
        ) from exc

    official = db.execute(
        select(Official).where(Official.id == official_id)
    ).scalar_one_or_none()

    if official is None or not official.is_active:
        # A deactivated official's unexpired access token stops working here,
        # immediately, rather than when it happens to expire.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Unknown official"
        )
    return official


def registration_claim(
    request: Request,
    settings: Settings = Depends(get_settings),
) -> str:
    """The phone number a `registering` token was issued for.

    Registration binds the new profile to this claim rather than to a phone in
    the request body. Trusting the body would let anyone with a token for their
    own number create an account against someone else's — and phone number is
    the identity this whole system hangs off.
    """
    token = _bearer_token(request)
    if token is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Verify your phone number first",
        )

    payload = decode_token(token, settings)

    if payload.get("role") != REGISTERING_ROLE:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This token cannot be used to register",
        )

    phone = payload.get("phone")
    if not phone:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token is missing its phone claim",
        )

    return str(phone)

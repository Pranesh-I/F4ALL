"""OTP authentication endpoints.

Sprint 7 owns this properly — real OTP delivery, rate limiting, expiry. What is
here keeps the contract implemented and lets the token path be exercised
end to end, while refusing to pretend it is secure.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import Athlete
from ..schemas import AuthResponse, MessageResponse, RequestOtpRequest, VerifyOtpRequest
from ..security import create_access_token

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/auth", tags=["Authentication"])


@router.post("/request-otp", response_model=MessageResponse)
def request_otp(
    payload: RequestOtpRequest,
    settings: Settings = Depends(get_settings),
):
    if settings.is_production:
        # Better a clear 501 than an endpoint that looks like it authenticates
        # and does not.
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail="OTP delivery is implemented in Sprint 7",
        )

    logger.info("Development OTP requested for %s", payload.phone)
    return MessageResponse(
        message="Development mode: use OTP 000000. Real delivery lands in Sprint 7."
    )


@router.post("/verify-otp", response_model=AuthResponse)
def verify_otp(
    payload: VerifyOtpRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    if settings.is_production:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail="OTP verification is implemented in Sprint 7",
        )

    if payload.otp != DEVELOPMENT_OTP:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid or expired OTP"
        )

    athlete = db.execute(
        select(Athlete).where(Athlete.phone == payload.phone)
    ).scalar_one_or_none()

    if athlete is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No athlete registered with that phone number",
        )

    return AuthResponse(
        access_token=create_access_token(str(athlete.id), settings),
        athlete_id=athlete.id,
    )


DEVELOPMENT_OTP = "000000"

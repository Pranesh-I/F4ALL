"""Serves media behind signed, expiring URLs.

Videos: development only. In production they live in S3 and the dashboard
receives presigned S3 URLs directly; this router refuses to serve them there.

Registration photos: every environment. They are encrypted before storage, so a
presigned S3 URL would hand the browser ciphertext; they are decrypted here,
for an official holding a short-lived signed link, and never cached.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import jwt
from fastapi import APIRouter, Depends, HTTPException, Response, status
from fastapi.responses import FileResponse

from ..config import Settings, get_settings
from ..services import identity_crypto
from ..storage import MEDIA_TOKEN_PURPOSE, LocalStorage, get_storage

IDENTITY_TOKEN_PURPOSE = "identity-photo"

router = APIRouter(prefix="/api/media", tags=["Media"], include_in_schema=False)

CONTENT_TYPES = {
    ".mp4": "video/mp4",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".json": "application/json",
}


def identity_photo_url(settings: Settings, key: str, expires_seconds: int = 900) -> str:
    """A short-lived link to a registration photo, for the review dashboard."""
    token = jwt.encode(
        {
            "key": key,
            "purpose": IDENTITY_TOKEN_PURPOSE,
            "exp": datetime.now(UTC) + timedelta(seconds=expires_seconds),
        },
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    return f"{settings.public_base_url.rstrip('/')}/api/media/identity/{token}"


@router.get("/identity/{token}")
def serve_identity_photo(token: str, settings: Settings = Depends(get_settings)):
    claims = _claims(token, settings, IDENTITY_TOKEN_PURPOSE)
    try:
        photo = identity_crypto.load_photo(
            get_storage(settings), settings, str(claims["key"])
        )
    except (FileNotFoundError, ValueError, identity_crypto.IdentityDecryptError) as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND) from exc

    return Response(
        content=photo,
        media_type="image/jpeg",
        headers={"Cache-Control": "private, no-store"},
    )


def _claims(token: str, settings: Settings, purpose: str) -> dict:
    try:
        claims = jwt.decode(
            token, settings.jwt_secret, algorithms=[settings.jwt_algorithm]
        )
    except jwt.InvalidTokenError as exc:
        # Expired and forged look identical from outside: both are just gone.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND) from exc

    # An access token is also a JWT signed with this secret. Without the purpose
    # check, any athlete's login token would be accepted here as a media URL.
    if claims.get("purpose") != purpose or not claims.get("key"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)
    return claims


@router.get("/{token}")
def serve_media(token: str, settings: Settings = Depends(get_settings)):
    storage = get_storage(settings)

    if settings.is_production or not isinstance(storage, LocalStorage):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    claims = _claims(token, settings, MEDIA_TOKEN_PURPOSE)

    try:
        path = storage.path_for(str(claims["key"]))
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND) from exc

    if not path.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    return FileResponse(
        path,
        media_type=CONTENT_TYPES.get(path.suffix.lower(), "application/octet-stream"),
        headers={"Cache-Control": "private, no-store"},
    )

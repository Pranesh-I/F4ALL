"""Serves locally-stored media behind signed, expiring URLs.

Development only. In production media lives in S3 and the dashboard receives
presigned S3 URLs directly; this router refuses to serve anything there.
"""

from __future__ import annotations

import jwt
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import FileResponse

from ..config import Settings, get_settings
from ..storage import MEDIA_TOKEN_PURPOSE, LocalStorage, get_storage

router = APIRouter(prefix="/api/media", tags=["Media"], include_in_schema=False)

CONTENT_TYPES = {
    ".mp4": "video/mp4",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".json": "application/json",
}


@router.get("/{token}")
def serve_media(token: str, settings: Settings = Depends(get_settings)):
    storage = get_storage(settings)

    if settings.is_production or not isinstance(storage, LocalStorage):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

    try:
        claims = jwt.decode(
            token, settings.jwt_secret, algorithms=[settings.jwt_algorithm]
        )
    except jwt.InvalidTokenError as exc:
        # Expired and forged look identical from outside: both are just gone.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND) from exc

    # An access token is also a JWT signed with this secret. Without the purpose
    # check, any athlete's login token would be accepted here as a media URL.
    if claims.get("purpose") != MEDIA_TOKEN_PURPOSE or not claims.get("key"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)

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

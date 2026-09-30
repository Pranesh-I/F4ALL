"""Chunked resumable video upload endpoints.

Server half of the protocol ``UploadClient.kt`` speaks. Paths and payloads match
the ``/api/videos/upload/...`` section of docs/openapi.yaml exactly.
"""

from __future__ import annotations

import logging
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..errors import ApiError
from ..models import Athlete, Video
from ..schemas import (
    UploadChunkResponse,
    UploadCompleteResponse,
    UploadInitRequest,
    UploadSessionResponse,
)
from ..security import current_athlete
from ..services import uploads
from ..storage import build_object_key, get_storage

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/videos/upload", tags=["Videos"])


def _as_http(error: uploads.UploadError) -> HTTPException:
    return ApiError(error.status_code, error.code, error.message)


def _session_response(session) -> UploadSessionResponse:
    return UploadSessionResponse(
        upload_id=session.id,
        chunk_size_bytes=session.chunk_size_bytes,
        received_chunks=sorted(session.received_set()),
        expires_at=session.expires_at,
    )


@router.post("/init", response_model=UploadSessionResponse)
def init_upload(
    payload: UploadInitRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    athlete: Athlete | None = Depends(current_athlete),
):
    try:
        session = uploads.create_session(
            db,
            settings,
            test_code=payload.test_type.upper(),
            file_size_bytes=payload.file_size_bytes,
            checksum_sha256=payload.checksum_sha256,
            chunk_size_bytes=payload.chunk_size_bytes,
            content_type=payload.content_type,
            athlete_id=athlete.id if athlete else None,
        )
    except uploads.UploadError as error:
        raise _as_http(error) from error

    return _session_response(session)


@router.get("/{upload_id}", response_model=UploadSessionResponse)
def get_upload_status(
    upload_id: str,
    db: Session = Depends(get_db),
    _: Athlete | None = Depends(current_athlete),
):
    """Which chunks the server actually holds.

    The client resumes from this rather than from its own record of what it
    sent — a chunk that left the device but never landed is exactly the case
    resumption exists for.
    """
    try:
        session = uploads.get_session(db, upload_id)
    except uploads.UploadError as error:
        raise _as_http(error) from error

    return _session_response(session)


@router.put(
    "/{upload_id}/chunks/{index}",
    response_model=UploadChunkResponse,
)
async def upload_chunk(
    upload_id: str,
    index: int,
    request: Request,
    db: Session = Depends(get_db),
    _: Athlete | None = Depends(current_athlete),
):
    try:
        session = uploads.get_session(db, upload_id)
    except uploads.UploadError as error:
        raise _as_http(error) from error

    data = await request.body()

    try:
        session = uploads.store_chunk(db, session, index, data)
    except uploads.UploadError as error:
        raise _as_http(error) from error

    received = session.received_set()
    missing = sorted(set(range(session.total_chunks)) - received)

    return UploadChunkResponse(
        received_chunks_count=len(received),
        next_expected_index=missing[0] if missing else None,
    )


@router.post("/{upload_id}/complete", response_model=UploadCompleteResponse)
def complete_upload(
    upload_id: str,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    _: Athlete | None = Depends(current_athlete),
):
    try:
        session = uploads.get_session(db, upload_id)
    except uploads.UploadError as error:
        raise _as_http(error) from error

    # Completing twice must not create a second video row. The mobile client
    # retries after a dropped response, and it cannot tell "the response was
    # lost" apart from "the request never arrived".
    if session.completed and session.video_id:
        return UploadCompleteResponse(video_id=session.video_id, checksum_verified=True)

    try:
        assembled_path, actual_checksum = uploads.assemble(session)
        uploads.verify_checksum(session, actual_checksum)
    except uploads.UploadError as error:
        logger.warning("Upload %s failed to finalize: %s", upload_id, error.message)
        raise _as_http(error) from error

    video = Video(
        s3_key="",
        checksum_sha256=actual_checksum,
        file_size_bytes=session.file_size_bytes,
    )
    db.add(video)
    db.flush()

    key = build_object_key(
        athlete_id=str(session.athlete_id) if session.athlete_id else None,
        test_code=session.test_code,
        video_id=str(video.id),
    )

    storage = get_storage(settings)
    storage.store(Path(assembled_path), key)

    video.s3_key = key
    session.completed = True
    session.video_id = video.id

    db.add(video)
    db.add(session)
    db.commit()
    db.refresh(video)

    # Staging chunks are only removed once the video is durably stored.
    uploads.cleanup(session)

    logger.info("Upload %s completed as video %s", upload_id, video.id)

    return UploadCompleteResponse(video_id=video.id, checksum_verified=True)

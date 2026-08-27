"""Chunked, resumable upload receiver.

Server half of the protocol the mobile client in Sprint 4 already implements
(``UploadClient.kt``) and that Sprint 4 added to ``docs/openapi.yaml``.

The contract that makes resumption work: this module is the authority on which
chunks exist. The client asks, and re-sends only what is genuinely missing. It
never gets to assert what it already sent.
"""

from __future__ import annotations

import hashlib
import logging
import secrets
import shutil
from datetime import UTC, datetime, timedelta
from pathlib import Path

from sqlalchemy.orm import Session

from ..config import Settings
from ..models import UploadSession

logger = logging.getLogger(__name__)


class UploadError(Exception):
    """Base for upload failures, carrying the HTTP status to surface."""

    status_code = 400

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class UploadTooLarge(UploadError):
    status_code = 413


class UploadNotFound(UploadError):
    status_code = 404


class ChunkOutOfRange(UploadError):
    status_code = 409


class UploadIncomplete(UploadError):
    status_code = 400


class ChecksumMismatch(UploadError):
    # 422, matching what UploadClient.kt treats as permanently non-retryable:
    # re-sending the same corrupt source cannot fix it.
    status_code = 422


def _chunk_path(staging_dir: Path, index: int) -> Path:
    return staging_dir / f"chunk_{index:06d}"


def total_chunks_for(file_size_bytes: int, chunk_size_bytes: int) -> int:
    if file_size_bytes <= 0:
        return 0
    return (file_size_bytes + chunk_size_bytes - 1) // chunk_size_bytes


def create_session(
    db: Session,
    settings: Settings,
    *,
    test_code: str,
    file_size_bytes: int,
    checksum_sha256: str,
    chunk_size_bytes: int | None = None,
    content_type: str = "video/mp4",
    athlete_id=None,
) -> UploadSession:
    if file_size_bytes <= 0:
        raise UploadError("file_size_bytes must be positive")

    if file_size_bytes > settings.upload_max_file_bytes:
        raise UploadTooLarge(
            f"File exceeds the {settings.upload_max_file_bytes} byte limit"
        )

    # The server decides the chunk size. The client proposes one, but honouring
    # an arbitrary client value would let a bad client request 1-byte chunks and
    # turn one upload into millions of round trips.
    effective_chunk_size = settings.upload_chunk_size_bytes
    if chunk_size_bytes and 0 < chunk_size_bytes <= settings.upload_chunk_size_bytes:
        effective_chunk_size = chunk_size_bytes

    upload_id = f"up_{secrets.token_hex(12)}"
    staging_dir = Path(settings.upload_staging_path) / upload_id
    staging_dir.mkdir(parents=True, exist_ok=True)

    session = UploadSession(
        id=upload_id,
        athlete_id=athlete_id,
        test_code=test_code,
        file_size_bytes=file_size_bytes,
        chunk_size_bytes=effective_chunk_size,
        total_chunks=total_chunks_for(file_size_bytes, effective_chunk_size),
        checksum_sha256=checksum_sha256.lower(),
        content_type=content_type,
        received_chunks="",
        staging_path=str(staging_dir),
        expires_at=datetime.now(UTC)
        + timedelta(hours=settings.upload_session_ttl_hours),
    )

    db.add(session)
    db.commit()
    db.refresh(session)

    logger.info(
        "Created upload %s: %d bytes in %d chunks",
        upload_id,
        file_size_bytes,
        session.total_chunks,
    )
    return session


def get_session(db: Session, upload_id: str) -> UploadSession:
    session = db.get(UploadSession, upload_id)
    if session is None:
        raise UploadNotFound(f"Upload session {upload_id} not found")

    expires_at = session.expires_at
    if expires_at.tzinfo is None:
        # SQLite hands back naive datetimes; treat stored values as UTC.
        expires_at = expires_at.replace(tzinfo=UTC)

    if expires_at < datetime.now(UTC) and not session.completed:
        raise UploadNotFound(f"Upload session {upload_id} has expired")

    return session


def store_chunk(
    db: Session, session: UploadSession, index: int, data: bytes
) -> UploadSession:
    if index < 0 or index >= session.total_chunks:
        raise ChunkOutOfRange(
            f"Chunk {index} is outside 0..{session.total_chunks - 1}"
        )

    expected = expected_chunk_size(session, index)
    if len(data) != expected:
        # A short chunk usually means a proxy truncated it. Accepting it would
        # produce a file that only fails at the final checksum, after the
        # athlete has paid for every byte.
        raise UploadError(
            f"Chunk {index} is {len(data)} bytes, expected {expected}"
        )

    staging_dir = Path(session.staging_path)
    staging_dir.mkdir(parents=True, exist_ok=True)

    # Written to a temporary name first: a chunk half-written when the process
    # dies must not be recorded as received.
    target = _chunk_path(staging_dir, index)
    temporary = target.with_suffix(".partial")
    temporary.write_bytes(data)
    temporary.replace(target)

    received = session.received_set()
    received.add(index)
    session.set_received(received)

    db.add(session)
    db.commit()
    db.refresh(session)
    return session


def expected_chunk_size(session: UploadSession, index: int) -> int:
    offset = index * session.chunk_size_bytes
    return min(session.chunk_size_bytes, session.file_size_bytes - offset)


def assemble(session: UploadSession) -> tuple[Path, str]:
    """Concatenate the chunks and hash the result.

    Returns the assembled path and its SHA-256. The hash is computed over what
    was actually assembled, never trusted from the client — that is the whole
    point of verifying it.
    """
    if not session.is_complete():
        missing = sorted(
            set(range(session.total_chunks)) - session.received_set()
        )
        raise UploadIncomplete(
            f"{len(missing)} chunk(s) still missing, first is {missing[0]}"
        )

    staging_dir = Path(session.staging_path)
    assembled = staging_dir / "assembled.mp4"

    digest = hashlib.sha256()

    with assembled.open("wb") as output:
        for index in range(session.total_chunks):
            chunk_file = _chunk_path(staging_dir, index)
            if not chunk_file.exists():
                raise UploadIncomplete(f"Chunk {index} is recorded but missing on disk")
            data = chunk_file.read_bytes()
            output.write(data)
            digest.update(data)

    return assembled, digest.hexdigest()


def verify_checksum(session: UploadSession, actual_sha256: str) -> None:
    if actual_sha256.lower() != session.checksum_sha256.lower():
        raise ChecksumMismatch(
            "Assembled video does not match the checksum declared by the device"
        )


def cleanup(session: UploadSession) -> None:
    """Remove staging chunks once the video is safely stored."""
    staging_dir = Path(session.staging_path)
    if staging_dir.exists():
        shutil.rmtree(staging_dir, ignore_errors=True)

"""Video storage.

Two backends behind one interface:

* ``LocalStorage`` writes under a directory. The default, because Sprint 0's AWS
  account does not exist yet and a backend that cannot start without cloud
  credentials cannot be developed or tested against.
* ``S3Storage`` is the production path: private bucket, server-side encryption,
  signed URLs only.

Nothing above this module knows which is in use.
"""

from __future__ import annotations

import logging
import shutil
from abc import ABC, abstractmethod
from datetime import UTC, datetime, timedelta
from pathlib import Path

from .config import Settings

logger = logging.getLogger(__name__)


class VideoStorage(ABC):
    @abstractmethod
    def store(self, source: Path, key: str) -> str:
        """Persist ``source`` under ``key``; returns the stored key."""

    @abstractmethod
    def fetch_to(self, key: str, destination: Path) -> Path:
        """Materialise the object at ``key`` as a local file."""

    @abstractmethod
    def exists(self, key: str) -> bool: ...

    @abstractmethod
    def signed_url(self, key: str, expires_seconds: int = 900) -> str:
        """Time-limited read URL. Videos are never publicly readable."""

    @abstractmethod
    def store_bytes(self, key: str, content: bytes, *, content_type: str) -> str:
        """Persist in-memory content under ``key``.

        Separate from ``store`` because a registration photo arrives as request
        bytes, and writing it to a temporary file first only to copy it again
        buys nothing but a chance to leave the temporary behind.
        """

    @abstractmethod
    def delete(self, key: str) -> bool:
        """Remove the object. False when there was nothing there.

        Needed for data that must not be kept indefinitely — a superseded face
        photo, and the Sprint 11-12 retention policy.
        """


def build_object_key(athlete_id: str | None, test_code: str, video_id: str) -> str:
    """Date-partitioned key.

    Partitioning by date keeps prefixes small enough to list, and makes the
    Sprint 11-12 retention policy ("delete videos older than N") expressible as
    a lifecycle rule rather than a full-bucket scan.
    """
    today = datetime.now(UTC)
    owner = athlete_id or "unassigned"
    return (
        f"videos/{today:%Y/%m/%d}/{owner}/{test_code.lower()}/{video_id}.mp4"
    )


MEDIA_TOKEN_PURPOSE = "media"


class LocalStorage(VideoStorage):
    def __init__(self, root: Path, settings: Settings | None = None) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.settings = settings

    def _path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        # Keys are server-generated, but a key is also what a signed media URL
        # names. Refuse anything that resolves outside the storage root.
        if not path.is_relative_to(self.root.resolve()):
            raise ValueError(f"Storage key escapes the storage root: {key}")
        return path

    def path_for(self, key: str) -> Path:
        return self._path(key)

    def store(self, source: Path, key: str) -> str:
        destination = self._path(key)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        logger.info("Stored video locally at %s", destination)
        return key

    def fetch_to(self, key: str, destination: Path) -> Path:
        source = self._path(key)
        if not source.exists():
            raise FileNotFoundError(f"No stored video for key {key}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        return destination

    def exists(self, key: str) -> bool:
        return self._path(key).exists()

    def signed_url(self, key: str, expires_seconds: int = 900) -> str:
        """A short-lived URL served by this backend's /api/media endpoint.

        Signed and expiring like an S3 presigned URL, so the dashboard behaves
        the same in development as in production — and so a dev server does
        not casually hand out permanent links to recordings of minors.
        """
        import jwt

        if self.settings is None:
            raise RuntimeError("LocalStorage needs settings to sign media URLs")

        token = jwt.encode(
            {
                "key": key,
                "purpose": MEDIA_TOKEN_PURPOSE,
                "exp": datetime.now(UTC) + timedelta(seconds=expires_seconds),
            },
            self.settings.jwt_secret,
            algorithm=self.settings.jwt_algorithm,
        )
        base = self.settings.public_base_url.rstrip("/")
        return f"{base}/api/media/{token}"

    def store_bytes(self, key: str, content: bytes, *, content_type: str) -> str:
        destination = self._path(key)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
        return key

    def delete(self, key: str) -> bool:
        path = self._path(key)
        if not path.exists():
            return False
        path.unlink()
        return True


class S3Storage(VideoStorage):
    def __init__(self, settings: Settings) -> None:
        import boto3  # imported lazily so local dev needs no AWS SDK config

        if not settings.s3_bucket:
            raise ValueError("storage_backend is 's3' but s3_bucket is not set")

        self.bucket = settings.s3_bucket
        self.client = boto3.client(
            "s3",
            region_name=settings.s3_region,
            endpoint_url=settings.s3_endpoint_url,
        )

    def store(self, source: Path, key: str) -> str:
        self.client.upload_file(
            str(source),
            self.bucket,
            key,
            ExtraArgs={
                "ContentType": "video/mp4",
                # Encryption at rest is a Sprint 11-12 checklist item, but it
                # costs one parameter here and retrofitting it would mean
                # rewriting every object already stored.
                "ServerSideEncryption": "AES256",
            },
        )
        logger.info("Stored video in s3://%s/%s", self.bucket, key)
        return key

    def fetch_to(self, key: str, destination: Path) -> Path:
        destination.parent.mkdir(parents=True, exist_ok=True)
        self.client.download_file(self.bucket, key, str(destination))
        return destination

    def exists(self, key: str) -> bool:
        from botocore.exceptions import ClientError

        try:
            self.client.head_object(Bucket=self.bucket, Key=key)
            return True
        except ClientError:
            return False

    def signed_url(self, key: str, expires_seconds: int = 900) -> str:
        return self.client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": key},
            ExpiresIn=expires_seconds,
        )

    def store_bytes(self, key: str, content: bytes, *, content_type: str) -> str:
        self.client.put_object(
            Bucket=self.bucket,
            Key=key,
            Body=content,
            ContentType=content_type,
            ServerSideEncryption="AES256",
        )
        return key

    def delete(self, key: str) -> bool:
        from botocore.exceptions import ClientError

        try:
            self.client.delete_object(Bucket=self.bucket, Key=key)
            return True
        except ClientError:
            logger.warning("Could not delete s3://%s/%s", self.bucket, key)
            return False


def get_storage(settings: Settings) -> VideoStorage:
    if settings.storage_backend.lower() == "s3":
        return S3Storage(settings)

    if settings.is_production:
        # Local disk in production would mean videos vanish with the container
        # and are readable by anything on the host.
        raise RuntimeError(
            "storage_backend must be 's3' in production, got "
            f"'{settings.storage_backend}'"
        )

    return LocalStorage(settings.storage_local_path, settings)

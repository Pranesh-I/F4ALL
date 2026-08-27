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
from datetime import UTC, datetime
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


class LocalStorage(VideoStorage):
    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        return self.root / key

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
        # Local development only. There is no signing here, which is precisely
        # why this backend must never be selected in production.
        return f"file://{self._path(key).resolve()}"


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

    return LocalStorage(settings.storage_local_path)

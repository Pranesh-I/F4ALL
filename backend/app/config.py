"""Application settings, loaded from the environment."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- General ---
    environment: str = "development"
    debug: bool = True

    # --- Database ---
    # Port 5433 matches docker-compose.yml, which deliberately avoids 5432 so a
    # developer's existing local Postgres is not shadowed.
    database_url: str = (
        "postgresql+psycopg://f4all_user:f4all_password@localhost:5433/f4all_db"
    )

    # --- Redis / Celery ---
    redis_url: str = "redis://localhost:6379/0"

    # --- Auth ---
    # Sprint 7 replaces this with real OTP-issued tokens. The secret MUST come
    # from the environment in any deployed setting; a default this guessable is
    # only tolerable because nothing real is protected by it yet.
    jwt_secret: str = "dev-only-insecure-secret-change-me"
    jwt_algorithm: str = "HS256"
    jwt_expiry_minutes: int = 60 * 24 * 7

    # Lets the mobile app talk to a dev server before Sprint 7 issues tokens.
    # Fails closed: any non-development environment ignores it.
    allow_unauthenticated: bool = True

    # --- Storage ---
    # "local" writes under storage_local_path; "s3" uses the bucket below.
    # Local is the default because Sprint 0's AWS account does not exist yet,
    # and a backend that cannot run without cloud credentials cannot be
    # developed against.
    storage_backend: str = "local"
    storage_local_path: Path = Path("./var/storage")

    s3_bucket: str = ""
    s3_region: str = "ap-south-1"
    s3_endpoint_url: str | None = None

    # --- Upload ---
    upload_chunk_size_bytes: int = 256 * 1024
    upload_max_file_bytes: int = 200 * 1024 * 1024
    upload_session_ttl_hours: int = 72
    upload_staging_path: Path = Path("./var/uploads")

    # --- Verification ---
    # Sprint 5's Definition of Done asks for an SLA. Five minutes from upload to
    # a server score is the target; this bound is what the health endpoint
    # reports against so a backlog is visible rather than silent.
    verification_sla_seconds: int = 300

    # A device score this far from the server's is auto-flagged for review.
    # Both are absolute, in the test's own unit.
    discrepancy_tolerance_reps: float = 2.0
    discrepancy_tolerance_cm: float = 5.0

    @property
    def is_production(self) -> bool:
        return self.environment.lower() in {"production", "prod", "staging"}

    @property
    def unauthenticated_allowed(self) -> bool:
        """Auth bypass is a development affordance and nothing else."""
        return self.allow_unauthenticated and not self.is_production


@lru_cache
def get_settings() -> Settings:
    return Settings()

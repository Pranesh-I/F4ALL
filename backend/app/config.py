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

    # An access token cannot be withdrawn before it expires, so it is kept
    # short and paired with a revocable refresh token. One hour is long enough
    # that a test recorded offline and synced later still carries a live token
    # in the common case, and the sync client refreshes when it does not.
    jwt_expiry_minutes: int = 60

    # Long, because these athletes may go weeks between sessions and have
    # patchy signal. Safe to be long only because refresh tokens are stored,
    # rotated on every use, and revocable.
    refresh_token_expiry_days: int = 90

    # Lets the mobile app talk to a dev server before a real login exists.
    # Fails closed: any non-development environment ignores it.
    allow_unauthenticated: bool = True

    # --- SMS (OTP delivery) ---
    # "console" logs, "memory" captures for tests, "http" posts to a gateway.
    # Production accepts only "http" — a deployment quietly logging OTPs to
    # stdout instead of sending them is an outage that looks like uptime.
    sms_backend: str = "console"
    sms_api_url: str = ""
    sms_api_key: str = ""
    sms_content_type: str = "application/x-www-form-urlencoded"

    # Indian transactional SMS requires both of these to be DLT-registered with
    # TRAI by SAI. Neither is something this code can arrange.
    sms_sender_id: str = ""
    sms_template_id: str = ""

    # Placeholders: {phone} {message} {sender_id} {template_id}. A template
    # rather than a vendor SDK, so changing provider is configuration.
    sms_payload_template: str = "to={phone}&body={message}&sender={sender_id}"

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

    # Where this API is reachable from a browser. Local-storage media URLs are
    # built against it, so a dashboard on another port can play videos.
    public_base_url: str = "http://localhost:8000"

    # Browser origins allowed to call the API — the official dashboard. Never
    # "*": the dashboard sends bearer tokens for accounts that approve results.
    cors_origins: list[str] = ["http://localhost:5173", "http://127.0.0.1:5173"]

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

    # --- Assessment sessions ---
    # An official attempt must be recorded inside its session's window, but may
    # arrive later: an athlete in a village records during the session and the
    # phone only finds signal days afterwards. This is how late it may arrive.
    session_submission_grace_hours: int = 72

    # When true, every official submission must name an assessment session.
    # Off by default so app versions from before sessions keep working; turn it
    # on once every phone in the field sends a session.
    sessions_required: bool = False

    # --- Identity ---
    # 32 random bytes, base64url, that encrypt registration face photos before
    # they reach storage. Required in production; development derives one from
    # jwt_secret. Generate with `python -m app.cli identity-key`.
    identity_encryption_key: str = ""

    # Pre-test identity checks an athlete may run per hour. Enough for several
    # retakes before each of a session's tests; not enough to tune a photo
    # against the matcher by trial and error.
    identity_checks_per_hour: int = 12

    @property
    def is_production(self) -> bool:
        return self.environment.lower() in {"production", "prod", "staging"}

    @property
    def sms_configured(self) -> bool:
        return self.sms_backend != "http" or bool(self.sms_api_url)

    @property
    def unauthenticated_allowed(self) -> bool:
        """Auth bypass is a development affordance and nothing else."""
        return self.allow_unauthenticated and not self.is_production


@lru_cache
def get_settings() -> Settings:
    return Settings()

"""Health and readiness."""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import AWAITING_VERIFICATION, TestResult
from ..schemas import HealthResponse, ReadinessResponse

logger = logging.getLogger(__name__)

router = APIRouter(tags=["Health"])


@router.get("/health", response_model=HealthResponse)
def health(
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    """Liveness. Cheap enough for a load balancer to hit constantly."""
    database_state = "up"
    try:
        db.execute(text("SELECT 1"))
    except Exception as exc:
        logger.error("Database health check failed: %s", exc)
        database_state = "down"

    return HealthResponse(
        status="ok" if database_state == "up" else "degraded",
        environment=settings.environment,
        database=database_state,
        storage=settings.storage_backend,
    )


@router.get("/ready", response_model=ReadinessResponse)
def readiness(
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    """Readiness: can this instance actually do its job right now."""
    database_ok = True
    broker_ok = True
    details: list[str] = []

    try:
        db.execute(text("SELECT 1"))
    except Exception as exc:
        database_ok = False
        details.append(f"database: {exc}")

    try:
        import redis

        client = redis.Redis.from_url(settings.redis_url, socket_connect_timeout=2)
        client.ping()
    except Exception as exc:
        broker_ok = False
        details.append(f"broker: {exc}")

    return ReadinessResponse(
        ready=database_ok and broker_ok,
        database=database_ok,
        broker=broker_ok,
        detail="; ".join(details) or None,
    )


@router.get("/health/verification")
def verification_backlog(
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    """Reports against the Sprint 5 SLA.

    The Definition of Done names a time window from upload to server score.
    Without something that measures it, a growing backlog is invisible until an
    athlete complains — so the SLA is reported here rather than assumed.
    """
    cutoff = datetime.now(UTC) - timedelta(
        seconds=settings.verification_sla_seconds
    )

    pending = db.execute(
        select(func.count())
        .select_from(TestResult)
        .where(TestResult.status.in_(AWAITING_VERIFICATION))
    ).scalar_one()

    breaching = db.execute(
        select(func.count())
        .select_from(TestResult)
        .where(
            TestResult.status.in_(AWAITING_VERIFICATION),
            TestResult.created_at < cutoff,
        )
    ).scalar_one()

    return {
        "sla_seconds": settings.verification_sla_seconds,
        "pending": pending,
        "breaching_sla": breaching,
        "healthy": breaching == 0,
        "recent": _recent_timings(db, settings),
    }


RECENT_WINDOW = timedelta(hours=24)
RECENT_LIMIT = 1000


def _recent_timings(db: Session, settings: Settings) -> dict:
    """Measured, not assumed: how long finished verifications actually took.

    ``turnaround`` is submission to verdict — what the SLA promises the
    athlete, queue wait included. ``processing`` is the worker's own time.
    Computed in Python over at most RECENT_LIMIT rows so it reads the same on
    SQLite and Postgres.
    """
    since = datetime.now(UTC) - RECENT_WINDOW
    rows = db.execute(
        select(
            TestResult.created_at,
            TestResult.verified_at,
            TestResult.processing_duration_ms,
        )
        .where(
            TestResult.verified_at.is_not(None),
            TestResult.processing_started_at.is_not(None),
            TestResult.verified_at >= since,
        )
        .order_by(TestResult.verified_at.desc())
        .limit(RECENT_LIMIT)
    ).all()

    turnaround = sorted(
        (_aware(done) - _aware(created)).total_seconds() for created, done, _ in rows
    )
    processing = sorted(ms / 1000 for _, _, ms in rows if ms is not None)
    sla = settings.verification_sla_seconds

    return {
        "window_hours": int(RECENT_WINDOW.total_seconds() // 3600),
        "completed": len(rows),
        "turnaround_p50_seconds": _percentile(turnaround, 0.5),
        "turnaround_p95_seconds": _percentile(turnaround, 0.95),
        "processing_p50_seconds": _percentile(processing, 0.5),
        "processing_p95_seconds": _percentile(processing, 0.95),
        "within_sla": sum(1 for seconds in turnaround if seconds <= sla),
    }


def _percentile(ordered: list[float], fraction: float) -> float | None:
    """Nearest-rank percentile; None when there is nothing to measure."""
    if not ordered:
        return None
    index = min(len(ordered) - 1, max(0, round(fraction * len(ordered)) - 1))
    return round(ordered[index], 2)


def _aware(value: datetime) -> datetime:
    # SQLite hands back naive datetimes; everything stored is UTC.
    return value if value.tzinfo else value.replace(tzinfo=UTC)

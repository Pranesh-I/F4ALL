"""Health and readiness."""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import TestResult, TestResultStatus
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
        .where(TestResult.status == TestResultStatus.processing)
    ).scalar_one()

    breaching = db.execute(
        select(func.count())
        .select_from(TestResult)
        .where(
            TestResult.status == TestResultStatus.processing,
            TestResult.created_at < cutoff,
        )
    ).scalar_one()

    return {
        "sla_seconds": settings.verification_sla_seconds,
        "pending": pending,
        "breaching_sla": breaching,
        "healthy": breaching == 0,
    }

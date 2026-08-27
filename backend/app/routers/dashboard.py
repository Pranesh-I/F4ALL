"""Official dashboard endpoints.

Sprint 8 builds the React UI; these are the endpoints it consumes. Implemented
now because the review queue is meaningless without the flags Sprint 5 raises,
and because writing them alongside the flagging logic keeps the two honest.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    Athlete,
    Official,
    OfficialRole,
    ReviewAction,
    ReviewActionRecord,
    Test,
    TestResult,
    TestResultStatus,
)
from ..schemas import (
    FlagResponse,
    ReviewActionRequest,
    ReviewActionResponse,
    ReviewDetailResponse,
    ReviewItemResponse,
)
from ..security import current_official
from ..storage import get_storage

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/dashboard", tags=["Dashboard"])


def _visible_regions_filter(query, official: Official | None):
    """Regional reviewers see only their own region.

    Enforced in the query, not the UI. A reviewer must never be able to reach
    another region's athletes by editing a URL.
    """
    if official is None:
        return query
    if official.role == OfficialRole.sai_admin or official.role == "sai_admin":
        return query
    if official.region:
        return query.where(Athlete.region == official.region)
    return query


@router.get("/reviews", response_model=list[ReviewItemResponse])
def review_queue(
    status_filter: str | None = Query(default=None, alias="status"),
    test_type: str | None = Query(default=None),
    region: str | None = Query(default=None),
    limit: int = Query(default=50, le=200),
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    query = (
        select(TestResult, Athlete, Test)
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .join(Test, Test.id == TestResult.test_id)
    )

    if status_filter:
        query = query.where(TestResult.status == status_filter)
    else:
        # Default view is work that needs a human: flagged submissions first.
        query = query.where(
            TestResult.status.in_(
                [TestResultStatus.flagged, TestResultStatus.processing]
            )
        )

    if test_type:
        query = query.where(Test.code == test_type.upper())

    if region:
        query = query.where(Athlete.region == region)

    query = _visible_regions_filter(query, official)
    query = query.order_by(TestResult.created_at.asc()).limit(limit)

    rows = db.execute(query).all()

    return [
        ReviewItemResponse(
            result_id=result.id,
            athlete_name=athlete.name,
            region=athlete.region,
            test_type=test.code,
            status=_status_value(result.status),
            provisional_score=_as_float(result.provisional_score),
            server_score=_as_float(result.server_score),
            flag_count=len(result.flags),
            created_at=result.created_at,
        )
        for result, athlete, test in rows
    ]


@router.get("/reviews/{result_id}", response_model=ReviewDetailResponse)
def review_detail(
    result_id: uuid.UUID,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    official: Official | None = Depends(current_official),
):
    result = db.get(TestResult, result_id)
    if result is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Result not found"
        )

    athlete = db.get(Athlete, result.athlete_id)
    test = db.get(Test, result.test_id)

    _assert_region_access(official, athlete)

    video_url = None
    video = next(iter(result.videos), None)
    if video is not None:
        # Signed and short-lived. These recordings show minors; a durable public
        # URL would be a data-protection incident waiting to happen.
        video_url = get_storage(settings).signed_url(video.s3_key)

    return ReviewDetailResponse(
        result_id=result.id,
        athlete_name=athlete.name if athlete else "Unknown",
        region=athlete.region if athlete else "",
        test_type=test.code if test else "UNKNOWN",
        status=_status_value(result.status),
        provisional_score=_as_float(result.provisional_score),
        server_score=_as_float(result.server_score),
        unit=test.unit if test else "",
        video_url=video_url,
        flags=[
            FlagResponse(
                reason=flag.reason,
                detail=flag.detail,
                severity=_status_value(flag.severity),
                source=_status_value(flag.source),
                created_at=flag.created_at,
            )
            for flag in result.flags
        ],
        created_at=result.created_at,
        verified_at=result.verified_at,
    )


@router.post("/reviews/{result_id}/action", response_model=ReviewActionResponse)
def review_action(
    result_id: uuid.UUID,
    payload: ReviewActionRequest,
    db: Session = Depends(get_db),
    official: Official | None = Depends(current_official),
):
    result = db.get(TestResult, result_id)
    if result is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Result not found"
        )

    try:
        action = ReviewAction(payload.action)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unknown action '{payload.action}'",
        ) from exc

    athlete = db.get(Athlete, result.athlete_id)
    _assert_region_access(official, athlete)

    if official is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Review actions require an identified official for the audit trail",
        )

    if action is ReviewAction.approved:
        result.status = TestResultStatus.approved
        # final_score is set ONLY here, by a human. The server score is the
        # measurement; approval is what makes it official.
        result.final_score = (
            result.server_score
            if result.server_score is not None
            else result.provisional_score
        )
    elif action is ReviewAction.rejected:
        result.status = TestResultStatus.rejected
        result.final_score = None
    else:
        result.status = TestResultStatus.pending_sync
        result.final_score = None

    db.add(
        ReviewActionRecord(
            test_result_id=result.id,
            official_id=official.id,
            action=action,
            notes=payload.notes,
        )
    )

    for flag in result.flags:
        if flag.resolved_at is None:
            flag.resolved_by = official.id
            flag.resolved_at = datetime.now(UTC)
            flag.resolution = (
                "confirmed" if action is ReviewAction.rejected else "dismissed"
            )
            db.add(flag)

    db.add(result)
    db.commit()

    return ReviewActionResponse(
        result_id=result.id,
        action=action.value,
        status=_status_value(result.status),
        message=f"Submission {action.value}",
    )


def _assert_region_access(official: Official | None, athlete: Athlete | None) -> None:
    if official is None or athlete is None:
        return
    role = _status_value(official.role)
    if role == "sai_admin":
        return
    if official.region and athlete.region != official.region:
        # 404 rather than 403: confirming a result exists in another region
        # leaks information a regional reviewer is not entitled to.
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Result not found"
        )


def _status_value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)


def _as_float(value) -> float | None:
    return float(value) if value is not None else None

"""Reading the access audit log (Sprint 20). SAI admins only.

Answers "who looked at this athlete's results, and when?" and "who has been
failing to sign in?". Read-only: nothing edits or deletes entries except the
retention job.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import AccessLog, Official, OfficialRole
from ..schemas import AccessLogEntry, AccessLogPage
from ..security import current_official

router = APIRouter(prefix="/api/dashboard", tags=["Dashboard"])


@router.get("/access-log", response_model=AccessLogPage)
def access_log(
    actor_id: uuid.UUID | None = Query(default=None),
    event: str | None = Query(default=None, max_length=30),
    path_contains: str | None = Query(default=None, max_length=100),
    since: datetime | None = Query(default=None),
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
    official: Official = Depends(current_official),
):
    role = official.role.value if hasattr(official.role, "value") else str(official.role)
    if role != OfficialRole.sai_admin.value:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN, "Only an SAI admin can read the access log"
        )

    query = select(AccessLog)
    if actor_id is not None:
        query = query.where(AccessLog.actor_id == actor_id)
    if event:
        query = query.where(AccessLog.event == event)
    if path_contains:
        query = query.where(AccessLog.path.contains(path_contains))
    if since is not None:
        query = query.where(AccessLog.created_at >= since)

    total = db.execute(select(func.count()).select_from(query.subquery())).scalar_one()
    rows = db.execute(
        query.order_by(AccessLog.created_at.desc(), AccessLog.id)
        .limit(limit)
        .offset(offset)
    ).scalars()
    return AccessLogPage(
        items=[AccessLogEntry.model_validate(row, from_attributes=True) for row in rows],
        total=total,
    )

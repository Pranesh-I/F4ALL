"""The athlete's notifications: push tokens and the inbox (Sprint 17).

Only ``/api/athletes/me/...`` routes. A notification belongs to the account,
not to the phone: the inbox follows the athlete to any phone they sign in on,
and a push token is moved to whoever registers it last, so a shared phone
only receives the signed-in athlete's notifications.
"""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Query, Response, status
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import Athlete, DeviceToken, Notification
from ..schemas import (
    DeviceRegistration,
    MarkRead,
    NotificationItem,
    NotificationPage,
    UnreadCount,
)
from ..security import require_athlete

router = APIRouter(prefix="/api/athletes/me", tags=["Notifications"])


@router.post("/devices", status_code=status.HTTP_204_NO_CONTENT)
def register_device(
    payload: DeviceRegistration,
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Address this phone's push token to the signed-in athlete.

    Idempotent. A token registered by another athlete before — the same phone,
    someone else signed in — is moved, so their notifications stop arriving
    here. A token disabled as invalid is enabled again: the phone has just
    proved it is alive.
    """
    now = datetime.now(UTC)
    device = db.execute(
        select(DeviceToken).where(DeviceToken.token == payload.token)
    ).scalar_one_or_none()
    if device is None:
        device = DeviceToken(token=payload.token, created_at=now)
        db.add(device)
    device.athlete_id = athlete.id
    device.platform = payload.platform
    device.provider = payload.provider
    device.last_seen_at = now
    device.disabled_at = None
    device.disabled_reason = None
    db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.delete("/devices/{token}", status_code=status.HTTP_204_NO_CONTENT)
def unregister_device(
    token: str,
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Stop pushing to this phone — called when the athlete signs out.

    Only the athlete it is addressed to can remove it; anyone else gets the
    same empty answer, which says nothing about whose token it is.
    """
    device = db.execute(
        select(DeviceToken)
        .where(DeviceToken.token == token)
        .where(DeviceToken.athlete_id == athlete.id)
    ).scalar_one_or_none()
    if device is not None:
        db.delete(device)
        db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/notifications", response_model=NotificationPage)
def my_notifications(
    before: datetime | None = Query(default=None),
    unread_only: bool = Query(default=False),
    limit: int = Query(default=30, ge=1, le=100),
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """The athlete's notifications, newest first, a page at a time."""
    query = select(Notification).where(Notification.athlete_id == athlete.id)
    if unread_only:
        query = query.where(Notification.read_at.is_(None))
    if before is not None:
        query = query.where(Notification.created_at < before)
    rows = db.execute(
        query.order_by(Notification.created_at.desc(), Notification.id).limit(limit + 1)
    ).scalars().all()

    page = rows[:limit]
    return NotificationPage(
        items=[_item(row) for row in page],
        unread_count=_unread(db, athlete),
        next_before=page[-1].created_at if len(rows) > limit else None,
    )


@router.post("/notifications/read", response_model=UnreadCount)
def mark_read(
    payload: MarkRead,
    db: Session = Depends(get_db),
    athlete: Athlete = Depends(require_athlete),
):
    """Mark the given notifications (or all of them) read. Unknown ids are ignored."""
    statement = (
        update(Notification)
        .where(Notification.athlete_id == athlete.id)
        .where(Notification.read_at.is_(None))
        .values(read_at=datetime.now(UTC))
    )
    if not payload.all:
        if not payload.notification_ids:
            return UnreadCount(unread_count=_unread(db, athlete))
        statement = statement.where(Notification.id.in_(payload.notification_ids))
    db.execute(statement)
    db.commit()
    return UnreadCount(unread_count=_unread(db, athlete))


def _unread(db: Session, athlete: Athlete) -> int:
    return db.execute(
        select(func.count())
        .select_from(Notification)
        .where(Notification.athlete_id == athlete.id)
        .where(Notification.read_at.is_(None))
    ).scalar_one()


def _item(row: Notification) -> NotificationItem:
    return NotificationItem(
        notification_id=row.id,
        event_type=row.event_type,
        title=row.title,
        body=row.body,
        data=row.data or {},
        created_at=row.created_at,
        read_at=row.read_at,
    )

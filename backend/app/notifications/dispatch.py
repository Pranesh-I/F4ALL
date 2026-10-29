"""The notifier: find events, deliver notifications, retry what failed.

    python -m app.notifications.dispatch           # run forever
    python -m app.notifications.dispatch --once    # one pass, then exit

A separate process from the API and the verification worker. If it is down,
nothing else is: submissions are still accepted, verified and reviewed, and
the events it missed are found when it comes back (its cursor is in the
database, and every event is deduplicated).

Delivery of one notification:

* no push provider ("inbox"), or no working device for the athlete — it is
  marked ``inbox``: the phone reads it from the athlete's inbox;
* pushed to at least one device — ``sent``;
* every device answered "this token is dead" — those tokens are disabled and
  the notification falls back to ``inbox``;
* the provider failed — ``retrying`` with backoff, then ``failed`` after
  ``notification_max_attempts``. It is still in the inbox either way.
"""

from __future__ import annotations

import argparse
import logging
import time
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import SessionLocal
from ..models import DeviceToken, Notification
from . import events
from .providers import Outcome, Push, PushProvider, provider_for

logger = logging.getLogger(__name__)

BATCH = 200
BACKOFF_BASE = timedelta(seconds=30)
BACKOFF_CAP = timedelta(minutes=30)


def deliver(
    db: Session,
    provider: PushProvider | None,
    settings: Settings,
    now: datetime | None = None,
) -> dict[str, int]:
    """Deliver due notifications. Returns how many ended in each state."""
    now = now or datetime.now(UTC)
    due = (
        db.execute(
            select(Notification)
            .where(Notification.delivery_status.in_(("pending", "retrying")))
            .where(Notification.next_attempt_at <= now)
            .order_by(Notification.created_at)
            .limit(BATCH)
        )
        .scalars()
        .all()
    )

    counts: dict[str, int] = {}
    for notification in due:
        _deliver_one(db, notification, provider, settings, now)
        state = notification.delivery_status
        counts[state] = counts.get(state, 0) + 1
        # One at a time: a crash part-way never re-sends what already went.
        db.commit()
    return counts


def _deliver_one(
    db: Session,
    notification: Notification,
    provider: PushProvider | None,
    settings: Settings,
    now: datetime,
) -> None:
    tokens = (
        db.execute(
            select(DeviceToken)
            .where(DeviceToken.athlete_id == notification.athlete_id)
            .where(DeviceToken.disabled_at.is_(None))
        )
        .scalars()
        .all()
        if provider is not None
        else []
    )
    if not tokens:
        notification.delivery_status = "inbox"
        return

    data = {key: str(value) for key, value in (notification.data or {}).items()}
    data["notification_id"] = str(notification.id)

    delivered = retry = False
    errors = []
    for device in tokens:
        try:
            result = provider.send(
                Push(
                    token=device.token,
                    title=notification.title,
                    body=notification.body,
                    data=data,
                )
            )
        except Exception as exc:  # a provider bug must not stop the loop
            logger.exception("Push provider failed for notification %s", notification.id)
            result = None
            errors.append(f"provider: {exc}")
            retry = True
            continue
        if result.outcome is Outcome.DELIVERED:
            delivered = True
        elif result.outcome is Outcome.INVALID_TOKEN:
            device.disabled_at = now
            device.disabled_reason = (result.detail or "invalid token")[:100]
            logger.info("Disabled push token %s…: %s", device.token[:8], result.detail)
        else:
            retry = True
            errors.append(result.detail)

    notification.attempts += 1
    if delivered:
        notification.delivery_status = "sent"
        notification.sent_at = now
        notification.last_error = None
    elif retry:
        notification.last_error = "; ".join(e for e in errors if e)[:300] or "retry"
        if notification.attempts >= settings.notification_max_attempts:
            notification.delivery_status = "failed"
        else:
            notification.delivery_status = "retrying"
            notification.next_attempt_at = now + min(
                BACKOFF_BASE * 2 ** (notification.attempts - 1), BACKOFF_CAP
            )
    else:
        # Every device the athlete had is gone; the inbox still has it.
        notification.delivery_status = "inbox"


def run_once(db: Session, provider: PushProvider | None, settings: Settings) -> dict:
    created = events.scan(db, settings)
    delivered = deliver(db, provider, settings)
    return {"created": created, **delivered}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Find and deliver athlete notifications")
    parser.add_argument("--once", action="store_true", help="one pass, then exit")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s"
    )
    settings = get_settings()
    provider = provider_for(settings)
    logger.info("Notifier started (provider: %s)", settings.notification_provider)

    while True:
        try:
            with SessionLocal() as db:
                summary = run_once(db, provider, settings)
            if any(summary.values()):
                logger.info("Notifier pass: %s", summary)
        except Exception:  # keep running; the next pass retries
            logger.exception("Notifier pass failed")
        if args.once:
            return 0
        time.sleep(settings.notifier_interval_seconds)


if __name__ == "__main__":
    raise SystemExit(main())

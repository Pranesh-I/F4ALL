"""Access and refresh tokens.

## Why there are two kinds

An access token is a bearer credential: anyone holding it is the athlete until
it expires, and there is no way to withdraw it early. That argues for a short
life. But this app's users have intermittent connectivity, and an athlete who
must complete an SMS round trip every hour to record a test in a village with
one bar of signal will simply not record the test.

The resolution is the standard one: a short-lived access token that endpoints
verify without a database read, and a long-lived refresh token that is stored,
revocable, and exchanged for new access tokens.

## Rotation

Every exchange issues a new refresh token and marks the old one replaced. This
matters because a refresh token lives on a phone for months, and phones are
lost, shared and resold.

With rotation, a stolen token stops working the moment the real device refreshes
— and the theft becomes *detectable*, because a replaced token being presented
again means two parties hold it. That case revokes the whole chain rather than
guessing which of the two is the athlete: forcing one re-login is a far smaller
harm than leaving an attacker with a working session on a minor's account.
"""

from __future__ import annotations

import hashlib
import logging
import secrets
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings
from ..models import RefreshToken
from ..security import create_access_token

logger = logging.getLogger(__name__)

# 256 bits from a CSPRNG. Unlike the OTP there is nothing to brute-force here,
# so a plain digest at rest is enough.
REFRESH_TOKEN_BYTES = 32


class TokenError(RuntimeError):
    pass


class InvalidRefreshToken(TokenError):
    pass


@dataclass(frozen=True)
class TokenPair:
    access_token: str
    refresh_token: str
    expires_in: int
    token_type: str = "bearer"


def hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def issue_token_pair(
    db: Session,
    *,
    subject_id: uuid.UUID,
    role: str,
    settings: Settings,
    claims: dict | None = None,
    now: datetime | None = None,
) -> TokenPair:
    now = now or datetime.now(UTC)
    raw = secrets.token_urlsafe(REFRESH_TOKEN_BYTES)

    db.add(
        RefreshToken(
            token_hash=hash_refresh_token(raw),
            subject_id=subject_id,
            subject_role=role,
            subject_phone=(claims or {}).get("phone"),
            expires_at=now + timedelta(days=settings.refresh_token_expiry_days),
            created_at=now,
        )
    )
    db.commit()

    return TokenPair(
        access_token=create_access_token(
            str(subject_id), settings, role=role, claims=claims
        ),
        refresh_token=raw,
        expires_in=settings.jwt_expiry_minutes * 60,
    )


def rotate_refresh_token(
    db: Session, raw_token: str, settings: Settings, *, now: datetime | None = None
) -> TokenPair:
    """Exchange a refresh token for a fresh pair, retiring the old one."""
    now = now or datetime.now(UTC)

    record = db.execute(
        select(RefreshToken).where(
            RefreshToken.token_hash == hash_refresh_token(raw_token)
        )
    ).scalar_one_or_none()

    if record is None:
        raise InvalidRefreshToken("Unknown refresh token")

    if record.replaced_by is not None:
        # This token was already exchanged, yet someone is presenting it again.
        # Either the athlete's phone replayed it, or it was stolen — and there
        # is no way to tell which from here. Revoking the whole chain costs the
        # athlete one login; the alternative leaves an attacker holding a live
        # session on a minor's account.
        logger.warning(
            "Reuse of a rotated refresh token for subject %s; revoking chain",
            record.subject_id,
        )
        revoke_all_for_subject(db, record.subject_id, now=now)
        raise InvalidRefreshToken("Refresh token has already been used")

    if record.revoked_at is not None:
        raise InvalidRefreshToken("Refresh token has been revoked")

    if _aware(record.expires_at) <= now:
        raise InvalidRefreshToken("Refresh token has expired")

    raw = secrets.token_urlsafe(REFRESH_TOKEN_BYTES)
    replacement = RefreshToken(
        token_hash=hash_refresh_token(raw),
        subject_id=record.subject_id,
        subject_role=record.subject_role,
        subject_phone=record.subject_phone,
        expires_at=now + timedelta(days=settings.refresh_token_expiry_days),
        created_at=now,
    )
    db.add(replacement)
    db.flush()

    record.replaced_by = replacement.id
    record.revoked_at = now
    record.last_used_at = now
    db.add(record)
    db.commit()

    return TokenPair(
        access_token=create_access_token(
            str(record.subject_id),
            settings,
            role=record.subject_role,
            # Reproduced from the stored row, not copied from the presented
            # token: a refresh must not be a way to smuggle in new claims.
            claims={"phone": record.subject_phone} if record.subject_phone else None,
        ),
        refresh_token=raw,
        expires_in=settings.jwt_expiry_minutes * 60,
    )


def revoke_refresh_token(
    db: Session, raw_token: str, *, now: datetime | None = None
) -> bool:
    now = now or datetime.now(UTC)
    record = db.execute(
        select(RefreshToken).where(
            RefreshToken.token_hash == hash_refresh_token(raw_token)
        )
    ).scalar_one_or_none()

    if record is None or record.revoked_at is not None:
        return False

    record.revoked_at = now
    db.add(record)
    db.commit()
    return True


def revoke_all_for_subject(
    db: Session, subject_id: uuid.UUID, *, now: datetime | None = None
) -> int:
    now = now or datetime.now(UTC)
    rows = list(
        db.execute(
            select(RefreshToken)
            .where(RefreshToken.subject_id == subject_id)
            .where(RefreshToken.revoked_at.is_(None))
        ).scalars()
    )

    for row in rows:
        row.revoked_at = now
        db.add(row)

    db.commit()
    return len(rows)


def _aware(value: datetime) -> datetime:
    """SQLite returns naive datetimes; Postgres returns aware ones."""
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)

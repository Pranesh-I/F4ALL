"""Phone OTP: issue, deliver, verify.

## What actually protects a six-digit code

Not the hash. A six-digit code has a million possibilities and any hash of it
falls to an offline sweep in seconds. What protects it is that an attacker only
gets a handful of online guesses before the challenge is dead, and that it stops
being valid within five minutes whether or not anyone guessed.

So the security properties that matter here are `MAX_VERIFY_ATTEMPTS` and
`TTL_SECONDS`, and both are enforced in the database rather than in a cache that
can be restarted away.

The HMAC still earns its place: a leaked database dump should not hand the
reader a list of live, ready-to-use login codes against real phone numbers.

## Why the rate limits look the way they do

This platform's users are in places with patchy signal, on prepaid phones,
sometimes sharing a handset. Rate limits that assume a reliable network punish
them for their network rather than for anything they did.

`RESEND_COOLDOWN_SECONDS` is short enough that someone whose SMS genuinely did
not arrive can try again while still standing there. `MAX_REQUESTS_PER_WINDOW`
is generous enough to survive a bad afternoon and tight enough that nobody's
phone can be used as an SMS cannon at our expense.

Every limit here fails **closed** and returns the same shape of error, so the
endpoint cannot be used to enumerate which phone numbers are registered.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings
from ..models import OtpChallenge

logger = logging.getLogger(__name__)

OTP_LENGTH = 6
TTL_SECONDS = 300

# Online guesses allowed against one challenge before it is burned. Five leaves
# room for a genuine typo or two and still leaves an attacker a 5-in-a-million
# chance per code issued.
MAX_VERIFY_ATTEMPTS = 5

RESEND_COOLDOWN_SECONDS = 60
MAX_REQUESTS_PER_WINDOW = 5
REQUEST_WINDOW_SECONDS = 3600

# The code every dev/test request issues, so the flow can be walked without an
# SMS gateway. `OtpService` refuses to use it outside development.
DEVELOPMENT_OTP = "000000"


class OtpError(RuntimeError):
    """Base for OTP failures that are safe to surface to a caller."""


class RateLimited(OtpError):
    def __init__(self, retry_after_seconds: int):
        self.retry_after_seconds = retry_after_seconds
        super().__init__("Too many OTP requests. Please wait before trying again.")


class OtpDeliveryUnavailable(OtpError):
    """No SMS transport is configured, and this is not a development machine."""


@dataclass(frozen=True)
class IssuedOtp:
    expires_at: datetime

    # Populated only in development, where it is echoed to the caller so the
    # flow can be walked without a gateway. Always None in production.
    development_code: str | None = None


def hash_code(phone: str, code: str, settings: Settings) -> str:
    """Keyed digest of the code, salted by the phone it was issued to.

    Keyed so that database read access alone is not enough to recover codes,
    and salted by phone so one rainbow table cannot cover every row.
    """
    message = f"{phone}:{code}".encode()
    return hmac.new(
        settings.jwt_secret.encode(), message, hashlib.sha256
    ).hexdigest()


def generate_code() -> str:
    """A uniformly random six-digit code.

    `secrets`, not `random` — this is a credential, and the difference between
    a CSPRNG and a Mersenne Twister here is the difference between guessing a
    code and predicting every future one from a few observed samples.
    """
    return f"{secrets.randbelow(10**OTP_LENGTH):0{OTP_LENGTH}d}"


class OtpService:
    def __init__(self, db: Session, settings: Settings):
        self.db = db
        self.settings = settings

    # -- issuing ----------------------------------------------------------

    def request(self, phone: str, *, now: datetime | None = None) -> IssuedOtp:
        """Issue a challenge for `phone`, or raise [RateLimited].

        Deliberately does NOT check whether an athlete exists with this number.
        Answering that question differently for registered and unregistered
        numbers turns this endpoint into a way to test whether a given person
        has an account.
        """
        now = now or datetime.now(UTC)
        self._enforce_request_limits(phone, now)

        development = not self.settings.is_production
        code = DEVELOPMENT_OTP if development else generate_code()

        if not development and not self.settings.sms_configured:
            # Refuse rather than issue a code that nothing will deliver. An
            # athlete waiting for an SMS that was never sent has no way to tell
            # that from a slow network.
            raise OtpDeliveryUnavailable(
                "No SMS provider is configured; OTP cannot be delivered"
            )

        # Any earlier live challenge is retired. Two valid codes for one number
        # doubles an attacker's chances for no benefit to the athlete, who is
        # only ever looking at the most recent message.
        self._retire_live_challenges(phone, now)

        expires_at = now + timedelta(seconds=TTL_SECONDS)
        self.db.add(
            OtpChallenge(
                phone=phone,
                code_hash=hash_code(phone, code, self.settings),
                expires_at=expires_at,
                created_at=now,
            )
        )
        self.db.commit()

        self._deliver(phone, code)

        return IssuedOtp(
            expires_at=expires_at,
            development_code=code if development else None,
        )

    def _enforce_request_limits(self, phone: str, now: datetime) -> None:
        window_start = now - timedelta(seconds=REQUEST_WINDOW_SECONDS)

        recent = list(
            self.db.execute(
                select(OtpChallenge)
                .where(OtpChallenge.phone == phone)
                .where(OtpChallenge.created_at >= window_start)
                .order_by(OtpChallenge.created_at.desc())
            ).scalars()
        )

        if recent:
            since_last = (now - _aware(recent[0].created_at)).total_seconds()
            if since_last < RESEND_COOLDOWN_SECONDS:
                raise RateLimited(int(RESEND_COOLDOWN_SECONDS - since_last) + 1)

        if len(recent) >= MAX_REQUESTS_PER_WINDOW:
            oldest = _aware(recent[-1].created_at)
            retry_after = REQUEST_WINDOW_SECONDS - (now - oldest).total_seconds()
            raise RateLimited(max(1, int(retry_after) + 1))

    def _retire_live_challenges(self, phone: str, now: datetime) -> None:
        for challenge in self._live_challenges(phone, now):
            challenge.expires_at = now
            self.db.add(challenge)

    def _deliver(self, phone: str, code: str) -> None:
        if not self.settings.is_production:
            # Never log a real code. In development the code is fixed and
            # public anyway, so this leaks nothing that is not already known.
            logger.info("Development OTP for %s is %s", phone, code)
            return

        from .sms import get_sms_sender

        get_sms_sender(self.settings).send(
            phone,
            f"{code} is your SAI talent assessment verification code. "
            f"It expires in {TTL_SECONDS // 60} minutes. Do not share it.",
        )

    # -- verifying --------------------------------------------------------

    def verify(self, phone: str, code: str, *, now: datetime | None = None) -> bool:
        """True when `code` is the live challenge for `phone`.

        Consumes the challenge on success, and counts the attempt either way.
        """
        now = now or datetime.now(UTC)

        challenge = next(iter(self._live_challenges(phone, now)), None)
        if challenge is None:
            return False

        if challenge.attempts >= MAX_VERIFY_ATTEMPTS:
            return False

        # Counted before the comparison, and committed even when the code is
        # wrong. An attempt counter that only increments on some paths is not a
        # limit — it is a suggestion.
        challenge.attempts += 1

        expected = challenge.code_hash
        actual = hash_code(phone, code, self.settings)

        # Constant-time: string equality on a digest leaks its prefix through
        # timing, and this comparison is reachable by anyone with the number.
        matched = hmac.compare_digest(expected, actual)

        if matched:
            challenge.consumed_at = now

        self.db.add(challenge)
        self.db.commit()

        return matched

    def _live_challenges(self, phone: str, now: datetime) -> list[OtpChallenge]:
        rows = self.db.execute(
            select(OtpChallenge)
            .where(OtpChallenge.phone == phone)
            .where(OtpChallenge.consumed_at.is_(None))
            .order_by(OtpChallenge.created_at.desc())
        ).scalars()

        return [row for row in rows if _aware(row.expires_at) > now]


def _aware(value: datetime) -> datetime:
    """SQLite hands back naive datetimes; Postgres hands back aware ones.

    Comparing the two raises, so every read is normalised here rather than at
    each call site. Values are stored in UTC throughout.
    """
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)

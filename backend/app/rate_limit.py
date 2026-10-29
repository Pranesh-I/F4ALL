"""Request rate limits for the whole API (Sprint 20).

Every request is counted against its client address and, when it carries a
valid token, against that account too — so neither one machine nor one
account can flood the API, and a shared mobile-network address (many athletes
behind one carrier IP) is not the only thing that decides.

Fixed one-minute windows, counted:

* in this process (``memory``) — for development and tests; or
* in Redis (``redis``) — required in production, so the limit is shared by
  every worker and every container. If Redis is unreachable the request is
  allowed: a monitoring outage must not take the API down with it.

Stricter rules come first. Sign-in and OTP have their own small allowances on
top of the per-account lockouts and OTP limits they already have; video
chunks and signed media get larger ones, because a single upload or a single
playback is many requests. Health checks are not limited.
"""

from __future__ import annotations

import logging
import math
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

import jwt
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse

from .config import Settings, settings_for_request

logger = logging.getLogger(__name__)

WINDOW_SECONDS = 60
EXEMPT_PREFIXES = ("/health", "/ready", "/docs", "/redoc", "/openapi.json")


@dataclass(frozen=True)
class Rule:
    name: str
    prefixes: tuple[str, ...]
    per_ip: int
    per_account: int | None


def rules_for(settings: Settings) -> list[Rule]:
    return [
        Rule(
            "sign_in",
            ("/api/dashboard/auth/login",),
            settings.rate_limit_sign_in_per_minute,
            None,
        ),
        Rule("otp", ("/api/auth/",), settings.rate_limit_otp_per_minute, None),
        Rule(
            "media",
            ("/api/videos/", "/api/media/"),
            settings.rate_limit_upload_per_minute,
            settings.rate_limit_upload_per_minute,
        ),
        Rule(
            "api",
            ("/api/",),
            settings.rate_limit_per_minute,
            settings.rate_limit_per_minute,
        ),
    ]


class MemoryCounter:
    """Fixed-window counts in this process."""

    def __init__(self, clock: Callable[[], float] = time.time) -> None:
        self.clock = clock
        self.counts: dict[str, int] = {}
        self.lock = threading.Lock()
        self.window = -1

    def hit(self, key: str) -> tuple[int, int]:
        """Count one request; return (count in this window, seconds until it ends)."""
        now = self.clock()
        window = int(now // WINDOW_SECONDS)
        with self.lock:
            if window != self.window:
                self.counts.clear()
                self.window = window
            self.counts[key] = self.counts.get(key, 0) + 1
            count = self.counts[key]
        return count, max(1, math.ceil((window + 1) * WINDOW_SECONDS - now))


class RedisCounter:
    """Fixed-window counts in Redis, shared by every worker."""

    # After a failure, requests skip Redis for this long. Without it, every
    # request during an outage waited out two connection timeouts (Sprint 23
    # Redis-outage run: about a second added to every call).
    RETRY_AFTER_SECONDS = 30

    def __init__(
        self, url: str, clock: Callable[[], float] = time.time, client=None
    ) -> None:
        if client is None:
            import redis

            client = redis.Redis.from_url(
                url, socket_timeout=0.5, socket_connect_timeout=0.5
            )
        self.client = client
        self.clock = clock
        self.down_until = 0.0

    def hit(self, key: str) -> tuple[int, int] | None:
        now = self.clock()
        if now < self.down_until:
            return None
        window = int(now // WINDOW_SECONDS)
        name = f"rl:{window}:{key}"
        try:
            pipe = self.client.pipeline()
            pipe.incr(name)
            pipe.expire(name, WINDOW_SECONDS + 5)
            count = pipe.execute()[0]
        except Exception:  # Redis down: allow, and say so once per outage
            if not self.down_until:
                logger.warning(
                    "Rate limit store unavailable; requests allowed without"
                    " counting, retrying every %ss",
                    self.RETRY_AFTER_SECONDS,
                )
            self.down_until = now + self.RETRY_AFTER_SECONDS
            return None
        if self.down_until:
            logger.warning("Rate limit store is back; counting again")
            self.down_until = 0.0
        return int(count), max(1, math.ceil((window + 1) * WINDOW_SECONDS - now))


class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, settings: Settings, counter=None) -> None:
        super().__init__(app)
        self.settings = settings
        if counter is not None:
            self.counter = counter
        elif settings.rate_limit_backend == "redis":
            self.counter = RedisCounter(settings.redis_url)
        else:
            self.counter = MemoryCounter()

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        settings = settings_for_request(request, self.settings)
        if not settings.rate_limit_enabled or path.startswith(EXEMPT_PREFIXES):
            return await call_next(request)
        rules = rules_for(settings)
        rule = next((r for r in rules if path.startswith(r.prefixes)), None)
        if rule is None:
            return await call_next(request)

        keys = [(f"{rule.name}:ip:{self._client(request, settings)}", rule.per_ip)]
        account = self._account(request, settings)
        if account is not None and rule.per_account is not None:
            keys.append((f"{rule.name}:acct:{account}", rule.per_account))

        for key, limit in keys:
            counted = self.counter.hit(key)
            if counted is None:
                continue
            count, retry_after = counted
            if count > limit:
                logger.info(
                    "Rate limited %s on %s (%s)", key.split(":")[1], rule.name, path
                )
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Too many requests. Try again in a minute."},
                    headers={"Retry-After": str(retry_after)},
                )
        return await call_next(request)

    def _client(self, request: Request, settings: Settings) -> str:
        if settings.rate_limit_trust_forwarded:
            forwarded = request.headers.get("X-Forwarded-For", "")
            if forwarded:
                return forwarded.split(",")[0].strip()
        return request.client.host if request.client else "unknown"

    def _account(self, request: Request, settings: Settings) -> str | None:
        """The token's subject — only when the token is genuine, so nobody can
        spend someone else's allowance by naming them in a forged token."""
        header = request.headers.get("Authorization", "")
        if not header.lower().startswith("bearer "):
            return None
        try:
            claims = jwt.decode(
                header[7:].strip(),
                settings.jwt_secret,
                algorithms=[settings.jwt_algorithm],
            )
        except jwt.InvalidTokenError:
            return None
        subject = claims.get("sub")
        return str(subject) if subject else None

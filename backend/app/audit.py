"""The data-access audit log (Sprint 20).

Two writers:

* ``AuditMiddleware`` records every request to the officials' side —
  ``/api/dashboard/*``, an official reading ``/api/verification/*``, and
  ``/api/media/*`` — after it is answered, with who asked (from a verified
  token), what, the outcome and from where. Requests without a valid official
  token are recorded too: a refused attempt is worth knowing about.
* ``record`` is called by the sign-in endpoint for each attempt, because the
  middleware cannot see who was trying to sign in.

A failure to write the log never fails the request it describes; it is logged
loudly instead.
"""

from __future__ import annotations

import hashlib
import logging
import uuid

import jwt
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request

from .config import Settings, settings_for_request
from .models import AccessLog

logger = logging.getLogger(__name__)

AUDITED_PREFIXES = ("/api/dashboard/", "/api/media/", "/api/verification/")
# Recorded by the endpoint itself, with the outcome it alone knows.
SELF_AUDITED = ("/api/dashboard/auth/login", "/api/dashboard/auth/refresh")
OFFICIAL_ROLES = {"sai_admin", "regional_reviewer"}


def record(
    db: Session,
    *,
    event: str,
    request: Request | None,
    status_code: int,
    actor_id: uuid.UUID | None = None,
    actor_role: str | None = None,
    detail: str | None = None,
) -> None:
    """Add one entry and commit it."""
    db.add(
        AccessLog(
            actor_id=actor_id,
            actor_role=actor_role,
            event=event,
            method=request.method if request else "-",
            path=(request.url.path if request else "-")[:500],
            status_code=status_code,
            client_ip=(request.client.host if request and request.client else None),
            request_id=getattr(request.state, "request_id", None) if request else None,
            detail=detail[:200] if detail else None,
        )
    )
    db.commit()


def fingerprint(email: str) -> str:
    """An attempted sign-in address, recorded without keeping the address."""
    return "email#" + hashlib.sha256(email.strip().lower().encode()).hexdigest()[:16]


class AuditMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, settings: Settings, session_factory=None) -> None:
        super().__init__(app)
        self.settings = settings
        self.session_factory = session_factory

    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)
        path = request.url.path
        if request.method == "OPTIONS" or not path.startswith(AUDITED_PREFIXES):
            return response
        if path.startswith(SELF_AUDITED):
            return response

        actor_id, role = self._official(request)
        if path.startswith("/api/verification/") and role is None:
            # An athlete reading their own result: not the officials' side.
            return response

        event = (
            "media"
            if path.startswith("/api/media/")
            else ("read" if request.method in ("GET", "HEAD") else "change")
        )
        # A signed media link is itself a credential: keep only its route.
        logged_path = "/api/media/…" if event == "media" else path
        entry = AccessLog(
            actor_id=actor_id,
            actor_role=role,
            event=event,
            method=request.method,
            path=logged_path[:500],
            status_code=response.status_code,
            client_ip=request.client.host if request.client else None,
            request_id=getattr(request.state, "request_id", None),
            detail=None if event == "media" else (request.url.query[:200] or None),
        )
        factory = self.session_factory or getattr(
            request.app.state, "session_factory", None
        )

        def write() -> None:
            from .database import SessionLocal

            with (factory or SessionLocal)() as db:
                db.add(entry)
                db.commit()

        try:
            # Off the event loop: one insert must not stall other requests.
            await run_in_threadpool(write)
        except Exception:
            logger.exception(
                "Could not write the access log for %s %s", request.method, path
            )
        return response

    def _official(self, request: Request) -> tuple[uuid.UUID | None, str | None]:
        settings = settings_for_request(request, self.settings)
        header = request.headers.get("Authorization", "")
        if not header.lower().startswith("bearer "):
            return None, None
        try:
            claims = jwt.decode(
                header[7:].strip(),
                settings.jwt_secret,
                algorithms=[settings.jwt_algorithm],
            )
        except jwt.InvalidTokenError:
            return None, None
        role = claims.get("role")
        if role not in OFFICIAL_ROLES:
            return None, None
        try:
            return uuid.UUID(str(claims.get("sub"))), role
        except ValueError:
            return None, role

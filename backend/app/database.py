"""Database engine and session management."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from .config import get_settings

_settings = get_settings()

_connect_args: dict[str, object] = {}
_pool_args: dict[str, object] = {}
if _settings.database_url.startswith("sqlite"):
    _connect_args["check_same_thread"] = False
else:
    # Sprint 23: with Postgres unreachable, a connection attempt behind a
    # proxy waited for the client to give up (30 s and more). Fail in seconds
    # instead, so the API answers 503 and the phone retries later.
    _connect_args["connect_timeout"] = 3
    _pool_args["pool_timeout"] = 10

engine = create_engine(
    _settings.database_url,
    pool_pre_ping=True,
    connect_args=_connect_args,
    echo=False,
    **_pool_args,
)

SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def get_db() -> Iterator[Session]:
    """FastAPI dependency: one session per request, always closed."""
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


@contextmanager
def session_scope() -> Iterator[Session]:
    """Transactional scope for Celery tasks, which have no request lifecycle.

    Commits on success, rolls back on any exception. A verification worker that
    half-committed would leave a result claiming to be verified with no score.
    """
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()

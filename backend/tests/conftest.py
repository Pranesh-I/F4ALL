"""Test fixtures.

The suite runs against SQLite in-memory and local-disk storage, so it needs no
Postgres, no Redis, no S3 and no network. A test suite that only runs when four
services are up is a test suite people stop running.
"""

from __future__ import annotations

import sys
import uuid
from datetime import date
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

BACKEND_ROOT = Path(__file__).resolve().parent.parent
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.config import Settings, get_settings
from app.database import get_db
from app.main import create_app
from app.models import Athlete, Base, Official, OfficialRole, Test


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        environment="test",
        debug=True,
        database_url="sqlite://",
        storage_backend="local",
        storage_local_path=tmp_path / "storage",
        upload_staging_path=tmp_path / "uploads",
        allow_unauthenticated=True,
        jwt_secret="test-secret",
        upload_chunk_size_bytes=1024,
        discrepancy_tolerance_reps=2.0,
        discrepancy_tolerance_cm=5.0,
    )


@pytest.fixture
def db_session_factory():
    # StaticPool keeps one connection alive so an in-memory database survives
    # across sessions within a test.
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    yield sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    Base.metadata.drop_all(engine)
    engine.dispose()


@pytest.fixture
def db(db_session_factory):
    session = db_session_factory()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture
def client(settings, db_session_factory, monkeypatch):
    app = create_app()

    def override_get_db():
        session = db_session_factory()
        try:
            yield session
        finally:
            session.close()

    app.dependency_overrides[get_db] = override_get_db
    app.dependency_overrides[get_settings] = lambda: settings

    # The verification task needs Celery and a real video; the API tests are
    # about the HTTP contract, so it is stubbed and asserted separately.
    import app.routers.tests_submit as submit_module

    queued: list[tuple[str, float | None]] = []
    monkeypatch.setattr(
        submit_module,
        "_enqueue_verification",
        lambda *, result_id, athlete_height_cm, settings: queued.append(
            (result_id, athlete_height_cm)
        ),
    )

    with TestClient(app) as test_client:
        test_client.queued_verifications = queued  # type: ignore[attr-defined]
        yield test_client

    app.dependency_overrides.clear()


@pytest.fixture
def seeded_tests(db) -> dict[str, Test]:
    situps = Test(
        id=uuid.uuid4(),
        name="Sit-ups",
        code="SIT_UPS",
        unit="reps",
        description="Maximum sit-ups in the allotted time",
    )
    jump = Test(
        id=uuid.uuid4(),
        name="Vertical Jump",
        code="VERTICAL_JUMP",
        unit="cm",
        description="Standing vertical jump height",
    )
    db.add_all([situps, jump])
    db.commit()
    return {"SIT_UPS": situps, "VERTICAL_JUMP": jump}


@pytest.fixture
def athlete(db) -> Athlete:
    record = Athlete(
        id=uuid.uuid4(),
        name="Test Athlete",
        dob=date(2006, 6, 15),
        gender="female",
        region="Tamil Nadu",
        phone="9999999999",
        height_cm=165,
    )
    db.add(record)
    db.commit()
    return record


@pytest.fixture
def official(db) -> Official:
    record = Official(
        id=uuid.uuid4(),
        name="Regional Reviewer",
        email="reviewer@sai.example",
        role=OfficialRole.regional_reviewer,
        region="Tamil Nadu",
    )
    db.add(record)
    db.commit()
    return record

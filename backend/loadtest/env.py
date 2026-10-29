"""A disposable environment for load and reliability runs (Sprint 23).

Everything lives apart from development data: its own Postgres database
(``f4all_load``, in the compose ``f4all-postgres`` container), its own Redis
database (1), its own storage folder, and the API on port 8020.

    python -m loadtest.env setup --athletes 200   # fresh database, seeded
    python -m loadtest.env api                     # run the API (4 processes)
    python -m loadtest.env worker                  # run one verification worker
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import uuid
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
REPO = BACKEND.parent
VAR = BACKEND / "var" / "load"
PYTHON = sys.executable

PG_CONTAINER = "f4all-postgres"
PG_USER, PG_PASSWORD, PG_PORT = "f4all_user", "f4all_password", 5434
DB_NAME = "f4all_load"
API_PORT = 8020
API_URL = f"http://127.0.0.1:{API_PORT}"
JWT_SECRET = "load-test-secret-not-for-production-0123456789"

ATHLETES_FILE = VAR / "athletes.json"
SAMPLE_VIDEO = VAR / "sample.mp4"


def environment() -> dict[str, str]:
    """The settings every process of the load environment runs with."""
    return {
        **os.environ,
        "ENVIRONMENT": "development",
        "DEBUG": "false",
        "DATABASE_URL": (
            f"postgresql+psycopg://{PG_USER}:{PG_PASSWORD}@localhost:{PG_PORT}/{DB_NAME}"
        ),
        "REDIS_URL": "redis://localhost:6380/1",
        "JWT_SECRET": JWT_SECRET,
        "ALLOW_UNAUTHENTICATED": "false",
        "STORAGE_BACKEND": "local",
        "STORAGE_LOCAL_PATH": str(VAR / "storage"),
        "UPLOAD_STAGING_PATH": str(VAR / "uploads"),
        "PUBLIC_BASE_URL": API_URL,
        # Limits on, counted in Redis as in production, but high enough that
        # one machine generating every athlete's traffic is not refused.
        "RATE_LIMIT_ENABLED": "true",
        "RATE_LIMIT_BACKEND": "redis",
        "RATE_LIMIT_PER_MINUTE": "100000",
        "RATE_LIMIT_UPLOAD_PER_MINUTE": "100000",
        "LOG_LEVEL": "WARNING",
    }


def psql(sql: str) -> None:
    subprocess.run(
        [
            "docker",
            "exec",
            PG_CONTAINER,
            "psql",
            "-U",
            PG_USER,
            "-d",
            "f4all_db",
            "-c",
            sql,
        ],
        check=True,
        capture_output=True,
    )


def setup(athletes: int) -> None:
    VAR.mkdir(parents=True, exist_ok=True)
    psql(f"DROP DATABASE IF EXISTS {DB_NAME} WITH (FORCE)")
    psql(f"CREATE DATABASE {DB_NAME}")
    env = environment()
    subprocess.run(
        [PYTHON, "-m", "alembic", "upgrade", "head"],
        cwd=BACKEND,
        env=env,
        check=True,
        capture_output=True,
    )
    subprocess.run([PYTHON, "-m", "app.cli", "seed"], cwd=BACKEND, env=env, check=True)
    subprocess.run(
        [PYTHON, "-m", "loadtest.env", "seed-athletes", "--athletes", str(athletes)],
        cwd=BACKEND,
        env=env,
        check=True,
    )
    make_sample_video()
    print(f"Load environment ready: {DB_NAME}, {athletes} athletes, {SAMPLE_VIDEO}")


def seed_athletes(count: int) -> None:
    """Athletes with consent, one open session, and a token each."""
    os.environ.update(environment())
    from app.config import get_settings
    from app.database import session_scope
    from app.models import (
        AssessmentSession,
        Athlete,
        AthleteConsent,
        ConsentGiver,
        ConsentPurpose,
    )
    from app.security import create_access_token

    settings = get_settings()
    now = datetime.now(UTC)
    regions = ["Tamil Nadu", "Kerala", "Karnataka", "Maharashtra", "Odisha"]
    tokens = []
    with session_scope() as db:
        session = AssessmentSession(
            name="Load test trials",
            starts_at=now - timedelta(hours=1),
            ends_at=now + timedelta(days=2),
            enabled=True,
            allowed_tests=["VERTICAL_JUMP", "SIT_UPS", "SQUATS"],
        )
        db.add(session)
        for n in range(count):
            athlete = Athlete(
                id=uuid.uuid4(),
                name=f"Load Athlete {n:04d}",
                dob=date(2008 + n % 6, 1 + n % 12, 1 + n % 28),
                gender="female" if n % 2 else "male",
                region=regions[n % len(regions)],
                city="Test City",
                phone=f"9{n:09d}",
                height_cm=150 + n % 40,
            )
            db.add(athlete)
            db.flush()
            db.add(
                AthleteConsent(
                    athlete_id=athlete.id,
                    purpose=ConsentPurpose.registration,
                    version="load-test",
                    given_by=ConsentGiver.guardian,
                    guardian_name="Guardian",
                )
            )
            tokens.append(
                {
                    "id": str(athlete.id),
                    "height_cm": float(athlete.height_cm),
                    "token": create_access_token(str(athlete.id), settings),
                }
            )
        db.flush()
        session_id = str(session.id)
    ATHLETES_FILE.write_text(
        json.dumps({"session_id": session_id, "athletes": tokens}), encoding="utf-8"
    )


def make_sample_video() -> None:
    """The jump clip from the reference set when present, else a synthetic one.

    The synthetic clip has no person in it: the worker still decodes it fully
    and reaches a verdict (no pose found), which is what a load run needs.
    """
    source = REPO / "docs" / "reference-videos" / "vertical-jump" / "vj_01.mp4"
    if source.exists():
        SAMPLE_VIDEO.write_bytes(source.read_bytes())
        return
    import cv2
    import numpy as np

    writer = cv2.VideoWriter(
        str(SAMPLE_VIDEO), cv2.VideoWriter_fourcc(*"mp4v"), 30, (640, 480)
    )
    for i in range(300):
        frame = np.full((480, 640, 3), 40, np.uint8)
        cv2.rectangle(frame, (50 + i, 200), (150 + i, 300), (200, 200, 200), -1)
        writer.write(frame)
    writer.release()


def unique_video() -> tuple[bytes, str]:
    """The sample with a few trailing bytes, so each upload has its own checksum.

    MP4 readers ignore bytes after the last box; the server's checksum and
    duplicate checks then treat each upload as a different recording, as
    different athletes' videos would be.
    """
    data = SAMPLE_VIDEO.read_bytes() + uuid.uuid4().bytes
    return data, hashlib.sha256(data).hexdigest()


def run_api(processes: int = 4) -> subprocess.Popen:
    return subprocess.Popen(
        [
            PYTHON,
            "-m",
            "uvicorn",
            "app.main:app",
            "--host",
            "127.0.0.1",
            "--port",
            str(API_PORT),
            "--workers",
            str(processes),
            "--log-level",
            "warning",
        ],
        cwd=BACKEND,
        env=environment(),
    )


def run_worker(name: str = "w1") -> subprocess.Popen:
    return subprocess.Popen(
        [
            PYTHON,
            "-m",
            "celery",
            "-A",
            "app.worker:celery_app",
            "worker",
            "--pool=solo",
            "--loglevel=WARNING",
            "-n",
            f"{name}@%h",
        ],
        cwd=BACKEND,
        env=environment(),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    setup_parser = sub.add_parser("setup")
    setup_parser.add_argument("--athletes", type=int, default=200)
    seed_parser = sub.add_parser("seed-athletes")
    seed_parser.add_argument("--athletes", type=int, default=200)
    api_parser = sub.add_parser("api")
    api_parser.add_argument("--processes", type=int, default=4)
    sub.add_parser("worker")
    args = parser.parse_args()

    if args.command == "setup":
        setup(args.athletes)
    elif args.command == "seed-athletes":
        seed_athletes(args.athletes)
    elif args.command == "api":
        run_api(args.processes).wait()
    elif args.command == "worker":
        run_worker().wait()
    return 0


if __name__ == "__main__":
    sys.exit(main())

# F4ALL Backend

FastAPI service that receives athlete test videos and **independently re-scores
every one of them**. The phone's score is provisional; this service's score is
what counts.

## Quick start

```bash
cd backend
python -m venv .venv
.venv/Scripts/activate          # Windows;  source .venv/bin/activate elsewhere
pip install -r requirements.txt

cp .env.example .env            # then edit JWT_SECRET

# Postgres + Redis
docker compose -f ../docker-compose.yml up -d

python -m alembic upgrade head  # create the schema
python -m app.cli seed          # create the test battery rows
python -m app.cli seed-benchmarks   # PROVISIONAL age/gender norms (see below)
python -m scripts.fetch_model   # pose + face models, not committed

# a dashboard account (password is prompted for, never passed as an argument)
python -m app.cli create-official --email you@sai.example --name "Your Name"     --role regional_reviewer --region "Tamil Nadu"

uvicorn app.main:app --reload   # API on :8000
celery -A app.worker.celery_app worker --loglevel=info   # verification worker
```

Interactive API docs: <http://localhost:8000/docs>

### Running without Docker

The service starts against SQLite and local-disk storage, which is how the test
suite runs:

```bash
DATABASE_URL=sqlite:///./var/dev.db STORAGE_BACKEND=local uvicorn app.main:app
```

Verification jobs still need Redis. Without it, submissions are accepted and sit
in `processing` until `python -m app.cli reverify-pending` is run — they are
never lost.

## Layout

```
app/
├── main.py              application factory
├── config.py            settings (pydantic-settings, env-driven)
├── models.py            SQLAlchemy models for docs/db-schema-v1.md
├── schemas.py           request/response models matching docs/openapi.yaml
├── security.py          JWT verification, athlete/official resolution
├── storage.py           video storage: local disk or S3
├── database.py          engine, request sessions, task session scope
├── logging_config.py    JSON logs with per-request correlation ids
├── worker.py            Celery app
├── tasks.py             the re-verification job
├── cli.py               seed / reverify-pending / sla
├── routers/             health, auth, videos, tests_submit, dashboard
├── services/uploads.py  chunked resumable upload receiver
└── verification/
    ├── thresholds.py    MUST match the mobile AnalyzerThresholds.kt
    ├── pose.py          geometry, One-Euro smoothing, quality gate
    ├── analyzers.py     sit-up and vertical-jump scorers
    ├── extractor.py     MediaPipe landmark extraction (the only heavy dep)
    └── discrepancy.py   device-vs-server comparison and flagging
```

## The design rule that matters most

`app/verification/` is split so that **only `extractor.py` touches MediaPipe**.
The scoring algorithms are plain Python over plain dataclasses.

That is what allows `tests/test_parity.py` to replay the mobile app's own
recorded pose sequences through the server's scorers and assert identical
scores, with no model, no video decoding and no native dependencies.

It matters because the server auto-flags a submission when the two scores
disagree. If the Kotlin and Python implementations drift apart, that flag starts
firing on athletes who did nothing wrong, and the reviewer looking at it cannot
tell a real discrepancy from an implementation gap. Two implementations of the
same algorithm *will* drift; the parity test is what makes it impossible to
notice too late.

**When you change a threshold or an algorithm, change both sides**, regenerate
the fixtures, and let the parity test prove it:

```bash
cd ../mobile
./gradlew :app:testDebugUnitTest --tests "*ParityFixtureExportTest*"
cd ../backend
python -m pytest tests/test_parity.py
```

CI does this automatically and fails if the committed fixtures are stale.

## Tests

```bash
python -m pytest          # 222 tests, no Postgres/Redis/S3/network needed
python -m ruff check app tests
```

SQLite in-memory and local-disk storage throughout. A suite that only runs when
four services are up is a suite people stop running.

The pose-extraction and rendered-tampering tests skip unless
`python -m scripts.fetch_model` has been run; everything else, parity included,
runs on a clean checkout.

## Benchmarks are provisional

`app/data/benchmarks_provisional.csv` is a placeholder. SAI's published Khelo
India norms cover a different battery (600m, 50m, sit-and-reach, push-ups,
partial curl-ups) with no vertical jump and no classic sit-up, so there is no
official table to encode for the MVP tests. Every row carries a `source`; the
API marks comparisons from it `provisional: true` and both the app and the
dashboard show that caveat. Load an official table with
`python -m app.cli seed-benchmarks --file official.csv --replace` and the caveat
disappears without a code change.

## Operations

```bash
python -m app.cli seed                # idempotent; safe on every deploy
python -m app.cli sla                 # verification backlog vs the SLA
python -m app.cli reverify-pending    # re-queue submissions stuck in processing
python -m app.cli reverify <id>       # re-queue one
python -m app.cli seed-benchmarks     # load norms (--file, --replace)
python -m app.cli create-official     # provision or reset a dashboard account
```

`GET /health`, `GET /ready` and `GET /health/verification` cover liveness,
readiness and SLA breach counts respectively.

## Production requirements

These are enforced in code, not left to a checklist:

- `STORAGE_BACKEND=s3` — local disk raises at startup in production, because
  videos would vanish with the container and be readable on the host.
- `ALLOW_UNAUTHENTICATED` is ignored outside development regardless of value.
- `JWT_SECRET` must be set to a real secret.
- Uploaded objects are written with `ServerSideEncryption=AES256`, and videos
  are only ever exposed through short-lived signed URLs — these recordings show
  minors.

## Known gaps

- **Auth is a stub.** `/api/auth/*` accepts a fixed development OTP and returns
  501 in production. Sprint 7 implements delivery, expiry and rate limiting.
- **Sprint 6 cheat detection is not here yet.** Frame-consistency, single-person
  and face-match checks all hang off the same verification task.
- **The SLA is monitored but unproven.** The end-to-end timing target needs a
  real worker under real load; `GET /health/verification` reports it.

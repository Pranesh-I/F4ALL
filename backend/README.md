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

Interactive API docs: <http://localhost:8000/docs>. The same contract is
committed as [`docs/openapi.yaml`](../docs/openapi.yaml), generated from the code
with `python -m app.cli export-openapi`; a test fails if the two disagree.

### The whole stack in Docker

```bash
docker compose --profile app up --build    # Postgres, Redis, migrations, API, worker
docker compose exec api python -m app.cli seed
```

One image serves both roles (API and verification worker), so the worker can
never be a different version from the API that queued its work. The models are
fetched while the image builds. `F4ALL_API_PORT` changes the host port.

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
├── cli.py               operations commands (see Operations)
├── routers/             one module per API area:
│   ├── auth.py            OTP sign-in, refresh, logout
│   ├── athletes.py        registration, profile, consent, history, bests
│   ├── identity.py        the photo check before an official test
│   ├── practice.py        private practice history
│   ├── sessions.py        assessment sessions (athlete + admin)
│   ├── videos.py          chunked resumable upload
│   ├── tests_submit.py    official submissions and results
│   ├── verification.py    verification status and re-running it
│   ├── dashboard*.py      officials: sign-in, review queue, decisions
│   └── media.py, health.py
├── services/            the rules behind the routers (OTP, tokens, consent,
│                        sessions, badges, benchmarks, identity encryption…)
└── verification/
    ├── thresholds.py    MUST match the mobile AnalyzerThresholds.kt
    ├── pose.py          geometry, One-Euro smoothing, quality gate
    ├── analyzers.py     scorer for each test type
    ├── rep_exercises.py squats, push-ups, curls, lunges
    ├── extractor.py     MediaPipe landmark extraction (the only heavy dep)
    ├── discrepancy.py   device-vs-server comparison and flagging
    └── cheat/           integrity and identity checks
alembic/                 migrations
scripts/fetch_model.py   downloads the pose and face models
```

The sprint plan sketches a package per area (`auth/`, `athletes/`, …). Here
each area is a router module plus its service; the split is the same, without
moving every file and breaking every import for a rename.

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
python -m app.cli identity-key        # a new key for IDENTITY_ENCRYPTION_KEY
python -m app.cli encrypt-photos      # encrypt photos stored before encryption existed
python -m app.cli export-openapi      # regenerate docs/openapi.yaml after an API change
```

An SAI admin can also re-run a stuck verification from the API:
`POST /api/verification/{result_id}/process`.

`GET /health`, `GET /ready` and `GET /health/verification` cover liveness,
readiness and SLA breach counts respectively.

## Production requirements

With `ENVIRONMENT=production` the API **refuses to start** and names every
setting that is wrong (`Settings.production_problems`):

| Setting | Required |
|---|---|
| `JWT_SECRET` | random, 32+ characters — not the development default |
| `IDENTITY_ENCRYPTION_KEY` | set; back it up, since losing it loses every face photo |
| `STORAGE_BACKEND` / `S3_BUCKET` | `s3` and a bucket — local disk vanishes with the container |
| `SMS_BACKEND` / `SMS_API_URL` | `http` and a gateway — otherwise no OTP is ever delivered |
| `DEBUG` | false |
| `PUBLIC_BASE_URL` | an `https://` address |
| `CORS_ORIGINS` | only the dashboard's real origin — no `*`, no localhost |

Also: `ALLOW_UNAUTHENTICATED` is ignored outside development; uploads are
written with `ServerSideEncryption=AES256` and only exposed through short-lived
signed URLs, because these recordings show minors.

## API map

The sprint plan's API list, and where each is served (all under `/api`). The
test `tests/test_api_contract.py` checks every one exists.

| Plan | Served as |
|---|---|
| `POST /auth/send-otp` | `POST /api/auth/request-otp` |
| `POST /auth/verify-otp`, `POST /auth/login` | `POST /api/auth/verify-otp` — signing in *is* verifying the OTP |
| `POST /auth/register` | `POST /api/athletes/register` — needs the token from a verified OTP |
| `GET`/`PUT /athlete/profile` | `GET`/`PATCH /api/athletes/me` |
| `GET /athlete/history` | `GET /api/athletes/me/history` (paged) |
| `GET /athlete/personal-bests` | `GET /api/athletes/me/personal-bests` |
| `GET /sessions/active`, `GET /sessions/{id}` | `GET /api/sessions/active`, `GET /api/sessions/{id}` |
| `POST`/`PUT`/`DELETE /admin/sessions` | `POST`/`PATCH`/`DELETE /api/dashboard/sessions` |
| `POST /tests/practice` | `PUT /api/athletes/me/practice/{client_attempt_id}` — idempotent retry |
| `POST /tests/session`, `GET /tests/{id}` | `POST /api/tests/submit`, `GET /api/results/{id}` |
| `POST /uploads/initiate`, `/chunk`, `/complete` | `/api/videos/upload/init`, `PUT …/chunks/{index}`, `…/complete` |
| `GET /verification/{id}`, `POST /verification/process` | `GET /api/verification/{id}`, `POST /api/verification/{id}/process` |

## Known gaps

- **Notifications do not exist yet** (the plan's `notifications/` module).
- **The SLA is monitored but unproven.** The end-to-end timing target needs a
  real worker under real load; `GET /health/verification` reports it.

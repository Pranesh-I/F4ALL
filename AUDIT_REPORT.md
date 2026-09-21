# F4ALL PROJECT AUDIT

## 1. Overall Status

- **Backend:** READY
- **Database:** READY
- **Redis:** READY
- **Dashboard:** READY
- **Android:** READY
- **Physical Phone:** NEEDS FIX

---

## 2. Project Structure

```
C:\SIH\
├── .github/
│   └── workflows/
│       └── ci.yml                      # CI pipeline (structure, backend, dashboard, mobile, parity)
├── backend/
│   ├── alembic/                        # Database migration scripts & env
│   │   ├── env.py                      # DB URL binding with % escape
│   │   └── versions/                   # 4 migrations (Head: 8c43f54b1ac2)
│   ├── app/
│   │   ├── main.py                     # FastAPI application factory & lifespan
│   │   ├── config.py                   # Pydantic-settings configuration
│   │   ├── database.py                 # SQLAlchemy engine, SessionLocal, get_db
│   │   ├── models.py                   # 12 SQLAlchemy ORM models
│   │   ├── schemas.py                  # Pydantic request/response schemas
│   │   ├── security.py                 # JWT decoding, role resolution
│   │   ├── storage.py                  # LocalStorage & S3Storage implementations
│   │   ├── worker.py                   # Celery application configuration
│   │   ├── tasks.py                    # Celery task: verify_submission
│   │   ├── cli.py                      # CLI commands: seed, create-official, reverify-pending
│   │   ├── logging_config.py           # Structured JSON & correlation ID middleware
│   │   ├── routers/                    # API route handlers
│   │   │   ├── auth.py                 # Mobile OTP & token management
│   │   │   ├── athletes.py             # Athlete profile, registration, results, badges
│   │   │   ├── videos.py               # Chunked video upload endpoints
│   │   │   ├── tests_submit.py         # Test submission & result retrieval
│   │   │   ├── dashboard_auth.py       # Official login, refresh, me, logout
│   │   │   ├── dashboard.py            # Reviews queue, review details, action, leaderboard, stats
│   │   │   ├── media.py                # Signed video playback URLs
│   │   │   └── health.py               # /health, /ready, /health/verification
│   │   ├── services/                   # Business logic layer
│   │   │   ├── otp.py                  # OTP generation, rate-limiting, HMAC hashing
│   │   │   ├── tokens.py               # Athlete JWT issuance, rotation, revocation
│   │   │   ├── passwords.py            # scrypt password hashing for officials
│   │   │   ├── sms.py                  # Console & HTTP SMS sender implementations
│   │   │   ├── uploads.py              # Chunk reassembly & SHA-256 validation
│   │   │   ├── benchmarks.py           # Percentile & cohort evaluation
│   │   │   ├── badges.py               # Dynamic badge calculation
│   │   │   └── athlete_leaderboard.py  # Public opt-in leaderboard logic
│   │   └── verification/               # AI/ML verification engine
│   │       ├── extractor.py            # MediaPipe Python video landmark extractor
│   │       ├── pose.py                 # Geometry, One-Euro smoothing, quality gate
│   │       ├── analyzers.py            # Python sit-up & vertical jump scorers
│   │       ├── thresholds.py           # Canonical scoring constants
│   │       ├── discrepancy.py          # Device vs Server discrepancy evaluation
│   │       └── cheat/                  # Cheat detection modules (frames, cuts, face, subject, metadata)
│   ├── models/                         # Pre-trained ML models (.task, .tflite)
│   ├── tests/                          # 244 pytest unit, parity & integration tests
│   ├── .env                            # Active environment variables
│   ├── .env.example                    # Template environment variables
│   └── requirements.txt                # Python package dependencies
├── dashboard/
│   ├── src/
│   │   ├── App.tsx                     # Main layout & client-side routes
│   │   ├── main.tsx                    # Vite entry point & React Query provider
│   │   ├── api/
│   │   │   ├── client.ts               # DashboardApi HTTP client & token handling
│   │   │   └── types.ts                # TypeScript interfaces for API contracts
│   │   ├── auth/                       # Official authentication context & session guard
│   │   ├── components/                 # Shared UI components (VideoWithSkeleton, etc.)
│   │   └── pages/                      # LoginPage, QueuePage, ReviewPage, LeaderboardPage
│   ├── index.html                      # HTML root
│   ├── package.json                    # Dependencies & scripts
│   ├── vite.config.ts                  # Vite build & development proxy configuration
│   ├── .env                            # Dashboard environment configuration
│   └── .env.example                    # Template environment file
├── mobile/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml     # App manifest, permissions, FileProvider
│   │   │   ├── assets/                 # pose_landmarker_lite.task
│   │   │   └── java/com/sai/sports/
│   │   │       ├── F4allApplication.kt # Application class & initialization
│   │   │       ├── MainActivity.kt     # Single Activity hosting Jetpack Compose
│   │   │       ├── PoseLandmarkerHelper.kt # MediaPipe Tasks Vision on-device helper
│   │   │       ├── navigation/         # AppNavigation Compose NavHost
│   │   │       ├── ui/                 # Compose screens (auth, capture, results, sync, etc.)
│   │   │       ├── analyzer/           # Kotlin sit-up & vertical jump state machines
│   │   │       ├── api/                # F4allApi (OkHttp client for backend APIs)
│   │   │       ├── auth/               # AuthSession & EncryptedSharedPreferences storage
│   │   │       ├── sync/               # SyncConfig, UploadClient, SyncWorker, VideoCompressor
│   │   │       └── data/               # Room database (TestAttemptEntity, DAO, Repository)
│   │   ├── build.gradle.kts            # App module build configuration
│   │   └── proguard-rules.pro          # Obfuscation & reflection rules
│   ├── build.gradle.kts                # Root build file
│   └── settings.gradle.kts             # Gradle project definitions
├── docker-compose.yml                  # PostgreSQL 16 (port 5434) & Redis 7 (port 6380)
├── README.md                           # Architecture & product specification
└── TASK.md                             # Sprint tracking task board
```

---

## 3. Backend Architecture

- **Application Factory:** `backend/app/main.py` uses `create_app()` with an async lifespan manager initializing staging directories. Includes `CORSMiddleware`, `RequestIdMiddleware`, and global exception handling.
- **Routers Layer:** All routes live in `app/routers/` grouped by domain (`auth`, `athletes`, `videos`, `tests_submit`, `dashboard_auth`, `dashboard`, `media`, `health`).
- **Services Layer:** Modular business logic in `app/services/` decoupled from HTTP routing. Handles OTP hashing (HMAC-SHA256), token issuance/rotation, chunked upload assembly, provisional benchmark ranking, and badge computation.
- **Verification Engine:** Isolated in `app/verification/`. Only `extractor.py` binds to MediaPipe; mathematical analyzers (`analyzers.py`, `pose.py`) operate purely on plain dataclasses, enabling strict cross-language test parity against Kotlin.
- **Asynchronous Task Processing:** Uses Celery (`app/worker.py`) backed by Redis (`f4all-redis`). Tasks are queued with late-acknowledgement (`task_acks_late=True`) to guarantee no submission loss on worker restarts.
- **Storage Layer:** Abstracted through `VideoStorage` (`app/storage.py`), supporting both `LocalStorage` (writing signed media paths to disk) and `S3Storage` (AWS S3 with signed pre-authenticated URLs).

---

## 4. Android Architecture

- **Entry Point:** `F4allApplication.kt` initializes application-wide services; `MainActivity.kt` is the sole Activity containing the Jetpack Compose hierarchy.
- **Navigation Flow:** `AppNavigation.kt` enforces state-driven routing:
  `Onboarding` → `Login` → `Register` → `Home` → `Instructions` → `Capture` → `Results` → `Sync Status`.
- **Camera & Live Pose:** CameraX (`PreviewView` + `ImageAnalysis`) feeds frames in real time (`RunningMode.LIVE_STREAM`) to `PoseLandmarkerHelper.kt` (MediaPipe BlazePose Lite). Overlays are rendered on a custom Canvas `PoseOverlayView`.
- **Scoring State Machines:** `SitUpAnalyzer.kt` (hysteresis angle thresholds with minimum rep duration guards) and `VerticalJumpAnalyzer.kt` (standing height calibration to pixel/cm ratio).
- **Offline Storage & Resilient Sync:**
  - **Room Database:** Tracks attempts through states: `RECORDED` → `COMPRESSING` → `COMPRESSED` → `QUEUED` → `UPLOADING` → `SYNCED` / `FAILED`.
  - **Transcoding:** `VideoCompressor.kt` downscales video to 480p H.264 via AndroidX Media3 Transformer.
  - **Resumable Upload:** `UploadClient.kt` executes chunked byte uploads (256KB) with SHA-256 integrity verification.
  - **WorkManager:** `SyncWorker.kt` schedules resilient uploads gated by network connectivity.
- **Token Security:** `EncryptedTokenStorage.kt` uses Jetpack Security (`EncryptedSharedPreferences`) with AES-256 encryption.

---

## 5. Dashboard Architecture

- **Core Framework:** React 19 + TypeScript + Vite with Tailwind CSS.
- **State Management & Data Fetching:** TanStack React Query (`@tanstack/react-query`) handles caching, optimistic updates, and background refetching.
- **Session & Security:** Official tokens are maintained in `sessionStorage` (`f4all.dashboard.session`), preventing persistence across tab closures on shared administrative machines. Automatic token refresh is implemented on HTTP 401 responses.
- **Component Hierarchy:**
  - `LoginPage.tsx`: Official authentication with lockout handling.
  - `QueuePage.tsx`: Review queue with severity sorting, pagination, and multi-faceted filtering (status, region, test type).
  - `ReviewPage.tsx`: Detailed side-by-side inspection showing server-rendered skeleton overlays, frame-by-frame scrubbing, cheat flags with jump timestamps, face verification comparison, and the decision action form.
  - `LeaderboardPage.tsx`: Regional and demographic performance rankings based strictly on approved official scores.

---

## 6. Database

The schema comprises 12 tables managed via SQLAlchemy declarative models and Alembic migrations:

```
┌───────────────────────────────── DATABASE SCHEMA ─────────────────────────────────┐
│                                                                                   │
│  ┌──────────────┐         1:N         ┌──────────────┐                            │
│  │   athletes   │────────────────────<│ test_results │>─┐                         │
│  └──────────────┘                     └──────────────┘  │                         │
│         │ 1:1                                │ 1:1      │                         │
│         │                                    │          │                         │
│         ▼                                    ▼          │                         │
│  ┌──────────────────────┐             ┌──────────────┐  │ 1:N                     │
│  │  face_verifications  │             │    videos    │  │                         │
│  └──────────────────────┘             └──────────────┘  │                         │
│                                              ▲          │                         │
│                                          1:1 │          ▼                         │
│  ┌──────────────┐                     ┌─────────────────────┐   1:N  ┌─────────┐  │
│  │    tests     │                     │   upload_sessions   │   ┌───<│  flags  │  │
│  └──────────────┘                     └─────────────────────┘   │    └─────────┘  │
│         ▲                                                       │                 │
│     1:N │                                                       │                 │
│         ├────────────────────────┐                              │                 │
│         │                        │                              │                 │
│  ┌──────────────┐         ┌──────────────┐                      │                 │
│  │  benchmarks  │         │  officials   │                      ▼                 │
│  └──────────────┘         └──────────────┘             ┌────────────────┐         │
│                                  │ 1:N                 │ review_actions │<────────┘
│                                  └────────────────────<│                │         │
│                                                        └────────────────┘         │
│                                                                                   │
│  ┌────────────────────┐                 ┌────────────────────┐                    │
│  │   otp_challenges   │                 │   refresh_tokens   │                    │
│  └────────────────────┘                 └────────────────────┘                    │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### Table Definitions & Primary Relationships

1. **`athletes`**: Primary athlete record (`id`, `phone`, `name`, `date_of_birth`, `gender`, `region`, `height_cm`, `weight_kg`, `reference_face_key`, `leaderboard_opt_in`, `language`).
2. **`tests`**: Static test battery definitions (`code`, `name`, `unit`, `higher_is_better`, `description`).
3. **`test_results`**: Scored fitness assessments (`id`, `athlete_id`, `test_id`, `video_id`, `device_score`, `server_score`, `final_score`, `status`, `discrepancy_reasons`, `pose_sequence_json`).
4. **`videos`**: Stored video metadata (`id`, `storage_key`, `sha256_checksum`, `duration_seconds`, `resolution_width`, `resolution_height`).
5. **`upload_sessions`**: Resumable upload chunk tracker (`upload_id`, `total_bytes`, `chunk_size_bytes`, `received_chunks`, `expires_at`).
6. **`flags`**: Automated cheat-detection flags (`id`, `result_id`, `flag_type`, `severity`, `timestamp_ms`, `details_json`).
7. **`face_verifications`**: Match results between registration selfie and test video (`id`, `result_id`, `status`, `similarity_score`, `confidence`).
8. **`officials`**: Administrative reviewers (`id`, `email`, `password_hash`, `role`, `region`, `failed_login_attempts`, `locked_until`).
9. **`review_actions`**: Immutable official audit trail (`id`, `result_id`, `official_id`, `action`, `final_score`, `notes`, `created_at`).
10. **`benchmarks`**: Cohort normative percentiles (`id`, `test_id`, `gender`, `age_min`, `age_max`, `p10` to `p90`).
11. **`otp_challenges`**: Ephemeral phone verification codes (`id`, `phone`, `code_hash`, `attempts`, `expires_at`, `consumed_at`).
12. **`refresh_tokens`**: Revocable, rotated JWT session tokens (`id`, `subject_id`, `token_hash`, `role`, `expires_at`, `revoked_at`).

- **Migration Head:** `8c43f54b1ac2` (`sprint_9_leaderboard_opt_in_and_language.py`).
- **Seed Utilities:** 
  - `python -m app.cli seed` (populates `SIT_UPS` and `VERTICAL_JUMP` into `tests`).
  - `python -m app.cli seed-benchmarks` (populates provisional age/gender norms).

---

## 7. API Inventory

| Feature | Endpoint | Method | Status | Target Consumer |
|---|---|---|---|---|
| Service Health | `/health` | `GET` | Implemented | Monitoring / LB |
| Service Readiness | `/ready` | `GET` | Implemented | Monitoring / LB |
| Verification SLA Backlog | `/health/verification` | `GET` | Implemented | Monitoring / Admin |
| Mobile Request OTP | `/api/auth/request-otp` | `POST` | Implemented | Mobile App |
| Mobile Verify OTP | `/api/auth/verify-otp` | `POST` | Implemented | Mobile App |
| Mobile Refresh Token | `/api/auth/refresh` | `POST` | Implemented | Mobile App |
| Mobile Logout | `/api/auth/logout` | `POST` | Implemented | Mobile App |
| Athlete Registration | `/api/athletes/register` | `POST` | Implemented | Mobile App |
| Athlete Profile Me | `/api/athletes/me` | `GET` | Implemented | Mobile App |
| Athlete Test History | `/api/athletes/me/results` | `GET` | Implemented | Mobile App |
| Athlete Badges | `/api/athletes/me/badges` | `GET` | Implemented | Mobile App |
| Athlete Settings | `/api/athletes/me/settings` | `GET` / `PATCH` | Implemented | Mobile App |
| Public Leaderboard | `/api/athletes/leaderboard` | `GET` | Implemented | Mobile App |
| Init Resumable Upload | `/api/videos/upload/init` | `POST` | Implemented | Mobile App |
| Query Upload Status | `/api/videos/upload/{upload_id}` | `GET` | Implemented | Mobile App |
| Put Upload Chunk | `/api/videos/upload/{upload_id}/chunks/{chunk_index}` | `PUT` | Implemented | Mobile App |
| Complete Upload | `/api/videos/upload/{upload_id}/complete` | `POST` | Implemented | Mobile App |
| Submit Test for Scoring | `/api/tests/submit` | `POST` | Implemented | Mobile App |
| Fetch Test Result Detail | `/api/tests/results/{result_id}` | `GET` | Implemented | Mobile App |
| Media Streaming URL | `/api/media/{signed_token}` | `GET` | Implemented | Mobile / Dashboard |
| Dashboard Login | `/api/dashboard/auth/login` | `POST` | Implemented | Dashboard |
| Dashboard Current User | `/api/dashboard/auth/me` | `GET` | Implemented | Dashboard |
| Dashboard Refresh Token | `/api/dashboard/auth/refresh` | `POST` | Implemented | Dashboard |
| Dashboard Logout | `/api/dashboard/auth/logout` | `POST` | Implemented | Dashboard |
| Dashboard Stats Overview | `/api/dashboard/stats` | `GET` | Implemented | Dashboard |
| Dashboard Review Queue | `/api/dashboard/reviews` | `GET` | Implemented | Dashboard |
| Dashboard Review Detail | `/api/dashboard/reviews/{result_id}` | `GET` | Implemented | Dashboard |
| Dashboard Pose Sequence | `/api/dashboard/reviews/{result_id}/pose` | `GET` | Implemented | Dashboard |
| Dashboard Review Action | `/api/dashboard/reviews/{result_id}/action` | `POST` | Implemented | Dashboard |
| Dashboard Leaderboard | `/api/dashboard/leaderboard` | `GET` | Implemented | Dashboard |

---

## 8. Environment Variables

### Backend (`backend/.env`)

| Variable | Configured Status | Purpose | Development Requirement |
|---|---|---|---|
| `ENVIRONMENT` | Configured | Sets runtime profile (`development` vs `production`) | Mandatory |
| `DEBUG` | Configured | Enables debug logs and FastAPI docs | Mandatory |
| `DATABASE_URL` | Configured | PostgreSQL connection string | Mandatory |
| `REDIS_URL` | Configured | Celery message broker address | Mandatory |
| `JWT_SECRET` | Configured | Key used for signing auth tokens | Mandatory |
| `JWT_EXPIRY_MINUTES` | Configured | Access token lifetime | Optional (defaults to 60) |
| `REFRESH_TOKEN_EXPIRY_DAYS` | Configured | Refresh token lifetime | Optional (defaults to 90) |
| `ALLOW_UNAUTHENTICATED` | Configured | Development bypass flag | Optional (ignored in prod) |
| `SMS_BACKEND` | Configured | Mode of SMS delivery (`console` vs `http`) | Mandatory |
| `SMS_API_URL` | Missing / Empty | Gateway URL for production SMS | Optional in dev |
| `SMS_API_KEY` | Missing / Empty | Authentication key for SMS gateway | Optional in dev |
| `SMS_SENDER_ID` | Missing / Empty | DLT approved 6-character sender ID | Optional in dev |
| `SMS_TEMPLATE_ID` | Missing / Empty | DLT approved message template ID | Optional in dev |
| `PUBLIC_BASE_URL` | Configured | Root URL for signing media streaming links | Mandatory |
| `CORS_ORIGINS` | Configured | Permitted dashboard origins for web access | Mandatory |
| `STORAGE_BACKEND` | Configured | Video persistence driver (`local` vs `s3`) | Mandatory |
| `STORAGE_LOCAL_PATH` | Configured | Disk path for locally stored videos | Mandatory if local |
| `S3_BUCKET` | Missing / Empty | AWS S3 bucket name | Mandatory if S3 |
| `S3_REGION` | Configured | AWS region identifier | Mandatory if S3 |
| `UPLOAD_CHUNK_SIZE_BYTES` | Configured | Expected chunk size for resumable uploads | Optional (defaults to 256KB) |
| `UPLOAD_MAX_FILE_BYTES` | Configured | Maximum allowable video upload size | Optional (defaults to 200MB) |

### Dashboard (`dashboard/.env`)

| Variable | Configured Status | Purpose | Development Requirement |
|---|---|---|---|
| `VITE_API_BASE_URL` | Configured | Base backend API URL | Optional in dev (Vite proxies `/api`) |

---

## 9. End-to-End Flows

### FLOW 1: Android Login
```
CLIENT (Android LoginScreen)
  │  POST /api/auth/request-otp {"phone": "9876543210"}
  ▼
ENDPOINT: /api/auth/request-otp
  ▼
ROUTER: app.routers.auth.request_otp
  ▼
SERVICE: app.services.otp.OtpService.request
  ▼
DATABASE: Table `otp_challenges` (Inserts HMAC hash of code, expires_at)
  ▼
RESPONSE: {"message": "Verification code sent", "development_code": "000000"}
  │
CLIENT (Android LoginScreen)
  │  POST /api/auth/verify-otp {"phone": "9876543210", "otp": "000000"}
  ▼
ENDPOINT: /api/auth/verify-otp
  ▼
ROUTER: app.routers.auth.verify_otp
  ▼
SERVICE: OtpService.verify & TokenService.issue_token_pair
  ▼
DATABASE: Table `otp_challenges` (Mark consumed) & Table `refresh_tokens` (Store rotated token)
  ▼
RESPONSE: {"access_token": "...", "refresh_token": "...", "athlete_id": "...", "registered": true}
  │
CLIENT: Stores tokens in EncryptedSharedPreferences; attaches `Authorization: Bearer <token>` to future calls.
```

### FLOW 2: Android Registration
```
CLIENT (Android RegistrationScreen)
  │  POST /api/athletes/register (Multipart form: name, dob, gender, region, photo bytes)
  ▼
ENDPOINT: /api/athletes/register
  ▼
ROUTER: app.routers.athletes.register_athlete
  ▼
SERVICE: storage.store_bytes (Writes photo) & TokenService.issue_token_pair
  ▼
DATABASE: Table `athletes` (Inserts new row with reference_face_key) & Table `refresh_tokens`
  ▼
RESPONSE: AthleteResponse {"athlete_id": "...", "name": "...", "region": "...", "registered": true}
```

### FLOW 3: Android Exercise Submission & Verification
```
CLIENT (Android CaptureScreen / ResultsScreen / SyncWorker)
  │  1. Video recorded locally -> Media3 transcodes to 480p H.264
  │  2. POST /api/videos/upload/init -> PUT /chunks/{n} -> POST /complete
  ▼
ENDPOINT: /api/videos/upload/*
  ▼
ROUTER: app.routers.videos
  ▼
SERVICE: app.services.uploads.UploadManager (Assembles chunks & verifies SHA-256)
  ▼
DATABASE: Table `upload_sessions` & Table `videos`
  │
CLIENT (SyncWorker)
  │  POST /api/tests/submit {"test_type": "SIT_UPS", "video_id": "...", "device_score": 24.0}
  ▼
ENDPOINT: /api/tests/submit
  ▼
ROUTER: app.routers.tests_submit.submit_test
  ▼
DATABASE: Table `test_results` (status: "processing")
  │
CELERY WORKER (tasks.verify_submission)
  ▼
SERVICE: PoseLandmarkExtractor -> SitUpAnalyzer -> CheatDetectionPipeline -> DiscrepancyEvaluator
  ▼
DATABASE: Table `test_results` (status: "verified" / "flagged", server_score: 24.0), Table `flags`, Table `face_verifications`
  ▼
RESPONSE: Asynchronous result visible via GET /api/tests/results/{result_id}
```

### FLOW 4: Dashboard Login
```
CLIENT (Dashboard LoginPage)
  │  POST /api/dashboard/auth/login {"email": "...", "password": "..."}
  ▼
ENDPOINT: /api/dashboard/auth/login
  ▼
ROUTER: app.routers.dashboard_auth.login
  ▼
SERVICE: app.services.passwords.verify_password (scrypt) & OfficialTokenService.issue_token_pair
  ▼
DATABASE: Table `officials` (Checks lockout & role) & Table `refresh_tokens`
  ▼
RESPONSE: OfficialLoginResponse {"access_token": "...", "official": {"role": "sai_admin", ...}}
  │
CLIENT: Stores tokens in sessionStorage; attaches Bearer token to all administrative queries.
```

### FLOW 5: Dashboard Reviews
```
CLIENT (Dashboard QueuePage)
  │  GET /api/dashboard/reviews?status=flagged&region=Delhi&limit=25
  ▼
ENDPOINT: /api/dashboard/reviews
  ▼
ROUTER: app.routers.dashboard.review_queue
  ▼
DATABASE: Queries `test_results` JOIN `athletes` JOIN `flags`, enforcing official's region scope
  ▼
RESPONSE: List of ReviewQueueItem objects
  │
CLIENT (Dashboard ReviewPage & ActionForm)
  │  POST /api/dashboard/reviews/{id}/action {"action": "approved", "final_score": 24, "notes": "Clear reps"}
  ▼
ENDPOINT: /api/dashboard/reviews/{result_id}/action
  ▼
ROUTER: app.routers.dashboard.act_on_review
  ▼
DATABASE: Table `review_actions` (Insert audit record) & Table `test_results` (status -> "approved", final_score -> 24.0)
  ▼
RESPONSE: {"status": "approved", "message": "Result updated"}
```

### FLOW 6: Dashboard Leaderboard
```
CLIENT (Dashboard LeaderboardPage)
  │  GET /api/dashboard/leaderboard?test_type=VERTICAL_JUMP&gender=female&region=Haryana
  ▼
ENDPOINT: /api/dashboard/leaderboard
  ▼
ROUTER: app.routers.dashboard.leaderboard
  ▼
DATABASE: Table `tests` (checks higher_is_better) JOIN `test_results` (filters status="approved") JOIN `athletes`
          Aggregates best attempt per athlete grouped by athlete_id
  ▼
RESPONSE: LeaderboardResponse {"test_type": "VERTICAL_JUMP", "entries": [{"rank": 1, "score": 52.0, ...}]}
```

---

## 10. Physical Phone Readiness

| Requirement | Status | Evidence | Required Action |
|---|---|---|---|
| Base URL for Physical Wi-Fi | **BLOCKER** | `SyncConfig.kt:34` defaults non-emulators to `http://127.0.0.1:8000`. Over Wi-Fi, `127.0.0.1` hits the phone's loopback and fails. | Must point `DEFAULT_BASE_URL` to host machine's LAN IP (e.g. `http://10.190.14.101:8000`) or provide an in-app server URL dialog. |
| USB Reverse Tunneling | **READY** | Port forwarding verified via `adb reverse tcp:8000 tcp:8000`. | Works over USB cable only; does not work untethered. |
| In-App Configurable URL | **PARTIAL** | `SyncConfig.setBaseUrl()` exists but has no UI screen or input field calling it. | Add a developer URL configuration input on the login screen. |
| Cleartext HTTP Allowed | **READY** | `AndroidManifest.xml:15` has `android:usesCleartextTraffic="true"`. | None for local testing. |
| Internet Permission | **READY** | `AndroidManifest.xml:9` declares `android.permission.INTERNET`. | None. |
| Camera Permission | **READY** | `AndroidManifest.xml:4` declares `android.permission.CAMERA`. | None. |
| Backend Host Binding | **READY** | Backend command uses `--host 0.0.0.0 --port 8000`, accepting LAN traffic. | Ensure Windows Defender Firewall allows incoming connections on port 8000. |
| Media URL Construction | **NEEDS FIX** | `app/config.py:73` sets `public_base_url = "http://localhost:8000"`. Signed media URLs sent to phone use `localhost`. | Set `PUBLIC_BASE_URL=http://<YOUR_LAN_IP>:8000` in `backend/.env`. |

---

## 11. Configuration Mismatches

1. **Docker Compose vs .env.example Documentation:**
   - `docker-compose.yml` maps Postgres to host port **5434** and Redis to host port **6380**.
   - `backend/.env.example` still lists port **5433** and **6379** in its comments and sample strings.
2. **Dashboard Production URL:**
   - `dashboard/.env` contains `VITE_API_BASE_URL=http://localhost:8000`. If accessed from a mobile browser on the same Wi-Fi, it will attempt to contact `localhost` instead of the PC's LAN IP.
3. **Public Base URL:**
   - `PUBLIC_BASE_URL` in `backend/.env` is set to `http://localhost:8000`. When a physical device requests a signed video URL, the returned link starts with `http://localhost:8000/api/media/...`, which cannot be loaded by an untethered phone.

---

## 12. Security Findings

### Development-Only (Permissible for Local Testing)
- `ALLOW_UNAUTHENTICATED=true`: Enables development convenience. The code explicitly ignores this setting when `ENVIRONMENT=production`.
- `SMS_BACKEND=console`: Development OTP is fixed to `000000` and returned in API responses to facilitate automated and manual testing without SMS credits.
- `android:usesCleartextTraffic="true"`: Required for local testing over unencrypted HTTP.
- Hardcoded scrypt development credentials for initial official seed.

### Production Blockers (Must Resolve Prior to Deployment)
- **Cleartext HTTP:** The mobile app transmits athlete metadata, tokens, and videos over unencrypted HTTP. Production deployment requires HTTPS/TLS certificates.
- **Unrestricted Video Storage (Local):** `STORAGE_BACKEND=local` writes video assets directly to local server disk without backup or disaster recovery. Production mandates AWS S3 with server-side encryption (`AES256`).
- **DLT Regulatory Compliance:** Indian telecom regulations require commercial SMS delivery to register under Distributed Ledger Technology (DLT) headers and templates. Without `SMS_BACKEND=http` and valid DLT IDs, no commercial SMS can be delivered.
- **Parental Consent Missing:** Because the platform assesses youth athletes, explicit verifiable parental/guardian consent is legally required prior to biometric/video ingest.

---

## 13. Missing / Broken Components

1. **Shuttle Run & Endurance Run (Sprint 10):**
   - Completely absent from both mobile and backend.
   - Mobile only implements `SitUpAnalyzer` and `VerticalJumpAnalyzer`.
   - Backend verification and database seed only recognize `SIT_UPS` and `VERTICAL_JUMP`.
2. **Reference Validation Videos:**
   - The directory `docs/reference-videos/` contains no sample recordings or ground-truth verification CSVs.
3. **In-App Base URL Configuration UI:**
   - Although `SyncConfig.setBaseUrl()` is implemented, there is no settings dialog or developer toggle in the Compose UI. Users on physical devices cannot update the target IP without rebuilding the app or running `adb` commands.
4. **Crash Reporting SDK:**
   - Sentry or equivalent error tracking is not integrated in either the mobile app or backend service.

---

## 14. Recommended Test Order

Execute verification in the following strict sequential dependency order:

1. **PC Database:** Confirm PostgreSQL container is healthy on port 5434 (`f4all_db`).
2. **Redis:** Confirm Redis container is healthy on port 6380 (`redis-cli ping`).
3. **PC Backend:** Launch FastAPI on `0.0.0.0:8000` and verify `/health` and `/docs`.
4. **Dashboard:** Start Vite dev server on port 5173 and confirm `/api/dashboard/stats` loads without errors.
5. **Android Emulator:** Test end-to-end user journey on Android Studio Emulator (`10.0.2.2:8000`).
6. **Physical Android Phone (USB Tethered):** Run `adb reverse tcp:8000 tcp:8000` and launch the app.
7. **Registration:** Submit athlete profile with a real captured camera photo.
8. **Login:** Request OTP for a registered phone number.
9. **OTP Verification:** Enter `000000` and confirm arrival at `HomeScreen`.
10. **Face Verification Asset Check:** Confirm captured face photo is stored under `var/storage/`.
11. **Exercise Capture:** Execute a 30-second sit-up test; observe live skeleton rendering and counter.
12. **Video Upload:** Monitor WorkManager compressing to 480p and transmitting 256KB chunks.
13. **Result Processing (Celery):** Ensure Celery worker picks up `verification.reverify` and persists `server_score`.
14. **Dashboard Review:** Log into `localhost:5173`, inspect flagged/verified recording, and approve.
15. **Leaderboard:** Check that approved score appears on both the mobile and administrative leaderboards.

---

## 15. FINAL VERDICT

The core platform—comprising the **FastAPI backend**, **PostgreSQL database schema**, **Redis-backed Celery worker**, **React administrative dashboard**, and **Jetpack Compose mobile client**—is fully implemented and operational for the MVP exercise battery (**Vertical Jump** and **Sit-ups**).

The system currently runs successfully on local developer environments and via USB-tethered Android devices using `adb reverse`. **It is not yet ready for wireless physical phone deployment over local Wi-Fi** because the Android app's base URL defaults to `127.0.0.1` and the backend's signed media URL defaults to `localhost`. Updating these two host addresses to your computer's local network IP will enable physical mobile testing over Wi-Fi.

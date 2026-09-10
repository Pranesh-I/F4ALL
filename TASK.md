# F4ALL — Implementation Task Board

Phase-by-phase task tracker derived from [README.md](README.md) (Section 6 — Full Sprint-Wise Workflow).
Update the checkboxes as work lands. **Do not tick a sprint's Definition of Done until every task above it is ticked.**

## Legend

- `[x]` — done and verified in the repo
- `[ ]` — not started / incomplete
- `[~]` — partially done (details in the note beside it)
- **DoD** — Definition of Done, the gate that closes a sprint

## Current Position

| Sprint | Title | Status |
|---|---|---|
| 0 | Foundations | `[~]` Cloud + design + reference data pending |
| 1 | Capture UI + Camera Pipeline | `[~]` Low-end device validation pending |
| 2 | On-Device Pose Estimation | `[~]` Low-end benchmark pending |
| **3** | **Rep-Counting & Jump Measurement** | **`[~]` Built + unit-tested; accuracy validation blocked on reference videos** |
| **4** | **Offline Queue, Compression & Sync** | **`[~]` Built + 99 tests; backend now exists — DoD walk needs only a device** |
| **5** | **Backend Ingest + Re-Verification** | **`[~]` Built + 63 tests; SLA + Postgres unmeasured** |
| **6** | **Cheat Detection v1** | **`[x]` Complete — 114 tests, DoD met against rendered tampered video** |
| **7** | **Auth, Profiles & Benchmarking** | **`[ ]` ← NEXT** |
| 8 | Official Dashboard v1 | `[ ]` Not started (`dashboard/` empty) |
| 9 | Gamification & UX Polish | `[ ]` Not started |
| 10 | Shuttle Run & Endurance Run | `[ ]` Not started |
| 11–12 | Hardening | `[ ]` Not started |
| 13–14 | Field Pilot | `[ ]` Not started |

---

## Sprint 0 — Foundations

> **Goal:** Everything is set up so Sprint 1 can start writing feature code on day one.

- [x] Finalize MVP test scope: vertical jump + sit-ups only for first release
- [x] Set up GitHub repo (mono-repo: `mobile/`, `backend/`, `dashboard/`, `docs/`)
- [~] Set up CI skeleton — [.github/workflows/ci.yml](.github/workflows/ci.yml) only checks directory structure
  - [ ] Add Gradle build + lint job for `mobile/`
  - [ ] Add Python lint (ruff) + import check job for `backend/`
  - [ ] Add Node build + typecheck job for `dashboard/`
- [ ] Create AWS account, claim free-tier credits, set up IAM users (never use root credentials in code)
- [ ] Set up S3 bucket (private, versioned) for raw video storage
- [~] Set up PostgreSQL instance
  - [x] Local Docker for dev — [docker-compose.yml](docker-compose.yml) (port 5433)
  - [ ] RDS free tier for staging
- [ ] Figma wireframes: onboarding, test capture screen, results screen, dashboard review screen
- [x] Define DB schema v1 — [docs/db-schema-v1.md](docs/db-schema-v1.md) (9 tables incl. officials, review_actions, benchmarks)
- [x] Write API contract (OpenAPI spec) — [docs/openapi.yaml](docs/openapi.yaml) (auth, submit-test, results, dashboard review)
- [ ] Collect 10+ reference test videos (vertical jump + sit-ups, multiple people) into `docs/reference-videos/`
  - [x] `.gitignore` rule for reference video files already in place
  - [ ] Manually ground-truth each video (rep count / jump height) into a CSV alongside them

**DoD**
- [ ] Repos exist with CI passing on empty projects
- [x] DB schema reviewed
- [x] API contract written (even if not implemented)
- [ ] 10+ reference videos collected

---

## Sprint 1 — Capture UI + Camera Pipeline

> **Goal:** App can record a video reliably and you can pull raw frames for processing.
> Notes: [docs/SPRINT-1.md](docs/SPRINT-1.md)

- [x] Android project setup, Jetpack Compose navigation skeleton — [AppNavigation.kt](mobile/app/src/main/java/com/sai/sports/navigation/AppNavigation.kt)
- [x] CameraX integration: preview, record, save to local storage — [CaptureScreen.kt](mobile/app/src/main/java/com/sai/sports/ui/capture/CaptureScreen.kt)
- [x] Capture screen UI: test selector, instructions overlay, record/stop controls, countdown timer
- [x] Handle permissions (camera, storage) properly with rationale screens
- [~] Test on at least 2 real devices
  - [x] Mid-range device (OPPO) — full flow verified
  - [ ] Genuinely low-end/old Android device — **BLOCKED: device not yet available**
- [x] Basic frame extraction pipeline — [FrameExtractor.kt](mobile/app/src/main/java/com/sai/sports/utils/FrameExtractor.kt) (500 ms intervals, per-video JPEG dir, IO dispatcher)

**DoD**
- [~] Record a test video end-to-end on a low-end device without crashes — verified on mid-range only
- [x] Extract frames from it programmatically

---

## Sprint 2 — On-Device Pose Estimation Integration

> **Goal:** Live pose landmarks are being extracted reliably from camera frames.

- [x] Integrate MediaPipe Pose Landmarker (TFLite) — [PoseLandmarkerHelper.kt](mobile/app/src/main/java/com/sai/sports/PoseLandmarkerHelper.kt) + `assets/pose_landmarker_lite.task`
- [x] Run in real-time on the camera preview stream (`RunningMode.LIVE_STREAM`, `detectAsync`)
- [~] Benchmark inference speed/battery drain on the low-end test device
  - [x] Live FPS counter wired into the capture screen
  - [ ] Recorded FPS numbers on a genuinely low-end device — **BLOCKED on same device as Sprint 1**
  - [ ] Battery drain measurement over a 10-minute session
- [ ] Fallback path (only if real-time proves too heavy): record first, then run pose estimation on saved frames on-device
- [x] Visualize landmarks on screen (skeleton overlay) — [PoseOverlayView.kt](mobile/app/src/main/java/com/sai/sports/ui/capture/PoseOverlayView.kt)
- [x] Log landmark confidence scores for later quality filtering

**DoD**
- [x] Skeleton overlay renders correctly on live video
- [~] Acceptable frame rate (15+ fps minimum) — met on mid-range, unverified on low-end

---

## Sprint 3 — Rep-Counting & Jump Measurement Logic

> **Goal:** The app can actually score a sit-up test and a vertical jump test from pose data.
> Implementation complete and unit-tested; see [docs/SPRINT-3.md](docs/SPRINT-3.md).
> **Accuracy validation is blocked on the Sprint 0 reference videos.**

### 3.1 Shared pose infrastructure
- [x] Angle calculation between three landmarks (`PoseMath.angle`)
- [x] Landmark visibility gate (`PoseMath` + [FrameQualityGate.kt](mobile/app/src/main/java/com/sai/sports/analyzer/FrameQualityGate.kt))
- [x] Landmark smoothing filter — One-Euro, [PoseSmoother.kt](mobile/app/src/main/java/com/sai/sports/analyzer/PoseSmoother.kt) (adaptive: no lag at the jump peak)
- [x] Frame-quality gate: skip frames where required landmarks fall below the visibility threshold
- [x] Shared result model — [AnalyzerModels.kt](mobile/app/src/main/java/com/sai/sports/analyzer/AnalyzerModels.kt) (score, unit, confidence, event trace)
- [x] Framework-independent pose model so analyzers are JVM-testable and portable to the Sprint 5 Python port — [PoseModels.kt](mobile/app/src/main/java/com/sai/sports/analyzer/PoseModels.kt), [MediaPipeMapper.kt](mobile/app/src/main/java/com/sai/sports/analyzer/MediaPipeMapper.kt)
- [x] All tunable constants centralised — [AnalyzerThresholds.kt](mobile/app/src/main/java/com/sai/sports/analyzer/AnalyzerThresholds.kt)

### 3.2 Sit-ups — state machine
- [x] Required landmarks with side auto-selection by visibility (locked on first good frame)
- [x] Compute torso angle (shoulder–hip–knee) per frame
- [x] DOWN / UP state machine with hysteresis (135° / 70°, no flicker)
- [~] Calibrate up/down angle thresholds — starting values set; **real calibration blocked on reference videos**
- [x] Reject partial reps (must cross both thresholds), reported to the athlete
- [x] Minimum rep duration guard — 200ms; **caught a bug where 600ms silently deleted reps at normal cadence**
- [x] Expose live rep count to the capture screen
- [x] Unit tests over recorded landmark sequences — [SitUpAnalyzerTest.kt](mobile/app/src/test/java/com/sai/sports/analyzer/SitUpAnalyzerTest.kt) (11 tests)

### 3.3 Vertical jump — displacement measurement
- [x] Calibration: athlete height → pixel-to-cm ratio from standing reference frame
- [x] Stable standing reference (12 consecutive stable frames, runs during the 3-2-1 countdown)
- [x] Track hip vertical displacement across the jump
- [x] Detect takeoff → peak → landing; best of multiple jumps reported
- [x] Convert pixel displacement to centimetres
- [x] Reject invalid attempts (subject leaves frame, no clean landing, athlete/camera moved via apparent-stature drift)
- [x] Unit tests over recorded landmark sequences — [VerticalJumpAnalyzerTest.kt](mobile/app/src/test/java/com/sai/sports/analyzer/VerticalJumpAnalyzerTest.kt) (11 tests)

### 3.4 Validation against ground truth
- [x] Accuracy targets defined explicitly — ±1 rep, ±3cm in `AnalyzerThresholds`
- [x] Batch harness: replays sequences through the live analyzer classes, dumps CSV — [ValidationHarness.kt](mobile/app/src/test/java/com/sai/sports/analyzer/ValidationHarness.kt)
- [x] Reference-data scaffolding + collection guide — [docs/reference-videos/](docs/reference-videos/)
- [ ] **Compare against manual ground truth for 15–20 videos of different people** — BLOCKED: no reference videos collected (Sprint 0 task)
- [ ] **Iterate thresholds until targets are met** — BLOCKED on the same
  - Highest-risk parameter is `SITUP_UP_ENTER_ANGLE`; sensitivity table in [docs/SPRINT-3.md](docs/SPRINT-3.md)

### 3.5 Results screen
- [x] Local scoring/results screen showing provisional score — [ResultsScreen.kt](mobile/app/src/main/java/com/sai/sports/ui/results/ResultsScreen.kt)
- [x] Skeleton replay of the recorded attempt (play/pause/scrub)
- [x] Tracking-quality readout and attempt log (why reps were rejected)
- [x] Retry / done actions
- [x] "Provisional score" notice — the athlete is never surprised when SAI's number differs
- [x] Attempt persistence surviving process death — [AttemptStore.kt](mobile/app/src/main/java/com/sai/sports/data/AttemptStore.kt)
- [x] Write [docs/SPRINT-3.md](docs/SPRINT-3.md)

**DoD**
- [ ] Both algorithms produce results within the defined accuracy target against manually verified ground truth — **BLOCKED on reference videos**
- [ ] Tested across at least 15–20 reference videos of different people — **BLOCKED on reference videos**
- [x] Algorithms implemented and behaviourally verified (50 unit tests, all green)
- [x] Batch validation harness ready to enforce the targets the moment data exists

---

## Sprint 4 — Offline Queue, Compression & Sync

> **Goal:** A test recorded with no internet connection reliably reaches the server once connectivity returns.
> Implementation complete, 98 unit tests green; see [docs/SPRINT-4.md](docs/SPRINT-4.md).
> **The end-to-end DoD walk is blocked: there is no backend to upload to until Sprint 5.**

### 4.1 Local queue
- [x] Room entity with the full status state machine: `RECORDED → COMPRESSING → COMPRESSED → QUEUED → UPLOADING → SYNCED`, plus `FAILED` — [SyncStatus.kt](mobile/app/src/main/java/com/sai/sports/sync/SyncStatus.kt), [TestAttemptEntity.kt](mobile/app/src/main/java/com/sai/sports/data/local/TestAttemptEntity.kt)
- [x] Room DAO + repository layer — [TestAttemptDao.kt](mobile/app/src/main/java/com/sai/sports/data/local/TestAttemptDao.kt), [SyncRepository.kt](mobile/app/src/main/java/com/sai/sports/data/SyncRepository.kt)
- [x] Transition rules enforced in one place; illegal transitions refused, not thrown
- [x] Schema v1 exported and committed (`mobile/app/schemas/`) so future migrations are diff-reviewable
- [x] No destructive migration fallback — this table can hold the only record of a test
- [x] Recovery from process death (COMPRESSING → RECORDED, UPLOADING → QUEUED) on app start
- [x] **Fixed 3 livelock paths** where a vanished transcode left an attempt retrying forever — [SyncRecoveryTest.kt](mobile/app/src/test/java/com/sai/sports/sync/SyncRecoveryTest.kt)

### 4.2 Compression
- [x] Compress to 480p H.264 before upload — [VideoCompressor.kt](mobile/app/src/main/java/com/sai/sports/sync/VideoCompressor.kt)
- [x] **Media3 Transformer substituted for FFmpeg-Kit** — the README's dependency is retired and removed from Maven Central; the replacement is hardware-accelerated, which matters more on a ₹8,000 phone. Rationale in [docs/SPRINT-4.md](docs/SPRINT-4.md)
- [x] Runs off the main thread, cancellable with the worker
- [x] Scales the SHORT side to 480 so portrait recordings keep the detail the Sprint 5 server pose pass needs

### 4.3 Upload
- [x] Chunked/resumable upload client — [UploadClient.kt](mobile/app/src/main/java/com/sai/sports/sync/UploadClient.kt)
- [x] Resumes from the last chunk the **server** confirms, not what the client believes it sent
- [x] `upload_id` persisted before any bytes move, so a killed process costs one chunk not the whole video
- [x] SHA-256 computed on-device over the exact uploaded bytes — [Checksum.kt](mobile/app/src/main/java/com/sai/sports/sync/Checksum.kt)
- [x] **Added the missing upload endpoints to the API contract** — Sprint 0's spec referenced a `video_id` with no endpoint that produces one ([docs/openapi.yaml](docs/openapi.yaml))
- [x] Verified against MockWebServer: byte-identical reassembly, resume, drops, checksum mismatch — [UploadClientTest.kt](mobile/app/src/test/java/com/sai/sports/sync/UploadClientTest.kt)

### 4.4 Background scheduling
- [x] WorkManager job with a connectivity constraint — [SyncWorker.kt](mobile/app/src/main/java/com/sai/sports/sync/SyncWorker.kt), [SyncScheduler.kt](mobile/app/src/main/java/com/sai/sports/sync/SyncScheduler.kt)
- [x] One-shot kick after recording + 15-minute periodic safety net
- [x] Exponential backoff with jitter, capped attempts — [RetryPolicy.kt](mobile/app/src/main/java/com/sai/sports/sync/RetryPolicy.kt)
- [x] Transient failures retry; terminal ones (401, checksum mismatch) go straight to FAILED
- [x] `NetworkType.CONNECTED` not `UNMETERED` — a wifi-only upload never happens for these athletes
- [x] Blocking network + hashing moved off the CPU dispatcher

### 4.5 Sync status UI
- [x] Per-test state with plain-language labels — [SyncStatusScreen.kt](mobile/app/src/main/java/com/sai/sports/ui/sync/SyncStatusScreen.kt)
- [x] Pending count on the home screen, not buried in a menu
- [x] Upload progress bar, failure reasons, manual retry
- [x] "Sent to SAI" never says "verified" — the score stays provisional until Sprint 5/8
- [x] Write [docs/SPRINT-4.md](docs/SPRINT-4.md)

**DoD**
- [ ] **Record in airplane mode, close the app, reopen with wifi — video compresses and uploads automatically** — **BLOCKED: no backend exists until Sprint 5; no low-end device available**
- [x] Every component of that journey individually implemented and unit-tested (98 tests)
- [x] Resumable upload verified end-to-end against a mock server, including the resume-after-drop path

---

## Sprint 5 — Backend Ingest + Server-Side Re-Verification

> **Goal:** Uploaded videos are received, stored, and independently re-scored by the server.
> Implementation complete, 63 tests green, lint clean; see [docs/SPRINT-5.md](docs/SPRINT-5.md).
> **SLA timing and PostgreSQL remain unmeasured** — see the DoD below.

### 5.1 API foundation
- [x] FastAPI scaffold, app factory, pydantic-settings, `.env.example` — [backend/app/main.py](backend/app/main.py)
- [x] Health, readiness, and an SLA-backlog endpoint — [health.py](backend/app/routers/health.py)
- [x] Auth middleware verifying JWTs and resolving athlete/official — [security.py](backend/app/security.py)
  - Fails closed: the dev bypass is ignored in production regardless of config
- [x] Structured JSON logging with per-request correlation ids — [logging_config.py](backend/app/logging_config.py)
- [x] All endpoints in [docs/openapi.yaml](docs/openapi.yaml) implemented
- [x] Chunked upload receiver matching the Sprint 4 mobile client — [videos.py](backend/app/routers/videos.py), [uploads.py](backend/app/services/uploads.py)
  - Verified over real HTTP: resume-after-interruption reassembles byte-identically

### 5.2 Storage & database
- [x] Storage behind one interface: S3 (encrypted, signed URLs only) or local disk — [storage.py](backend/app/storage.py)
  - Local default because Sprint 0's AWS account does not exist; production refuses local
- [x] SQLAlchemy models for all 9 schema tables, plus `upload_sessions` — [models.py](backend/app/models.py)
- [x] Alembic setup + initial migration — applies **and reverses** cleanly
- [x] Seed command for the test battery — `python -m app.cli seed` (idempotent)
- [x] Server recomputes SHA-256 over what it assembled; mismatch → 422, non-retryable

### 5.3 Async verification pipeline
- [x] Redis added to [docker-compose.yml](docker-compose.yml) with a healthcheck
- [x] Celery worker — [worker.py](backend/app/worker.py), [tasks.py](backend/app/tasks.py)
  - `task_acks_late` + `reject_on_worker_lost`: a killed worker returns the job, never loses it
- [x] Server-side pose pipeline extracting its **own** landmarks from the video — [extractor.py](backend/app/verification/extractor.py)
  - Verified against real MediaPipe on a real video file
- [x] Sprint 3 algorithms ported to Python — [analyzers.py](backend/app/verification/analyzers.py)
- [x] **Cross-language parity enforced** — [test_parity.py](backend/tests/test_parity.py) asserts identical scores on 10 fixtures + all 18 thresholds
  - CI regenerates fixtures from the mobile source and fails if the committed ones are stale
- [x] Discrepancy comparison and auto-flagging with reason, detail, severity — [discrepancy.py](backend/app/verification/discrepancy.py)
- [x] Both scores persisted; status set to verified / flagged
  - `final_score` is written **only** on human approval, never by the machine
- [x] Backend in CI: ruff lint, tests, migration up/down, plus a dedicated parity job
- [x] **Fixed: a Redis outage hung every submission** — the Celery *result backend*'s own retry loop blocked `apply_async`. Removed entirely; nothing read task return values. [test_broker_resilience.py](backend/tests/test_broker_resilience.py)

**DoD**
- [ ] **Uploaded video independently re-scored within the SLA (under 5 minutes)** — **NOT MEASURED: needs a Celery worker under load with a real athlete video.** Instrumented via `GET /health/verification` and `python -m app.cli sla`
- [ ] **A mismatch case correctly auto-flagged end to end** — unit-verified in [test_discrepancy.py](backend/tests/test_discrepancy.py); not yet run through a live worker
- [ ] **Verified against PostgreSQL** — Docker was unavailable; every check ran on SQLite. Models avoid dialect-specific types and the migration applies cleanly, but this is an assumption until `docker compose up`
- [x] Video uploaded from the app is received, stored and queued for verification (verified over real HTTP)
- [x] Server re-scores independently of the device, from its own extracted landmarks
- [x] Discrepancies auto-flag with a reason a reviewer can act on

---

## Sprint 6 — Cheat Detection v1

> **Goal:** Obvious manipulation attempts get caught automatically.

> Implementation complete, 114 tests green (none skipped), lint clean; see [docs/SPRINT-6.md](docs/SPRINT-6.md).
> Every check runs off Sprint 5's **single decode pass**, so the SLA is not multiplied per check.

- [x] Frame-consistency checks: perceptual hash comparison to detect duplicated/looped frames — [frames.py](backend/app/verification/cheat/frames.py)
  - [x] **Hashes alone do not work** and the measurement is recorded: after the mobile 480p transcode, hash distance for true copies (8.4 mean) overlaps honest frames one rep apart (3.0 min). A 16x16 greyscale signature separates them tenfold. Hash prefilters, signature confirms
  - [x] False-positive guards: 20-frame minimum run, 15-frame minimum gap, and a matched run must contain internal movement
- [x] Abrupt-cut detection (unnatural scene changes between adjacent frames)
- [x] Single-person-in-frame validation — [subject.py](backend/app/verification/cheat/subject.py) (sustained second person flagged; brief passer-by not; subject-swap detected)
- [x] Face crop from the test video — largest face, sampled across 12 frames — [face.py](backend/app/verification/cheat/face.py)
- [x] Face embedding similarity vs the athlete's registration photo (`athletes.reference_face_key`)
  - [x] Never returns `fail` and never `high` severity — an unvalidated embedder must not be given authority to accuse a minor
- [x] Persist results to the `face_verifications` table (`pass` / `fail` / `manual_review`)
  - [x] **No row at all when the check did not run** — an absent row means "not verified"; a `pass` row there would be a false assurance
- [x] Metadata sanity checks: duration vs expected test duration, resolution consistency, re-encoding artifacts — [metadata.py](backend/app/verification/cheat/metadata.py)
- [x] Flag reason system: store *why* something was flagged, with severity and a timestamp to look at, into the `flags` table
- [x] Build a tampered-video test set (looped clip, partial loop, spliced footage, still image, short clip, sped-up) — rendered, not committed — [test_tampered_videos.py](backend/tests/test_tampered_videos.py)
  - [x] Honest controls alongside every tampered case; a detector that flags everything passes all of them and is worse than none
- [x] **Fixed: `_persist_video_duration` referenced an unimported `Video`** — a guaranteed `NameError`, swallowed by the task's outer handler, which would have flagged *every* cleanly-scored submission with a Python error as the reason
- [x] **Fixed: 16 frame-check tests still called the removed hash-only API** and failed with `TypeError`. Fixtures rebuilt to derive both representations from one grid the way the extractor derives them from a real frame
- [x] `scripts/fetch_model.py` also fetches the two face models (optional — absent models report "not run")
- [x] Write [docs/SPRINT-6.md](docs/SPRINT-6.md)

**DoD**
- [x] **A deliberately tampered test video gets correctly auto-flagged in the test set** — 6 tampering modes against real rendered video, including a loop that survives a real re-encode
- [x] The honest control is **not** flagged — the property that keeps the review queue worth reading

---

## Sprint 7 — Auth, Athlete Profiles & Benchmarking Engine

> **Goal:** Athletes have real accounts, and their scores mean something against age/gender norms.

- [ ] OTP-based phone auth backend (request-otp / verify-otp per the OpenAPI contract)
- [ ] OTP rate limiting and expiry
- [ ] JWT issuance + refresh
- [ ] Mobile auth flow: phone entry, OTP entry, token storage (EncryptedSharedPreferences)
- [ ] Athlete registration: name, DOB, gender, region, self-reported height/weight
- [ ] Registration photo capture (feeds Sprint 6 face verification)
- [ ] Research SAI's published fitness benchmarks (Annexure A) and encode into the `benchmarks` table
- [ ] Benchmark seed/migration script
- [ ] Benchmark comparison service: given athlete age/gender + score, return percentile standing
- [ ] Instant feedback UI after a verified test: where the athlete stands vs their cohort
- [ ] Athlete profile screen: test history, personal bests, benchmark comparison

**DoD**
- [ ] A registered athlete completes a test and sees an accurate benchmark comparison based on their age/gender cohort

---

## Sprint 8 — Official Dashboard v1

> **Goal:** SAI officials can actually review and act on submitted results.
> Current state: `dashboard/` is empty.

- [ ] React + TypeScript project setup (Vite, Tailwind, React Query)
- [ ] Official auth with role-based access: `sai_admin` vs `regional_reviewer`
- [ ] Region scoping: regional reviewers only see their own region
- [ ] Review queue: pending/flagged submissions, filterable by region / test type / status
- [ ] Video review screen: playback with skeleton overlay
- [ ] Flag reasons displayed with severity
- [ ] On-device vs server score comparison view
- [ ] Approve / reject / request-resubmission actions
- [ ] Audit trail written to `review_actions` (who approved what, when, notes)
- [ ] Basic leaderboard view (top performers by test / region / age group)
- [ ] Add `dashboard/` to CI: typecheck + build

**DoD**
- [ ] An official can log in, review a flagged submission with full context, and approve or reject it — action is logged and reflected back to the athlete

---

## Sprint 9 — Gamification & UX Polish

> **Goal:** Athletes actually want to use this repeatedly, not just once.

- [ ] Progress badges: first test completed, personal best broken, consistency streaks
- [ ] Regional/age-group leaderboards visible to athletes
- [ ] Privacy controls: opt-in for public leaderboard display
- [ ] Onboarding flow polish
- [ ] Multi-language support: Hindi + 2–3 regional languages
- [ ] Instructional videos/diagrams for each test
- [ ] Low-bandwidth testing: throttle to 2G/3G, verify graceful degradation (no hangs, clear "queued for upload" states)
- [ ] Accessibility pass: font scaling, colour contrast, screen reader labels on key flows

**DoD**
- [ ] A non-technical test user outside the dev circle completes onboarding and a full test cycle without help, in a non-English language, on a throttled connection

---

## Sprint 10 — Shuttle Run & Endurance Run

> **Goal:** Extend the test battery beyond pose-only tests. Treat as its own mini-project.

- [ ] Shuttle run: keypoint centroid tracking / optical flow for lateral movement
- [ ] Shuttle run: accelerometer/gyroscope fusion for direction-change detection
- [ ] Marked-court setup instructions in the capture UI
- [ ] Endurance run: GPS track logging (outdoor)
- [ ] Endurance run: step-pattern / motion classification, distance & time calculation
- [ ] Sensor telemetry log upload (feeds `videos.sensor_telemetry_key`)
- [ ] Server-side re-verification for shuttle run
- [ ] Server-side re-verification for endurance run
- [ ] Update benchmarking engine for the new test types
- [ ] Update dashboard review screens for the new test types

**DoD**
- [ ] Both new tests produce server-verified results within the Sprint 3 accuracy targets, validated against manually timed/measured reference runs

---

## Sprint 11–12 — Hardening

> **Goal:** This stops being a demo and starts being something you could hand to real users and a real government body.

### Compatibility & load
- [ ] Device compatibility matrix: 5–6 Android devices across price tiers (₹6k–₹8k entry-level up to mid-range)
- [ ] Load test backend with Locust or k6 (concurrent uploads, queue processing under load)
- [ ] Define and meet a load target; document results

### Security (this app touches minors' data, location, and face data)
- [ ] Encrypt data at rest and in transit (TLS everywhere, S3 encryption)
- [ ] Data retention policy: how long videos are kept, who can access them
- [ ] Consent flows for minors (parental consent screen)
- [ ] Rate limiting on all public endpoints
- [ ] Input validation hardening
- [ ] Dependency vulnerability scanning in CI
- [ ] External security review sign-off

### Ops & docs
- [ ] Full multi-language pass on all user-facing text
- [ ] Sentry wired into mobile
- [ ] Sentry wired into backend
- [ ] Architecture document
- [ ] Runbook for common ops issues
- [ ] Generated API docs published

**DoD**
- [ ] Security checklist fully signed off
- [ ] App tested and stable across the full device matrix
- [ ] Backend survives the defined load test target without failure

---

## Sprint 13–14 — Field Pilot

> **Goal:** Real-world validation with real users, in the conditions the product is meant for.

- [ ] Recruit a pilot group including at least one genuinely rural / low-connectivity location
- [ ] Run structured pilot sessions; observe where users get stuck, where connectivity fails, where the UI confuses people
- [ ] Parallel manual scoring by a human coach/evaluator for accuracy comparison
- [ ] Compare app server-verified scores against human scores; compute error rates
- [ ] Fix what breaks, prioritized by real pilot feedback (not hypothetical edge cases)
- [ ] Tune benchmark tables using real pilot data
- [ ] Tune cheat-detection thresholds using real pilot data
- [ ] Write the pilot report: accuracy numbers, user feedback, known limitations, recommended next steps

**DoD**
- [ ] Real accuracy data from real users in real conditions
- [ ] Documented list of what works and what doesn't
- [ ] Clear post-MVP roadmap (Aadhaar e-KYC, iOS, more test types, scaling infra)

---

## Post-MVP Backlog (Do Not Build Early)

- [ ] Aadhaar/DigiLocker e-KYC integration for identity binding
- [ ] iOS app (Swift + Vision framework or MediaPipe iOS)
- [ ] Migration to government-empanelled cloud (NIC/MeghRaj)
- [ ] Advanced cheat detection (adversarial ML, deepfake detection)
- [ ] Coach/scout accounts and talent scouting workflows
- [ ] Height/weight measurement via computer vision (self-reported in MVP)

---

## Standing Invariants (check every PR against these)

1. **On-device score is provisional. Server score is truth.** Never let this invariant break.
2. **Low-end device performance is a feature, not an afterthought.**
3. **Every DoD is validated against real reference data, not "it looks like it works."**
4. **Cheat detection is never finished.** Ship v1, keep iterating.
5. **This handles minors' data and location/biometric-adjacent data.** Security is a Sprint 0 concern hardened in Sprint 11–12, not started there.

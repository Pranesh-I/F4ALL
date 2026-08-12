# SAI Sports Talent Assessment Platform
### AI-Powered Mobile Talent Identification System

> A mobile-first platform enabling athletes across India — especially in rural and remote areas — to record standardized fitness assessments, get AI-verified results on-device, and have their performance evaluated by the Sports Authority of India (SAI) through a secure, scalable pipeline.

---

## 1. Problem Statement (Reference)

SAI needs a low-cost, mobile-based solution to democratize sports talent assessment across India. The platform must let athletes record standard fitness tests (height, weight, vertical jump, shuttle run, sit-ups, endurance run), verify authenticity using on-device AI/ML, securely transmit verified data to SAI, and remain usable on entry-level smartphones over low-bandwidth networks.

---

## 2. Product Vision

Build a system where **the phone does the first pass of verification, and the server has the final say** — so it works offline-first for the athlete, but stays tamper-resistant for SAI. Success looks like: a first-generation athlete in a village with a ₹8,000 Android phone and patchy 3G can record a sit-up test, get instant feedback, and have it reliably queued for official evaluation without needing to travel anywhere.

---

## 3. Scope Decisions (Locked for MVP)

| Decision | Choice | Why |
|---|---|---|
| Platform (MVP) | Android only, native Kotlin | Best camera/ML performance on low-end devices; matches primary user base |
| iOS | Phase 2 | Not core to "reach rural India" mission |
| Framework | Native Kotlin + Jetpack Compose (NOT Flutter) | Real-time on-device pose inference on cheap phones is the performance-critical path — abstraction layers cost too much here |
| MVP test battery | Vertical Jump + Sit-ups first, then Shuttle Run + Endurance Run | Pose-based tests are tractable first; GPS/motion-based tests are a structurally different problem |
| Cloud | AWS (with awareness that production/govt deployment may later require NIC/MeghRaj empanelled cloud) | Most mature ecosystem, S3 is the storage standard, best documentation |
| On-device ML | MediaPipe Pose Landmarker (BlazePose) + TFLite | Purpose-built, runs on low-end hardware, free, Google-maintained |
| Backend | Python + FastAPI | Keeps ML re-verification and API in one ecosystem |
| Server-side truth | Server ALWAYS re-verifies; on-device score is provisional only | Prevents trivial gaming via modified client |

---

## 4. Final Architecture

```
┌─────────────────────────── MOBILE APP (Android, Kotlin) ────────────────────────────┐
│ CameraX Capture → MediaPipe Pose Landmarker (on-device) → Rep-counting / jump        │
│ state machines → Local provisional score → Room DB (offline queue) → FFmpeg          │
│ compression → WorkManager background sync → Chunked/resumable HTTPS upload           │
└───────────────────────────────────────┬──────────────────────────────────────────────┘
                                          │
┌─────────────────────────────────────────▼──────────────────────────────────────────┐
│                           BACKEND (FastAPI, Python, AWS)                            │
│ Auth (OTP, e-KYC later) → Ingest API → S3 video storage → Celery workers            │
│ (server-side re-verification using full pose pipeline) → Cheat-detection scoring    │
│ → PostgreSQL (athlete profiles, results) → Redis (queues, cache, leaderboards)      │
│ → Benchmarking engine (age/gender norms) → Notification service (FCM)               │
└───────────────────────────────────────┬──────────────────────────────────────────────┘
                                          │
┌─────────────────────────────────────────▼──────────────────────────────────────────┐
│                    OFFICIAL DASHBOARD (React + TypeScript)                          │
│ Review queue → Video playback with AI-flagged frames → Approve/reject workflow      │
│ → Athlete leaderboards → Regional analytics → Role-based access (SAI/regional admin)│
└────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Finalized Tech Stack

**Mobile**
- Kotlin, Jetpack Compose, CameraX
- MediaPipe Tasks (Pose Landmarker), TFLite (quantized int8)
- Room (offline DB), WorkManager (background sync)
- Media3/ExoPlayer, FFmpeg-Kit (compression)
- Retrofit/OkHttp (networking, resumable upload)

**Backend**
- Python 3.11+, FastAPI
- Celery + Redis (async job queue)
- PostgreSQL (primary DB)
- AWS S3 (video storage), AWS EC2/ECS or Elastic Beanstalk (compute)
- Firebase Auth or custom OTP service (MVP auth)
- Firebase Cloud Messaging (push notifications)

**Dashboard**
- React + TypeScript, Tailwind CSS, Recharts
- React Query for API state

**ML / CV**
- MediaPipe Pose Landmarker (device + server)
- OpenCV (frame analysis, optical flow for cheat detection)
- scikit-learn (anomaly scoring, once flagged data exists)

**DevOps**
- Docker, GitHub Actions (CI/CD)
- Sentry (error tracking), basic CloudWatch logging

---

## 6. Full Sprint-Wise Workflow

Each sprint = 2 weeks. Assume solo full-time or small team; adjust duration 1.5–2x if part-time. **Do not skip the "Definition of Done" — that's what prevents scope rot.**

---

### Sprint 0 — Foundations (Week 1)

**Goal:** Everything is set up so Sprint 1 can start writing feature code on day one.

**Tasks**
- Finalize MVP test scope: vertical jump + sit-ups only for first release
- Set up GitHub repo (mono-repo or 3 repos: `mobile`, `backend`, `dashboard`)
- Set up CI skeleton (GitHub Actions — lint + build check only for now)
- Create AWS account, claim free-tier credits, set up IAM users (never use root credentials in code)
- Set up S3 bucket (private, versioned) for raw video storage
- Set up PostgreSQL instance (local Docker for dev, RDS free tier for staging)
- Figma wireframes: onboarding, test capture screen, results screen, dashboard review screen
- Define DB schema v1: `athletes`, `tests`, `test_results`, `videos`, `flags`
- Write API contract (OpenAPI spec) for: auth, submit-test, get-results, dashboard review endpoints
- Get a handful of reference test videos of yourself/friends doing vertical jumps and sit-ups (you'll need these for calibration in Sprint 2–3)

**Definition of Done:** Repos exist with CI passing on empty projects; DB schema reviewed; API contract written (even if not implemented); 10+ reference videos collected.

---

### Sprint 1 — Capture UI + Camera Pipeline (Weeks 2–3)

**Goal:** App can record a video reliably and you can pull raw frames for processing.

**Tasks**
- Android project setup, Jetpack Compose navigation skeleton
- CameraX integration: preview, record, save to local storage
- Build the capture screen UI: test selector, instructions overlay, record/stop controls, countdown timer
- Handle permissions (camera, storage) properly with rationale screens
- Test on at least 2 real devices: one mid-range, one genuinely low-end/old Android device
- Set up basic frame extraction pipeline (get frames as bitmaps from recorded video)

**Definition of Done:** You can record a test video end-to-end on a low-end device without crashes, and extract frames from it programmatically.

---

### Sprint 2 — On-Device Pose Estimation Integration (Weeks 4–5)

**Goal:** Live pose landmarks are being extracted reliably from camera frames.

**Tasks**
- Integrate MediaPipe Pose Landmarker (TFLite) into the Android app
- Run it in real-time on the camera preview stream (not just post-recording) to validate feasibility
- Benchmark inference speed/battery drain on your low-end test device — this is your reality check moment
- If real-time proves too heavy on low-end hardware, fall back to: record first, then run pose estimation on saved video frames (still on-device, just not live)
- Visualize landmarks on screen (skeleton overlay) for debugging and as a nice UX touch
- Log landmark confidence scores — you'll need these later for quality filtering

**Definition of Done:** Skeleton overlay renders correctly on a recorded/live video on your low-end device, with acceptable frame rate (aim for 15+ fps minimum for usable rep-counting).

---

### Sprint 3 — Rep-Counting & Jump Measurement Logic (Weeks 6–7)

**Goal:** The app can actually score a sit-up test and a vertical jump test from pose data.

**Tasks**
- **Sit-ups:** Build a state machine using torso angle (shoulder-hip-knee angle) with calibrated up/down thresholds; count clean state transitions, reject partial reps
- **Vertical jump:** Track hip/ankle keypoint vertical displacement from a calibrated standing reference frame to peak frame; decide your calibration method now (known athlete height entered at registration + pixel-to-cm ratio from standing frame is the simplest MVP approach)
- Validate both algorithms against your reference videos — manually count reps/measure jump height and compare to what the algorithm outputs; iterate until error margin is acceptable (define a target, e.g., ±1 rep, ±3cm)
- Build the local scoring/results screen showing provisional score + skeleton replay

**Definition of Done:** Both algorithms produce results within your defined accuracy target against manually verified ground truth, tested across at least 15–20 reference videos of different people.

---

### Sprint 4 — Offline Queue, Compression & Sync (Weeks 8–9)

**Goal:** A test recorded with no internet connection reliably reaches the server once connectivity returns.

**Tasks**
- Room DB schema for local test records (status: recorded → compressed → queued → uploading → synced → failed)
- FFmpeg-Kit integration: compress video to target bitrate/resolution (480p H.264) before upload
- WorkManager background job: watches for connectivity, triggers upload when available, retries with backoff on failure
- Implement chunked/resumable upload (so a dropped connection mid-upload doesn't waste the athlete's data — resume from last successful chunk)
- Build a "sync status" UI so athletes can see what's pending/uploaded

**Definition of Done:** Record a test in airplane mode, close the app, reopen with wifi on later — video compresses and uploads automatically without user intervention.

---

### Sprint 5 — Backend Ingest + Server-Side Re-Verification (Weeks 10–11)

**Goal:** Uploaded videos are received, stored, and independently re-scored by the server — this is what makes results trustworthy.

**Tasks**
- FastAPI project setup: auth middleware, ingest endpoint (chunked upload receiver), health checks
- S3 upload integration (server stores raw video securely, private bucket, signed URLs only)
- PostgreSQL schema implementation: athletes, tests, results, videos, flags tables + migrations (Alembic)
- Celery worker setup with Redis broker
- Server-side pose re-verification job: runs the same (or a heavier, more accurate) pose pipeline on the uploaded video independently of the on-device score
- Compare on-device score vs server score — large discrepancy = auto-flag for review
- Store both scores in DB, mark final status: verified / flagged / rejected

**Definition of Done:** A video uploaded from the app is independently re-scored server-side within a reasonable time window (define an SLA, e.g., under 5 minutes), and a mismatch case correctly gets auto-flagged.

---

### Sprint 6 — Cheat Detection v1 (Weeks 12–13)

**Goal:** Obvious manipulation attempts get caught automatically.

**Tasks**
- Frame-consistency checks: detect duplicated/looped frames (hash comparison across frames), detect abrupt unnatural cuts
- Single-person-in-frame validation (reject/flag videos with multiple people or no clear subject)
- Basic liveness/identity check: compare a face crop from the test video against the athlete's registration photo (simple face embedding similarity, not a full biometric system for MVP)
- Metadata sanity checks: video duration vs expected test duration, resolution consistency, no re-encoding artifacts suggesting edited footage
- Build the flag reason system (store *why* something was flagged, not just that it was — dashboard reviewers need this)

**Definition of Done:** A deliberately tampered test video (e.g., looped clip, wrong person) gets correctly auto-flagged in your test set.

---

### Sprint 7 — Auth, Athlete Profiles & Benchmarking Engine (Weeks 14–15)

**Goal:** Athletes have real accounts, and their scores mean something against age/gender norms.

**Tasks**
- OTP-based phone auth (MVP — defer Aadhaar/e-KYC integration to post-MVP/govt-partnership phase)
- Athlete profile: name, DOB, gender, region, height/weight self-reported at registration
- Build age/gender benchmark tables (research SAI's actual published fitness benchmarks — Annexure A referenced in the problem statement — and encode them)
- Instant feedback logic: after a verified test, show athlete where they stand vs their benchmark cohort
- Basic athlete-facing profile screen: test history, personal bests, benchmark comparison

**Definition of Done:** A registered athlete completes a test and sees an accurate benchmark comparison based on their age/gender cohort.

---

### Sprint 8 — Official Dashboard v1 (Weeks 16–17)

**Goal:** SAI officials can actually review and act on submitted results.

**Tasks**
- React + TypeScript project setup, auth (role-based: SAI admin vs regional reviewer)
- Review queue: list of pending/flagged test submissions, filterable by region/test type/status
- Video review screen: playback with skeleton overlay, flag reasons displayed, on-device vs server score comparison
- Approve/reject/request-resubmission actions with audit trail (who approved what, when)
- Basic leaderboard view (top performers by test/region/age-group)

**Definition of Done:** An official can log in, review a flagged submission with full context, and approve or reject it — action is logged and reflected back to the athlete.

---

### Sprint 9 — Gamification & UX Polish (Weeks 18–19)

**Goal:** Athletes actually want to use this repeatedly, not just once.

**Tasks**
- Progress badges (first test completed, personal best broken, consistency streaks)
- Regional/age-group leaderboards visible to athletes (with privacy controls — opt-in for public display)
- Onboarding flow polish: multi-language support (Hindi + at least 2–3 regional languages for MVP), clear instructional videos/diagrams for each test
- Real low-bandwidth testing: throttle network to simulate 2G/3G, verify the app degrades gracefully (no hangs, clear "queued for upload" states)
- Accessibility pass: font scaling, color contrast, screen reader labels on key flows

**Definition of Done:** A non-technical test user (ideally someone outside your dev circle) can complete onboarding and a full test cycle without help, in a non-English language, on a throttled connection.

---

### Sprint 10 — Shuttle Run & Endurance Run (Weeks 20–21)

**Goal:** Extend the test battery beyond pose-only tests — this is structurally new work, treat it as its own mini-project.

**Tasks**
- Shuttle run: combine keypoint centroid tracking / optical flow for lateral movement with device accelerometer/gyroscope data for direction-change detection; require a marked-court setup in instructions
- Endurance run: GPS track logging (outdoor), step-pattern/motion classification, distance/time calculation; video becomes secondary evidence, telemetry is primary
- Server-side re-verification equivalents for both new tests
- Update benchmarking engine and dashboard to handle the new test types

**Definition of Done:** Both new tests produce server-verified results within the same accuracy targets defined in Sprint 3, validated against manually timed/measured reference runs.

---

### Sprint 11–12 — Hardening (Weeks 22–25)

**Goal:** This stops being a demo and starts being something you could actually hand to real users and a real government body.

**Tasks**
- Device compatibility matrix testing: at least 5–6 different Android devices across price tiers (₹6k–₹8k entry-level up to mid-range)
- Load testing backend (simulate concurrent uploads, queue processing under load — use Locust or k6)
- Security audit: this app touches minors' data, location, and biometric-adjacent (face) data — get this reviewed seriously
  - Encrypt data at rest and in transit (TLS everywhere, S3 encryption)
  - Data retention policy (how long are videos kept, who can access them)
  - Consent flows for minors (parental consent screen if applicable)
  - Rate limiting, input validation, dependency vulnerability scanning
- Full multi-language pass on all user-facing text
- Error monitoring (Sentry) wired into both mobile and backend
- Write internal documentation: architecture doc, runbook for common ops issues, API docs

**Definition of Done:** Security checklist fully signed off; app tested and stable across your full device matrix; backend survives your defined load test target without failure.

---

### Sprint 13–14 — Field Pilot (Weeks 26–29)

**Goal:** Real-world validation with real users, ideally in the exact conditions the product is meant for.

**Tasks**
- Recruit a small real pilot group — ideally including at least one genuinely rural/low-connectivity location
- Run structured pilot sessions: observe where users get stuck, where connectivity actually fails, where the UI confuses people
- Collect accuracy data: have a human coach/evaluator manually score the same tests in parallel, compare against your app's server-verified scores
- Fix what breaks — prioritize ruthlessly based on real pilot feedback, not hypothetical edge cases
- Tune benchmark tables and cheat-detection thresholds using real pilot data (your assumptions from Sprint 6–7 were educated guesses; this is where they get corrected)
- Write a pilot report: accuracy numbers, user feedback, known limitations, recommended next steps

**Definition of Done:** You have real accuracy data from real users in real conditions, a documented list of what works and what doesn't, and a clear roadmap for what comes after MVP (Aadhaar e-KYC, iOS, more test types, scaling infra).

---

## 7. Post-MVP Roadmap (Not in Scope Yet — Don't Build Early)

- Aadhaar/DigiLocker e-KYC integration for identity binding
- iOS app (Swift + Vision framework or MediaPipe iOS)
- Migration to government-empanelled cloud (NIC/MeghRaj) if adopted officially
- More sophisticated cheat-detection (adversarial ML, deepfake detection) as real gaming attempts are observed
- Coach/scout accounts and talent scouting workflows
- Height/weight measurement via computer vision (currently self-reported in MVP — this is a hard CV problem on its own, don't tackle it prematurely)

---

## 8. Guiding Principles (Read This When You're Tempted to Cut a Corner)

1. **On-device score is provisional. Server score is truth.** Never let this invariant break, or the whole system's credibility collapses.
2. **Low-end device performance is a feature, not an afterthought.** If it doesn't run well on a ₹8,000 phone, it doesn't matter how well it runs on your flagship dev device.
3. **Every sprint's "Definition of Done" is validated against real reference data, not "it looks like it works."** Rep-counting and jump measurement need actual measured accuracy numbers before you move on.
4. **Cheat detection is never finished.** Ship v1, expect to keep iterating as real users find gaps.
5. **This handles minors' data and location/biometric-adjacent data.** Security and privacy are Sprint 0 concerns that get *hardened* in Sprint 11–12, not concerns you start thinking about in Sprint 11–12.

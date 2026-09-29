# F4ALL — Two-Member Plan for Sprints 11–24

Companion to `F4ALL_FULL_IMPLEMENTATION_SPRINT_PLAN.md`. Sprints 3–10 are done.
This plan splits the remaining work, Sprints 11–23, between two people so that
**neither ever waits for the other**, then brings both together for Sprint 24,
the pilot.

| | Member A — **Verification, Trust & Platform** | Member B — **Athlete & Admin Experience** |
|---|---|---|
| Sprints | 11, 12, 16, 22, 20, 23 | 13, 14, 15, 17, 18, 19, 21 |
| Mostly works in | `backend/app/verification`, pipeline, security, analytics, test harnesses | `dashboard/`, `mobile/`, admin and athlete APIs, notifications |
| Branch | `track/a-verification` | `track/b-experience` |

**Together at the end:** integration, then Sprint 24 (the end-to-end pilot).

---

## 1. Starting point (Day 0, one time, ten minutes)

1. Commit the Sprint 10 work that is currently uncommitted on
   `feature/sprints-3-9`. That commit is the shared starting point.
2. Create both track branches from it:
   ```bash
   git switch feature/sprints-3-9
   git switch -c track/a-verification && git push -u origin track/a-verification
   git switch feature/sprints-3-9
   git switch -c track/b-experience   && git push -u origin track/b-experience
   ```
3. Nothing else needs to be set up. Each member can start straight away.

---

## 2. Why there are no blockers

Five rules make the two tracks independent. Keep to them and neither member
waits on the other, and the final merge is mechanical.

1. **Contracts are fixed in this document (§6).** The few places where one
   track's output is shown by the other are specified here up front: field
   names, codes, shapes. Each side builds against the contract, not against the
   other person's code.
2. **Anything from the other track is optional.** When B's screens display
   something A produces (server form feedback, new flag types), a missing value
   renders as "Not available" or as the generic label. B's features are
   complete, and tested with mock data, before A ships anything. The reverse
   holds too.
3. **Every file has one owner (§7).** A file owned by the other member is not
   edited. The handful of genuinely shared files have a stated rule: where each
   member adds lines, so git merges them without conflicts.
4. **Separate migration lines.** Each track starts its own Alembic branch from
   the current head (`e7a3c1d95b24`), with its own `branch_labels`
   (`track_a` / `track_b`). Both lines apply independently. One merge revision
   joins them at integration. Room (on-phone) database versions are **only ever
   changed by B**; A needs no on-device schema change.
5. **Work already on the phone and the server is reused, not rebuilt.** Most
   sprints below are "close the gaps" sprints. The "Already done" lines say
   what not to redo (plan §29: *What should not be rewritten*).

Neither member needs the other's branch to finish a sprint. Merging each
other's branches midway is allowed, as an early conflict check, but never
required.

---

## 3. Member A — Verification, Trust & Platform

Order: **11 → 12 → 16 → 22 → 20 → 23**

### Sprint 11 — Server-side AI verification (size: M)

**Already done:** upload → frame extraction → MediaPipe → server re-scoring of all
six tests → cheat checks → device-vs-server comparison → verified/flagged, and an
official decides approved/rejected. Result states map onto the plan like this
(**no new states are added**, see §6.3):

| Plan | Ours |
|---|---|
| UPLOADED | video stored, not yet submitted (`videos.test_result_id` null) |
| PROCESSING | `processing` |
| VERIFIED / FLAGGED | `verified` / `flagged` |
| APPROVED / REJECTED | `approved` / `rejected` (official decision) |

**Build:**
1. **Exercise detection.** Check that the video actually shows the test that was
   claimed (a squat video submitted as push-ups, say). A mismatch raises flag
   `exercise_mismatch` (§6.1).
2. **Server-side form validation.** Run the Python rep analyzers' fault
   detection during verification, store it per result, and expose it as
   `server_form` on `GET /api/verification/{id}` (§6.2), for athletes and
   officials alike.
3. **Validate the video before decoding it.** Reject unreadable, zero-length or
   wrong-codec files early with `server_could_not_score`, instead of a crash
   deep in extraction.
4. **End-to-end pipeline test.** Run a real recorded video through the Celery
   task in CI, with the models fetched, from upload to `verified`, and assert
   the server score.

**Definition of Done:** the server independently processes a mobile submission
and produces a final result. Proven by the end-to-end test.
**Report:** plan §32 format.

### Sprint 12 — Anti-cheat & authenticity (size: M)

**Already done:**
- **Integrity checks:** multiple people, no person, subject swapped, face and
  wrong-person check with the pre-test identity check, duration,
  resolution/frame rate, looped frames, abrupt cut, static video, and
  device/server mismatch.
- **Randomised verification gesture:** done on the phone (Sprint 5).
- **The flag record:** already carries everything the plan lists. Mapping:

| Plan field | Ours |
|---|---|
| flag_id | `id` |
| submission_id | `test_result_id` |
| flag_type | `reason` |
| severity | `severity` |
| reason | `detail` |
| detected_at | `created_at` |
| status | `resolution` (null = open) |
| reviewed_by | `resolved_by` |
| reviewed_at | `resolved_at` |

**Build (all server-side, in `app/verification/cheat/`):**
1. `playback_speed`: suspicious speed-up or slow-down, from frame timestamps
   against motion.
2. `impossible_movement`: joint velocities or accelerations beyond human limits.
3. `duplicate_video`: the same video (checksum, or near-duplicate frame
   signature) submitted twice, by the same athlete or by different athletes.
4. Tests for each with synthetic landmark and frame fixtures. A new check never
   produces "rejected" by itself; it only flags.

Flag codes are fixed in §6.1, so B's dashboard can label them before A ships.

**Definition of Done:** each listed manipulation produces a flag with a
reason a reviewer can act on.

### Sprint 16 — Leaderboards & analytics (size: M)

A owns this sprint end to end, including its two dashboard pages. B builds the
other admin pages in Sprint 13.

**Already done:**
- **Athlete leaderboard:** by test, region or national, same age band and
  gender, opt-in, on both phone and API.
- **Admin leaderboard page:** exists, with basic filters.
- **`GET /api/dashboard/stats`:** returns counts by status.

**Build:**
1. **Athlete leaderboard filters.** Add test, age group, region and session to
   the backend (`services/athlete_leaderboard.py`, and the `athlete_leaderboard`
   function in `routers/athletes.py`), and to the phone's `LeaderboardScreen.kt`
   (A's file for this sprint).
2. **`GET /api/dashboard/analytics`** (new `routers/analytics.py`, region-scoped
   like every dashboard endpoint), returning:
   - totals: athletes, submissions, verified, flagged, rejected
   - average scores
   - regional performance
   - participation by test and by session
   - upload failures: abandoned or expired upload sessions
   - verification time: median and 95th percentile
   - time series for participation over time
   - distributions for region, status and score
3. **Dashboard `/analytics` page** (`pages/AnalyticsPage.tsx`) with the five
   charts from the plan. The Admin leaderboard page (`pages/LeaderboardPage.tsx`)
   gets the new filters.

**Definition of Done:** an admin can filter leaderboards and read every listed
metric and chart. Regional reviewers see only their region.

### Sprint 22 — Accuracy validation (size: L; mostly recording and labelling)

**Already done:** `docs/reference-videos/` with a `ground-truth.csv`, and the
Kotlin/Python parity fixtures.

**Build:**
1. **A labelled dataset.** Per exercise, videos recorded across devices,
   lighting, distance and camera angle. Human counts are made by two
   independent counters.
2. **An evaluation harness** (`backend/scripts/evaluate_accuracy.py`) that
   produces, per video, the plan's columns:
   - video, human ground truth, AI result, absolute error, validity
   - device, lighting, distance, camera angle

   Per exercise, it adds rep-count accuracy, false-positive and false-negative
   rates, jump measurement error, form-fault accuracy and cheat-check accuracy.
3. **Threshold tuning** from the results. Follow the parity rule: every change
   goes into **both** `AnalyzerThresholds.kt` and `thresholds.py`, and the parity
   fixtures are regenerated.
4. **The accuracy report**, including the face-check false-positive rate by
   cohort, which was flagged as unmeasured in Sprint 8.

**Definition of Done:** a published accuracy report with numbers per exercise
and per condition.

### Sprint 20 — Security & privacy (size: M)

**Already done:**
- HTTPS enforcement in production config, a private bucket and signed URLs
- JWT with rotating refresh tokens, and OTP rate limits
- an encrypted face photo, consent including parental consent, and role-based
  admin access
- the review audit trail, and refusal to start with unsafe production settings

**Build:**
1. **General API rate limiting**, as middleware in `main.py`, per IP and per
   account.
2. **Data-access audit log**, via middleware on `/api/dashboard/*` and
   `/api/media/*`: who viewed which athlete's data or video, and when. It needs
   no changes to B's routers.
3. **Data retention job** (`python -m app.cli retention`), covering videos,
   identity checks, OTP challenges, expired refresh tokens and abandoned
   uploads, with configurable periods.
4. **Dependency and secret scanning in CI**: `pip-audit`, `npm audit`, the
   Gradle dependency check, and a secret scanner.
5. **An input-validation sweep:** automated tests that send malformed input to
   every endpoint in `docs/openapi.yaml` and assert a 4xx response, never a 500.
   Issues in B's files go to B as tickets; they don't block A.

**Definition of Done:** every item in the plan's Security list is either done or
has a documented owner and date.

### Sprint 23 — Backend load & reliability (size: M)

**Build:**
1. **Load scripts** (Locust or k6) in `backend/load/` covering:
   - concurrent chunked uploads
   - many athletes submitting in the same session
   - dashboard reads under load
   - the API rate limits
2. **Failure-recovery tests** for API down, database unavailable, Redis
   unavailable, an interrupted upload, a worker crash mid-verification, and the
   mobile app killed while the network disappears. The official result must
   never be corrupted.
3. **Observability:** a metrics endpoint for queue depth, verification time and
   error rates, plus alert thresholds, documented.

**Definition of Done:** load numbers published, and every failure scenario
recovers without corrupting a result.

---

## 4. Member B — Athlete & Admin Experience

Order: **13 → 14 → 15 → 17 → 18 → 19 → 21**

### Sprint 13 — Admin dashboard foundation (size: M)

**Already done:**
- the official sign-in, the review queue and the review page
- a basic Sessions page to create, enable/disable and delete sessions
- the admin session API

**Build** (pages as in the plan; Leaderboards and Analytics are A's, in Sprint 16):
1. `/dashboard`: a home page with today's counts, from the existing `/stats`.
2. `/sessions/create` and `/sessions/:id`: the full session form with name,
   dates, duration, tests, status and instructions; edit; and per-session
   submissions.
3. **Session states:** add **DRAFT** (built but not published) to the existing
   scheduled, active, ended and disabled. This is B's change in
   `services/sessions.py` and `routers/sessions.py`. Athletes never see a draft.
4. `/athletes` and `/athletes/:id`: a region-scoped list with search, and a
   profile showing history, consents and reference photo. New backend router:
   `routers/admin_athletes.py`.

**Definition of Done:** an admin can run a session from draft to ended, and look
up any athlete in their scope, entirely from the dashboard.

### Sprint 14 — Athlete submission review (size: S–M)

**Already done:**
- the review queue and review page: video with skeleton overlay, device vs
  server score, flags, identity check, benchmark
- approve, reject and request resubmission, each with an audit record

**Build:**
1. **Submission table columns:** athlete, session, test, score, AI status,
   server status, flag status, submitted time and review status, with filters
   by session and flag.
2. **Review screen additions:** session information, and server form feedback.
   The feedback comes from `server_form` on `GET /api/verification/{id}` (§6.2):
   show "Not available" while it is null, and build it against the mock in §6.2.
3. **A fourth action, "Flag"** (a reviewer raises a manual flag with a reason),
   audited like the other three.
4. **Labels** for the new flag codes in §6.1, in `dashboard/src/lib/format.ts`.

**Definition of Done:** every action creates an audit record, and the review
screen shows every field the plan lists.

### Sprint 15 — Results, history & benchmarking (size: M)

**Already done:**
- the phone's results screen, and the server result screen with benchmark
- a paged history API (`GET /api/athletes/me/history`)
- the personal-bests API
- benchmarks by age band and gender (provisional norms)

**Build:**
1. **The phone's result screen** shows test, score, form score, validity,
   benchmark, personal best and the previous attempt:
   - **Practice attempts:** the form score comes from the phone's own form
     summary.
   - **Official attempts:** it comes from `server_form` when present (§6.2).
2. **The phone's history screen:** date, test, score, status and
   session-or-practice, from the paged history API plus practice history.
3. **Region in benchmarking** (the plan asks for region as well as age, gender
   and test), with the "provisional" label kept until SAI's own benchmark table
   is loaded.

**Definition of Done:** an athlete can see what every past result means against
their cohort.

### Sprint 17 — Notifications (size: M)

**Decoupled by design:** notifications are raised by comparing database state
(result status, session windows), not by calls placed in the verification or
review code. B never edits A's pipeline.

**Build:**
1. **Backend:**
   - device-token registration: `POST /api/athletes/me/devices` and
     `DELETE /api/athletes/me/devices/{token}`
   - an outbox table
   - `python -m app.notifications.dispatch`, a loop that detects the plan's
     eight events and sends them through FCM
   - a `notifier` service in `docker-compose.yml`
2. **The eight events:**
   - session opened
   - session ending soon
   - submission received
   - verification completed
   - submission flagged
   - approved
   - rejected
   - resubmission requested
3. **Mobile:**
   - FCM client and the notification permission prompt
   - each notification opens the right screen
   - notifications are sent in the athlete's chosen language
   - they are addressed to the account, never to whoever last used the phone

**Definition of Done:** each of the eight events reaches a signed-in phone, and
a shared phone never shows another athlete's notification.

### Sprint 18 — Additional test battery (size: L)

**Already done:** sit-ups, on the phone and on the server.

**Build**, as a separate "field test" path, because these tests aren't
pose-based:
1. **Shuttle run:** accelerometer and gyroscope direction-change detection,
   timing, and optional camera confirmation.
2. **Endurance run:** GPS distance, time, pace and motion validation.
3. **Where it lives:**
   - **Phone:** `mobile/.../fieldtest/`. It does **not** change the pose
     `TestType` enum, which is A's.
   - **Server:** `app/field_tests/` with its own submission endpoint
     `POST /api/field-tests/submit`. The server checks the uploaded sensor or
     GPS trace for plausibility: speed limits, GPS jumps, missing motion.
   - **Sessions:** accept the codes `SHUTTLE_RUN` and `ENDURANCE_RUN` (§6.4).
   - **Test rows:** seeded by B's own migration.
4. Both tests are timed, so **lower is better**, which personal bests and
   leaderboards already handle since Sprint 10.

**Definition of Done:** both tests can be run offline, in practice and in a
session, and verified by the server.

### Sprint 19 — UX, language & accessibility (size: M)

**Already done:**
- English, Hindi, Tamil and Bengali, with a translation-completeness test
- voice coaching, countdown, offline and upload status, and screen-reader
  labels on key screens

**Build:**
1. **Exercise demonstrations** on the instructions screen, as short loops.
2. **A progress indicator** through the official flow: identity → gesture →
   countdown → test → upload.
3. **Better error recovery** on every screen: a clear action after every
   failure.
4. **Accessibility pass:** dynamic font sizes up to 200%, contrast, 48dp touch
   targets, and screen-reader labels on every control.
5. **Language review** by native speakers, and a decision on adding languages
   for the pilot regions.

**Definition of Done:** an accessibility checklist passes on every screen, and
the UX items above are live in all shipped languages.

### Sprint 21 — Device compatibility (size: M; mostly testing)

**Build:**
1. **A device matrix:** 2–3 entry-level and 2–3 mid-range Android phones.
2. **On each phone, measure:** camera, frames per second, pose tracking,
   battery, heat, memory, storage, upload, offline mode, background sync and
   app lifecycle.
3. **Run the device tests** that have so far only been compiled: the
   airplane-mode sync test and the local database migrations.
4. **Fix issues in B's code:** camera lifecycle, sync, memory. Pose or analyzer
   accuracy findings go to A's Sprint 22 as data, not as blockers.

**Definition of Done:** a published device report, and the app passes on every
phone in the matrix.

---

## 5. Sprint 24 — End-to-end pilot (both, after integration)

1. **Integration (1–2 days, together):**
   - merge `track/a-verification` and `track/b-experience` into `release/v1`
   - `alembic merge heads` to join the two migration lines
   - regenerate `docs/openapi.yaml`
   - run the full test suites and the plan's Release Acceptance Checklist (§27)
2. **Pilot:** the plan's full athlete → backend → admin journey, with real
   athletes, a real session and real officials.
3. **Pilot report:** issues found, fixes, and the go/no-go decision for v1.0.

Suggested split during the pilot:

| | Runs | Watches |
|---|---|---|
| **A** | the backend and verification | queue, flags, accuracy |
| **B** | the phones and the dashboard | athletes and officials |

---

## 6. Contracts (fixed now — both sides build against these)

### 6.1 New flag codes (A raises them; B labels them)

| `reason` | Raised in | Dashboard label |
|---|---|---|
| `exercise_mismatch` | Sprint 11 | "Video does not show the claimed test" |
| `playback_speed` | Sprint 12 | "Playback speed looks altered" |
| `impossible_movement` | Sprint 12 | "Movement faster than humanly possible" |
| `duplicate_video` | Sprint 12 | "Same video submitted before" |

Existing codes are unchanged. Unknown codes already display as readable text.

### 6.2 `server_form` on `GET /api/verification/{result_id}` (A adds it; B shows it)

```json
"server_form": {
  "score": 82,
  "faults": [
    { "code": "KNEE_PAST_TOES", "reps_affected": 3 },
    { "code": "TORSO_LEAN",     "reps_affected": 1 }
  ]
}
```

- `score` runs from 0 to 100: the share of reps with no fault.
- `code` uses the existing `FormIssue` names, which already have labels on the
  phone.
- The whole field is `null` when unavailable, and it is visible to the athlete
  and to officials.
- B builds against this mock until A's Sprint 11 lands.

### 6.3 Result statuses

No new statuses. The plan's states map onto the existing ones (see Sprint 11).
Neither member adds a status value.

### 6.4 Field-test codes (B owns them)

`SHUTTLE_RUN` and `ENDURANCE_RUN`, unit `seconds`, `higher_is_better = false`.
They are **not** added to the pose `TestType` enums; A's analyzers never see them.

### 6.5 Stable interfaces (neither member changes their shape)

- `services/sessions.status_of(session, now)`. B may return the new `draft` value.
- The `Flag` and `TestResult` columns that exist today. Additions are fine;
  renames and removals are not.
- Existing endpoint paths and response fields. Additions only; after any API
  change, regenerate `docs/openapi.yaml`.

---

## 7. File ownership

**Owned by A:**

| Area | Files |
|---|---|
| Backend | `app/verification/**`, `app/tasks.py`, `app/worker.py`, `app/routers/verification.py`, `routers/videos.py`, `routers/tests_submit.py`, `routers/media.py`, `routers/health.py`, `services/uploads.py`, `storage.py`, `services/athlete_leaderboard.py`, new `routers/analytics.py`, `security.py`, `config.py`, `cli.py`, `backend/load/`, `backend/scripts/` |
| Dashboard | `pages/AnalyticsPage.tsx`, `pages/LeaderboardPage.tsx`, new `api/analytics.ts` |
| Mobile | `analyzer/**`, `gesture/**`, `PoseLandmarkerHelper.kt`, `ui/engage/LeaderboardScreen.kt` |
| Repo | `.github/workflows/ci.yml` |

**Owned by B:**

| Area | Files |
|---|---|
| Backend | `routers/dashboard.py`, `routers/dashboard_auth.py`, `routers/sessions.py`, `services/sessions.py`, `routers/athletes.py` (all except the `athlete_leaderboard` function), `routers/identity.py`, `routers/practice.py`, `services/benchmarks*.py`, new `routers/admin_athletes.py`, new `app/notifications/`, new `app/field_tests/` |
| Dashboard | everything else in `dashboard/src/`, including `api/client.ts` and `api/types.ts` |
| Mobile | everything else in `mobile/`, including every Room entity, migration and schema version, `build.gradle.kts`, the manifest and navigation |

**Shared files:** each member adds only in their own place.

| File | Member A | Member B |
|---|---|---|
| `app/models.py` | new models directly after `class FaceVerification` | new models at the end of the file |
| `app/schemas.py` | new schemas directly after `class FlagResponse` | at the end |
| `app/main.py` | middleware and `include_router` lines at the top of the list | `include_router` lines at the bottom |
| `alembic/versions/` | own line, `branch_labels=("track_a",)` | own line, `branch_labels=("track_b",)` |
| `res/values*/strings.xml` | next to the existing `leaderboard_*` strings | at the end of the file, as each sprint so far has |
| `dashboard/src/App.tsx`, `components/Layout.tsx` | one route and nav line each for its pages | the rest |
| `mobile/.../api/F4allApi.kt`, `ApiModels.kt` | only the `leaderboard(...)` function and its models | everything else |
| `docker-compose.yml` | — | the `notifier` service |

---

## 8. Rules both members keep (from the original plan, §32)

- **Report every sprint** in the §32 format: SPRINT / GOAL / IMPLEMENTED / FILES
  CHANGED / TESTS / KNOWN ISSUES / DEFINITION OF DONE / GIT / NEXT SPRINT.
- **Parity rule:** any change to scoring goes into both Kotlin and Python, and
  the parity fixtures are regenerated. This is A's rule to keep, because A owns
  both sides.
- **Data belongs to the account:** history follows the athlete across phones
  and stays private on a shared phone. This is B's rule to keep for everything
  on the phone.
- **Tests must pass before merging:** backend, dashboard and mobile. Run `alembic
  check` for your own migration line, and regenerate `docs/openapi.yaml` after
  any API change.
- **Never mark a device test as passed** until it has run on a phone.

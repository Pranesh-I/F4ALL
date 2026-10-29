# Sprint 18 — Additional Test Battery

## Status

The battery now has eight tests. Sit-ups were already complete; this sprint
adds the **shuttle run (4 × 10 m)**, timed by the phone's motion sensors, and
the **endurance run (600 m)**, timed by GPS. Both run offline, in practice and
in a session. The server re-scores each from the raw trace it is sent, and
they go through the same review, benchmarks, leaderboards and notifications as
the camera tests.

- **Backend:** 761 tests green (55 new in `tests/test_field_tests.py`), ruff clean, `docs/openapi.yaml` regenerated. **One
  migration** (`f7c2a9e15b48`). It upgrades, passes `alembic check`, downgrades
  and re-upgrades on SQLite and on **Postgres 16**, and seeds the two tests
  itself.
- **Phone and server agree exactly:** 11 shared fixtures, with the same scores
  and the same time for every turn, split and finish. All 32 thresholds match
  by name.
- **Live on Postgres, real API over HTTP:** 16 of 17 checks passed on the first
  run. The one failure was my script's expected value (4); the API's answer (3)
  was right — see below.
- **Mobile:** 358 unit tests green (15 new), 1 skipped (the reference-video
  harness). Android lint: 0 errors, no new warnings.
- **Dashboard:** typecheck clean, 130 tests green (4 new), production build OK.
- **Not done here:** neither run has been tried with a real phone, a real
  athlete or a measured course. Every threshold is a starting value; the
  hand-timed reference runs that calibrate them belong to Sprint 22. This is
  on the deferred list in `TASK.md`.

---

## Sit-ups (already complete)

Pose-based rep counter, torso angle, an up/down state machine with
hysteresis, partial reps rejected and reported, a local result, and server
re-verification with Kotlin/Python parity (README Sprint 3; F4ALL Sprints 3 and
11). Unchanged here. Accuracy against reference video is still Sprint 22's,
like every pose test.

## Shuttle run, 4 × 10 m

**Protocol** (shown on the setup screen):
1. Two lines 10 m apart on flat ground.
2. The phone in a pocket or waistband, or held firmly.
3. Stand still; the phone counts down and beeps GO; the time runs from GO.
4. Four lengths, three turns, then run through the line and stand still.

**How it is measured** (`field_tests/shuttle.py`, `ShuttleRunAnalyzer.kt`):
- **Turns:** the body rotating at least 120° about the vertical within 1.5 s.
  Rotation is the gyroscope's rate projected onto gravity, which a slow
  low-pass of the accelerometer tracks, so it makes no difference how the
  phone is carried. A test proves this with the phone lying flat, upright and
  sideways.
- **Finish:** the moment the athlete is still again after the third turn, less
  an allowance for the steps it takes to stop (550 ms).
- **Refused, with a reason the athlete can act on:**
  - moving before GO;
  - not exactly three turns;
  - a length under 1.2 s;
  - a total under 7 s;
  - no finish within 30 s;
  - a sensor sampling slower than 40 Hz, or pausing.
- **Phones without a gyroscope** are told the test cannot be run on them,
  rather than given a wrong time.

**Fixed during the sprint:** after a turn had been counted, the rest of that
same turn stayed in the rotation window. When the next turn went the other way,
it cancelled part of it and delayed its detection by up to 0.5 s. Rotation
during the 0.8 s after a counted turn is now ignored. This affected the leg
splits and the "impossibly fast length" check, but not the finish time.

**Accuracy on synthetic runs:** within 0.01 s of the true finish for fast,
middle and slow runners, at every phone orientation.
**This is not evidence about real runs.** The finish allowance was fitted to
the generator's own model of an athlete stopping, so agreeing with it is
partly circular. It stays a placeholder until real hand-timed runs exist.

## Endurance run, 600 m

The distance is the one in SAI's Khelo India battery.

**Protocol:**
1. Outdoors, on a track or a flat open route of at least 600 m.
2. Wait for "GPS ready"; the Start button stays off until then.
3. Carry the phone. Walking is allowed, as in the official run/walk.
4. The time stops by itself at 600 m.

**How it is measured** (`field_tests/endurance.py`, `EnduranceRunAnalyzer.kt`):
- **Fixes used:** any fix less accurate than 25 m is ignored. A jump faster
  than 12 m/s is GPS error and is skipped; more than 5 jumps (or 10% of fixes)
  and the track is not trusted at all.
- **Distance:** movement under 3 m between counted points is jitter, so
  standing still adds no distance (a test checks five minutes of it). The
  finish time is interpolated between the two fixes either side of 600 m, and
  100 m splits are kept.
- **Pace:** worked out from the finish time.
- **On foot:** the accelerometer must show a step cadence of at least 90 steps
  a minute in 70% of 10-second windows. A bicycle or a bus fails this check.
  An average speed over 8.5 m/s (600 m in under about 70 s) also fails.
- **Refused, with a reason:**
  - GPS too weak, unreliable, or lost for too long;
  - no position fix at the start;
  - stopped before 600 m;
  - not on foot, or impossibly fast.

**Accuracy on synthetic runs:** within 1 s of the true time for steady runs,
1.5 s with GPS jumps, and 2.5 s for a run that turns into a walk. GPS noise in
the generator is correlated over time, like a real receiver's; white noise
would inflate the distance far more than real GPS does.

## Server verification

`POST /api/field-tests/submit` (`routers/field_tests.py`,
`field_tests/verify.py`). A trace is small, so it is checked in the request
itself, and the athlete gets the answer at once:

- **rejected:** the trace cannot be a valid run. The reason is stored and shown
  to the athlete in their language.
- **flagged:** a reviewer needs to look, because either:
  - the phone's claimed time differs from its own trace by more than 0.05 s
    (shuttle) or 0.5 s (endurance), or
  - the same trace was submitted before, by anyone.
- **verified:** otherwise. As with every test, only an official's approval
  makes it official.

Other properties:
- **Idempotent:** keyed on the phone's attempt id, so a lost response can be
  retried safely.
- **Session rules and identity check:** the same as video tests, including one
  attempt per session.
- **Stored trace:** gzipped and named by its SHA-256, so a replayed trace is
  found by its name.
- **What the reviewer sees:** turns and lengths for the shuttle run; distance,
  pace, cadence, splits and the GPS track (up to 300 points) for the
  endurance run. Nothing in the video verification path changed.

**Honest limit.** A trace is easier to fake than a video of a person. These
checks catch replays, edited claims and impossible physics. They do not prove
who carried the phone. That is why official sessions keep the pre-test photo
check, and why every field result is seen by a reviewer before it becomes
official. Optional camera confirmation (the plan lists it as optional) was not
built.

## Across the platform

- **Tests table:** `SHUTTLE_RUN` and `ENDURANCE_RUN`, unit seconds, lower is
  better. They are seeded by the migration and by `cli seed`. One registry,
  `app/test_catalog.py`, now drives:
  - session creation;
  - practice;
  - the dashboard's test catalogue.
- **Benchmarks:** provisional rows for both, with descending percentiles
  because lower times are better. The 600 m run is in SAI's own battery, so
  the file says to load SAI's norms over these.
- **Leaderboards, personal bests, badges, analytics:** lowest time wins
  (already generic). Checked live.
- **Notifications:** the test is named in all four languages (server and app,
  checked against each other).

## Phone

- **`fieldtest/` package** (it does not touch the camera `TestType`):
  - analyzers, the sensor and GPS recorder (100 Hz, or 25 Hz motion for the
    endurance run);
  - the run screen: setup, GPS wait, countdown with beeps, live turns or
    distance, Stop, result with lengths or pace;
  - per-athlete storage;
  - the sync worker.
- **Practice:** both runs on the Practice screen with the best (lowest) time.
  Saved to the athlete's account as a result only, never the trace. Practice
  from the athlete's other phones is brought back.
- **Sessions:** a session's field tests appear in its band. Start goes to the
  photo check, then to the run. An unsent run holds the session slot, as an
  unsent video does.
- **Offline:**
  - an official run is kept with its trace and sent by WorkManager when there
    is a network;
  - the trace is deleted once SAI has answered;
  - a run SAI refuses for good is not resent.
- **History:** field practice in "My results"; official runs come from SAI's
  record, like every official test.
- **Permissions:** location only for the endurance run, asked on its setup
  screen. GPS and the gyroscope are optional features, so phones without them
  can still install the app.
- **Strings:** 41 new strings in English, Hindi, Tamil and Bengali.

## Dashboard

- **Review page:** "Run evidence" replaces the video for a field test: turns
  and lengths, or distance, pace, cadence, GPS jumps, splits, and the GPS track
  drawn north-up.
- **Labels:** the two test names and every field reason and flag
  (`duplicate_trace`, `not_on_foot`, …).

## Live evidence (Postgres 16, uvicorn, HTTP)

| Check | |
|---|---|
| Admin opens a session offering both field tests | PASS |
| The athlete's phone sees both in the session | PASS |
| Shuttle run in the session verified on arrival (9.69 s) | PASS |
| A retried upload returns the same result | PASS |
| A second shuttle run in the same session refused (409) | PASS |
| 600 m run verified: 133.0 s against a true 133.34 s | PASS |
| A claimed time the trace does not support is flagged | PASS |
| Asha's trace replayed under Ravi's account is flagged | PASS |
| 600 m on a bicycle rejected: `not_on_foot` | PASS |
| Provisional benchmark shown (top 10%, p50 12.8 s) | PASS |
| …and it is the athlete's personal best | PASS |
| The reviewer gets the GPS track and splits | PASS |
| Review page: no video, approve offered | PASS |
| An official approves the shuttle run | PASS |
| The leaderboard ranks the approved time | PASS |
| Analytics count the field test | FAIL — my script expected 4 submissions. The fourth (Asha's second run in the session) was correctly refused, so 3 is right: 1 approved, 2 flagged |
| Asha is notified, with the test named | PASS |

Asha got no "session open" notice, because she had already finished both of
its tests. That is the Sprint 17 rule working.

## Definition of Done

| Item | |
|---|---|
| All required additional tests implemented | ✅ sit-ups (earlier), shuttle run, endurance run |
| Mobile results work | ✅ on synthetic traces and in unit tests; **not yet on a phone** |
| Backend accepts the new test types | ✅ |
| Server verification exists | ✅ re-scores the raw trace, plus plausibility, discrepancy and replay checks |
| Dashboard can display them | ✅ |
| Benchmarking supports them where data exists | ✅ provisional rows, clearly labelled |
| Existing tests not regressed | ✅ all suites green; parity unchanged |
| Accuracy documented | ✅ synthetic accuracy above; **real accuracy needs Sprint 22's reference runs** |
| Test setup clearly defined | ✅ on the setup screen, in 4 languages |
| Manual reference runs validate accuracy | ❌ deferred to Sprint 22 (needs a measured course and a stopwatch) |

## Known limits

- **No real runs yet.** The finish allowance, turn angle, step threshold and
  cadence floor are starting values.
- **Gyroscope required for the shuttle run.** Phones without one are told so;
  very cheap phones may lack it.
- **GPS accuracy limits the 600 m run.** With good sky view the time is within
  about 1–2 s; under trees or between buildings it is refused rather than
  guessed.
- **Clock alignment:** the sensor and GPS timestamps are assumed to be on the
  uptime clock (`elapsedRealtimeNanos`), as they are on modern Android. An old
  phone that stamps differently would need checking on the device.
- **Offline identity photo:** an official field run takes the identity check
  only if the photo was checked online; an offline photo check is not yet
  attached to field runs.
- **No camera confirmation** of the shuttle run (optional in the plan).

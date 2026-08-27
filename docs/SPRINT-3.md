# Sprint 3 — Rep-Counting & Jump Measurement Logic

## Status

Implementation complete. Verified against synthetic sequences (50 unit tests, all green).

**Accuracy validation against real reference video is outstanding**, because the reference
videos are a Sprint 0 deliverable that has not been collected. Sprint 3's Definition of
Done is therefore **not yet met** — the machinery to meet it is built and waiting for data.

---

## Goal

Score a sit-up test and a vertical jump test from on-device pose data, and show the
athlete a provisional result.

---

## What was built

### Architecture decision: analyzers do not know MediaPipe exists

Everything under `analyzer/` operates on a plain Kotlin `PoseFrame`, never on MediaPipe's
`NormalizedLandmark`. Conversion happens in exactly one file, `MediaPipeMapper.kt`.

Two things fall out of this, both of which paid for themselves inside this sprint:

- The scoring logic is testable on a plain JVM. All 50 tests run in seconds with no
  device, no emulator, and no camera.
- Sprint 5 has to re-implement this same logic in Python for server-side re-verification.
  With the input model this small, that becomes transcription rather than redesign.

```
CameraX frame
   └─> PoseLandmarkerHelper (MediaPipe)
         └─> MediaPipeMapper ─────── the only MediaPipe boundary
               └─> PoseFrame
                     ├─> PoseSmoother      (One-Euro, jitter suppression)
                     ├─> FrameQualityGate  (drop unreliable frames)
                     └─> TestAnalyzer
                           ├─> SitUpAnalyzer
                           └─> VerticalJumpAnalyzer
                                 └─> AnalyzerResult (provisional score)
```

### Sit-ups

State machine over the shoulder–hip–knee angle at the hip, with a wide hysteresis band:

```
   angle >= 135°              angle <= 70°
 ┌──────────────┐          ┌──────────────┐
 │     DOWN     │  ──────> │      UP      │   rep counted on this crossing
 │ (lying back) │  <────── │ (sat fully up)│
 └──────────────┘          └──────────────┘
```

Reps are refused three ways: the ascent never reaches the UP threshold (partial), the
crossing happens faster than a human can move, or the frame never reached the state
machine because the quality gate dropped it. Partial reps are counted separately and
reported to the athlete — a rep that silently does not count reads as a broken app.

The side of the body is chosen once from the first good frame and held. Letting it switch
mid-attempt puts a discontinuity in the angle signal, and the state machine reads a
discontinuity as a rep.

### Vertical jump

Peak hip displacement against a calibrated standing reference:

1. Wait for 12 consecutive stable frames — the athlete standing still.
2. Measure the nose-to-ankle span, divide by 0.936 to estimate full stature (the pose
   model's topmost dependable landmark is the nose, not the crown of the head).
3. `cmPerUnit = registeredHeight / estimatedStature`.
4. Track upward hip displacement; the peak times `cmPerUnit` is the jump height.

Calibration runs during the existing 3-2-1 countdown, so the athlete standing still for
the countdown is doing the calibration without being asked to.

Multiple jumps in one recording are allowed and the best is reported.

### Known measurement limits

Both of these are real error sources, and both are why the server re-scores in Sprint 5:

- **Depth.** Normalized coordinates map to centimetres only at the distance where
  calibration happened. Step toward the lens and everything grows, jump height included.
  The analyzer cannot see depth, so it watches apparent stature instead and rejects the
  attempt if that changes by more than 15%.
- **Self-reported height.** An athlete who rounds 163cm up to 170cm shifts every jump they
  record by about 4%. Sprint 7 collects this at registration; it does not make it true.

---

## Measured behaviour (synthetic)

Generated from geometric fixtures, not recordings. These numbers show the algorithms are
internally sound; they say nothing about real bodies.

**Vertical jump** — measured vs. truth, 170cm athlete:

| Frame rate | 15cm | 25cm | 40cm | 60cm |
|---|---|---|---|---|
| 30 fps | −0.24 | −0.30 | −0.31 | −0.32 |
| 15 fps | −0.48 | −0.51 | −0.53 | −0.54 |

Error is a consistent slight *under*-estimate from smoothing lag at the peak, and it
roughly doubles when frame rate halves. Well inside the ±3cm target, with headroom for the
error real conditions will add. The low-end device floor of 15 fps from Sprint 2 is
therefore adequate for jump measurement.

**Sit-ups** — 10 reps at varying cadence, all counted correctly at 660ms–1980ms ascents.

---

## A bug the numbers caught

The minimum rep-duration guard was initially set to 600ms, reasoning that anything faster
was a shaken phone. Characterising the analyzer across cadences showed a **660ms ascent
scored zero reps** — a completely ordinary pace.

This mattered more than a typical off-by-one because of what the guard does when it
triggers: it *silently deletes* the rep. And the SAI sit-up test scores maximum reps in a
fixed window, so the faster the athlete, the more reps vanish. The guard would have
penalised precisely the strongest athletes, and the app would have looked like it was
working.

Now 200ms, which corresponds to a full rep of roughly 0.6s — the edge of human capability
— while still rejecting the sub-100ms transitions that shaking the phone produces. A
regression test pins the fast-cadence case.

This is the argument for the Sprint 3.4 validation step in miniature: a threshold that
sounded reasonable was wrong in the direction that hurts the users who matter most, and
only measurement showed it.

---

## Highest-risk parameter

`SITUP_UP_ENTER_ANGLE` (currently 70°). Sensitivity across a 10-rep sequence:

| Top angle reached | Reps counted |
|---|---|
| 45° | 10 |
| 55° | 10 |
| 65° | 10 |
| 75° | **0** |
| 85° | **0** |

It is a cliff, not a slope, and where the cliff sits depends on how much the athlete bends
their knees — which changes the shoulder–hip–knee angle at full sit-up considerably. If
one threshold gets moved during reference-video calibration, it will be this one.

---

## Validation harness

`ValidationHarness` replays recorded pose sequences through the same analyzer classes the
app runs and compares against manual ground truth. Threshold changes can be evaluated
across the whole reference set in seconds without a device.

```
cd mobile
gradlew :app:testDebugUnitTest --tests "*ValidationHarnessTest*"
```

With no reference data present the test **skips** rather than fails — a red build for data
nobody has recorded yet just trains people to ignore red builds. The moment sequences
appear in `docs/reference-videos/`, it enforces ±1 rep and ±3cm.

Report: `mobile/app/build/reports/validation/sprint3-accuracy.csv`.

See [reference-videos/README.md](reference-videos/README.md) for how to collect the data.

---

## Results screen

Shows the provisional score, a skeleton replay of the attempt, tracking-quality
percentage, and the attempt log (reps counted, partials rejected, and why).

The replay is not decoration. When the count is not what the athlete expected, the only
useful answer is showing them what the app actually saw — and it is the same view an SAI
official gets in the Sprint 8 dashboard.

Every result carries the notice that the score is provisional and SAI confirms the
official number. Per the project's first invariant, the athlete should never be surprised
when the two differ.

---

## Storage

Attempts are stored as flat files under `filesDir`, deliberately not Room:

```
videos/test_<ts>.mp4        the recording
sequences/test_<ts>.csv     pose frames, for replay and offline tuning
attempts/test_<ts>.json     the provisional result
```

Sprint 4 owns the local database, complete with the sync-status state machine the upload
queue needs. A throwaway schema here would only have to be migrated away from.

The sequence CSV is written with `Locale.US` explicitly. In a locale that uses a decimal
comma — which includes several this app is meant to ship in — the default formatter writes
`0,51234` and silently adds a column to every row.

---

## Sprint 3 Definition of Done

| Criterion | Status |
|---|---|
| Both algorithms produce results within the defined accuracy target against manually verified ground truth | **Blocked** — no reference videos collected |
| Tested across at least 15–20 reference videos of different people | **Blocked** — same |
| Accuracy targets defined explicitly | Done — ±1 rep, ±3cm in `AnalyzerThresholds` |
| Algorithms implemented and behaviourally verified | Done — 50 unit tests |
| Batch validation harness | Done |
| Local scoring/results screen with skeleton replay | Done |

**To close this sprint:** collect the reference videos (Sprint 0), ground-truth them, pull
their pose sequences, and get `ValidationHarnessTest` green across 15–20 of them.

---

## Notes for the next sprint

- Sprint 1's JPEG frame extraction still runs after every recording. Scoring no longer
  needs it — the pose sequence is captured live. On a low-end device it is 100+ JPEG
  writes per test for no current consumer. Sprint 4 should decide what the upload payload
  actually contains and delete it if the answer is "not these".
- `AthleteProfileStore` holds the athlete's height in SharedPreferences as a stand-in for
  registration. Sprint 7 should replace the backing store and leave the call sites alone.
- `AnalyzerThresholds` is the single source of truth for every tunable number, so that the
  Sprint 5 Python port is a transcription and any drift between the two implementations
  shows up in a diff. Drift here causes the server to auto-flag honest athletes.

# Sprint 12 — Anti-Cheat & Authenticity

## Status

Every required v1 check exists. Each one stores a reason and its evidence, and
feeds the Sprint 11 verdict.

- **Backend:** 458 tests green (53 new), ruff clean. The migration applies,
  reverses and re-applies on Postgres 16 and SQLite, with `alembic check` clean.
- **Controlled dataset:** 17 cases, 17 detected as intended. The 2 honest
  controls stay clean. 2 false flags are recorded and explained (below).
- **Live, real footage:** a real squat recording with controlled tampering
  went through the real stack (Postgres + Redis + Celery + MediaPipe). Every
  tampering was flagged with evidence.
- **Dashboard:** typecheck clean, 30 tests green, and it builds. **Mobile:**
  unchanged; Gradle reports its tests up to date from the 309-pass run.
- **Not proven:** accuracy on real athletes' phone recordings. Everything
  here is synthetic, rendered, or one downloaded clip.

Sprint 6 had already built most of the detectors (loops, cuts, static video,
people, subject swap, metadata, face). This sprint added what was missing:
persisted evidence, aggregated outcomes, and four new kinds of check. It
wired the new checks into the same verification job; nothing was rewritten.

---

## Checks

| Required | Flag type (`flags.reason`) | Status |
|---|---|---|
| Multiple people | `multiple_people` | Sprint 6. Now also aggregated into a person outcome |
| No person | `no_subject` | Sprint 6. Same aggregation |
| Wrong person | `face_mismatch`, `identity_unconfirmed` | Sprint 6–9. Now also aggregated into an identity outcome |
| Duplicated frames | `duplicate_frames` | **New** |
| Looped frames | `looped_frames` | Sprint 6. Evidence now names the affected frame ranges |
| Abrupt cuts | `abrupt_cut`, `timestamp_anomaly` | Cuts from Sprint 6; **timestamp gaps and reversals are new** |
| Playback speed | `playback_speed_suspicious`, `framerate_implausible` | **New:** frame-timing consistency and jump physics |
| Invalid metadata | `duration_implausible`, `resolution_unexpected`, `framerate_implausible` | Sprint 6 |
| Impossible movement | `impossible_movement` | **New:** reps under the physical minimum, and landmark teleports |
| Score mismatch | `score_discrepancy` | Sprint 11. The flag now carries mobile value, server value, difference and tolerance |
| Duplicate submission | `duplicate_submission` | **New:** identical bytes or near-identical footage. A repeat in the same session is still refused by the database |

The flag-type names are the project's existing lowercase codes. For example,
the plan's `MULTIPLE_PERSONS` is `multiple_people` here, and `VIDEO_LOOP` is
`looped_frames`.

Two aggregated outcomes are stored on every verdict, whether or not anything
fired:

- **Person:** `VALID_PERSON` / `NO_PERSON` / `MULTIPLE_PEOPLE` / `UNCERTAIN`.
  It records the frames sampled, frames with no one, frames with several
  people, their fractions and the mean torso confidence. A flag needs a
  sustained share of frames, so one bad frame never decides it.
- **Identity:** `IDENTITY_MATCH` / `IDENTITY_MISMATCH` / `IDENTITY_UNCERTAIN`.
  It is built from the pre-test photo check and the existing video face check.
  It is never a match by default. "Mismatch" means "a human must compare",
  never "not the athlete". **No new biometric data is stored:** only outcomes
  and the similarity number that `face_verifications` already held.

## Thresholds, and where they came from

Each default lives beside its check, with the measurement behind it. Each can
be overridden through an `INTEGRITY_*` setting
(`verification/cheat/limits.py`), and every flag's evidence records the limit
in force.

| Limit | Default | Basis |
|---|---|---|
| Repeated-frame share (`duplicate_frames`) | 0.15 of frames in motion | Measured: 0.00 honest render, 0.17 with every 5th frame repeated, 0.50 when frame-doubled. A real web clip converted between frame rates measured 0.29–0.30 |
| Repeat / motion definition | change ≤ 3 amid neighbours ≥ 12 (64×64 grid) | Honest render: min 14. Repeats ≤ 2. A still athlete does not count |
| Timestamp gap | ≥ 4× median interval and ≥ 250 ms | Phones dropping a frame produce 66–100 ms gaps |
| Frame-rate mismatch | 25% | Declared vs timestamp-measured |
| Jump time scale | outside 1/1.6 – 1.6 | Ballistic flight, h = g·t²/8, only for jumps ≥ 25 cm. **Unvalidated on real jumps** |
| Too-fast reps | ≥ 3 reps and ≥ 30% of attempts | Uses each scorer's existing minimum rep duration |
| Hip speed | 15 torso lengths/s, jump must not undo itself within 5 frames | Real squat peaks at 5.6. A splice measures 18–24. A one-frame tracking glitch is excluded |
| Near-identical video | 90th-percentile sample difference ≤ 3.0 | Re-encoded copy: 1.9–2.1. Different take in the same room: 4.5–6 |

A measurement that shaped the design: the Sprint 6 16×16 frame signature
**cannot** detect duplicated frames. On real footage, half of all adjacent
frame pairs fall under its "identical" threshold. So the extractor now also
records a localised 64×64 change per frame, in the same decode pass.

## Flags

Each automatic flag stores:

| Field | Where |
|---|---|
| flag id | `flags.id` |
| submission | `test_results.id` |
| flag type | `reason` |
| severity | `severity` |
| reason text | `detail` |
| evidence | **new:** `evidence` (JSON) |
| detected at | `created_at` |
| status | open, `confirmed` or `dismissed` (`resolution`) |
| reviewed by / at | `resolved_by` / `resolved_at` |

Nothing already there was duplicated.

- Flags never overwrite each other. The verdict is **named by the most severe
  flag**, and every flag is kept.
- Severity routes attention and never rejects. Any flag means FLAGGED.
  REJECTED stays reserved for an unreadable or missing video (Sprint 11).
- **A check that crashes is flagged** (`integrity_check_failed`, low) instead
  of letting the verdict pass as clean. Sprint 6 recorded such a crash only as
  "skipped".

## API

`GET /api/verification/{id}`, official view, now carries:

- flags with `flag_id`, `status`, `evidence` and `reviewed_by`;
- an `integrity` block: every check's summary, what was skipped and why, any
  crashes, and the limits in force.

The athlete view gets no flags and no integrity data, as before.
`GET /api/results/{id}` still lists flags for the athlete (pre-existing, see
below) but never their evidence.

---

## Controlled dataset

`backend/tests/anticheat_dataset.py` (run it with `python -m
tests.anticheat_dataset`). It runs **every** check on every case, so an
unexpected flag shows up instead of going unseen.

**All synthetic.** Pixel signals come from rendered video, encoded and decoded
as the server would. Pose signals come from the parity fixtures, edited in
controlled ways. MediaPipe does not detect the rendered figure, so the two
halves are composed. This is detection validation, not an accuracy
measurement.

| case | tampering | detected | false flags | verdict |
|---|---|---|---|---|
| normal | none (control) | – | – | verified |
| looped | 2 s copied back in | yes | – | flagged |
| duplicated_frames | every frame doubled | yes | `looped_frames` ¹ | flagged |
| wrong_person | face similarity 0.20 | yes | – | flagged |
| multiple_people | second person in 40% | yes | – | flagged |
| no_person | tracked in 1 frame of 5 | yes | – | flagged |
| cut_video | different scene spliced in | yes | – | flagged |
| timestamp_gap | 0.6 s removed | yes | – | flagged |
| wrong_duration | 2.5 s squat test | yes | – | flagged |
| sped_up_reps | reps under the minimum | yes | – | flagged |
| slowed_jump | 40 cm jump at half speed | yes | `subject_swapped` ² | flagged |
| frame_rate_mismatch | declares 30, timed at 60 | yes | – | flagged |
| landmark_teleport | athlete jumps sideways and stays | yes | – | flagged |
| score_mismatch | claims 12, video shows 5 | yes | – | flagged |
| duplicate_identical | same file again | yes | – | flagged |
| duplicate_reencoded | same footage re-encoded | yes | – | flagged |
| other_take_same_room | different take (control) | – | – | verified |

1. The rendered figure repeats exactly within a rep, and doubling stretches
   those repeats past the 20-frame minimum. This is a synthetic-motion
   artifact, but it is recorded as a false flag.
2. The synthetic jump fixture changes apparent size by 89% at take-off, so
   this check fires on the unmodified fixture too.

**Also recorded:** across the 38 parity fixtures, the new landmark-jump check
fires on `jump_40cm`, `jump_60cm_tall` and `pushups_then_standing`. Their hips
move at ~13 m/s or switch from plank to standing in one frame. These fixtures
are not physically realistic, and the check is correct about that; they
remain valid for scoring parity.

## Real footage (not committed)

"Squat - exercise demonstration video" by FitnessScape, Wikimedia Commons,
CC BY 3.0. Tampered variants were run through the live stack with Postgres,
Redis, Celery and MediaPipe. The device claims were simulated.

| Submission | Verdict (named by) | Flags |
|---|---|---|
| Original | flagged | `duplicate_frames` low ³ (+ `duplicate_submission` medium: an earlier identical upload by the same athlete) |
| Looped (3 s repeated) | flagged (`looped_frames`) | `looped_frames` high (frames 50–139 repeated at 213–302), `impossible_movement` medium (splice at 7.1 s, 18.1 torso lengths/s), `duplicate_frames` low ³ |
| Frame-doubled (2× slow) | flagged | `duplicate_frames` medium (ratio ≥ 0.4), `duplicate_submission` medium (recognised as the same footage) |
| Original, re-submitted by another athlete | flagged | `duplicate_submission` high ×2 (both earlier copies) |

3. Correct about the pixels: the downloaded source contains exact duplicate
   frames (verified at full resolution), because it was converted between
   frame rates before upload. The cause is benign, and the flag's text says
   so. **This is also a behaviour change:** the same clip was VERIFIED under
   Sprint 11 and is FLAGGED (low) now.

The looped video scored 3 reps against a claim of 2. That is within
tolerance, so the score comparison alone would have passed it. The integrity
checks are what catch it.

The live run also found and fixed a bug: a LOW flag found first named the
verdict of a HIGH-severity loop. There is now a regression test.

---

## What is not proven

- **Real-world accuracy.** No real athlete's phone recording has been through
  these checks. False-positive rates on real footage are unknown, apart from
  the single web clip above.
- **The jump physics model**, on real jumps.
- **The face check's error rate** (unchanged from Sprint 6).
- **Trimmed copies** of a video are not recognised as duplicates, only whole
  re-encodes.

## Known issues

- `GET /api/results/{id}` shows flag reasons to athletes, while
  `/api/verification/{id}` hides them. This predates Sprint 11 and is Sprint
  14's to decide.
- The Sprint 11 real-device validation is still pending. Its test stack must
  not share a Redis queue with another worker: a stale worker from that
  session took a job here and flagged it `video_missing_from_storage`
  (correctly, from its point of view).

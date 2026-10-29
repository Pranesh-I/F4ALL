# Sprint 24 — End-to-End Pilot

## Status

**Rehearsed end to end; the pilot itself needs real athletes.** The plan says
plainly: "This is not another unit-testing sprint. It is the complete journey
with real users under realistic conditions." That part cannot be done from a
development machine. What can be done was:

- **The rehearsal passes: 24 of 24 steps** of the plan's chain, against the
  real API, a real verification worker and the notifier, with nothing
  stubbed. It took 57 seconds ([pilot/rehearsal.json](pilot/rehearsal.json)).
- **The protocol is written** ([PILOT.md](PILOT.md)): participants, battery,
  instructions, the two-evaluator ground-truth procedure, data sheets,
  incident reporting, P0–P3, and threshold-change rules.
- **The data sheets are ready** in `docs/pilot/`: attempts, observations,
  issues, threshold changes. The participant list is git-ignored, because it
  identifies children.
- **One pre-pilot issue is already logged** (PRE-01, below).

## The rehearsal

```bash
cd backend
python -m loadtest.env setup && python -m loadtest.rehearsal
```

| Plan stage | Rehearsed as |
|---|---|
| Registration, OTP | OTP requested and verified (development code); registered as a 15-year-old with **guardian consent** |
| Profile | read, then updated |
| Face verification | face-verification consent given, then the photo stored (encrypted) |
| Practice | a practice attempt saved to the account |
| Session | an admin is provisioned and signs in, then creates a session; the athlete sees it |
| Gesture, countdown, exercise | **on the phone only:** covered by the app's tests, and by the pilot |
| Result, upload | pre-test identity check; video uploaded in chunks, checksum verified; official result submitted to the session |
| Backend, server verification | the worker reached a verdict |
| Flag / approve, admin review | the submission appears in the queue; the review opens with the video link, flags and scores; **the video plays** through its signed link; approved |
| The athlete | sees the approval, never the integrity flags, and gets a notification |
| Audit | the review history and the access log record it; the consistency check is clean |

**What the rehearsal showed:**
- The server could not score the jump video, so it went to a reviewer, and
  the reviewer approved with the score they saw. This is the designed path
  for an unscored video, and it worked.
- Why it couldn't score: **the side-on view hides the far ankle, and the jump
  analyzer requires both ankles** (Sprint 22's first poor-performing case).
- The "identity unconfirmed" flag was expected: the rehearsal's selfie is a
  frame from the video.

## Issues before the pilot

| Id | Priority | Issue | Action |
|---|---|---|---|
| PRE-01 | P1 | Vertical jump: side-on recording hides the far ankle; 53% of frames rejected and the jump not detected | Check the first pilot jump recordings. If confirmed, require one ankle (the more visible side) in both analyzers, with parity fixtures and a threshold-change entry |

## Definition of Done (the plan's)

These need the pilot:

| | |
|---|---|
| Pilot participants recruited and run | **Not yet** (needs SAI: athletes, guardians, sites) |
| Human ground truth vs mobile vs server | Procedure and sheets ready; data **not yet** |
| Athlete, backend and admin observations | Checklists ready; **not yet** |
| P0/P1 fixed before release | One P1 open (PRE-01) |
| Thresholds tuned from pilot data, every change recorded | Process ready; **not yet** |

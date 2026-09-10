# Sprint 6 — Cheat Detection v1

## Status

Implementation complete. **114 backend tests green, none skipped**, lint clean.
The Definition of Done runs against real rendered video that goes through a real
encode, and the honest control passes alongside every tampered case.

---

## Goal

Obvious manipulation attempts get caught automatically — and, just as important,
honest submissions do not.

---

## The shape of it

Every check runs inside the existing verification task, off the **single decode
pass** that already extracts pose landmarks. Decoding a multi-megabyte video
once per check would multiply straight into the five-minute SLA.

```
 analyze_video (one decode)
   ├─ pose frames ────────────> scoring (Sprint 5)   ─┐
   ├─ 256-bit perceptual hash ─┐                      │
   ├─ 256-byte signature ──────┼─> frame checks       ├─> flags
   ├─ per-frame person count ──┼─> subject checks     │
   └─ container metadata ──────┴─> metadata checks    │
                                   face check ────────┘
```

Nothing in `app/verification/cheat/` decides that an athlete cheated. Every
check produces *findings* — an observation, a severity, a timestamp to look at,
and the raw numbers behind it. The pipeline's job is to route work to a
reviewer, not to punish.

That separation is not decoration. These are heuristics with real
false-positive rates, and the people being judged are largely minors who may
have travelled a long way for the attempt.

---

## The check that would have quietly ruined the review queue

Loop detection was originally built on perceptual hashes alone. It does not
work, and the reason is worth recording because it is not obvious.

The mobile app transcodes to 480p H.264 before upload, so **the server only ever
sees re-encoded footage**. Measured over rendered test video:

| | true copied frames | honest frames one rep apart |
|---|---|---|
| hash distance | 8.4 mean, 16 max | 11.4 mean, **3 min** |
| signature MAE | 0.09 mean, **0.12 max** | **1.55 min** |

The hash distributions overlap almost completely. There is no threshold that
separates a copied segment from ordinary repetitive-exercise video — because a
difference hash *binarises* every comparison and throws away magnitude, and
magnitude is exactly what lossy re-encoding perturbs.

A 16×16 greyscale signature keeps magnitude and separates the two cases by more
than tenfold. It is too expensive to compare across every candidate offset in a
long video, hence two stages: **the hash proposes, the signature disposes.**

The failure mode this avoided is the one that matters. A sit-up athlete returns
to the same position twenty times against a wall that does not move. A detector
tuned on hashes alone flags every honest recording, the queue fills with
innocent submissions, and reviewers learn to dismiss the flag — at which point
the real tampering passes through it too.

Three guards keep the honest case clean, and each has a test asserting the
negative as well as the positive:

- a repeat must be **20 frames** or more (8 frames produced false positives:
  consecutive reps genuinely match at the same point in the movement)
- it must start **15 frames away**, so near-still frames are not a loop of
  themselves
- the run must contain **internal movement** — a repeat of a motionless scene
  is a motionless scene, reported separately as `static_video`

---

## The face check, and why it is deliberately weak

`face.py` carries a long docstring; the short version is that this is the most
consequential and least reliable check in the system.

It is a general-purpose image embedder over a face crop. Generic embedders
perform unevenly across skin tones, ages and lighting, and this platform's users
are largely rural Indian teenagers photographed on cheap cameras in variable
light. **The population most likely to be wrongly flagged is the population the
platform exists to serve.**

So:

- It **never** produces `fail`. A mismatch is `manual_review`, always. The
  `fail` value exists for a human who has compared the photographs themselves.
- Severity is never `high`, even for a total mismatch — severity drives how a
  reviewer weights what they are looking at, and this check has not earned that
  confidence.
- The threshold (0.55 cosine) is set to catch only gross mismatches.
- Embeddings and crops live only inside the call. Only a similarity score
  reaches the database; no face crop is stored.

Sprint 13–14 must measure its false-positive rate **by cohort** against pilot
data before anyone leans on it harder.

---

## "We did not look" is not "we looked and it was fine"

The single most load-bearing distinction in this sprint.

A check can fail to run for reasons that say nothing about the athlete: no
registration photo on file, face models absent from the server, no face visible
anywhere in the recording. Every one of those is a gap in *our* data.

`CheatReport.skipped` records them explicitly, separately from findings, and
`face_verifications` gets **no row at all** when the comparison did not happen.
An absent row means "not verified". A `pass` row written in that situation would
be a false assurance about a real person, in the one table an official would
consult to ask whether identity had been confirmed.

`test_face_verification_record.py` pins all three states apart: checked and
matched, checked and unclear, never checked.

---

## Two bugs found

**`_persist_video_duration` referenced an unimported `Video`.** A guaranteed
`NameError` on every single verification that got far enough to reach it —
swallowed by the task's outer `except Exception`, which flagged the submission
with "Unexpected verification error". Every athlete whose video scored cleanly
would still have been flagged, and the reviewer would have seen a Python error
message as the reason. It was invisible because no test exercised the path with
a real video end to end.

**The frame checks had outgrown their tests.** The implementation had moved to
the two-representation design above, but sixteen tests still called the
hash-only API and failed with `TypeError`. A suite that does not compile is a
suite that is not defending anything. The fixtures were rebuilt to model frames
as 16×16 grids and derive *both* representations from the grid using the same
rules the extractor applies to a real frame — fixtures that invented the hash
and the signature independently could disagree in ways no real video can.

---

## Sensor noise is the signal

Both the synthetic and the rendered fixtures add per-frame noise, and this is
load-bearing rather than realism for its own sake.

Every real camera adds noise, so two genuine recordings of the same motionless
scene are never identical. A copy-pasted segment **is** identical. That gap is
precisely what loop detection reads. A noiseless fixture would make the detector
look far better than it is.

The rendered fixture also varies each rep's amplitude, timing and position, and
lets the athlete tire over the recording. An earlier version drove the figure
with a pure sine wave, which made the "honest" control a literal pixel-perfect
loop — the detector was correctly flagging a fixture that really did repeat
itself.

---

## Test coverage

114 tests, no Postgres, Redis, S3 or network required. Model files are fetched
by `python -m scripts.fetch_model`.

| Area | Tests | What is verified |
|---|---|---|
| `test_cheat_detection.py` | 33 | Loop/cut/static logic, subject counting, metadata bounds, report semantics — every positive paired with an honest negative |
| `test_tampered_videos.py` | 12 | The DoD, against rendered video: looped clip, partial loop, spliced footage, still image, short clip, sped-up footage — plus honest controls, and loops surviving a real encode |
| `test_face_verification_record.py` | 6 | The three-state distinction, mismatch never recorded as `fail`, severity never `high` |
| Sprints 4–5 | 63 | Unchanged, still green |

`scripts/fetch_model.py` now also fetches the two face models. They are
optional: without them the identity check reports itself as not run, because a
missing model on the server is not evidence about the athlete.

---

## Sprint 6 Definition of Done

| Criterion | Status |
|---|---|
| Frame-consistency checks (perceptual hash, duplicated/looped frames) | Done — hash prefilter + signature confirmation |
| Abrupt-cut detection | Done |
| Single-person-in-frame validation | Done — sustained second person flagged, brief passer-by not |
| Face crop from the test video | Done — largest face, sampled across 12 frames |
| Face embedding similarity vs the registration photo | Done |
| Persist to `face_verifications` (`pass` / `fail` / `manual_review`) | Done — and no row when the check did not run |
| Metadata sanity checks | Done — duration per test type, resolution, framerate, re-encode artefacts |
| Flag reason system with severity into `flags` | Done — reason, detail, severity, timestamp |
| Tampered-video test set | Done — 6 tampering modes, rendered not committed |
| **A deliberately tampered video is correctly auto-flagged** | **Done — and the honest control is not** |

---

## What is not proven

**Accuracy against real footage.** Every fixture here is rendered or synthetic.
The checks are verified to do what they are specified to do; whether their
thresholds are right for real athletes in real light is a Sprint 13–14 question,
and the `evidence` dict on every finding exists to make that tuning possible.

**The face check's error rate.** Unmeasured, on any population. Treat the flag
as "a human should look", never as a result.

**Live-worker behaviour.** As in Sprint 5, the pipeline is exercised in-process.
The Celery path is unchanged by this sprint but still unmeasured under load.

---

## Notes for the next sprint

- **`reference_face_key` is still always null.** The face check therefore
  reports "no registration photo on file" for every submission today. Sprint 7's
  registration photo capture is what switches this check on.
- **`athletes.height_cm` is the one that matters for jump.** With no profile
  height the server flags rather than guesses; Sprint 7 makes the profile the
  only source.
- Thresholds live at the top of each check module with the measurement that
  justifies them beside them. Change them against data, not intuition.

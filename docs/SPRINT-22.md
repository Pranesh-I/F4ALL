# Sprint 22 — Accuracy Validation

## Status

**The tooling is done. The evidence is not, because no human ground truth
exists.** Sprint 22's Definition of Done is about evidence. Evidence needs
recordings a person has measured, so no accuracy number is claimed anywhere.
The report says so itself.

- **Backend:** 6 new tests (`test_accuracy.py`); ruff clean.
- **Run for real** on the four clips in `docs/reference-videos/`, using the
  server's own pipeline (8½ minutes on a laptop CPU).

## Built

| | |
|---|---|
| `docs/accuracy/dataset.csv` | the dataset. Per recording: the video, the human result and evaluator, the jumper's height, an anonymous person id, device, lighting, distance, camera angle, clothing, background, the evaluator's form judgement, and a cheat label |
| `python -m app.cli accuracy` | runs every video through the **verification worker's own pipeline** (extraction, scorer, integrity checks), with no database; field runs take the server's time by hand |
| `docs/accuracy/REPORT.md` | generated, per test, in the plan's format: dataset size, people, mean, signed and max error, within target, reps over- and under-counted (false positive and negative rates), form agreement, cheat detection (caught, false, missed), device and condition spread, the cases outside target, and the recordings waiting for a human |
| `docs/accuracy/README.md` | how to collect the dataset (15–20 recordings per test, 5+ people, deliberate variety, bad reps included), and the review of the existing clips |

**The rule:** a recording without a human result is processed and listed, but
left out of every number. Form and cheat agreement count only where a person
labelled them.

## What the existing clips turned out to be

The four "reference videos" were downloaded exercise clips, not app
recordings. Two labelled as sit-ups contain no sit-ups:
- one is a stock warm-up clip;
- one is an edited squat tutorial with title cards and an inset of another
  person.

The pipeline counted 0 and 1 sit-ups. Its integrity checks flagged both
(abrupt cut, subject swapped, impossible movement). That is the right
behaviour, but it is not an accuracy result. Only `vj_01.mp4`, a genuine
side-on jump, stays in the dataset; it needs the jumper's height and a
measured jump.

## First poor-performing case

On the genuine jump clip, the server found no jump, because the side-on view
hides the far ankle and the jump analyzer requires both. All 181 rejected
frames were for that one landmark. This is documented in
`docs/accuracy/README.md` as the first thing the pilot must confirm. It was
not changed on the strength of one downloaded clip.

## Definition of Done

| | |
|---|---|
| Formal dataset exists | Format, tooling and process yes; recordings **no** |
| Human ground truth exists | **No** |
| AI results are recorded | Yes, for every recording run |
| Error metrics are calculated | Yes, by the tool, once ground truth exists (tested on synthetic cases) |
| Each core exercise has a report | Generated per test present in the dataset |
| Test conditions are recorded | Yes (dataset columns) |
| Accuracy targets compared with actual results | Automatic (±1 rep, ±3 cm), once there is data |
| Poor-performing cases documented | Automatic ("Outside the target") |
| No accuracy claim without evidence | **Yes:** the report refuses to make one |

The dataset is collected during the pilot (`docs/PILOT.md` §4): two
evaluators score every official attempt, and the videos go into
`dataset.csv`.

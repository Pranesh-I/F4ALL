# Accuracy Validation (Sprint 22)

Proves, with human ground truth, that the server measures what the app
claims. Three files:

| File | What |
|---|---|
| `dataset.csv` | One row per recording: the video, the human result, who measured it, the person, device and conditions |
| `REPORT.md` | Generated, per test, in the plan's format. Never edited by hand |
| `dataset.results.csv` | Generated: the dataset with the AI's result, status, form score and integrity flags |

```bash
cd backend
python -m app.cli accuracy              # runs every video through the server pipeline
python -m app.cli accuracy --skip-run   # report only, from ai_result already in the dataset
```

The command runs the **verification worker's own pipeline**: pose extraction,
the scorer and the integrity checks. The face check is left out because the
dataset has no registration photos. It takes about 2 minutes per minute of
video on a laptop CPU.

## The rule

A recording without a human result is processed and listed, but **left out of
every number**. The report says "No accuracy claim can be made yet" until at
least one recording has one.

## Collecting the dataset

Per test, aim for **15–20 recordings from at least 5 different people**. The
targets are ±1 rep for the rep tests and ±3 cm for the jump.

1. **Record in the app** (practice is fine), exactly as an athlete would.
   Copy the video off the phone, or download it from the dashboard's review
   page.
2. **Vary the conditions on purpose:** people, height and build, clothing
   (tight and loose), indoor, outdoor and dim light, 2 m and 3 m, side-on and
   slightly angled, plain and busy backgrounds, a low-end and a mid-range
   phone.
3. **Include bad reps** (half squats, sagging push-ups, swung curls). The
   human result is what a strict judge would count.
4. **Measure by hand.** Count reps frame by frame where it is arguable. For a
   jump, record the athlete's height and measure the jump against a wall mark
   or a jump mat. For a field run, use a stopwatch, and copy the server's time
   from the dashboard into `ai_result`.
5. **Label form and cheating only when you know.**
   - `human_form`: good, or faulty.
   - `cheat_label`: none, or the trick. Record some on purpose: a replayed
     clip, a sped-up video, someone else doing the test.
   - Blank means not labelled, and the report then makes no claim about it.
6. Add the row, run the command, and commit the CSV and the report. The videos
   themselves are git-ignored.

## Review of the clips in `docs/reference-videos/` (2026-10-13)

The four clips there were all downloaded exercise videos, not recordings made
with the app. Run through the pipeline and inspected frame by frame:

| Clip | What it actually is | Pipeline | Use |
|---|---|---|---|
| `sit-ups/su_01.mp4` | A stock warm-up clip of a standing exercise with an end card. **Not a sit-up** | 0 sit-ups; flagged abrupt cut, looped frames, subject swapped, impossible movement | None |
| `sit-ups/su_02.mp4` | An edited squat tutorial with title cards, an anatomy graphic and an inset of another person. **Not a sit-up** | 1 sit-up; flagged abrupt cut, subject swapped, impossible movement | An example of an edited video, which the integrity checks caught. It cannot be counted in the cheat numbers until a person labels it |
| `vertical-jump/vj_01.mp4` | A genuine side-on vertical jump with a wall reach | With an assumed height: **no jump detected**. The far ankle is hidden in 181 of 343 frames (see below) | **Kept in the dataset**; needs the real height and a measured jump |
| `vertical-jump/vj_02.mp4` | Mostly an instructor talking to camera | — | None |

Correctly, the pipeline did not count sit-ups in videos that contain none.
That is not an accuracy result, but it is a sanity check that it doesn't
invent reps.

## Poor-performing case: the far ankle in a side-on jump

Found by the pilot rehearsal (2026-10-13) on `vj_01.mp4`, the only genuine
clip. The server found **no jump**, though the athlete clearly jumps:

- **181 of 343 frames were rejected, every one for the same landmark: the far
  (left) ankle**, hidden behind the near leg. Its visibility was 0.22–0.36
  against the required 0.5. The jump analyzer requires *both* ankles (and
  both hips, and the nose) in every frame.
- The analyzer saw three take-offs. Rejected frames cut each flight short, so
  each was refused as too brief (69–138 ms off the ground; a real jump is
  roughly 400–600 ms, and the minimum is 150 ms).

The app tells athletes to stand **side-on** for the jump, which hides the far
ankle. The rep analyzers already use the more visible side
(`select_more_visible_side`); the jump analyzer does not.

**Not changed yet, deliberately.** This is one downloaded clip, not an app
recording, and has no ground truth. A change would touch the Kotlin and
Python analyzers and the parity fixtures. Before deciding, confirm on the
first pilot jump recordings whether app recordings show the same far-ankle
rejections. If they do, the likely change is to require one ankle (the more
visible side) for the jump's stature and landing, recorded in
`docs/pilot/threshold-changes.md`.

# Reference Videos & Ground Truth

This folder holds the calibration data Sprint 3's accuracy targets are measured against.
It is currently **empty of real data** — collecting it is the open Sprint 0 task that
blocks Sprint 3's Definition of Done.

## Why this exists

The analyzers are tested two ways, and only one of them can tell you the app is *correct*:

| | What it proves | Where |
|---|---|---|
| Unit tests on synthetic geometry | The state machines behave as specified | `app/src/test/.../*AnalyzerTest.kt` |
| **This reference set** | **The thresholds suit real human bodies** | `ValidationHarnessTest` |

A synthetic sit-up is a perfect trigonometric arc. A real one has an athlete rocking for
momentum, a shirt occluding the hip, a knee drifting, and a phone propped against a water
bottle. Only the second table row catches that.

## Layout

```
docs/reference-videos/
├── README.md            this file
├── ground-truth.csv     manual measurements, one row per recording
├── *.mp4                the recordings themselves (git-ignored — they are large)
└── sequences/
    └── *.csv            pose landmarks extracted from each recording
```

The `.mp4` files are ignored by git (see `.gitignore`); the pose sequences are committed,
because they are small text files and they are what the harness actually reads. Store the
videos somewhere shared and keep the sequences here.

## Collecting a reference recording

1. **Record** the test in the app, exactly as an athlete would — no special care taken.
   Vary the conditions deliberately: different people, body types, clothing, indoor and
   outdoor light, phone propped at different heights and distances.

2. **Measure it manually.** Count the reps yourself from the video, frame by frame if the
   count is arguable. For a vertical jump, measure against a wall mark or use a jump mat.
   This number is the ground truth — if it is sloppy, everything downstream is sloppy.

3. **Pull the pose sequence.** The app writes one per attempt:

   ```
   adb exec-out run-as com.sai.sports cat files/sequences/<attempt-id>.csv > sequences/<name>.csv
   ```

4. **Record the athlete's height** for vertical jumps. Without it the harness skips the
   case rather than guessing — a wrong height produces a confidently wrong jump number.

5. **Add a row** to `ground-truth.csv`.

## Running the validation

```
cd mobile
gradlew :app:testDebugUnitTest --tests "*ValidationHarnessTest*"
```

The report is written to `mobile/app/build/reports/validation/sprint3-accuracy.csv`, with
per-sequence expected vs. actual, error, and tracking quality.

With no data present the test **skips**. Once sequences exist it **enforces** the targets
in `AnalyzerThresholds`: ±1 rep for sit-ups, ±3 cm for vertical jump.

## What "done" looks like

Sprint 3 closes when 15–20 sequences covering different people are all within target.
Expect to adjust `AnalyzerThresholds` to get there — that is the point of the exercise,
not a sign something went wrong. The threshold most likely to need moving is
`SITUP_UP_ENTER_ANGLE`; see `docs/SPRINT-3.md` for why.

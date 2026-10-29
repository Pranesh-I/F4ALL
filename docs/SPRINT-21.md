# Sprint 21 — Device Compatibility

## Status

**Partly done: one phone of the 4–6 the plan asks for.** An OPPO Reno13 5G
(upper mid-range) was measured over USB. What was built makes every further
phone one command. The user decided to finish the code for all remaining
sprints first and do the hands-on device testing as one batch at the end. The
remaining device steps are on the deferred list in `TASK.md`.

- **Mobile:** 369 unit tests green (2 new), 1 skipped. Android lint: 67
  warnings (baseline unchanged). Release build OK.
- **Measured on the Reno13:**
  - cold start 1.7 s;
  - the pose pipeline before and after the change below;
  - CPU, memory, UI frame times, battery temperature and thermal status, and
    estimated battery drain;
  - the camera is released on Home.
- **Not yet measured:** any entry-level phone; resume and the 10 open/close
  cycles; offline → sync and background sync on a device.

## What changed

| | Before | After |
|---|---|---|
| Pose model processor | CPU | **GPU**, falling back to the CPU when the GPU cannot load the model or fails on the first frames |
| Frames to the model | YUV, converted to a bitmap on the CPU | **RGBA** from CameraX's native converter |
| Pose results per second (Reno13, nobody in view) | 16.1 (min 13) | **23.4** (min 22) |
| Frames dropped by the model | 34.6% | **1.5%** |
| Frame-to-result latency | 98.5 ms | **43.6 ms** |
| App CPU | ~200% | ~160% |
| UI janky frames | 8% | 19% (p95 17 → 24 ms): the screen shares the GPU with the model |

Dropping a third of the frames meant fast moments (a push-up's bottom, a
jump's peak) could fall between the frames analysed. The UI cost was judged
acceptable on a screen whose job is counting.

Also new: the app logs `PosePerf` once a second (results per second, frames
sent, latency, dropped, and which processor), so any phone can be measured
without a debugger.

## Built

| File | What |
|---|---|
| `PosePerfMeter.kt` | the per-second pose numbers (unit-tested) |
| `PoseLandmarkerHelper.kt` | GPU first, CPU fallback, at start and on a run-time GPU error; fixed an ordering bug that reset the recorded processor |
| `CameraBinding.kt` | RGBA analysis frames |
| `mobile/scripts/device_check.py` | **one command per phone.** Device facts, cold start, a timed pose run, CPU, memory, heat, battery and UI frames, the camera lifecycle (Home, resume, 10 open/close cycles), and an optional guided offline → sync walk. Writes `docs/device-matrix/<model>.json` and prints the matrix row |
| `docs/DEVICE-MATRIX.md` | the matrix, what the first phone changed, the proposed minimum requirements, known limitations |
| `docs/device-matrix/CPH2689.json` | the Reno13's numbers |

## Definition of Done

| | |
|---|---|
| Full device matrix tested | **No:** 1 of 4–6 |
| Low-end devices included | **No:** none available yet |
| No critical camera lifecycle failure | Release on Home passed; the rest deferred |
| No unacceptable memory crash | None seen; 266–344 MB under load |
| AI performance measured | **Yes** (Reno13) |
| Battery/heat measured | **Yes**, but while charging from 12%; to re-measure on battery |
| Offline mode tested | Deferred (device batch) |
| Background sync tested | Deferred (device batch) |
| Known device limitations documented | Yes |
| Release minimum-device requirements documented | Proposed; to confirm on entry-level phones |

## The device batch (to run later)

For each phone, signed in, in English, with the camera allowed once:

```bash
python mobile/scripts/device_check.py --apk mobile/app/build/outputs/apk/debug/app-debug.apk \
    --seconds 120 --offline --note "person in view, on battery, 80%"
```

Stand in front of the camera during the timed run. Then add the printed row
to `docs/DEVICE-MATRIX.md`.

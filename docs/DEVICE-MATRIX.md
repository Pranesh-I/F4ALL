# F4ALL — Device Matrix and Minimum Requirements

The plan asks for 2–3 entry-level and 2–3 mid-range Android phones. **One is
measured so far**, an upper-mid-range phone. Every low-end row is still open
and is on the deferred list in `TASK.md`. No claim about low-end phones is
made below without a measurement.

Each row comes from `python mobile/scripts/device_check.py`; the full numbers
are in [docs/device-matrix/](device-matrix/).

## Matrix

| Device | Class | Camera lifecycle | Pose FPS (processor) | Latency | Battery | Heat | Memory | Offline | Sync | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| OPPO Reno13 5G (CPH2689, Android 16, Dimensity 8350, 8 GB) | upper-mid | Release on Home PASS; resume and 10 cycles pending | **23.4** (GPU); 16.1 on CPU only | 44 ms (98 ms CPU) | ~3.3% per 10 min of camera | battery 41.3 °C, thermal status 3 while charging from 12% | 266–344 MB | pending | pending | partial |
| *entry-level phone 1* | low | — | — | — | — | — | — | — | — | not tested |
| *entry-level phone 2* | low | — | — | — | — | — | — | — | — | not tested |
| *mid-range phone 2* | mid | — | — | — | — | — | — | — | — | not tested |

"Pose FPS" is pose results per second with nobody in view. That is the
slowest case: with a person in view the model tracks instead of searching
every frame.

## What the first phone changed

The first measurement on the Reno13 found the pose model running on the CPU:
- about 16 results a second from about 26 camera frames, so a third of the
  frames were dropped;
- close to 100 ms from frame to result.

Fast movements (a push-up's bottom, a jump's peak) can fall between the
frames that remain. Two changes, both measured before being kept:

- The model now runs on the **GPU**, and falls back to the CPU if the phone's
  GPU cannot load it, or fails on the first frames.
- The camera hands over **RGBA frames** directly, removing one colour
  conversion per frame.

Result: 23.4 results a second, 1.5% dropped, 44 ms. App CPU went from about
200% to about 160%. The price: the screen's own drawing shares the GPU, and
janky UI frames rose from 8% to 19% (95th percentile 17 → 24 ms). That is
acceptable on a camera screen whose job is counting.

## Minimum requirements (proposed)

Set from what the app needs; **to be confirmed on entry-level phones**:

| | Minimum | Why |
|---|---|---|
| Android | 8.0 (API 26) | `minSdk`; CameraX and MediaPipe |
| CPU | 64-bit or 32-bit ARM | Native libraries ship for arm64-v8a and armeabi-v7a (also x86 for emulators) |
| RAM | 3 GB (provisional) | The app uses 270–350 MB with the camera and model running. Below 3 GB, Android may kill it mid-test; to be measured |
| Rear camera | 720p video | Recordings are HD (720p) for the server's re-verification |
| GPU | OpenGL ES 3.1 recommended | For the GPU path. Without it the CPU path runs, about a third slower on the first phone, and slower still on a weak CPU |
| Storage | 1 GB free | Videos wait on the phone until they are sent |
| Gyroscope | needed for the shuttle run | The run is timed from the turns it detects; without it, the setup screen says so before starting |
| GPS | needed for the endurance run | Distance is measured by GPS |
| Network | none to record | Everything is recorded offline and sent later |

## Known device limitations

- On the first phone the battery reached 41.3 °C and thermal status 3
  (severe) after about 8 minutes of camera use while charging from 12%. The
  charging confounds this. To re-measure on battery from above 50%.
- Battery drain on a laptop USB port: the phone lost charge while plugged in,
  because the port supplies less than the camera and GPU draw. Not a field
  problem, but measure on battery.
- Not yet measured anywhere: a low-end phone, offline → sync on a device,
  background sync with the app closed, and the 10-cycle camera test.

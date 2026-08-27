# Server-side pose model

The verification pipeline needs MediaPipe's Pose Landmarker task file here:

```
backend/models/pose_landmarker_full.task
```

It is **not committed** — it is a ~9MB binary that changes independently of this
code, and it is git-ignored.

## Fetch it

```bash
cd backend
python -m scripts.fetch_model
```

or directly:

```bash
curl -L -o models/pose_landmarker_full.task \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task
```

## Why "full" and not "lite"

The mobile app ships `pose_landmarker_lite.task`, chosen because it has to run
in real time on a ₹8,000 phone without draining the battery.

The server has no such constraint, so it runs the more accurate `full` variant.
That asymmetry is deliberate: the server is meant to be the *better*
measurement, not merely a second opinion of equal quality. It is also a reason
the two scores can legitimately differ slightly, which is why the discrepancy
tolerance in `Settings` is not zero.

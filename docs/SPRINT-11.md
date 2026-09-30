# Sprint 11 — Server-Side AI Verification

## Status

The server's verdict is now explicit, explained, persisted and retrievable.

- **Backend:** 405 tests green (51 new, 4 updated for the new lifecycle), ruff
  clean. Migration applies, reverses and re-applies on **Postgres 16** and
  SQLite, and `alembic check` reports no drift.
- **Live end to end:** API → Postgres + storage → Redis → Celery worker →
  MediaPipe → Postgres. Three submissions came out verified, flagged and
  rejected, as intended.
- **Mobile:** 309 unit tests green (1 skipped: the reference-data accuracy
  harness, which has no data yet).
- **Dashboard:** typecheck clean, 30 tests green, and it builds.
- **Not proven:** a submission from a real phone. No Android device was used.
  See "What is not proven".

Most of the pipeline already existed from Sprints 5 and 6: upload, Celery,
server-side scorers for all six exercises, discrepancy flags and integrity
checks. This sprint fixed what was wrong with it and filled in the lifecycle,
validation, finalization, persistence and duplicate-processing gaps. Nothing
was rewritten; the scorers are untouched and parity still holds 38/38.

---

## Lifecycle

```
upload_sessions:  created -> chunks arriving -> completed      (unchanged)
test_results:     uploaded -> processing -> verified | flagged | rejected
review:           verified | flagged | rejected -> approved | rejected | pending_sync
```

- `uploaded` is new. The video is stored and the job is queued, but no worker
  has claimed it. Submissions used to start at `processing`, so a job waiting
  in the queue looked the same as one a worker was running.
- `processing` is set only by a worker, when it claims the job. The claim also
  records `processing_started_at`.
- Nothing jumps from upload to a verdict, and nothing but an official approval
  sets `final_score`.
- `AWAITING_VERIFICATION` (`uploaded`, `processing`) is the one definition used
  by the worker's claim, `/health/verification`, `cli sla`, `reverify-pending`,
  the reprocess endpoint and the dashboard queue.

## When the machine rejects, and when it only flags

REJECTED is reserved for submissions that **nobody could verify**, whether
machine or human:

| Code | Verdict | Why |
|---|---|---|
| `video_not_submitted` | rejected | There is no recording |
| `video_empty`, `video_too_large` | rejected | Not a usable file |
| `unsupported_format` | rejected | Not an MP4. The bytes are checked; the declared type is not trusted |
| `video_unreadable`, `no_decodable_frames` | rejected | Corrupt or truncated |
| `video_missing_from_storage` | flagged (high) | We lost a checksum-verified upload. That is our failure, not the athlete's |
| `test_type_not_verifiable` | flagged | No automatic scorer exists; a reviewer can still score it |
| `athlete_height_unknown` | flagged | The jump can't be calibrated without a height |

Anything about the athlete's performance stays FLAGGED, exactly as before: a
disagreement, poor tracking, the server failing to score, or an integrity
finding.

An official can still act on a machine rejection until someone has reviewed
it. Requesting resubmission frees the athlete's slot in the session.

## The decision

`verification/finalization.py` is a pure function. Its precedence:

1. Validation failure: REJECTED or FLAGGED, per the table above.
2. A processing error that survived its retries: FLAGGED (`processing_error`).
3. Otherwise VERIFIED, but only if all of these hold: the server scored the
   video, the device agrees within tolerance, tracking confidence is at least
   the threshold, and every integrity check passed. If any fails, the result
   is FLAGGED with one flag per failing check.

Every verdict stores five checks (`validation`, `processing`,
`server_scoring`, `comparison`, `integrity`), each passed, failed or skipped
with a code and detail. This replaced two copies of the verdict logic in
`tasks.py` that could drift apart.

## Persisted per result

| Field | Notes |
|---|---|
| `processing_started_at`, `verified_at` (= completed), `processing_duration_ms` | Queue wait is `created_at` → `processing_started_at` |
| `verification_reason` | The deciding check's code |
| `pipeline_version` | `f4all-verify/1.1`. The snapshot also records the MediaPipe and OpenCV versions and the **pose model's SHA-256**, because `fetch_model` downloads "latest" |
| `mobile_result` | Written at submit time: score, rep count or measurement, form score. `authoritative: false` |
| `server_result` | Status, score, rep count or measurement, form score, confidence, frame counts, rep-attempt breakdown, comparison (difference, tolerance), video metadata, pipeline. `authoritative: true` |
| `verification_checks` | The five checks above |
| `verification_attempts`, `verification_run_id` | Duplicate-processing guard |

`provisional_score` and `server_score` stay the queryable numbers. The rep
count and measurement are the same number under the name that fits the test,
so no redundant columns were added.

**Form score** is a port of the app's `FormSummary.scorePercent`: good reps ÷
attempted reps, using integer division. The phone now sends its own copy
(`provisional_form_score`, optional). Both are stored and shown. No
form-score threshold exists, because none has been calibrated; the scores are
for a reviewer to read.

## Duplicate processing

Late acks mean Celery redelivers a job whose worker died, and
`reverify-pending` re-queues stale ones. Two protections cover this:

- A run **claims** the result (`uploaded`/`processing` → `processing`) under a
  fresh run id. A result that already has a verdict is skipped as
  `already_finalized`.
- The verdict, its flags and the face record are written in **one
  transaction**, and only if the run still holds the latest claim. A
  superseded run's verdict is discarded.

Tested in-process, and live: re-queuing a verified result through Redis was
skipped in 8 ms with no new flags.

## Failure handling

| Failure | What happens |
|---|---|
| Corrupt or unreadable video | Rejected at once. **No longer retried** (it used to wait through about 7 minutes of backoff as an "infrastructure" error) |
| Missing video | Rejected (`video_not_submitted`). Used to sit in `processing` forever with only a log line |
| Pose model or runtime missing, storage unreachable, database error | Retried with backoff, then flagged `processing_error` with the error recorded |
| Database down for the whole run | Nothing can be written. The result stays `uploaded`, visible in the `/health/verification` backlog and picked up by `reverify-pending` |
| Unexpected exception | Flagged `processing_error`; `verification_error` holds the message |
| Video uploaded as one test, submitted as another | `400 {"code": "test_type_mismatch"}` |
| Non-MP4 content type at upload init | `415 {"code": "unsupported_format"}` |

Upload errors now carry a stable `code` next to the `detail` string. The app
reads only `detail`, which is unchanged.

## Thresholds

| Setting | Default | Origin |
|---|---|---|
| `DISCREPANCY_TOLERANCE_REPS` | 2 | Sprint 5, unchanged. Two measurements that each meet the ±1-rep target can differ by 2 |
| `DISCREPANCY_TOLERANCE_CM` | 5 | Sprint 5, unchanged. Slightly stricter than the 6 cm two in-spec jumps (±3 cm each) could differ by |
| `VERIFICATION_MIN_CONFIDENCE` | 0.55 | Was a hard-coded constant; now configurable, value unchanged |
| `VERIFICATION_SLA_SECONDS` | 300 | Sprint 5 target, unchanged |
| `UPLOAD_ALLOWED_CONTENT_TYPES` | `["video/mp4"]` | New; the app only uploads MP4 |

None of these has been calibrated against real recordings yet, because
`docs/reference-videos/` is empty.

## API

`GET /api/verification/{result_id}` now adds:

- `processing_started_at`, `processing_completed_at`, `processing_duration_ms`
- `comparison`: mobile and server rep count, measurement and form score
- `verification_reason`

**Officials** also get `difference`, `tolerance`, `checks`, `mobile_result`,
`server_result`, `pipeline_version` and `verification_attempts`.

**Athletes** get the timing and the comparison numbers. They get the reason
only for a rejection, where it names what to fix. They never get the
tolerance: telling an athlete how far a claim may drift before it is flagged
tells them how far to inflate it.

`/health/verification` now also reports **measured** timings over the last
24 h: p50/p95 turnaround and processing time, and how many were within the SLA.

---

## End-to-end evidence (live stack)

Setup: Postgres 16 and Redis 7 from `docker-compose.yml`, uvicorn, one Celery
worker (`--pool=solo`, Windows), CPU only.

**Video:** "Squat - exercise demonstration video" by FitnessScape, Wikimedia
Commons, CC BY 3.0. Transcoded to 854×480 MP4 as the app would. 7.1 s, 213
frames. **Counted by hand from the frames: 2 squats.**

**Device claims are simulated.** No phone was involved.

| Submission | Claim | Server | Verdict | Reason | Processing | Turnaround |
|---|---|---|---|---|---|---|
| Real squat video | 2 reps, form 50 | 2 reps, form 50, confidence 0.97 | verified | `server_and_device_agree` | 12.98 s | 13.4 s |
| Same video | 9 reps | 2 reps | flagged (high) | `score_discrepancy` | 11.77 s | 11.8 s |
| Corrupt file (MP4 header + zeros) | 5 | — | rejected | `video_unreadable` | 0.34 s | 0.4 s |

`/health/verification` afterwards: `completed: 3`, `within_sla: 3`,
turnaround p95 13.39 s against the 300 s SLA.

Pose extraction dominates the time: about 10 s of the 13 s for a 7 s clip on
this laptop's CPU. That scales with video length, so a 60 s sit-up recording
should take roughly 1.5 minutes on similar hardware. **This is an estimate,
not a measurement.**

---

## What is not proven

1. **A real mobile submission.** The pipeline was driven by an HTTP client
   speaking the app's upload protocol, with simulated device scores. The app's
   side is unit-tested (it sends `provisional_form_score` and shows `uploaded`)
   but was not run on a device.
2. **Accuracy.** One real clip scored correctly: 2 of 2 reps. That is a single
   sample, not an accuracy figure. The 38 parity fixtures are synthetic and
   prove only that the server matches the app. `docs/reference-videos/` is
   still empty.
3. **SLA under load.** Timings are from one worker processing one short clip
   at a time.
4. **The pipeline on real pixels for all six exercises.** Only squats ran
   through real MediaPipe on real footage. All six exercises ran through the
   worker using fixture landmarks.

## Known issues (not changed in this sprint)

- `GET /api/results/{id}` still returns flag reasons to the athlete, which
  contradicts `routers/verification.py`'s policy of hiding them. This predates
  Sprint 11 and is left for Sprint 14 or 15.
- The comment in `config.py` says Postgres is on 5433, but
  `docker-compose.yml` maps **5434**.
- The Kotlin parity export writes LF line endings, while the committed
  fixtures are CRLF. On Windows, running the mobile unit tests leaves those
  fixtures showing as modified, with identical content.
- A machine rejection in an assessment session holds the athlete's slot until
  an official requests resubmission.

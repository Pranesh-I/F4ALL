# F4ALL — Load and Reliability Report (Sprint 23)

Run 2026-10-13 on **one laptop**: AMD Ryzen 5 7235HS, 4 cores / 8 threads,
24 GB RAM, Windows 11. On that one machine, Docker Desktop ran Postgres 16 and
Redis 7, alongside the API, the workers and the load generator. Every number
below is therefore a **lower bound** for production hardware.

Raw data:
- [load/run_a_stats.csv](load/run_a_stats.csv): Locust, per route;
- [load/reliability.json](load/reliability.json): each scenario.

## Load target

Defined before judging the results, for the **pilot and the first district
trials**:

| | Target |
|---|---|
| Concurrent athletes using the app | **100** |
| Every API route, 95th percentile | **under 1 s** (the phone is on 3G/4G; network time dominates) |
| Errors | **under 1%** |
| Upload success | **99% or better** |
| Lost submissions | **none, ever** |
| Verification | within the 5-minute SLA, **given enough workers**. Worker capacity is measured, so the count can be sized |

National scale (thousands at once) is **not** the target of this run. It
needs production hardware, S3 and horizontal API and worker scaling, and is
listed as a gap below.

## How to reproduce

```bash
cd backend
python -m loadtest.env setup --athletes 200     # its own database, Redis db and storage
python -m loadtest.env api                       # 4 API processes on :8020
locust -f loadtest/locustfile.py --headless -u 100 -r 10 -t 3m \
    --host http://127.0.0.1:8020 --csv var/load/run_a
python -m loadtest.reliability all               # scenarios B–G (stops and starts containers)
python -m app.cli consistency                    # the reliability rules, against any database
```

## A — 100 athletes at once

Each simulated athlete repeats the app's pattern:
- opens the app (profile, open session, notifications);
- looks at history;
- opens the leaderboard;
- often records a test: a chunked upload of a real 1.7 MB jump video, then
  the submission.

The run is **upload-heavy**: about one video per athlete every 20 s, far more
than a trials day.

| | Result | Target |
|---|---|---|
| Requests | 17,142 in 3 minutes, **95.5 per second** | — |
| Errors | **0 (0.00%)** | under 1% |
| Videos uploaded and submitted | **620**, 0 failed (11 interrupted by the end of the run) | 99%+ |
| Median / p95 / p99, all routes | 220 / 720 / 990 ms | — |
| Slowest route at p95 | `POST /api/tests/submit`, 850 ms | under 1 s |
| Upload chunk (256 KB) at p95 | 700 ms | under 1 s |

**Target met**, with the database, the API and the load generator sharing
one laptop.

## Worker capacity (from B)

| | |
|---|---|
| Processing a 12-second 720p video | **25 s** median, 28 s p95 (CPU, full pose model, every integrity check) |
| Throughput | **2.2 videos per minute per worker** |
| Sizing | A 60-second video takes roughly 2 minutes. To keep a trials day's verification inside the 5-minute SLA, run at least **(submissions per minute) ÷ 2.2 × (video seconds ÷ 12)** workers, more with headroom. A GPU or more cores per worker would raise this |

## Reliability scenarios

`python -m app.cli consistency` checks the five forbidden states (uploaded
but missing, verified without a result, decided without verification,
duplicate official result, stuck in verification). It was **clean after
every scenario**.

| Scenario | What was done | Result |
|---|---|---|
| **B** verification backlog | 16 videos queued at once, 2 workers | **16/16 verdicts, 0 lost.** Queue wait p50 109 s, p95 191 s; drained in 218 s |
| **C** worker crash | worker hard-killed while processing | **Recovered.** Both stranded jobs reached a verdict 54 s after the crash (new worker, plus `reverify-pending`). The interrupted job ran twice and wrote exactly one verdict |
| **D** API down | API stopped mid-upload, then restarted | The phone sees a network error and keeps its queue. **Chunks kept across the restart**; the upload finished from where it stopped; **a retried submission returned the same result**, not a second one |
| **E** database down | Postgres stopped under traffic | **Nothing reported as saved that wasn't.** Back 2.3 s after Postgres returned. *Found a hang; fixed (below)* |
| **F** Redis down | Redis stopped during submissions | **Submissions still accepted** and held as "uploaded"; all verified once Redis returned. *Found a slowdown and a dying worker; fixed (below)* |
| **G** interrupted upload | half a video, then the rest | 3 of 7 chunks kept, **only the 4 missing ones resent**, checksum exact |

## Found and fixed

| Found | Before | Fix | After (re-measured) |
|---|---|---|---|
| A worker **exited** when Redis went away (`broker_connection_max_retries=0`, meant only for the API's publish path). Nothing restarted it, so verification would stop until someone noticed | worker dead | Workers now reconnect indefinitely (a `celeryd_init` override); the API still fails fast. Compose `api`, `worker`, `notifier` and `retention` now `restart: unless-stopped` | worker survived a 20 s outage and verified the next job |
| Every request with Redis down paid the rate limiter's two 0.5 s Redis timeouts, and logged a warning each time | **25.8 s** per submission (10 requests) | After a failure the counter skips Redis for 30 s, and logs once per outage | **6.1 s** per submission |
| With Postgres down, requests **hung until the client gave up**, and so did `/ready` | 30 s+, no answer | 3 s connect timeout, 10 s pool timeout; "database unreachable" answers **503 with Retry-After: 30**. The phone retries 5xx with back-off | **503 in 6.6 s** |
| `/ready`'s Redis ping had no read timeout, so it hung behind Docker's port proxy | hung | 2 s read timeout | answers in about 4 s while Redis is down |

## Gaps against the target, and beyond it

- **Verification throughput is CPU-bound:** 2.2 videos per minute per worker
  here. Workers must be sized to the session's expected submissions (formula
  above). An autoscaling rule on queue length is recommended for production.
- **During an outage, `/ready` takes 4–7 s to answer.** A load balancer's
  health-check timeout must be at least 10 s, or it will see a timeout rather
  than "not ready". It correctly reports not ready either way.
- **Worker crash recovery takes up to 25 minutes when unattended.** With
  Redis as the broker, a job held by a dead worker otherwise comes back only
  after the broker's visibility timeout (1 hour). The new compose `requeue`
  service runs `reverify-pending` every 5 minutes for jobs over 20 minutes
  old. An operator can run it at once for one-minute recovery, as scenario C
  did.
- **Not tested here:** national scale; S3 instead of local disk; a managed
  Postgres with TLS; several API hosts behind a load balancer. These belong to
  SAI's staging environment, and the same scripts run against it by changing
  the host.

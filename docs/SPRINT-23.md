# Sprint 23 — Backend Load & Reliability

## Status

**Done against an explicit pilot-scale target, on one laptop.** The full
report, with the numbers, is [LOAD-TEST.md](LOAD-TEST.md).

- **Load:** 100 concurrent athletes, upload-heavy, for 3 minutes. 17,142
  requests, **0 errors**, 620 videos uploaded and submitted, and every route
  under 1 s at p95 (the slowest was 850 ms).
- **Reliability:** all seven scenarios pass: backlog, worker crash, API down,
  database down, Redis down, interrupted upload, and a worker surviving a
  Redis outage. The consistency check was clean after each.
- **Found and fixed four outage problems.** Each was re-measured after the
  fix:
  - a worker exited at the first Redis blip, with nothing to restart it;
  - with Redis down, submissions took 25.8 s; now 6.1 s;
  - with Postgres down, requests hung for 30 s+; now a 503 to retry, in 6.6 s;
  - `/ready` hung when Redis was down.
- **Backend:** tests green (see the final run in the report); new tests for
  the consistency check, the worker reconnect, the Redis circuit breaker and
  the 503.

## Built

| | |
|---|---|
| `app/services/consistency.py`, `python -m app.cli consistency` | the plan's reliability rules as queries: uploaded but missing, verified without a result, decided without verification, duplicate official result, stuck in verification. Read-only; exits 1 on a problem. For after load tests, after outages, and on a schedule |
| `loadtest/env.py` | a disposable environment: its own database, Redis db, storage and port, 200 seeded athletes and an open session |
| `loadtest/locustfile.py` | scenario A: the app's real request mix, chunked uploads of a real video |
| `loadtest/reliability.py` | scenarios B to G, plus the worker-survives-Redis check. It starts and kills its own API and workers, stops and starts the containers, and writes `docs/load/reliability.json` |
| Fixes | `worker.py` (workers reconnect indefinitely), `rate_limit.py` (30 s circuit breaker, one log line per outage), `database.py` (connect and pool timeouts), `main.py` (503 + Retry-After on an unreachable database), `health.py` (read timeout), `docker-compose.yml` (restart policies; a `requeue` service every 5 minutes) |

Locust was installed in the development virtualenv only; it is not a
production requirement.

## Definition of Done

| | |
|---|---|
| Concurrent uploads tested | Yes: 620 under 100 concurrent users |
| Queue backlog tested | Yes: 16 videos, 2 workers, 0 lost |
| Worker crash tested | Yes: recovered, one verdict |
| API failure tested | Yes: upload resumed, retried submission idempotent |
| DB failure tested | Yes: fails cleanly (503), nothing half-written |
| Redis failure tested | Yes: accepted, held, verified afterwards; the worker survives |
| Upload interruption tested | Yes: only missing chunks resent |
| No official result corruption observed | Yes: consistency clean after every scenario |
| Recovery behaviour documented | Yes |
| Load target explicitly defined | Yes: the pilot scale, in LOAD-TEST.md |
| Meets it, or gaps documented | Meets it on a laptop. Gaps: CPU-bound verification (size workers by the formula), the health-check timeout, and national scale on SAI's staging |

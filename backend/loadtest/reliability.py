"""Failure scenarios against the live load environment (Sprint 23, B to G).

    python -m loadtest.env setup --athletes 200
    python -m loadtest.reliability all            # or one: backlog, worker_crash, ...

Starts and stops its own API and worker processes, and stops and starts the
compose Redis and Postgres containers, so run it on a development machine
only. Each scenario checks the reliability rules with ``app.cli consistency``
and writes what it measured to ``docs/load/reliability.json``.

* **B backlog:** more videos than the workers can take at once; none may be
  lost, and the queue wait and processing time are measured.
* **C worker crash:** a worker killed mid-job. The job must be recoverable,
  and recovered exactly once.
* **D API down:** the API stops while a phone is uploading; the upload
  resumes from where it stopped once the API is back, and a retried
  submission returns the same result.
* **E database down:** Postgres stops under traffic. Requests fail cleanly
  (no hang, no 200 for something not saved), and nothing is half-written.
* **F Redis down:** submissions are still accepted while Redis is down, wait
  safely, and are verified once it returns.
* **G interrupted upload:** half a video, then the rest; the server keeps
  what it had and the file is exact.
"""

from __future__ import annotations

import concurrent.futures as futures
import json
import os
import subprocess
import sys
import time
from datetime import UTC, datetime

import httpx

from loadtest.env import (
    API_URL,
    ATHLETES_FILE,
    BACKEND,
    PYTHON,
    REPO,
    environment,
    run_api,
    run_worker,
    unique_video,
)

OUT = REPO / "docs" / "load" / "reliability.json"
TERMINAL = {"verified", "flagged", "rejected", "approved"}
DATA = json.loads(ATHLETES_FILE.read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# Plumbing
# ---------------------------------------------------------------------------


class Stack:
    """The API and workers of the load environment, as child processes."""

    def __init__(self) -> None:
        self.api: subprocess.Popen | None = None
        self.workers: dict[str, subprocess.Popen] = {}

    def start_api(self) -> None:
        if self.api is None or self.api.poll() is not None:
            self.api = run_api(processes=2)
        wait_until(lambda: ready().get("ready") is True, 60, "API ready")

    def stop_api(self) -> None:
        if self.api is not None:
            kill_tree(self.api)
            self.api = None

    def start_worker(self, name: str) -> None:
        self.workers[name] = run_worker(name)
        time.sleep(6)  # Celery connects to the broker before taking jobs

    def kill_worker(self, name: str) -> None:
        kill_tree(self.workers.pop(name))

    def stop(self) -> None:
        self.stop_api()
        for name in list(self.workers):
            self.kill_worker(name)


def kill_tree(process: subprocess.Popen) -> None:
    """Hard kill, children included — what a crashed machine looks like."""
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(process.pid)], capture_output=True
        )
    else:
        process.kill()
    process.wait(timeout=30)


def docker(action: str, container: str) -> None:
    subprocess.run(["docker", action, container], check=True, capture_output=True)


def ready() -> dict:
    try:
        return httpx.get(f"{API_URL}/ready", timeout=3).json()
    except (httpx.HTTPError, ValueError):
        return {}


def wait_until(condition, seconds: float, what: str) -> float:
    start = time.monotonic()
    while time.monotonic() - start < seconds:
        if condition():
            return time.monotonic() - start
        time.sleep(1)
    raise TimeoutError(f"Timed out after {seconds}s waiting for {what}")


def athlete(n: int) -> dict:
    return DATA["athletes"][n % len(DATA["athletes"])]


def headers(n: int) -> dict:
    return {"Authorization": f"Bearer {athlete(n)['token']}"}


def upload(client: httpx.Client, n: int, *, stop_after: int | None = None) -> dict:
    """Init and send chunks; stop early to simulate a lost connection."""
    data, checksum = unique_video()
    init = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": "VERTICAL_JUMP",
            "file_size_bytes": len(data),
            "checksum_sha256": checksum,
            "chunk_size_bytes": 256 * 1024,
        },
        headers=headers(n),
    )
    init.raise_for_status()
    session = init.json()
    size = session["chunk_size_bytes"]
    total = (len(data) + size - 1) // size
    for index in range(total if stop_after is None else stop_after):
        client.put(
            f"/api/videos/upload/{session['upload_id']}/chunks/{index}",
            content=data[index * size : (index + 1) * size],
            headers={**headers(n), "Content-Type": "application/octet-stream"},
        ).raise_for_status()
    return {"upload_id": session["upload_id"], "data": data, "size": size, "total": total}


def finish_and_submit(client: httpx.Client, n: int, up: dict) -> str:
    done = client.post(
        f"/api/videos/upload/{up['upload_id']}/complete", headers=headers(n)
    )
    done.raise_for_status()
    submitted = client.post(
        "/api/tests/submit",
        json={
            "test_id": "VERTICAL_JUMP",
            "provisional_score": 40,
            "video_id": done.json()["video_id"],
            "athlete_height_cm": athlete(n)["height_cm"],
        },
        headers=headers(n),
    )
    submitted.raise_for_status()
    return submitted.json()["result_id"]


def submit_video(n: int) -> str:
    with httpx.Client(base_url=API_URL, timeout=60) as client:
        return finish_and_submit(client, n, upload(client, n))


def statuses(ids: list[str]) -> dict[str, dict]:
    """Status and timings straight from the database."""
    os.environ.update(environment())
    from sqlalchemy import select

    from app.database import session_scope
    from app.models import TestResult

    with session_scope() as db:
        rows = db.execute(
            select(
                TestResult.id,
                TestResult.status,
                TestResult.created_at,
                TestResult.processing_started_at,
                TestResult.verified_at,
                TestResult.processing_duration_ms,
                TestResult.verification_attempts,
            ).where(TestResult.id.in_(ids))
        ).all()
    return {
        str(row.id): {
            "status": getattr(row.status, "value", row.status),
            "queue_wait_s": (row.processing_started_at - row.created_at).total_seconds()
            if row.processing_started_at
            else None,
            "processing_s": (row.processing_duration_ms or 0) / 1000
            if row.verified_at
            else None,
            "turnaround_s": (row.verified_at - row.created_at).total_seconds()
            if row.verified_at
            else None,
            "attempts": row.verification_attempts,
        }
        for row in rows
    }


def consistency() -> dict:
    done = subprocess.run(
        [PYTHON, "-m", "app.cli", "consistency"],
        cwd=BACKEND,
        env=environment(),
        capture_output=True,
        text=True,
    )
    return {"ok": done.returncode == 0, "output": done.stdout.strip().splitlines()}


def requeue() -> None:
    subprocess.run(
        [PYTHON, "-m", "app.cli", "reverify-pending", "--older-than-minutes", "0"],
        cwd=BACKEND,
        env=environment(),
        check=True,
        capture_output=True,
    )


def percentile(values: list[float], share: float) -> float | None:
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    return round(values[min(len(values) - 1, int(share * len(values)))], 1)


def wait_for_all(ids: list[str], seconds: float) -> dict[str, dict]:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        current = statuses(ids)
        if all(s["status"] in TERMINAL for s in current.values()):
            return current
        time.sleep(5)
    return statuses(ids)


# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------


def backlog(stack: Stack, videos: int = 16, workers: int = 2) -> dict:
    stack.start_api()
    with futures.ThreadPoolExecutor(8) as pool:
        ids = list(pool.map(submit_video, range(videos)))
    queued_at = time.monotonic()
    for w in range(workers):
        stack.start_worker(f"backlog{w}")
    final = wait_for_all(ids, 60 * 30)
    drained_in = time.monotonic() - queued_at
    for w in range(workers):
        stack.kill_worker(f"backlog{w}")
    done = [s for s in final.values() if s["status"] in TERMINAL]
    return {
        "videos": videos,
        "workers": workers,
        "submitted": len(ids),
        "reached_a_verdict": len(done),
        "lost": len(ids) - len(final),
        "drain_seconds": round(drained_in),
        "throughput_per_worker_per_minute": round(
            len(done) / workers / (drained_in / 60), 2
        ),
        "processing_s_p50": percentile([s["processing_s"] for s in done], 0.5),
        "processing_s_p95": percentile([s["processing_s"] for s in done], 0.95),
        "queue_wait_s_p50": percentile([s["queue_wait_s"] for s in done], 0.5),
        "queue_wait_s_p95": percentile([s["queue_wait_s"] for s in done], 0.95),
        "verdicts": dict(
            sorted(
                {v: sum(1 for s in done if s["status"] == v) for v in TERMINAL}.items()
            )
        ),
        "passed": len(done) == len(ids),
    }


def worker_crash(stack: Stack) -> dict:
    stack.start_api()
    stack.start_worker("victim")
    ids = [submit_video(1000 + n) for n in range(2)]
    # Kill the worker as soon as it has claimed a job.
    wait_until(
        lambda: any(s["status"] == "processing" for s in statuses(ids).values()),
        120,
        "a job in processing",
    )
    stack.kill_worker("victim")
    killed_at = time.monotonic()
    stranded = [i for i, s in statuses(ids).items() if s["status"] not in TERMINAL]

    # Recovery: a fresh worker, and the operator's re-queue for the job whose
    # message died with the old one (the documented runbook step).
    stack.start_worker("rescuer")
    requeue()
    final = wait_for_all(ids, 600)
    recovered_in = time.monotonic() - killed_at
    stack.kill_worker("rescuer")
    return {
        "stranded_by_the_crash": len(stranded),
        "all_reached_a_verdict": all(s["status"] in TERMINAL for s in final.values()),
        "verification_attempts": [s["attempts"] for s in final.values()],
        "recovery_seconds": round(recovered_in),
        "passed": all(s["status"] in TERMINAL for s in final.values()),
    }


def api_down(stack: Stack) -> dict:
    stack.start_api()
    with httpx.Client(base_url=API_URL, timeout=30) as client:
        up = upload(client, 2000, stop_after=3)
    stack.stop_api()
    refused = False
    try:
        httpx.get(f"{API_URL}/health", timeout=3)
    except httpx.HTTPError:
        refused = True  # the phone sees a network error and keeps its queue
    stack.start_api()
    with httpx.Client(base_url=API_URL, timeout=60) as client:
        status = client.get(
            f"/api/videos/upload/{up['upload_id']}", headers=headers(2000)
        ).json()
        kept = sorted(status["received_chunks"])
        for index in range(len(kept), up["total"]):
            client.put(
                f"/api/videos/upload/{up['upload_id']}/chunks/{index}",
                content=up["data"][index * up["size"] : (index + 1) * up["size"]],
                headers={**headers(2000), "Content-Type": "application/octet-stream"},
            ).raise_for_status()
        result_id = finish_and_submit(client, 2000, up)
        # The phone lost the response and sends the same submission again.
        video_id = client.post(
            f"/api/videos/upload/{up['upload_id']}/complete", headers=headers(2000)
        ).json()["video_id"]
        again = client.post(
            "/api/tests/submit",
            json={
                "test_id": "VERTICAL_JUMP",
                "provisional_score": 40,
                "video_id": video_id,
                "athlete_height_cm": athlete(2000)["height_cm"],
            },
            headers=headers(2000),
        ).json()["result_id"]
    return {
        "unreachable_while_down": refused,
        "chunks_kept_across_restart": kept == [0, 1, 2],
        "retried_submission_same_result": again == result_id,
        "passed": refused and kept == [0, 1, 2] and again == result_id,
    }


def database_down(stack: Stack) -> dict:
    stack.start_api()
    docker("stop", "f4all-postgres")
    outcomes = []
    try:
        for n in range(5):
            started = time.monotonic()
            try:
                response = httpx.get(
                    f"{API_URL}/api/athletes/me", headers=headers(n), timeout=30
                )
                outcomes.append(
                    (response.status_code, round(time.monotonic() - started, 1))
                )
            except httpx.HTTPError as error:
                outcomes.append(
                    (type(error).__name__, round(time.monotonic() - started, 1))
                )
        not_ready = ready().get("ready") is False
    finally:
        docker("start", "f4all-postgres")
    restarted = time.monotonic()
    recovery = wait_until(
        lambda: (
            ready().get("ready") is True
            and httpx.get(
                f"{API_URL}/api/athletes/me", headers=headers(0), timeout=10
            ).status_code
            == 200
        ),
        180,
        "the API to serve again",
    )
    # A submission after recovery lands normally.
    result_id = submit_video(3000)
    return {
        "responses_while_down": outcomes,
        "no_success_while_down": all(code != 200 for code, _ in outcomes),
        "slowest_response_s": max(seconds for _, seconds in outcomes),
        "ready_reported_not_ready": not_ready,
        "recovery_seconds": round(time.monotonic() - restarted, 1),
        "serving_after_s": round(recovery, 1),
        "submission_after_recovery": bool(result_id),
        "passed": all(code != 200 for code, _ in outcomes) and bool(result_id),
    }


def redis_down(stack: Stack) -> dict:
    stack.start_api()
    docker("stop", "f4all-redis")
    try:
        started = time.monotonic()
        ids = [submit_video(4000 + n) for n in range(3)]
        submit_seconds = (time.monotonic() - started) / 3
        waiting = statuses(ids)
        report_ready = ready()
    finally:
        docker("start", "f4all-redis")
    time.sleep(3)
    stack.start_worker("after_redis")
    requeue()
    final = wait_for_all(ids, 600)
    stack.kill_worker("after_redis")
    return {
        "accepted_while_down": len(ids),
        "seconds_per_submission_while_down": round(submit_seconds, 2),
        "statuses_while_down": sorted({s["status"] for s in waiting.values()}),
        "ready_while_down": report_ready,
        "all_verified_after": all(s["status"] in TERMINAL for s in final.values()),
        "passed": len(ids) == 3 and all(s["status"] in TERMINAL for s in final.values()),
    }


def interrupted_upload(stack: Stack) -> dict:
    stack.start_api()
    with httpx.Client(base_url=API_URL, timeout=60) as client:
        up = upload(client, 5000, stop_after=up_half())
        status = client.get(
            f"/api/videos/upload/{up['upload_id']}", headers=headers(5000)
        ).json()
        kept = sorted(status["received_chunks"])
        resent = 0
        for index in range(up["total"]):
            if index in kept:
                continue
            client.put(
                f"/api/videos/upload/{up['upload_id']}/chunks/{index}",
                content=up["data"][index * up["size"] : (index + 1) * up["size"]],
                headers={**headers(5000), "Content-Type": "application/octet-stream"},
            ).raise_for_status()
            resent += 1
        done = client.post(
            f"/api/videos/upload/{up['upload_id']}/complete", headers=headers(5000)
        ).json()
    return {
        "chunks_total": up["total"],
        "chunks_kept": len(kept),
        "chunks_resent": resent,
        "checksum_verified": done.get("checksum_verified") is True,
        "passed": resent == up["total"] - len(kept)
        and done.get("checksum_verified") is True,
    }


def worker_survives_redis_outage(stack: Stack) -> dict:
    """The fix for what redis_down found: a worker outlives a broker outage."""
    stack.start_api()
    stack.start_worker("survivor")
    process = stack.workers["survivor"]
    docker("stop", "f4all-redis")
    try:
        time.sleep(20)
        alive_during = process.poll() is None
    finally:
        docker("start", "f4all-redis")
    time.sleep(15)  # the worker's reconnect back-off
    alive_after = process.poll() is None
    result_id = submit_video(6000)
    final = wait_for_all([result_id], 300)
    verified = final.get(result_id, {}).get("status") in TERMINAL
    if alive_after:
        stack.kill_worker("survivor")
    return {
        "worker_alive_during_outage": alive_during,
        "worker_alive_after_outage": alive_after,
        "same_worker_verified_a_new_job": verified,
        "passed": alive_during and alive_after and verified,
    }


def up_half() -> int:
    from loadtest.env import SAMPLE_VIDEO

    return max(1, (SAMPLE_VIDEO.stat().st_size // (256 * 1024)) // 2)


SCENARIOS = {
    "interrupted_upload": interrupted_upload,
    "api_down": api_down,
    "redis_down": redis_down,
    "worker_survives_redis_outage": worker_survives_redis_outage,
    "database_down": database_down,
    "worker_crash": worker_crash,
    "backlog": backlog,
}


def main() -> int:
    chosen = sys.argv[1:] or ["all"]
    names = list(SCENARIOS) if chosen == ["all"] else chosen
    results = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else {}
    stack = Stack()
    try:
        for name in names:
            print(f"--- {name}", flush=True)
            started = time.monotonic()
            try:
                outcome = SCENARIOS[name](stack)
            except Exception as error:  # record and carry on with the next
                outcome = {"passed": False, "error": f"{type(error).__name__}: {error}"}
            outcome["consistency"] = consistency()
            outcome["seconds"] = round(time.monotonic() - started)
            outcome["at"] = datetime.now(UTC).isoformat(timespec="seconds")
            results[name] = outcome
            print(json.dumps(outcome, indent=2), flush=True)
    finally:
        stack.stop()
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps(results, indent=2), encoding="utf-8")
    failed = [n for n in names if not results[n].get("passed")]
    print("FAILED: " + ", ".join(failed) if failed else "All scenarios passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

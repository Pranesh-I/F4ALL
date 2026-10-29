"""Many athletes using the API at once (Sprint 23, scenario A).

    python -m loadtest.env setup --athletes 200
    python -m loadtest.env api            # in another terminal
    locust -f loadtest/locustfile.py --headless -u 100 -r 10 -t 3m \\
        --host http://127.0.0.1:8020 --csv var/load/run

Each simulated athlete does what the app does on a trials day, in the app's
proportions: open the app (profile, the open session, history), and every so
often record a test — chunked video upload, then the submission. Requests are
grouped by route in the results, not by id.
"""

from __future__ import annotations

import json
import random

from locust import HttpUser, between, events, task

from loadtest.env import ATHLETES_FILE, unique_video

DATA = json.loads(ATHLETES_FILE.read_text(encoding="utf-8"))
CHUNK = 256 * 1024
UPLOADS = {"completed": 0, "failed": 0}


class Athlete(HttpUser):
    wait_time = between(1, 4)

    def on_start(self) -> None:
        athlete = random.choice(DATA["athletes"])
        self.height = athlete["height_cm"]
        self.client.headers["Authorization"] = f"Bearer {athlete['token']}"

    @task(4)
    def open_app(self) -> None:
        self.client.get("/api/athletes/me")
        self.client.get("/api/sessions/active")
        self.client.get("/api/athletes/me/notifications")

    @task(2)
    def look_at_history(self) -> None:
        self.client.get("/api/athletes/me/history")
        self.client.get("/api/athletes/me/personal-bests")

    @task(1)
    def leaderboard(self) -> None:
        self.client.get(
            "/api/athletes/leaderboard/SIT_UPS", name="/api/athletes/leaderboard/[test]"
        )

    @task(1)
    def record_and_send(self) -> None:
        data, checksum = unique_video()
        init = self.client.post(
            "/api/videos/upload/init",
            json={
                "test_type": "VERTICAL_JUMP",
                "file_size_bytes": len(data),
                "checksum_sha256": checksum,
                "chunk_size_bytes": CHUNK,
            },
        )
        if init.status_code != 200:
            UPLOADS["failed"] += 1
            return
        session = init.json()
        size = session["chunk_size_bytes"]
        for index in range(0, (len(data) + size - 1) // size):
            chunk = self.client.put(
                f"/api/videos/upload/{session['upload_id']}/chunks/{index}",
                data=data[index * size : (index + 1) * size],
                headers={"Content-Type": "application/octet-stream"},
                name="/api/videos/upload/[id]/chunks/[i]",
            )
            if chunk.status_code != 200:
                UPLOADS["failed"] += 1
                return
        done = self.client.post(
            f"/api/videos/upload/{session['upload_id']}/complete",
            name="/api/videos/upload/[id]/complete",
        )
        if done.status_code != 200:
            UPLOADS["failed"] += 1
            return
        submitted = self.client.post(
            "/api/tests/submit",
            json={
                "test_id": "VERTICAL_JUMP",
                "provisional_score": 40,
                "video_id": done.json()["video_id"],
                "athlete_height_cm": self.height,
            },
        )
        if submitted.ok:
            UPLOADS["completed"] += 1
        else:
            UPLOADS["failed"] += 1


@events.quitting.add_listener
def report_uploads(environment, **_kwargs) -> None:
    total = UPLOADS["completed"] + UPLOADS["failed"]
    rate = UPLOADS["completed"] / total if total else 0
    print(
        f"\nUploads: {UPLOADS['completed']} completed, {UPLOADS['failed']} failed ({rate:.1%})"
    )

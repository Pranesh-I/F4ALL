"""The whole pilot journey against the live stack, step by step (Sprint 24).

    python -m loadtest.env setup
    python -m loadtest.rehearsal

The plan's chain, run against a real API, a real verification worker and the
notifier — nothing stubbed — with every step timed and checked:

    OTP → registration (guardian consent, a minor) → profile → face photo →
    practice → session (created by an admin) → identity check → upload →
    submission → server verification → admin review and approval → the
    athlete sees the decision → notification → audit trail → consistency

Gesture, countdown and the exercise itself happen on the phone; they are
covered by the app's own tests and by the pilot with real athletes. The
rehearsal proves everything around them is wired together. Results go to
``docs/pilot/rehearsal.json``.
"""

from __future__ import annotations

import io
import json
import os
import secrets
import subprocess
import sys
import time
import uuid
from datetime import UTC, datetime, timedelta

import httpx

from loadtest.env import API_URL, BACKEND, PYTHON, REPO, SAMPLE_VIDEO, environment
from loadtest.reliability import TERMINAL, Stack, statuses, unique_video

OUT = REPO / "docs" / "pilot" / "rehearsal.json"
ADMIN_EMAIL = "rehearsal.admin@sai.example"
# Made fresh each run: the account lives only in the disposable load database.
ADMIN_PASSWORD = "Rh-" + secrets.token_urlsafe(18)


class Journey:
    def __init__(self) -> None:
        self.steps: list[dict] = []
        self.client = httpx.Client(base_url=API_URL, timeout=60)
        self.athlete: dict = {}
        self.admin: dict = {}
        self.notes: dict = {}

    def step(self, name: str, action) -> object:
        started = time.monotonic()
        try:
            value = action()
            self.steps.append(
                {
                    "step": name,
                    "ok": True,
                    "ms": round((time.monotonic() - started) * 1000),
                }
            )
            print(f"  ok   {name}", flush=True)
            return value
        except Exception as error:
            self.steps.append(
                {
                    "step": name,
                    "ok": False,
                    "ms": round((time.monotonic() - started) * 1000),
                    "error": f"{type(error).__name__}: {error}"[:300],
                }
            )
            print(f"  FAIL {name}: {error}", flush=True)
            raise

    def as_athlete(self) -> dict:
        return {"Authorization": f"Bearer {self.athlete['access_token']}"}

    def as_admin(self) -> dict:
        return {"Authorization": f"Bearer {self.admin['access_token']}"}


def check(response: httpx.Response, *codes: int) -> dict:
    if response.status_code not in (codes or (200,)):
        raise AssertionError(f"{response.status_code}: {response.text[:200]}")
    return response.json() if response.content else {}


def face_photo() -> bytes:
    """A frame of the sample clip, as a JPEG, standing in for a selfie."""
    import cv2

    capture = cv2.VideoCapture(str(SAMPLE_VIDEO))
    capture.set(cv2.CAP_PROP_POS_FRAMES, 30)
    ok, frame = capture.read()
    if not ok:
        raise RuntimeError("could not read a frame from the sample video")
    return cv2.imencode(".jpg", frame)[1].tobytes()


def provision_admin() -> None:
    subprocess.run(
        [
            PYTHON,
            "-m",
            "app.cli",
            "create-official",
            "--email",
            ADMIN_EMAIL,
            "--name",
            "Rehearsal Admin",
            "--role",
            "sai_admin",
        ],
        cwd=BACKEND,
        env={**environment(), "F4ALL_OFFICIAL_PASSWORD": ADMIN_PASSWORD},
        check=True,
        capture_output=True,
    )


def run(j: Journey) -> None:
    c = j.client
    phone = f"8{uuid.uuid4().int % 10**9:09d}"

    # --- Athlete: sign-in and registration -----------------------------------
    code = j.step(
        "OTP requested",
        lambda: check(c.post("/api/auth/request-otp", json={"phone": phone}))[
            "development_code"
        ],
    )
    registering = j.step(
        "OTP verified",
        lambda: check(c.post("/api/auth/verify-otp", json={"phone": phone, "otp": code})),
    )
    registered = j.step(
        "Registered (a minor, guardian consent)",
        lambda: check(
            c.post(
                "/api/athletes/register",
                json={
                    "name": "Rehearsal Athlete",
                    "dob": "2011-05-10",
                    "gender": "female",
                    "region": "Tamil Nadu",
                    "city": "Chennai",
                    "height_cm": 152,
                    "consent": {
                        "version": "2026-10",
                        "given_by": "guardian",
                        "guardian_name": "Rehearsal Guardian",
                    },
                },
                headers={"Authorization": f"Bearer {registering['access_token']}"},
            ),
            200,
            201,
        ),
    )
    j.athlete = registered["tokens"]
    j.step(
        "Profile read", lambda: check(c.get("/api/athletes/me", headers=j.as_athlete()))
    )
    j.step(
        "Profile updated",
        lambda: check(
            c.patch("/api/athletes/me", json={"weight_kg": 41}, headers=j.as_athlete())
        ),
    )

    # --- Face verification -------------------------------------------------------
    j.step(
        "Face-verification consent given",
        lambda: check(
            c.post(
                "/api/athletes/me/consents",
                json={
                    "purpose": "face_verification",
                    "version": "2026-10",
                    "given_by": "guardian",
                    "guardian_name": "Rehearsal Guardian",
                },
                headers=j.as_athlete(),
            ),
            200,
            201,
        ),
    )
    photo = face_photo()
    j.step(
        "Registration photo stored (encrypted)",
        lambda: check(
            c.post(
                "/api/athletes/me/photo",
                files={"file": ("me.jpg", io.BytesIO(photo), "image/jpeg")},
                headers=j.as_athlete(),
            )
        ),
    )

    # --- Practice ----------------------------------------------------------------
    j.step(
        "Practice attempt saved to the account",
        lambda: check(
            c.put(
                f"/api/athletes/me/practice/{uuid.uuid4()}",
                json={
                    "test_type": "SIT_UPS",
                    "score": 14,
                    "unit": "reps",
                    "status": "COMPLETE",
                    "confidence": 0.9,
                    "recorded_at_ms": int(time.time() * 1000),
                    "events": [],
                },
                headers=j.as_athlete(),
            ),
            200,
            201,
        ),
    )

    # --- Session, created by an admin ---------------------------------------------
    j.step("Admin account provisioned", provision_admin)
    j.admin = j.step(
        "Admin signed in",
        lambda: check(
            c.post(
                "/api/dashboard/auth/login",
                json={"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD},
            )
        ),
    )
    now = datetime.now(UTC)
    session = j.step(
        "Session created",
        lambda: check(
            c.post(
                "/api/dashboard/sessions",
                json={
                    "name": "Pilot rehearsal trials",
                    "description": "Rehearsal",
                    "starts_at": (now - timedelta(minutes=5)).isoformat(),
                    "ends_at": (now + timedelta(hours=3)).isoformat(),
                    "enabled": True,
                    "allowed_tests": ["VERTICAL_JUMP"],
                },
                headers=j.as_admin(),
            ),
            201,
        ),
    )
    session_id = session["id"]
    j.step(
        "Athlete sees the session",
        lambda: next(
            s
            for s in check(c.get("/api/sessions/active", headers=j.as_athlete()))[
                "sessions"
            ]
            if s["session_id"] == session_id
        ),
    )

    # --- Identity check, recording, upload ----------------------------------------
    identity = j.step(
        "Pre-test identity check recorded",
        lambda: check(
            c.post(
                "/api/athletes/me/identity-checks",
                files={"file": ("check.jpg", io.BytesIO(photo), "image/jpeg")},
                data={"session_id": session_id},
                headers=j.as_athlete(),
            ),
            200,
            201,
        ),
    )
    data, checksum = unique_video()

    def send_video() -> str:
        init = check(
            c.post(
                "/api/videos/upload/init",
                json={
                    "test_type": "VERTICAL_JUMP",
                    "file_size_bytes": len(data),
                    "checksum_sha256": checksum,
                },
                headers=j.as_athlete(),
            )
        )
        size = init["chunk_size_bytes"]
        for index in range((len(data) + size - 1) // size):
            check(
                c.put(
                    f"/api/videos/upload/{init['upload_id']}/chunks/{index}",
                    content=data[index * size : (index + 1) * size],
                    headers={
                        **j.as_athlete(),
                        "Content-Type": "application/octet-stream",
                    },
                )
            )
        done = check(
            c.post(
                f"/api/videos/upload/{init['upload_id']}/complete", headers=j.as_athlete()
            )
        )
        assert done["checksum_verified"] is True
        return done["video_id"]

    video_id = j.step("Video uploaded in chunks, checksum verified", send_video)
    result_id = j.step(
        "Official result submitted to the session",
        lambda: check(
            c.post(
                "/api/tests/submit",
                json={
                    "test_id": "VERTICAL_JUMP",
                    "provisional_score": 38,
                    "video_id": video_id,
                    "session_id": session_id,
                    "identity_check_id": identity["check_id"],
                    "recorded_at_ms": int(time.time() * 1000) - 60_000,
                    "athlete_height_cm": 152,
                },
                headers=j.as_athlete(),
            ),
            200,
            201,
        )["result_id"],
    )

    # --- Server verification ------------------------------------------------------
    def verified() -> str:
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            status = statuses([result_id])[result_id]["status"]
            if status in TERMINAL:
                return status
            time.sleep(3)
        raise TimeoutError("no verdict within 10 minutes")

    j.step("Server verification reached a verdict", verified)

    # --- Admin review ------------------------------------------------------------
    j.step(
        "Submission in the admin queue",
        lambda: next(
            row
            for row in check(c.get("/api/dashboard/submissions", headers=j.as_admin()))
            if row["result_id"] == result_id
        ),
    )
    review = j.step(
        "Review opened: video link, flags, scores",
        lambda: check(c.get(f"/api/dashboard/reviews/{result_id}", headers=j.as_admin())),
    )
    j.notes["server_score"] = review["server_score"]
    j.notes["machine_verdict"] = review.get("verification_verdict")
    j.notes["flags"] = sorted({f["reason"] for f in review["flags"]})
    j.step(
        "Video playable through its signed link",
        lambda: check_bytes(c.get(review["video_url"].replace(API_URL, ""))),
    )
    j.step(
        "Approved by the admin",
        lambda: check(
            c.post(
                f"/api/dashboard/reviews/{result_id}/action",
                json={
                    "action": "approved",
                    "notes": "Rehearsal approval",
                    # A video the server could not score is approved only with
                    # the score the reviewer saw, recorded as theirs.
                    **(
                        {"final_score": 38, "notes": "Scored by eye from the recording"}
                        if review["server_score"] is None
                        else {}
                    ),
                    # The version the reviewer looked at: a decision made
                    # meanwhile by someone else is refused, not overwritten.
                    "expected_version": review["review_version"],
                },
                headers=j.as_admin(),
            )
        ),
    )

    # --- The athlete sees it, and is told ------------------------------------------
    def athlete_sees_decision() -> None:
        result = check(c.get(f"/api/results/{result_id}", headers=j.as_athlete()))
        assert result["status"] == "approved", result["status"]
        assert result["latest_review"]["action"] == "approved"
        assert result["flags"] == []  # never the integrity flags

    j.step("Athlete sees the approval", athlete_sees_decision)

    def notified() -> None:
        subprocess.run(
            [PYTHON, "-m", "app.notifications.dispatch", "--once"],
            cwd=BACKEND,
            env=environment(),
            check=True,
            capture_output=True,
        )
        inbox = check(c.get("/api/athletes/me/notifications", headers=j.as_athlete()))
        assert inbox["items"], "no notification in the inbox"

    j.step("Athlete notified", notified)

    # --- Audit -------------------------------------------------------------------
    def audited() -> None:
        history = check(
            c.get(f"/api/dashboard/reviews/{result_id}", headers=j.as_admin())
        )["review_history"]
        assert any(h.get("action") == "approved" for h in history), history
        log = check(
            c.get(
                "/api/dashboard/access-log",
                params={"path_contains": result_id},
                headers=j.as_admin(),
            )
        )
        assert log["total"] >= 2, log  # the review read and the decision

    j.step("Review history and access log record it", audited)

    def consistent() -> None:
        done = subprocess.run(
            [PYTHON, "-m", "app.cli", "consistency"],
            cwd=BACKEND,
            env=environment(),
            capture_output=True,
            text=True,
        )
        assert done.returncode == 0, done.stdout

    j.step("Consistency check clean", consistent)


def check_bytes(response: httpx.Response) -> int:
    if response.status_code != 200 or len(response.content) < 1000:
        raise AssertionError(f"{response.status_code}, {len(response.content)} bytes")
    return len(response.content)


def main() -> int:
    stack = Stack()
    journey = Journey()
    started = time.monotonic()
    try:
        stack.start_api()
        stack.start_worker("rehearsal")
        run(journey)
    except Exception:
        pass  # already recorded as the failed step
    finally:
        stack.stop()
    report = {
        "at": datetime.now(UTC).isoformat(timespec="seconds"),
        "seconds": round(time.monotonic() - started),
        "passed": all(s["ok"] for s in journey.steps) and len(journey.steps) >= 20,
        "steps": journey.steps,
        "observed": journey.notes,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"{'PASSED' if report['passed'] else 'FAILED'}: {OUT}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    os.environ.update(environment())
    sys.exit(main())

"""Practice history follows the athlete's account, and nobody else's.

Three properties:

* an athlete who signs in on a new phone gets their practice back;
* no athlete can read, or overwrite, another athlete's practice;
* practice never turns into anything official — not a result, not a review
  item, not a personal best on the official record.
"""

from __future__ import annotations

import time
import uuid
from datetime import date

from app.models import Athlete, PracticeAttempt
from app.security import create_access_token

NOW_MS = int(time.time() * 1000)


def auth(athlete, settings):
    return {"Authorization": f"Bearer {create_access_token(str(athlete.id), settings)}"}


def other_athlete(db) -> Athlete:
    record = Athlete(
        id=uuid.uuid4(),
        name="Friend",
        dob=date(2007, 3, 1),
        gender="male",
        region="Tamil Nadu",
        phone="8888888888",
    )
    db.add(record)
    db.commit()
    return record


def attempt(
    score=12.0, *, recorded_at_ms=NOW_MS, test_type="SQUATS", unit="reps", **extra
):
    body = {
        "test_type": test_type,
        "score": score,
        "unit": unit,
        "status": "COMPLETE",
        "confidence": 0.9,
        "recorded_at_ms": recorded_at_ms,
        "events": [
            {"timestamp_ms": 1000, "label": "rep_counted", "detail": "Rep 1 in 900ms"},
            {"timestamp_ms": 1000, "label": "form_warning", "detail": "torso_lean"},
        ],
    }
    body.update(extra)
    return body


def put(client, athlete, settings, client_id, body):
    return client.put(
        f"/api/athletes/me/practice/{client_id}",
        json=body,
        headers=auth(athlete, settings),
    )


def history(client, athlete, settings, **params):
    response = client.get(
        "/api/athletes/me/practice", params=params, headers=auth(athlete, settings)
    )
    assert response.status_code == 200, response.text
    return response.json()["attempts"]


def test_practice_saved_on_one_phone_comes_back_on_another(client, athlete, settings):
    response = put(client, athlete, settings, "test_1", attempt(14.0))
    assert response.status_code == 200, response.text

    # "Another phone" is just the same account asking again.
    items = history(client, athlete, settings)

    assert len(items) == 1
    item = items[0]
    assert item["client_attempt_id"] == "test_1"
    assert item["test_type"] == "SQUATS"
    assert item["score"] == 14.0
    assert item["recorded_at_ms"] == NOW_MS
    # The trace comes back whole: the phone rebuilds form feedback from it.
    assert [event["label"] for event in item["events"]] == ["rep_counted", "form_warning"]


def test_retrying_an_upload_does_not_duplicate_it(client, athlete, settings, db):
    put(client, athlete, settings, "test_1", attempt(10.0))
    put(client, athlete, settings, "test_1", attempt(11.0))

    items = history(client, athlete, settings)

    assert len(items) == 1
    assert items[0]["score"] == 11.0
    assert db.query(PracticeAttempt).count() == 1


def test_an_athlete_never_sees_a_friends_practice(client, athlete, settings, db):
    friend = other_athlete(db)
    put(client, athlete, settings, "test_1", attempt(20.0))

    assert history(client, friend, settings) == []


def test_the_same_phone_id_under_another_account_is_a_separate_attempt(
    client, athlete, settings, db
):
    """Two athletes sharing a phone can produce the same attempt id; neither
    can overwrite the other's."""
    friend = other_athlete(db)
    put(client, athlete, settings, "test_1", attempt(20.0))
    put(client, friend, settings, "test_1", attempt(3.0))

    assert history(client, athlete, settings)[0]["score"] == 20.0
    assert history(client, friend, settings)[0]["score"] == 3.0


def test_practice_needs_a_signed_in_athlete(client):
    assert client.get("/api/athletes/me/practice").status_code == 401
    assert (
        client.put("/api/athletes/me/practice/test_1", json=attempt()).status_code == 401
    )


def test_history_is_newest_first_and_pages_backwards(client, athlete, settings):
    for index in range(5):
        put(
            client,
            athlete,
            settings,
            f"test_{index}",
            attempt(recorded_at_ms=NOW_MS - index * 60_000),
        )

    first_page = history(client, athlete, settings, limit=2)
    assert [item["client_attempt_id"] for item in first_page] == ["test_0", "test_1"]

    next_page = history(
        client, athlete, settings, limit=2, before_ms=first_page[-1]["recorded_at_ms"]
    )
    assert [item["client_attempt_id"] for item in next_page] == ["test_2", "test_3"]


def test_nonsense_is_refused(client, athlete, settings):
    cases = [
        attempt(test_type="MARATHON"),
        attempt(test_type="VERTICAL_JUMP", unit="reps"),
        attempt(status="MAYBE"),
        attempt(score=-1),
        attempt(recorded_at_ms=NOW_MS + 7 * 86_400_000),
        attempt(events=[{"timestamp_ms": 0, "label": "x" * 100}]),
    ]
    for body in cases:
        response = put(client, athlete, settings, "test_bad", body)
        assert response.status_code == 422, (body, response.text)

    bad_id = client.put(
        "/api/athletes/me/practice/../../etc",
        json=attempt(),
        headers=auth(athlete, settings),
    )
    assert bad_id.status_code in (404, 422)

    assert history(client, athlete, settings) == []


def test_an_unscored_attempt_is_kept_too(client, athlete, settings):
    body = attempt(0, status="INVALID", invalid_reason="No usable pose data", events=[])
    assert put(client, athlete, settings, "test_1", body).status_code == 200

    item = history(client, athlete, settings)[0]
    assert item["status"] == "INVALID"
    assert item["invalid_reason"] == "No usable pose data"


def test_practice_never_reaches_the_official_record(
    client, athlete, settings, seeded_tests
):
    put(client, athlete, settings, "test_1", attempt(99.0, test_type="SIT_UPS"))

    summary = client.get("/api/athletes/me/summary", headers=auth(athlete, settings))
    assert summary.status_code == 200, summary.text
    body = summary.json()
    assert body["history"] == []
    assert body["personal_bests"] == [] or all(
        best.get("score") != 99.0 for best in body["personal_bests"]
    )

    # And nothing an official reads mentions the table at all.
    from pathlib import Path

    routers = Path(__file__).resolve().parents[1] / "app" / "routers"
    for name in ("dashboard.py", "tests_submit.py", "athletes.py"):
        assert "PracticeAttempt" not in (routers / name).read_text(encoding="utf-8"), name

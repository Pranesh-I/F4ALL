"""Assessment sessions, end to end.

The Sprint 7 Definition of Done, as tests: an admin creates a session, the
athlete sees it, submits a test, and a second submission is refused. Around
that, the rules that make a session mean something — who sees it, what may be
submitted to it and when — and who may change it.
"""

from __future__ import annotations

import hashlib
import os
import uuid
from datetime import UTC, date, datetime, timedelta

import pytest

from app.models import (
    AssessmentSession,
    Athlete,
    Official,
    OfficialRole,
    TestResult,
    TestResultStatus,
)
from app.security import create_access_token
from app.services import sessions as rules

NOW = datetime.now(UTC)
CHUNK = 1024


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def athlete_auth(athlete, settings):
    return {"Authorization": f"Bearer {create_access_token(str(athlete.id), settings)}"}


def official_auth(official, settings):
    token = create_access_token(str(official.id), settings, role=official.role.value)
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def admin(db) -> Official:
    record = Official(
        id=uuid.uuid4(),
        name="SAI Admin",
        email="admin@sai.example",
        role=OfficialRole.sai_admin,
        region=None,
    )
    db.add(record)
    db.commit()
    return record


def make_athlete(db, *, region="Tamil Nadu", phone="7777777777") -> Athlete:
    record = Athlete(
        id=uuid.uuid4(),
        name="Other Athlete",
        dob=date(2008, 1, 1),
        gender="male",
        region=region,
        phone=phone,
    )
    db.add(record)
    db.commit()
    return record


def create_session(client, admin, settings, **overrides):
    body = {
        "name": "District trials, October",
        "description": "Open trials for the district squad",
        "rules": "Film outdoors if you can.",
        "starts_at": (NOW - timedelta(hours=1)).isoformat(),
        "ends_at": (NOW + timedelta(days=2)).isoformat(),
        "enabled": True,
        "allowed_tests": ["SIT_UPS"],
    }
    body.update(overrides)
    response = client.post(
        "/api/dashboard/sessions", json=body, headers=official_auth(admin, settings)
    )
    assert response.status_code == 201, response.text
    return response.json()


def upload(client, headers) -> str:
    video = os.urandom(2 * CHUNK)
    init = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": "SIT_UPS",
            "file_size_bytes": len(video),
            "checksum_sha256": hashlib.sha256(video).hexdigest(),
            "chunk_size_bytes": CHUNK,
        },
        headers=headers,
    ).json()
    for index in range(2):
        client.put(
            f"/api/videos/upload/{init['upload_id']}/chunks/{index}",
            content=video[index * CHUNK : (index + 1) * CHUNK],
            headers=headers,
        )
    return client.post(
        f"/api/videos/upload/{init['upload_id']}/complete", headers=headers
    ).json()["video_id"]


def submit(
    client, headers, session_id, *, test="SIT_UPS", recorded_at=None, video_id=None
):
    recorded = recorded_at or NOW - timedelta(minutes=5)
    return client.post(
        "/api/tests/submit",
        json={
            "test_id": test,
            "provisional_score": 20,
            "video_id": video_id or upload(client, headers),
            "session_id": session_id,
            "recorded_at_ms": int(recorded.timestamp() * 1000),
        },
        headers=headers,
    )


def active(client, athlete, settings):
    response = client.get("/api/sessions/active", headers=athlete_auth(athlete, settings))
    assert response.status_code == 200, response.text
    return response.json()["sessions"]


# ---------------------------------------------------------------------------
# The Definition of Done
# ---------------------------------------------------------------------------


def test_admin_creates_athlete_sees_submits_once_and_is_blocked(
    client, admin, athlete, settings, seeded_tests
):
    session = create_session(client, admin, settings)
    assert session["status"] == "active"

    visible = active(client, athlete, settings)
    assert [item["name"] for item in visible] == ["District trials, October"]
    assert visible[0]["tests"] == [
        {
            "test_type": "SIT_UPS",
            "unit": "reps",
            "submitted": False,
            "result_status": None,
        }
    ]

    headers = athlete_auth(athlete, settings)
    first = submit(client, headers, session["id"])
    assert first.status_code == 201, first.text

    after = active(client, athlete, settings)[0]["tests"][0]
    assert after["submitted"] is True
    assert after["result_status"] == "processing"

    second = submit(client, headers, session["id"])
    assert second.status_code == 409
    assert "already submitted" in second.json()["detail"]


def test_retrying_the_same_submission_returns_it_rather_than_refusing(
    client, admin, athlete, settings, seeded_tests
):
    session = create_session(client, admin, settings)
    headers = athlete_auth(athlete, settings)
    video_id = upload(client, headers)

    first = submit(client, headers, session["id"], video_id=video_id)
    retry = submit(client, headers, session["id"], video_id=video_id)

    assert retry.status_code in (200, 201)
    assert retry.json()["result_id"] == first.json()["result_id"]


def test_a_requested_resubmission_frees_the_slot(
    client, admin, athlete, settings, seeded_tests, db
):
    session = create_session(client, admin, settings)
    headers = athlete_auth(athlete, settings)
    first = submit(client, headers, session["id"]).json()

    result = db.get(TestResult, uuid.UUID(first["result_id"]))
    result.status = TestResultStatus.pending_sync
    db.commit()

    tests = active(client, athlete, settings)[0]["tests"]
    assert tests[0]["submitted"] is False
    assert tests[0]["result_status"] == "pending_sync"

    assert submit(client, headers, session["id"]).status_code == 201
    assert submit(client, headers, session["id"]).status_code == 409


def test_the_database_itself_refuses_a_second_live_submission(
    admin, athlete, seeded_tests, db
):
    """The check in the endpoint can race; the index cannot."""
    session = AssessmentSession(
        name="Race",
        starts_at=NOW - timedelta(hours=1),
        ends_at=NOW + timedelta(hours=1),
        enabled=True,
        allowed_tests=["SIT_UPS"],
    )
    db.add(session)
    db.commit()

    def result(number):
        return TestResult(
            athlete_id=athlete.id,
            test_id=seeded_tests["SIT_UPS"].id,
            session_id=session.id,
            attempt_number=number,
            status=TestResultStatus.processing,
        )

    db.add(result(1))
    db.commit()
    db.add(result(2))
    with pytest.raises(Exception, match=r"(?i)unique"):
        db.commit()
    db.rollback()


def test_practice_and_other_sessions_are_unaffected_by_the_limit(
    client, admin, athlete, settings, seeded_tests
):
    first = create_session(client, admin, settings)
    second = create_session(client, admin, settings, name="State trials")
    headers = athlete_auth(athlete, settings)

    assert submit(client, headers, first["id"]).status_code == 201
    assert submit(client, headers, second["id"]).status_code == 201


# ---------------------------------------------------------------------------
# Who sees what
# ---------------------------------------------------------------------------


def test_only_enabled_open_sessions_in_the_athletes_region_appear(
    client, admin, athlete, settings, db
):
    create_session(client, admin, settings, name="Open")
    create_session(client, admin, settings, name="Switched off", enabled=False)
    create_session(
        client,
        admin,
        settings,
        name="Not yet",
        starts_at=(NOW + timedelta(days=1)).isoformat(),
        ends_at=(NOW + timedelta(days=2)).isoformat(),
    )
    create_session(
        client,
        admin,
        settings,
        name="Over",
        starts_at=(NOW - timedelta(days=3)).isoformat(),
        ends_at=(NOW - timedelta(days=1)).isoformat(),
    )
    create_session(client, admin, settings, name="Kerala only", region="Kerala")
    create_session(client, admin, settings, name="Tamil Nadu only", region="Tamil Nadu")

    names = {item["name"] for item in active(client, athlete, settings)}

    assert names == {"Open", "Tamil Nadu only"}


def test_only_the_selected_tests_appear_in_order(client, admin, athlete, settings):
    create_session(
        client, admin, settings, allowed_tests=["squats", "VERTICAL_JUMP", "SQUATS"]
    )

    tests = active(client, athlete, settings)[0]["tests"]

    assert [item["test_type"] for item in tests] == ["SQUATS", "VERTICAL_JUMP"]
    assert [item["unit"] for item in tests] == ["reps", "cm"]


def test_sessions_need_a_signed_in_athlete(client):
    assert client.get("/api/sessions/active").status_code == 401


# ---------------------------------------------------------------------------
# What may be submitted, and when
# ---------------------------------------------------------------------------


def test_a_test_outside_the_session_is_refused(
    client, admin, athlete, settings, seeded_tests
):
    session = create_session(client, admin, settings, allowed_tests=["VERTICAL_JUMP"])

    response = submit(
        client, athlete_auth(athlete, settings), session["id"], test="SIT_UPS"
    )

    assert response.status_code == 400
    assert "not part of the assessment session" in response.json()["detail"]


def test_a_recording_made_offline_during_the_session_is_accepted_after_it_ends(
    client, admin, athlete, settings, seeded_tests
):
    session = create_session(
        client,
        admin,
        settings,
        starts_at=(NOW - timedelta(days=2)).isoformat(),
        ends_at=(NOW - timedelta(days=1)).isoformat(),
    )

    response = submit(
        client,
        athlete_auth(athlete, settings),
        session["id"],
        recorded_at=NOW - timedelta(days=1, hours=6),
    )

    assert response.status_code == 201, response.text


def test_recordings_outside_the_window_or_too_late_are_refused(
    client, admin, athlete, settings, seeded_tests
):
    headers = athlete_auth(athlete, settings)
    session = create_session(client, admin, settings)

    before = submit(client, headers, session["id"], recorded_at=NOW - timedelta(days=1))
    assert before.status_code == 400
    assert "not recorded during" in before.json()["detail"]

    future = submit(client, headers, session["id"], recorded_at=NOW + timedelta(hours=3))
    assert future.status_code == 400

    long_gone = create_session(
        client,
        admin,
        settings,
        name="Long gone",
        starts_at=(NOW - timedelta(days=30)).isoformat(),
        ends_at=(NOW - timedelta(days=20)).isoformat(),
    )
    late = submit(client, headers, long_gone["id"], recorded_at=NOW - timedelta(days=25))
    assert late.status_code == 400
    assert "closed" in late.json()["detail"]


def test_a_disabled_or_other_regions_session_takes_no_submissions(
    client, admin, athlete, settings, seeded_tests
):
    headers = athlete_auth(athlete, settings)
    closed = create_session(client, admin, settings, enabled=False)
    elsewhere = create_session(client, admin, settings, region="Kerala")

    assert submit(client, headers, closed["id"]).status_code == 400
    assert submit(client, headers, elsewhere["id"]).status_code == 400


def test_an_unknown_session_is_refused(client, athlete, settings, seeded_tests):
    response = submit(client, athlete_auth(athlete, settings), str(uuid.uuid4()))
    assert response.status_code == 400


def test_sessionless_submissions_can_be_switched_off(
    client, athlete, settings, seeded_tests
):
    headers = athlete_auth(athlete, settings)
    settings.sessions_required = True

    response = client.post(
        "/api/tests/submit",
        json={
            "test_id": "SIT_UPS",
            "provisional_score": 1,
            "video_id": upload(client, headers),
        },
        headers=headers,
    )

    assert response.status_code == 400
    assert "assessment session" in response.json()["detail"]


# ---------------------------------------------------------------------------
# Managing sessions
# ---------------------------------------------------------------------------


def test_only_an_admin_manages_sessions(client, official, admin, settings):
    body = {
        "name": "Nope",
        "starts_at": NOW.isoformat(),
        "ends_at": (NOW + timedelta(days=1)).isoformat(),
        "allowed_tests": ["SIT_UPS"],
    }
    reviewer = official_auth(official, settings)

    assert (
        client.post("/api/dashboard/sessions", json=body, headers=reviewer).status_code
        == 403
    )

    session = create_session(client, admin, settings)
    assert (
        client.patch(
            f"/api/dashboard/sessions/{session['id']}",
            json={"enabled": False},
            headers=reviewer,
        ).status_code
        == 403
    )
    assert client.get("/api/dashboard/sessions", headers=reviewer).status_code == 200


def test_sessions_are_created_closed_unless_opened(client, admin, settings):
    session = create_session(client, admin, settings, enabled=False)
    assert session["status"] == "disabled"

    opened = client.patch(
        f"/api/dashboard/sessions/{session['id']}",
        json={"enabled": True},
        headers=official_auth(admin, settings),
    )

    assert opened.status_code == 200
    assert opened.json()["status"] == "active"


def test_bad_sessions_are_refused(client, admin, settings):
    headers = official_auth(admin, settings)
    base = {
        "name": "Bad",
        "starts_at": NOW.isoformat(),
        "ends_at": (NOW + timedelta(days=1)).isoformat(),
        "allowed_tests": ["SIT_UPS"],
    }
    for override in (
        {"ends_at": (NOW - timedelta(days=1)).isoformat()},
        {"allowed_tests": ["MARATHON"]},
        {"allowed_tests": []},
        {"region": "Atlantis"},
    ):
        response = client.post(
            "/api/dashboard/sessions", json={**base, **override}, headers=headers
        )
        assert response.status_code == 422, (override, response.text)


def test_a_session_with_submissions_cannot_be_deleted_or_lose_those_tests(
    client, admin, athlete, settings, seeded_tests
):
    headers = official_auth(admin, settings)
    session = create_session(
        client, admin, settings, allowed_tests=["SIT_UPS", "VERTICAL_JUMP"]
    )
    submit(client, athlete_auth(athlete, settings), session["id"])

    listed = client.get("/api/dashboard/sessions", headers=headers).json()
    assert listed[0]["submission_count"] == 1

    assert (
        client.delete(
            f"/api/dashboard/sessions/{session['id']}", headers=headers
        ).status_code
        == 409
    )
    removing = client.patch(
        f"/api/dashboard/sessions/{session['id']}",
        json={"allowed_tests": ["VERTICAL_JUMP"]},
        headers=headers,
    )
    assert removing.status_code == 409

    # Closing it is always possible.
    closed = client.patch(
        f"/api/dashboard/sessions/{session['id']}",
        json={"enabled": False},
        headers=headers,
    )
    assert closed.json()["status"] == "disabled"


def test_an_unused_session_can_be_deleted(client, admin, settings):
    headers = official_auth(admin, settings)
    session = create_session(client, admin, settings)

    assert (
        client.delete(
            f"/api/dashboard/sessions/{session['id']}", headers=headers
        ).status_code
        == 200
    )
    assert client.get("/api/dashboard/sessions", headers=headers).json() == []


def test_a_reviewer_sees_national_sessions_and_their_own_regions(
    client, official, admin, settings
):
    create_session(client, admin, settings, name="National")
    create_session(client, admin, settings, name="Tamil Nadu", region="Tamil Nadu")
    create_session(client, admin, settings, name="Kerala", region="Kerala")

    names = {
        item["name"]
        for item in client.get(
            "/api/dashboard/sessions", headers=official_auth(official, settings)
        ).json()
    }

    assert names == {"National", "Tamil Nadu"}


# ---------------------------------------------------------------------------
# The rules on their own
# ---------------------------------------------------------------------------


def test_status_follows_the_clock():
    session = AssessmentSession(
        enabled=True,
        starts_at=NOW,
        ends_at=NOW + timedelta(hours=1),
        allowed_tests=[],
    )
    assert (
        rules.status_of(session, NOW - timedelta(seconds=1))
        is rules.SessionStatus.SCHEDULED
    )
    assert rules.status_of(session, NOW) is rules.SessionStatus.ACTIVE
    assert rules.status_of(session, NOW + timedelta(hours=1)) is rules.SessionStatus.ENDED
    session.enabled = False
    assert rules.status_of(session, NOW) is rules.SessionStatus.DISABLED

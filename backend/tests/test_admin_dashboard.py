"""Sprint 13: the admin dashboard foundation, as the API sees it.

The Definition of Done is one journey — an admin signs in, creates a session,
the session becomes active by the server's clock, the phone retrieves it, an
athlete submits to it, and the admin finds that submission in the list and
opens it. `test_the_sprint_13_journey` walks exactly that. The rest pin who may
reach the admin API at all, and the lifecycle rules the server owns.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.models import OfficialRole, TestResultStatus
from app.security import create_access_token
from tests.test_dashboard import (
    PASSWORD,
    add_flag,
    make_athlete,
    make_official,
    make_result,
    token_for,
)
from tests.test_sessions import athlete_auth, create_session, submit

ALL_SIX = ["SQUATS", "PUSH_UPS", "BICEP_CURLS", "LUNGES", "VERTICAL_JUMP", "SIT_UPS"]


def admin_login(client, official) -> dict:
    response = client.post(
        "/api/dashboard/auth/login",
        json={"email": official.email, "password": PASSWORD},
    )
    assert response.status_code == 200, response.text
    return response.json()


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def window(*, starts_in: timedelta, lasts: timedelta = timedelta(days=1)) -> dict:
    start = datetime.now(UTC) + starts_in
    return {"starts_at": start.isoformat(), "ends_at": (start + lasts).isoformat()}


# ---------------------------------------------------------------------------
# The Definition of Done
# ---------------------------------------------------------------------------


def test_the_sprint_13_journey(client, db, settings, athlete, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)

    # Sign in, and the dashboard knows who it is talking to.
    tokens = admin_login(client, admin)
    headers = bearer(tokens["access_token"])
    me = client.get("/api/dashboard/auth/me", headers=headers).json()
    assert me["role"] == "sai_admin" and me["email"] == admin.email

    # The tests offered come from the backend, all six of them.
    catalog = client.get("/api/dashboard/tests", headers=headers).json()
    assert [test["code"] for test in catalog] == ALL_SIX

    # Create a session with every test, open now.
    created = client.post(
        "/api/dashboard/sessions",
        json={
            "name": "District trials",
            "description": "Open trials",
            "rules": "Film side-on.",
            **window(starts_in=-timedelta(hours=1)),
            "enabled": True,
            "allowed_tests": ALL_SIX,
        },
        headers=headers,
    )
    assert created.status_code == 201, created.text
    session = created.json()
    assert session["status"] == "active"

    # It persisted: retrievable again, and "active" is the server's answer.
    again = client.get(f"/api/dashboard/sessions/{session['id']}", headers=headers)
    assert again.status_code == 200 and again.json()["allowed_tests"] == ALL_SIX
    listed = client.get("/api/dashboard/sessions?status=active", headers=headers).json()
    assert [item["id"] for item in listed] == [session["id"]]

    # The phone retrieves it from the backend, with its configuration.
    phone = client.get("/api/sessions/active", headers=athlete_auth(athlete, settings))
    assert phone.status_code == 200
    [seen] = phone.json()["sessions"]
    assert seen["session_id"] == session["id"]
    assert [test["test_type"] for test in seen["tests"]] == ALL_SIX
    assert phone.json()["server_time"]

    # An athlete submits to it...
    submitted = submit(client, athlete_auth(athlete, settings), session["id"])
    assert submitted.status_code in (200, 201), submitted.text
    result_id = submitted.json()["result_id"]

    # ...and the admin finds it in the submission list, filtered by session.
    page = client.get(
        f"/api/dashboard/submissions?session_id={session['id']}", headers=headers
    )
    assert page.status_code == 200
    assert page.headers["X-Total-Count"] == "1"
    [row] = page.json()
    assert row["result_id"] == result_id
    assert row["athlete_name"] == athlete.name
    assert row["session_name"] == "District trials"
    assert row["test_type"] == "SIT_UPS"
    assert row["status"] in {"uploaded", "processing"}

    # Opening it shows the session, and the verification view is available.
    detail = client.get(f"/api/dashboard/reviews/{result_id}", headers=headers).json()
    assert detail["session_id"] == session["id"]
    assert detail["session_name"] == "District trials"
    verification = client.get(f"/api/verification/{result_id}", headers=headers)
    assert verification.status_code == 200
    assert verification.json()["provisional_score"] == 20

    # The session counts it.
    assert client.get(
        f"/api/dashboard/sessions/{session['id']}", headers=headers
    ).json()["submission_count"] == 1

    # End it now: the server decides, and the phone stops seeing it.
    ended = client.post(f"/api/dashboard/sessions/{session['id']}/end", headers=headers)
    assert ended.status_code == 200, ended.text
    assert ended.json()["status"] == "ended"
    assert client.get(
        "/api/sessions/active", headers=athlete_auth(athlete, settings)
    ).json()["sessions"] == []

    # Sign out revokes the refresh token, not just the browser's copy.
    out = client.post(
        "/api/dashboard/auth/logout",
        json={"refresh_token": tokens["refresh_token"]},
        headers=headers,
    )
    assert out.status_code == 200
    assert client.post(
        "/api/dashboard/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    ).status_code == 401


# ---------------------------------------------------------------------------
# Who may reach the admin API
# ---------------------------------------------------------------------------

SOME_ID = "00000000-0000-0000-0000-000000000001"

ADMIN_ENDPOINTS = [
    ("GET", "/api/dashboard/auth/me"),
    ("POST", "/api/dashboard/auth/logout"),
    ("GET", "/api/dashboard/stats"),
    ("GET", "/api/dashboard/tests"),
    ("GET", "/api/dashboard/reviews"),
    ("GET", f"/api/dashboard/reviews/{SOME_ID}"),
    ("GET", f"/api/dashboard/reviews/{SOME_ID}/pose"),
    ("POST", f"/api/dashboard/reviews/{SOME_ID}/action"),
    ("GET", "/api/dashboard/submissions"),
    ("GET", "/api/dashboard/leaderboard?test_type=SIT_UPS"),
    ("GET", "/api/dashboard/sessions"),
    ("GET", f"/api/dashboard/sessions/{SOME_ID}"),
    ("POST", "/api/dashboard/sessions"),
    ("PATCH", f"/api/dashboard/sessions/{SOME_ID}"),
    ("POST", f"/api/dashboard/sessions/{SOME_ID}/end"),
    ("DELETE", f"/api/dashboard/sessions/{SOME_ID}"),
    ("POST", f"/api/verification/{SOME_ID}/process"),
]

BODIES = {
    "POST /api/dashboard/auth/logout": {},
    f"POST /api/dashboard/reviews/{SOME_ID}/action": {"action": "approved"},
    "POST /api/dashboard/sessions": {
        "name": "x",
        "starts_at": "2026-10-01T00:00:00Z",
        "ends_at": "2026-10-02T00:00:00Z",
        "allowed_tests": ["SQUATS"],
    },
    f"PATCH /api/dashboard/sessions/{SOME_ID}": {"enabled": True},
}


def call(client, method, path, headers=None):
    body = BODIES.get(f"{method} {path}")
    return client.request(method, path, json=body, headers=headers or {})


@pytest.mark.parametrize(("method", "path"), ADMIN_ENDPOINTS)
def test_the_anonymous_are_refused_even_on_a_development_server(
    client, settings, method, path
):
    # The fixture's settings allow unauthenticated athletes, as a dev server
    # does. That bypass must not reach the admin API.
    assert settings.unauthenticated_allowed
    assert call(client, method, path).status_code == 401


@pytest.mark.parametrize(("method", "path"), ADMIN_ENDPOINTS)
def test_an_athlete_token_is_refused(client, settings, athlete, method, path):
    assert call(client, method, path, athlete_auth(athlete, settings)).status_code == 403


def test_an_anonymous_caller_does_not_get_the_official_verification_view(
    client, db, seeded_tests
):
    result = make_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    assert client.get(f"/api/verification/{result.id}").status_code == 401


def test_a_token_signed_with_another_key_is_refused(client, settings, db):
    admin = make_official(db, role=OfficialRole.sai_admin)
    forged = create_access_token(
        str(admin.id), settings.model_copy(update={"jwt_secret": "x" * 40}),
        role="sai_admin",
    )
    response = client.get("/api/dashboard/sessions", headers=bearer(forged))
    assert response.status_code == 401


def test_only_an_admin_changes_a_session(client, db, settings):
    admin = make_official(db, role=OfficialRole.sai_admin)
    reviewer = make_official(db)
    session = create_session(client, admin, settings)
    as_reviewer = token_for(reviewer, settings)

    for method, path, body in (
        ("POST", "/api/dashboard/sessions", BODIES["POST /api/dashboard/sessions"]),
        ("PATCH", f"/api/dashboard/sessions/{session['id']}", {"enabled": False}),
        ("POST", f"/api/dashboard/sessions/{session['id']}/end", None),
        ("DELETE", f"/api/dashboard/sessions/{session['id']}", None),
    ):
        response = client.request(method, path, json=body, headers=as_reviewer)
        assert response.status_code == 403, (method, path)

    # Looking is allowed; the session is national.
    assert client.get(
        f"/api/dashboard/sessions/{session['id']}", headers=as_reviewer
    ).status_code == 200


# ---------------------------------------------------------------------------
# Session lifecycle: the server's clock decides
# ---------------------------------------------------------------------------


def test_sessions_can_be_filtered_by_the_status_the_server_computes(
    client, db, settings
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    made = {
        "disabled": create_session(
            client, admin, settings, name="Closed", enabled=False
        ),
        "scheduled": create_session(
            client, admin, settings, name="Next week",
            **window(starts_in=timedelta(days=7)),
        ),
        "active": create_session(client, admin, settings, name="Today"),
        "ended": create_session(
            client, admin, settings, name="Last month",
            **window(starts_in=-timedelta(days=30)),
        ),
    }
    for state, session in made.items():
        assert session["status"] == state
        listed = client.get(f"/api/dashboard/sessions?status={state}", headers=headers)
        assert [item["id"] for item in listed.json()] == [session["id"]], state

    both = client.get(
        "/api/dashboard/sessions?status=active,scheduled", headers=headers
    ).json()
    assert {item["name"] for item in both} == {"Today", "Next week"}
    assert len(client.get("/api/dashboard/sessions", headers=headers).json()) == 4
    assert client.get(
        "/api/dashboard/sessions?status=draft", headers=headers
    ).status_code == 422


def test_only_an_active_session_can_be_ended(client, db, settings):
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    scheduled = create_session(
        client, admin, settings, **window(starts_in=timedelta(days=1))
    )
    disabled = create_session(client, admin, settings, enabled=False)
    active = create_session(client, admin, settings)

    for session in (scheduled, disabled):
        response = client.post(
            f"/api/dashboard/sessions/{session['id']}/end", headers=headers
        )
        assert response.status_code == 409

    before = datetime.now(UTC)
    ended = client.post(f"/api/dashboard/sessions/{active['id']}/end", headers=headers)
    assert ended.status_code == 200
    closed_at = datetime.fromisoformat(ended.json()["ends_at"])
    assert before - timedelta(seconds=5) <= closed_at <= datetime.now(UTC)

    # Once ended it is ended; there is nothing left to end.
    assert client.post(
        f"/api/dashboard/sessions/{active['id']}/end", headers=headers
    ).status_code == 409
    assert client.post(
        f"/api/dashboard/sessions/{uuid.uuid4()}/end", headers=headers
    ).status_code == 404


def test_ending_still_accepts_a_recording_made_before_the_end(
    client, db, settings, athlete, seeded_tests
):
    """Ending is not disabling: the offline athlete who recorded while the
    session was open can still upload afterwards, within the grace period."""
    admin = make_official(db, role=OfficialRole.sai_admin)
    session = create_session(client, admin, settings)
    recorded = datetime.now(UTC) - timedelta(minutes=10)

    client.post(
        f"/api/dashboard/sessions/{session['id']}/end",
        headers=token_for(admin, settings),
    )

    late = submit(
        client, athlete_auth(athlete, settings), session["id"], recorded_at=recorded
    )
    assert late.status_code in (200, 201), late.text

    # A different athlete, so the one-submission rule is not what refuses it.
    newcomer = make_athlete(db)
    after = submit(
        client,
        athlete_auth(newcomer, settings),
        session["id"],
        recorded_at=datetime.now(UTC) + timedelta(seconds=30),
    )
    assert after.status_code == 400
    assert "not recorded during" in after.json()["detail"]


# ---------------------------------------------------------------------------
# Submission list
# ---------------------------------------------------------------------------


def test_submissions_list_every_status_newest_first_and_page(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    athlete = make_athlete(db)
    now = datetime.now(UTC)
    made = [
        make_result(
            db, athlete, seeded_tests["SIT_UPS"], status=state, attempt=attempt,
            created_at=now - timedelta(minutes=minutes),
        )
        for attempt, (state, minutes) in enumerate(
            (
                (TestResultStatus.approved, 40),
                (TestResultStatus.flagged, 30),
                (TestResultStatus.verified, 20),
                (TestResultStatus.processing, 10),
            ),
            start=1,
        )
    ]
    make_result(
        db, athlete, seeded_tests["VERTICAL_JUMP"], status=TestResultStatus.rejected,
        created_at=now - timedelta(minutes=50),
    )

    everything = client.get("/api/dashboard/submissions", headers=headers)
    assert everything.headers["X-Total-Count"] == "5"
    assert [row["result_id"] for row in everything.json()[:4]] == [
        str(result.id) for result in reversed(made)
    ]

    page = client.get("/api/dashboard/submissions?limit=2&offset=2", headers=headers)
    assert [row["status"] for row in page.json()] == ["flagged", "approved"]
    assert page.headers["X-Total-Count"] == "5"

    decided = client.get(
        "/api/dashboard/submissions?status=approved,rejected", headers=headers
    ).json()
    assert {row["status"] for row in decided} == {"approved", "rejected"}

    jumps = client.get(
        "/api/dashboard/submissions?test_type=vertical_jump", headers=headers
    ).json()
    assert [row["test_type"] for row in jumps] == ["VERTICAL_JUMP"]
    assert jumps[0]["unit"] == "cm"
    assert jumps[0]["session_id"] is None and jumps[0]["session_name"] is None

    assert client.get(
        "/api/dashboard/submissions?status=nonsense", headers=headers
    ).status_code == 422


def test_submissions_carry_their_flag_state(client, db, settings, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)
    result = make_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    add_flag(db, result)

    [row] = client.get(
        "/api/dashboard/submissions", headers=token_for(admin, settings)
    ).json()
    assert row["flag_count"] == 1
    assert row["max_severity"] == "high"
    assert row["server_score"] == 28 and row["provisional_score"] == 30


def test_submissions_are_region_scoped(client, db, settings, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)
    reviewer = make_official(db, region="Tamil Nadu")
    unassigned = make_official(db, region=None)
    home = make_result(db, make_athlete(db, region="Tamil Nadu"), seeded_tests["SIT_UPS"])
    away = make_result(db, make_athlete(db, region="Kerala"), seeded_tests["SIT_UPS"])

    def ids(official, query=""):
        response = client.get(
            f"/api/dashboard/submissions{query}", headers=token_for(official, settings)
        )
        return {row["result_id"] for row in response.json()}

    assert ids(admin) == {str(home.id), str(away.id)}
    assert ids(admin, "?region=Kerala") == {str(away.id)}
    assert ids(reviewer) == {str(home.id)}
    assert ids(reviewer, "?region=Kerala") == set()
    assert ids(unassigned) == set()

    # Opening another region's submission is "not found", in both views.
    as_reviewer = token_for(reviewer, settings)
    assert client.get(
        f"/api/dashboard/reviews/{away.id}", headers=as_reviewer
    ).status_code == 404
    assert client.get(
        f"/api/verification/{away.id}", headers=as_reviewer
    ).status_code == 404


def test_a_reviewer_counts_only_their_regions_submissions_to_a_national_session(
    client, db, settings, athlete, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    session = create_session(client, admin, settings)
    submit(client, athlete_auth(athlete, settings), session["id"])  # a Tamil Nadu athlete

    def count(official) -> tuple[int, int]:
        headers = token_for(official, settings)
        one = client.get(f"/api/dashboard/sessions/{session['id']}", headers=headers)
        [listed] = client.get("/api/dashboard/sessions", headers=headers).json()
        return one.json()["submission_count"], listed["submission_count"]

    assert count(admin) == (1, 1)
    assert count(make_official(db, region="Tamil Nadu")) == (1, 1)
    # The list would show a Kerala reviewer nothing; neither does the count.
    assert count(make_official(db, region="Kerala")) == (0, 0)
    assert count(make_official(db, region=None)) == (0, 0)


def test_the_tests_catalog_is_the_six_the_server_can_score(
    client, db, settings, seeded_tests
):
    reviewer = make_official(db)
    catalog = client.get(
        "/api/dashboard/tests", headers=token_for(reviewer, settings)
    ).json()

    assert [test["code"] for test in catalog] == ALL_SIX
    by_code = {test["code"]: test for test in catalog}
    # Named from the seeded tests table where it has a row...
    assert by_code["SIT_UPS"]["name"] == "Sit-ups"
    assert by_code["VERTICAL_JUMP"]["name"] == "Vertical Jump"
    # ...and still offered, readably, where it does not.
    assert by_code["BICEP_CURLS"]["name"] == "Bicep Curls"
    assert by_code["VERTICAL_JUMP"]["unit"] == "cm"
    assert {by_code[code]["unit"] for code in ALL_SIX if code != "VERTICAL_JUMP"} == {
        "reps"
    }


# ---------------------------------------------------------------------------
# Sign-out
# ---------------------------------------------------------------------------


def test_logout_revokes_only_the_callers_own_refresh_tokens(client, db):
    admin = make_official(db, role=OfficialRole.sai_admin)
    other = make_official(db, role=OfficialRole.sai_admin)
    office, laptop = admin_login(client, admin), admin_login(client, admin)
    theirs = admin_login(client, other)

    def refreshes(tokens) -> bool:
        return client.post(
            "/api/dashboard/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
        ).status_code == 200

    # Presenting someone else's refresh token revokes nothing.
    client.post(
        "/api/dashboard/auth/logout",
        json={"refresh_token": theirs["refresh_token"]},
        headers=bearer(office["access_token"]),
    )
    assert refreshes(theirs)

    client.post(
        "/api/dashboard/auth/logout",
        json={"refresh_token": office["refresh_token"]},
        headers=bearer(office["access_token"]),
    )
    assert not refreshes(office)

    fresh = admin_login(client, admin)
    everywhere = client.post(
        "/api/dashboard/auth/logout",
        json={"all_devices": True},
        headers=bearer(fresh["access_token"]),
    )
    assert everywhere.status_code == 200
    assert not refreshes(laptop)
    assert not refreshes(fresh)

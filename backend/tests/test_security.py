"""Sprint 20: the security review's checks, kept as tests.

* **Who may call what.** Generated from the OpenAPI document, so an endpoint
  added later is covered without anyone remembering to list it: every
  officials' operation refuses the anonymous and athletes; every athlete
  operation refuses the anonymous on a production-like server; one athlete
  cannot reach another's records; a reviewer stays inside their region; only
  an admin reads the access log.
* **Malformed input.** Every operation, fed broken ids and broken bodies,
  answers with a 4xx — never a 500 that would mean an unchecked path.
* **Rate limits** and **the access log**, end to end through the real app.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from pathlib import Path

import pytest
from sqlalchemy import select

from app.config import Settings, get_settings
from app.main import create_app
from app.models import AccessLog, OfficialRole, TestResultStatus
from app.security import create_access_token
from tests.test_dashboard import (
    PASSWORD,
    make_athlete,
    make_official,
    make_result,
    token_for,
)
from tests.test_sessions import athlete_auth


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    # Production-like where it matters here: no anonymous athletes. Rate
    # limits are off except in the tests that turn them on, so the sweep's
    # thousand requests are not refused for being a thousand requests.
    return Settings(
        environment="test",
        debug=True,
        database_url="sqlite://",
        storage_backend="local",
        storage_local_path=tmp_path / "storage",
        upload_staging_path=tmp_path / "uploads",
        allow_unauthenticated=False,
        jwt_secret="test-secret",
        upload_chunk_size_bytes=1024,
        rate_limit_enabled=False,
    )


def operations() -> list[tuple[str, str, dict]]:
    paths = create_app().openapi()["paths"]
    return [
        (method.upper(), path, op)
        for path, ops in paths.items()
        for method, op in ops.items()
        if path.startswith("/api/")
    ]


OPERATIONS = operations()
SIGN_IN = {"/api/dashboard/auth/login", "/api/dashboard/auth/refresh"}
OFFICIAL_ONLY = [
    (m, p)
    for m, p, _ in OPERATIONS
    if (p.startswith("/api/dashboard/") and p not in SIGN_IN)
    or p == "/api/verification/{result_id}/process"
]
# Reached before an account exists, or by design without one.
PUBLIC = {
    "/api/auth/request-otp",
    "/api/auth/verify-otp",
    "/api/auth/refresh",
    "/api/auth/logout",
    "/api/athletes/register",
    "/api/athletes/leaderboard-options",
    "/api/athletes/leaderboard/{test_type}",
    "/api/sessions/active",
    "/api/sessions/{session_id}",
    *SIGN_IN,
}
ATHLETE_ONLY = [
    (m, p) for m, p, _ in OPERATIONS if p not in PUBLIC and (m, p) not in OFFICIAL_ONLY
]
SOME_ID = "00000000-0000-0000-0000-000000000001"


def concrete(path: str, value: str = SOME_ID) -> str:
    import re

    return re.sub(r"\{[^}]+\}", value, path)


def test_the_lists_cover_the_api():
    # A sanity check on the generated lists, so a renamed prefix cannot quietly
    # empty them and pass every test below.
    assert len(OFFICIAL_ONLY) >= 18
    assert len(ATHLETE_ONLY) >= 20
    assert ("GET", "/api/dashboard/access-log") in OFFICIAL_ONLY
    assert ("GET", "/api/dashboard/analytics") in OFFICIAL_ONLY
    assert ("GET", "/api/results/{result_id}") in ATHLETE_ONLY


# ---------------------------------------------------------------------------
# Who may call what
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(("method", "path"), OFFICIAL_ONLY)
def test_officials_operations_refuse_the_anonymous_and_athletes(
    client, db, settings, method, path
):
    athlete = make_athlete(db)
    url = concrete(path)
    assert client.request(method, url, json={}).status_code == 401
    refused = client.request(
        method, url, json={}, headers=athlete_auth(athlete, settings)
    )
    assert refused.status_code in (401, 403)


@pytest.mark.parametrize(("method", "path"), ATHLETE_ONLY)
def test_athlete_operations_refuse_the_anonymous(client, method, path):
    response = client.request(method, concrete(path), json={})
    assert response.status_code == 401, (method, path, response.text)


@pytest.mark.parametrize(("method", "path"), OFFICIAL_ONLY)
def test_a_forged_official_token_is_refused(client, db, method, path):
    forged = create_access_token(
        str(uuid.uuid4()),
        Settings(jwt_secret="someone-elses-secret"),
        role="sai_admin",
    )
    response = client.request(
        method, concrete(path), json={}, headers={"Authorization": f"Bearer {forged}"}
    )
    assert response.status_code == 401


def test_one_athlete_cannot_reach_anothers_result(client, db, settings, seeded_tests):
    owner, other = make_athlete(db), make_athlete(db)
    result = make_result(db, owner, seeded_tests["SIT_UPS"])
    theirs = athlete_auth(other, settings)

    # Not found, rather than forbidden: whether the id exists is not theirs.
    assert client.get(f"/api/results/{result.id}", headers=theirs).status_code == 404
    assert client.get(f"/api/verification/{result.id}", headers=theirs).status_code == 404
    mine = athlete_auth(owner, settings)
    assert client.get(f"/api/results/{result.id}", headers=mine).status_code == 200


def test_a_reviewer_stays_in_their_region(client, db, settings, seeded_tests):
    reviewer = make_official(db, region="Kerala")
    elsewhere = make_result(
        db, make_athlete(db, region="Tamil Nadu"), seeded_tests["SIT_UPS"]
    )
    headers = token_for(reviewer, settings)

    assert (
        client.get(f"/api/dashboard/reviews/{elsewhere.id}", headers=headers).status_code
        == 404
    )
    refused = client.post(
        f"/api/dashboard/reviews/{elsewhere.id}/action",
        json={"action": "approved", "expected_status": "flagged"},
        headers=headers,
    )
    assert refused.status_code == 404
    listed = client.get("/api/dashboard/submissions", headers=headers).json()
    assert str(elsewhere.id) not in str(listed)


def test_only_an_admin_reads_the_access_log_or_changes_sessions(client, db, settings):
    reviewer = make_official(db)
    admin = make_official(db, role=OfficialRole.sai_admin)

    assert (
        client.get(
            "/api/dashboard/access-log", headers=token_for(reviewer, settings)
        ).status_code
        == 403
    )
    assert (
        client.get(
            "/api/dashboard/access-log", headers=token_for(admin, settings)
        ).status_code
        == 200
    )
    created = client.post(
        "/api/dashboard/sessions",
        json={
            "name": "x",
            "starts_at": "2030-01-01T00:00:00Z",
            "ends_at": "2030-01-02T00:00:00Z",
            "allowed_tests": ["SIT_UPS"],
        },
        headers=token_for(reviewer, settings),
    )
    assert created.status_code == 403


def test_a_deactivated_official_is_refused_with_a_valid_token(client, db, settings):
    official = make_official(db, role=OfficialRole.sai_admin, active=False)
    response = client.get("/api/dashboard/stats", headers=token_for(official, settings))
    assert response.status_code in (401, 403)


# ---------------------------------------------------------------------------
# Malformed input never reaches a 500
# ---------------------------------------------------------------------------

BAD_IDS = ["not-a-uuid", "..%2F..%2Fetc%2Fpasswd", "-1", "x" * 600, "%00"]
BAD_BODIES = [
    {"content": b"{not json", "headers": {"Content-Type": "application/json"}},
    {"json": []},
    {"json": {"unexpected": "x" * 10_000}},
    {"json": {"a": {"b": {"c": [None, 1e308, -1, "", True]}}}},
]


@pytest.mark.parametrize(("method", "path", "op"), OPERATIONS)
def test_malformed_requests_get_a_client_error(
    client, db, settings, seeded_tests, method, path, op
):
    athlete = make_athlete(db)
    admin = make_official(db, role=OfficialRole.sai_admin)
    who = (
        token_for(admin, settings)
        if (method, path) in OFFICIAL_ONLY
        else athlete_auth(athlete, settings)
    )

    def send(url: str, **kwargs) -> int:
        headers = {**who, **kwargs.pop("headers", {})}
        try:
            return client.request(method, url, headers=headers, **kwargs).status_code
        except Exception as error:  # the TestClient re-raises server errors
            pytest.fail(f"{method} {url} raised {error!r}")

    has_params = "{" in path
    urls = [concrete(path, bad) for bad in BAD_IDS] if has_params else [path]
    for url in urls:
        status = send(url, json={})
        assert status < 500, (method, url, status)
    if "requestBody" in op:
        for body in BAD_BODIES:
            status = send(concrete(path), **dict(body))
            assert status < 500, (method, path, body, status)


# ---------------------------------------------------------------------------
# Rate limits
# ---------------------------------------------------------------------------


def limited(client, settings, **limits) -> Settings:
    tight = settings.model_copy(update={"rate_limit_enabled": True, **limits})
    client.app.dependency_overrides[get_settings] = lambda: tight
    return tight


def test_too_many_requests_get_429_with_retry_after(client, db, settings):
    limited(client, settings, rate_limit_per_minute=3)
    statuses = [client.get("/api/sessions/active").status_code for _ in range(5)]

    assert 429 not in statuses[:3]
    assert statuses[3:] == [429, 429]
    response = client.get("/api/sessions/active")
    assert 1 <= int(response.headers["Retry-After"]) <= 60


def test_health_checks_are_never_limited(client, settings):
    limited(client, settings, rate_limit_per_minute=1)
    assert {client.get("/health").status_code for _ in range(5)} == {200}


def test_sign_in_has_its_own_small_allowance(client, db, settings):
    limited(client, settings, rate_limit_sign_in_per_minute=2, rate_limit_per_minute=100)
    attempt = {"email": "nobody@sai.example", "password": "wrong"}
    statuses = [
        client.post("/api/dashboard/auth/login", json=attempt).status_code
        for _ in range(3)
    ]
    assert statuses == [401, 401, 429]
    # The rest of the API is unaffected.
    assert client.get("/api/sessions/active").status_code != 429


def test_an_account_is_limited_wherever_it_calls_from(client, db, settings):
    limited(client, settings, rate_limit_per_minute=3, rate_limit_trust_forwarded=True)
    athlete = make_athlete(db)
    headers = athlete_auth(athlete, settings)

    statuses = [
        client.get(
            "/api/athletes/me",
            headers={**headers, "X-Forwarded-For": f"10.0.0.{n}"},
        ).status_code
        for n in range(4)
    ]
    assert statuses == [200, 200, 200, 429]


def test_a_forged_token_cannot_spend_someone_elses_allowance(client, db, settings):
    limited(client, settings, rate_limit_per_minute=3, rate_limit_trust_forwarded=True)
    victim = make_athlete(db)
    forged = create_access_token(
        str(victim.id), Settings(jwt_secret="someone-elses-secret")
    )
    for n in range(5):
        client.get(
            "/api/athletes/me",
            headers={
                "Authorization": f"Bearer {forged}",
                "X-Forwarded-For": f"10.1.0.{n}",
            },
        )
    # The forger was counted by address only; the real athlete is unaffected.
    response = client.get(
        "/api/athletes/me",
        headers={**athlete_auth(victim, settings), "X-Forwarded-For": "10.2.0.1"},
    )
    assert response.status_code == 200


def test_forwarded_addresses_are_ignored_unless_trusted(client, settings):
    limited(client, settings, rate_limit_per_minute=2)
    statuses = [
        client.get(
            "/api/sessions/active", headers={"X-Forwarded-For": f"10.3.0.{n}"}
        ).status_code
        for n in range(3)
    ]
    # Rotating a header an attacker controls does not buy a fresh allowance.
    assert statuses[-1] == 429


# ---------------------------------------------------------------------------
# The access log
# ---------------------------------------------------------------------------


def entries(db) -> list[AccessLog]:
    db.expire_all()
    return list(db.execute(select(AccessLog).order_by(AccessLog.created_at)).scalars())


def test_an_officials_read_is_logged_with_who_and_what(client, db, settings):
    admin = make_official(db, role=OfficialRole.sai_admin)
    client.get(
        "/api/dashboard/submissions?status=flagged", headers=token_for(admin, settings)
    )

    (entry,) = entries(db)
    assert entry.actor_id == admin.id
    assert entry.actor_role == "sai_admin"
    assert (entry.event, entry.method, entry.status_code) == ("read", "GET", 200)
    assert entry.path == "/api/dashboard/submissions"
    assert entry.detail == "status=flagged"
    assert entry.request_id


def test_a_change_and_a_refused_attempt_are_logged(client, db, settings):
    athlete = make_athlete(db)
    client.get("/api/dashboard/stats")
    client.get("/api/dashboard/stats", headers=athlete_auth(athlete, settings))
    admin = make_official(db, role=OfficialRole.sai_admin)
    client.post(
        f"/api/dashboard/sessions/{SOME_ID}/end", headers=token_for(admin, settings)
    )

    anonymous, as_athlete, change = entries(db)
    assert (anonymous.actor_id, anonymous.status_code) == (None, 401)
    # An athlete's token does not make them an official in the log either.
    assert (as_athlete.actor_id, as_athlete.actor_role) == (None, None)
    assert as_athlete.status_code in (401, 403)
    assert (change.event, change.actor_id, change.status_code) == (
        "change",
        admin.id,
        404,
    )


def test_an_athletes_own_verification_status_is_not_logged(
    client, db, settings, seeded_tests
):
    athlete = make_athlete(db)
    result = make_result(db, athlete, seeded_tests["SIT_UPS"])
    client.get(f"/api/verification/{result.id}", headers=athlete_auth(athlete, settings))
    assert entries(db) == []


def test_sign_in_attempts_are_logged_without_the_address_or_password(
    client, db, settings
):
    admin = make_official(db, role=OfficialRole.sai_admin, email="admin@sai.example")
    client.post(
        "/api/dashboard/auth/login",
        json={"email": "nobody@sai.example", "password": "guess-1"},
    )
    client.post(
        "/api/dashboard/auth/login",
        json={"email": "admin@sai.example", "password": "guess-2"},
    )
    client.post(
        "/api/dashboard/auth/login",
        json={"email": "admin@sai.example", "password": PASSWORD},
    )

    unknown, wrong, right = entries(db)
    assert unknown.event == "sign_in_failed" and unknown.actor_id is None
    assert unknown.detail.startswith("email#")
    assert (wrong.event, wrong.actor_id) == ("sign_in_failed", admin.id)
    assert (right.event, right.actor_id, right.status_code) == ("sign_in", admin.id, 200)
    logged = " ".join(str(vars(e)) for e in (unknown, wrong, right))
    assert "nobody@sai.example" not in logged
    assert "guess-" not in logged and PASSWORD not in logged


def test_a_signed_media_link_is_logged_without_the_link(client, db):
    client.get("/api/media/some-signed-token-value?x=1")

    (entry,) = entries(db)
    assert entry.event == "media"
    assert entry.path == "/api/media/…"
    assert entry.detail is None
    assert "some-signed-token-value" not in str(vars(entry))


def test_the_access_log_is_searchable_by_an_admin(client, db, settings):
    admin = make_official(db, role=OfficialRole.sai_admin)
    reviewer = make_official(db)
    client.get("/api/dashboard/stats", headers=token_for(reviewer, settings))
    client.get("/api/dashboard/tests", headers=token_for(reviewer, settings))

    body = client.get(
        f"/api/dashboard/access-log?actor_id={reviewer.id}&path_contains=stats",
        headers=token_for(admin, settings),
    ).json()

    assert body["total"] == 1
    assert body["items"][0]["path"] == "/api/dashboard/stats"
    assert body["items"][0]["actor_id"] == str(reviewer.id)


def test_a_log_failure_does_not_fail_the_request(client, db, settings):
    admin = make_official(db, role=OfficialRole.sai_admin)

    def broken():
        raise RuntimeError("database unavailable")

    client.app.state.session_factory = broken
    response = client.get("/api/dashboard/stats", headers=token_for(admin, settings))
    assert response.status_code == 200


def test_reviewing_a_decided_result_is_logged_as_a_read(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    result = make_result(
        db, make_athlete(db), seeded_tests["SIT_UPS"], status=TestResultStatus.approved
    )
    client.get(f"/api/dashboard/reviews/{result.id}", headers=token_for(admin, settings))
    (entry,) = entries(db)
    assert entry.path == f"/api/dashboard/reviews/{result.id}"
    assert entry.created_at.replace(tzinfo=UTC) <= datetime.now(UTC)


def test_a_redis_outage_costs_one_timeout_not_one_per_request(caplog):
    """Sprint 23: during an outage the counter stops asking Redis for a while."""
    from app.rate_limit import RedisCounter

    class Down:
        calls = 0

        def pipeline(self):
            Down.calls += 1
            raise ConnectionError("redis down")

    now = [1000.0]
    counter = RedisCounter("redis://unused", clock=lambda: now[0], client=Down())

    assert [counter.hit("k") for _ in range(50)] == [None] * 50
    assert Down.calls == 1
    assert sum("unavailable" in r.message for r in caplog.records) == 1

    now[0] += RedisCounter.RETRY_AFTER_SECONDS + 1
    counter.hit("k")
    assert Down.calls == 2  # tried again after the pause

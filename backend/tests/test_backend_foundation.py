"""Sprint 10: the endpoints the plan asks for that did not exist yet, and the
things a production deployment must refuse to get wrong."""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app import main as main_module
from app.config import Settings
from app.models import (
    Athlete,
    Flag,
    FlagSeverity,
    FlagSource,
    Official,
    OfficialRole,
    Test,
    TestResult,
    TestResultStatus,
    Video,
)
from app.security import create_access_token

from .test_sessions import create_session

NOW = datetime.now(UTC)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def bearer(subject, settings, role="athlete") -> dict:
    token = create_access_token(str(subject), settings, role=role)
    return {"Authorization": f"Bearer {token}"}


def make_official(db, role=OfficialRole.sai_admin, region=None) -> Official:
    official = Official(
        id=uuid.uuid4(),
        name=role.value,
        email=f"{uuid.uuid4().hex[:8]}@sai.example",
        role=role,
        region=region,
    )
    db.add(official)
    db.commit()
    return official


def make_athlete(db, region="Tamil Nadu") -> Athlete:
    athlete = Athlete(
        id=uuid.uuid4(),
        name="Another Athlete",
        dob=date(2010, 1, 1),
        gender="male",
        region=region,
        phone=uuid.uuid4().hex[:10],
    )
    db.add(athlete)
    db.commit()
    return athlete


def add_result(db, athlete, test, *, minutes_ago=0, video=False, **fields) -> TestResult:
    result = TestResult(
        id=uuid.uuid4(),
        athlete_id=athlete.id,
        test_id=test.id,
        attempt_number=fields.pop("attempt_number", 1),
        created_at=NOW - timedelta(minutes=minutes_ago),
        **fields,
    )
    db.add(result)
    if video:
        db.add(
            Video(
                test_result_id=result.id,
                s3_key=f"videos/{result.id}.mp4",
                checksum_sha256="0" * 64,
            )
        )
    db.commit()
    return result


# ---------------------------------------------------------------------------
# History and personal bests
# ---------------------------------------------------------------------------


def test_history_pages_newest_first(client, db, athlete, settings, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    ids = [
        add_result(
            db, athlete, situps, minutes_ago=m, attempt_number=5 - m,
            status=TestResultStatus.verified,
        ).id
        for m in range(5)
    ]
    add_result(db, make_athlete(db), situps)  # someone else's

    headers = bearer(athlete.id, settings)
    first = client.get("/api/athletes/me/history?limit=2", headers=headers).json()
    assert [item["result_id"] for item in first["items"]] == [str(i) for i in ids[:2]]
    assert first["next_before"]

    seen = [item["result_id"] for item in first["items"]]
    cursor = first["next_before"]
    while cursor:
        page = client.get(
            "/api/athletes/me/history", params={"limit": 2, "before": cursor},
            headers=headers,
        ).json()
        seen += [item["result_id"] for item in page["items"]]
        cursor = page["next_before"]

    assert seen == [str(i) for i in ids], "Every result exactly once, in order"


def test_history_filters_by_test(client, db, athlete, settings, seeded_tests):
    add_result(db, athlete, seeded_tests["SIT_UPS"])
    jump = add_result(db, athlete, seeded_tests["VERTICAL_JUMP"])

    body = client.get(
        "/api/athletes/me/history?test_type=vertical_jump",
        headers=bearer(athlete.id, settings),
    ).json()
    assert [item["result_id"] for item in body["items"]] == [str(jump.id)]
    assert body["next_before"] is None


def test_personal_bests_respect_timed_tests(client, db, athlete, settings):
    shuttle = Test(
        id=uuid.uuid4(), name="Shuttle run", code="SHUTTLE_RUN",
        unit="seconds", higher_is_better=False,
    )
    db.add(shuttle)
    db.commit()
    for attempt, seconds in enumerate([11.2, 10.4, 12.0], start=1):
        add_result(
            db, athlete, shuttle, attempt_number=attempt, server_score=seconds,
            status=TestResultStatus.verified, verified_at=NOW,
        )

    bests = client.get(
        "/api/athletes/me/personal-bests", headers=bearer(athlete.id, settings)
    ).json()
    assert bests == [
        {
            "test_type": "SHUTTLE_RUN",
            "unit": "seconds",
            "score": 10.4,
            "achieved_at": bests[0]["achieved_at"],
            "official": False,
        }
    ]


# ---------------------------------------------------------------------------
# Session detail
# ---------------------------------------------------------------------------


def test_an_athlete_sees_one_session_only_if_it_is_open_to_them(
    client, db, athlete, settings, seeded_tests
):
    admin = make_official(db)
    open_session = create_session(client, admin, settings)
    elsewhere = create_session(client, admin, settings, region="Kerala")
    closed = create_session(client, admin, settings, enabled=False)
    headers = bearer(athlete.id, settings)

    detail = client.get(f"/api/sessions/{open_session['id']}", headers=headers)
    assert detail.status_code == 200
    assert detail.json()["tests"][0]["submitted"] is False

    for hidden_id in (elsewhere["id"], closed["id"], uuid.uuid4()):
        response = client.get(f"/api/sessions/{hidden_id}", headers=headers)
        assert response.status_code == 404


def test_admins_see_any_session_and_reviewers_only_their_regions(
    client, db, settings, seeded_tests
):
    admin = make_official(db)
    kerala = create_session(client, admin, settings, region="Kerala")
    reviewer = make_official(db, OfficialRole.regional_reviewer, region="Tamil Nadu")

    as_admin = client.get(
        f"/api/dashboard/sessions/{kerala['id']}",
        headers=bearer(admin.id, settings, role="sai_admin"),
    )
    assert as_admin.status_code == 200 and as_admin.json()["region"] == "Kerala"

    as_reviewer = client.get(
        f"/api/dashboard/sessions/{kerala['id']}",
        headers=bearer(reviewer.id, settings, role="regional_reviewer"),
    )
    assert as_reviewer.status_code == 404


# ---------------------------------------------------------------------------
# Verification status and re-running it
# ---------------------------------------------------------------------------


def flagged_result(db, athlete, test) -> TestResult:
    result = add_result(
        db, athlete, test, status=TestResultStatus.flagged, provisional_score=30,
        server_score=22, verified_at=NOW,
    )
    db.add(
        Flag(
            test_result_id=result.id, source=FlagSource.auto,
            reason="looped_frames", detail="Frames repeat at 4.0s",
            severity=FlagSeverity.high,
        )
    )
    db.commit()
    return result


def test_the_athlete_sees_their_verification_but_not_the_cheat_checks(
    client, db, athlete, settings, seeded_tests
):
    result = flagged_result(db, athlete, seeded_tests["SIT_UPS"])

    body = client.get(
        f"/api/verification/{result.id}", headers=bearer(athlete.id, settings)
    ).json()
    assert body["status"] == "flagged"
    assert body["server_score"] == 22
    assert body["flags"] is None and body["verification_error"] is None

    stranger = make_athlete(db)
    assert client.get(
        f"/api/verification/{result.id}", headers=bearer(stranger.id, settings)
    ).status_code == 404


def test_an_official_sees_everything_within_their_region(
    client, db, athlete, settings, seeded_tests
):
    result = flagged_result(db, athlete, seeded_tests["SIT_UPS"])
    reviewer = make_official(db, OfficialRole.regional_reviewer, region="Tamil Nadu")
    outsider = make_official(db, OfficialRole.regional_reviewer, region="Kerala")

    body = client.get(
        f"/api/verification/{result.id}",
        headers=bearer(reviewer.id, settings, role="regional_reviewer"),
    ).json()
    assert [flag["reason"] for flag in body["flags"]] == ["looped_frames"]

    assert client.get(
        f"/api/verification/{result.id}",
        headers=bearer(outsider.id, settings, role="regional_reviewer"),
    ).status_code == 404


def test_a_waiting_submission_reports_how_late_it_is(
    client, db, athlete, settings, seeded_tests
):
    late = add_result(
        db, athlete, seeded_tests["SIT_UPS"], status=TestResultStatus.processing,
        minutes_ago=30,
    )
    body = client.get(
        f"/api/verification/{late.id}", headers=bearer(athlete.id, settings)
    ).json()
    assert body["waiting_seconds"] >= 30 * 60 - 5
    assert body["overdue"] is True


def test_an_admin_can_rerun_a_stuck_verification_and_nothing_else(
    client, db, athlete, settings, seeded_tests
):
    admin = make_official(db)
    reviewer = make_official(db, OfficialRole.regional_reviewer, region="Tamil Nadu")
    stuck = add_result(
        db, athlete, seeded_tests["SIT_UPS"], status=TestResultStatus.processing,
        video=True,
    )
    admin_headers = bearer(admin.id, settings, role="sai_admin")

    queued = client.post(f"/api/verification/{stuck.id}/process", headers=admin_headers)
    assert queued.status_code == 202, queued.text
    assert client.queued_verifications[-1][0] == str(stuck.id)

    assert client.post(
        f"/api/verification/{stuck.id}/process",
        headers=bearer(reviewer.id, settings, role="regional_reviewer"),
    ).status_code == 403
    assert client.post(f"/api/verification/{stuck.id}/process").status_code == 401

    decided = flagged_result(db, athlete, seeded_tests["VERTICAL_JUMP"])
    refused = client.post(
        f"/api/verification/{decided.id}/process", headers=admin_headers
    )
    assert refused.status_code == 409, "A finished verdict belongs to review"

    videoless = add_result(
        db, athlete, seeded_tests["SIT_UPS"], attempt_number=2,
        status=TestResultStatus.processing,
    )
    assert client.post(
        f"/api/verification/{videoless.id}/process", headers=admin_headers
    ).status_code == 409


# ---------------------------------------------------------------------------
# Production safety
# ---------------------------------------------------------------------------


def production(**overrides) -> Settings:
    values = {
        "environment": "production",
        "debug": False,
        "jwt_secret": "x" * 48,
        "identity_encryption_key": "k" * 44,
        "storage_backend": "s3",
        "s3_bucket": "f4all-videos",
        "sms_backend": "http",
        "sms_api_url": "https://sms.example/send",
        "public_base_url": "https://api.f4all.example",
        "cors_origins": ["https://dashboard.f4all.example"],
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_a_properly_configured_production_has_no_problems():
    assert production().production_problems() == []


@pytest.mark.parametrize(
    ("override", "problem"),
    [
        ({"jwt_secret": "dev-only-insecure-secret-change-me"}, "JWT_SECRET"),
        ({"identity_encryption_key": ""}, "IDENTITY_ENCRYPTION_KEY"),
        ({"storage_backend": "local"}, "STORAGE_BACKEND"),
        ({"sms_backend": "console"}, "SMS_BACKEND"),
        ({"debug": True}, "DEBUG"),
        ({"public_base_url": "http://api.f4all.example"}, "PUBLIC_BASE_URL"),
        ({"cors_origins": ["http://localhost:5173"]}, "CORS_ORIGINS"),
        ({"cors_origins": ["*"]}, "CORS_ORIGINS"),
    ],
)
def test_each_unsafe_production_setting_is_named(override, problem):
    problems = production(**override).production_problems()
    assert len(problems) == 1 and problem in problems[0]


def test_development_is_not_held_to_production_rules():
    assert Settings(_env_file=None, environment="development").production_problems() == []


def test_a_misconfigured_production_refuses_to_start(monkeypatch):
    monkeypatch.setattr(
        main_module, "get_settings", lambda: production(storage_backend="local")
    )
    with pytest.raises(RuntimeError, match="STORAGE_BACKEND"):
        main_module.create_app()


# ---------------------------------------------------------------------------
# Browser access and errors
# ---------------------------------------------------------------------------


def test_the_dashboard_may_send_delete_across_origins(client):
    preflight = client.options(
        "/api/dashboard/sessions/abc",
        headers={
            "Origin": "http://localhost:5173",
            "Access-Control-Request-Method": "DELETE",
        },
    )
    assert preflight.status_code == 200
    assert "DELETE" in preflight.headers["access-control-allow-methods"]


def test_an_unexpected_error_hides_the_trace_but_gives_the_request_id():
    app = main_module.create_app()

    @app.get("/boom")
    def boom():
        raise ValueError("secret internals")

    with TestClient(app, raise_server_exceptions=False) as test_client:
        response = test_client.get("/boom", headers={"X-Request-ID": "req-123"})

    assert response.status_code == 500
    assert response.json() == {"detail": "Internal server error", "request_id": "req-123"}
    assert "secret internals" not in response.text

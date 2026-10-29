"""Sprint 18: the shuttle run and the endurance run.

Three layers:

* the analyzers against synthetic runs whose true time is known;
* the shared fixtures in ``tests/fixtures/field``, which the phone's
  ``FieldParityTest`` scores too — the two implementations must agree;
* the submission endpoint: verified, flagged, rejected, idempotent, in a
  session, and stored.
"""

from __future__ import annotations

import json
import os
import re
import uuid
from dataclasses import asdict
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import select

from app import cli
from app.field_tests import Invalid, Outcome, score_endurance, score_shuttle
from app.field_tests import thresholds as T
from app.models import (
    AssessmentSession,
    Athlete,
    Flag,
    Test,
    TestResult,
    TestResultStatus,
)
from app.security import create_access_token
from app.services.athlete_results import personal_bests
from tests.field_traces import endurance_trace, shuttle_trace

FIXTURES = Path(__file__).parent / "fixtures" / "field"
REPO = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------------------
# Shuttle run
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("axis", ["z", "y", "x"])
def test_the_shuttle_time_does_not_depend_on_how_the_phone_is_carried(axis):
    truth = shuttle_trace(axis=axis)
    result = score_shuttle(truth.samples)

    assert result.outcome is Outcome.COMPLETE
    assert result.metrics["turns"] == 3
    assert abs(result.score - truth.finish_ms / 1000) <= 0.1


@pytest.mark.parametrize(
    "legs", [(2200, 2100, 2100, 2000), (2600, 2400, 2400, 2300), (3100, 2900, 2900, 2800)]
)
def test_fast_and_slow_runners_are_timed_within_a_tenth(legs):
    truth = shuttle_trace(seed=sum(legs), legs_ms=legs)
    result = score_shuttle(truth.samples)

    assert abs(result.score - truth.finish_ms / 1000) <= 0.1
    turn_times = [t for t, label, _ in result.events if label == "turn"]
    assert all(
        abs(found - real) <= 250
        for found, real in zip(turn_times, truth.turn_ms, strict=True)
    )


@pytest.mark.parametrize(
    ("kwargs", "reason"),
    [
        ({"moves_before_go": True}, Invalid.MOVED_BEFORE_START),
        ({"legs_ms": (2600, 2400, 2300)}, Invalid.WRONG_TURN_COUNT),
        ({"legs_ms": (2600, 2400, 2400, 2300, 2300)}, Invalid.WRONG_TURN_COUNT),
        ({"rate_hz": 20}, Invalid.LOW_SAMPLE_RATE),
        ({"gap_at_ms": 3000}, Invalid.SENSOR_GAP),
        ({"legs_ms": (2600, 2400, 2400, 900)}, Invalid.LEG_TOO_FAST),
        ({"still_after_ms": 300}, Invalid.NO_FINISH),
    ],
)
def test_a_shuttle_run_that_cannot_be_trusted_is_refused(kwargs, reason):
    result = score_shuttle(shuttle_trace(**kwargs).samples)
    assert result.outcome is Outcome.INVALID
    assert result.invalid is reason


def test_an_empty_shuttle_trace_is_refused():
    assert score_shuttle([]).invalid is Invalid.NOT_ENOUGH_DATA


# ---------------------------------------------------------------------------
# Endurance run
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("kwargs", "tolerance"),
    [
        ({}, 1.0),
        ({"seed": 3, "speed_mps": 3.2}, 1.0),
        ({"jumps": 3}, 1.5),
        ({"walk_from_m": 300}, 2.5),
    ],
)
def test_the_600_m_time_comes_from_gps_distance(kwargs, tolerance):
    truth = endurance_trace(**kwargs)
    result = score_endurance(truth.fixes, truth.motion)

    assert result.outcome is Outcome.COMPLETE
    assert abs(result.score - truth.finish_ms / 1000) <= tolerance
    assert result.metrics["distance_m"] == 600.0
    assert result.metrics["pace_s_per_km"] == pytest.approx(result.score / 0.6, abs=0.2)
    splits = [detail for _, label, detail in result.events if label == "split"]
    assert splits == ["100", "200", "300", "400", "500"]


@pytest.mark.parametrize(
    ("kwargs", "reason"),
    [
        ({"jumps": 12}, Invalid.GPS_UNRELIABLE),
        ({"speed_mps": 10, "step_amplitude": 0.5}, Invalid.TOO_FAST),  # a bus
        ({"speed_mps": 6, "step_amplitude": 0.8}, Invalid.NOT_ON_FOOT),  # a bicycle
        ({"distance_m": 450}, Invalid.DISTANCE_NOT_REACHED),
        ({"accuracy_m": 40}, Invalid.GPS_WEAK),
        ({"start_fix": False}, Invalid.NO_START_FIX),
    ],
)
def test_an_endurance_run_that_cannot_be_trusted_is_refused(kwargs, reason):
    truth = endurance_trace(**kwargs)
    assert score_endurance(truth.fixes, truth.motion).invalid is reason


def test_standing_still_with_gps_jitter_adds_no_distance():
    # Five minutes standing at the start line.
    truth = endurance_trace(speed_mps=0.0001, distance_m=0.03, cadence_spm=0)
    result = score_endurance(truth.fixes, truth.motion)
    assert result.invalid is Invalid.DISTANCE_NOT_REACHED
    assert result.metrics["distance_m"] < 20


# ---------------------------------------------------------------------------
# Shared fixtures: the phone scores these too
# ---------------------------------------------------------------------------

SHUTTLE_CASES = {
    "shuttle_waist_pouch": {},
    "shuttle_upright_pocket": {"axis": "y"},
    "shuttle_held_sideways": {"axis": "x", "seed": 4},
    "shuttle_fast_runner": {"seed": 7, "legs_ms": (2200, 2100, 2100, 2000)},
    "shuttle_false_start": {"moves_before_go": True},
    "shuttle_two_turns": {"legs_ms": (2600, 2400, 2300)},
}
ENDURANCE_CASES = {
    "endurance_steady_run": {},
    "endurance_run_then_walk": {"walk_from_m": 300, "seed": 5},
    "endurance_gps_jumps": {"jumps": 12, "seed": 6},
    "endurance_bicycle": {"speed_mps": 6, "step_amplitude": 0.8, "seed": 8},
    "endurance_stopped_short": {"distance_m": 450, "seed": 9},
}


def _expected(result) -> dict:
    return {
        "outcome": result.outcome.value,
        "score": result.score,
        "invalid": result.invalid.value if result.invalid else None,
        "events": [list(event) for event in result.events],
        "metrics": result.metrics,
    }


def _fixture(name: str) -> dict:
    if name in SHUTTLE_CASES:
        truth = shuttle_trace(**SHUTTLE_CASES[name])
        motion = [[s.t_ms, s.ax, s.ay, s.az, s.gx, s.gy, s.gz] for s in truth.samples]
        result = score_shuttle(truth.samples)
        return {
            "name": name,
            "test": "SHUTTLE_RUN",
            "motion": motion,
            "fixes": [],
            "expected": _expected(result),
        }
    truth = endurance_trace(**ENDURANCE_CASES[name])
    motion = [[m.t_ms, m.ax, m.ay, m.az] for m in truth.motion]
    fixes = [[f.t_ms, f.lat, f.lon, f.accuracy_m] for f in truth.fixes]
    result = score_endurance(truth.fixes, truth.motion)
    return {
        "name": name,
        "test": "ENDURANCE_RUN",
        "motion": motion,
        "fixes": fixes,
        "expected": _expected(result),
    }


@pytest.mark.parametrize("name", [*SHUTTLE_CASES, *ENDURANCE_CASES])
def test_the_shared_fixtures_match_the_python_scorer(name):
    """Regenerate with UPDATE_FIELD_FIXTURES=1, then run the phone's FieldParityTest."""
    fixture = _fixture(name)
    path = FIXTURES / f"{name}.json"
    text = json.dumps(fixture, separators=(",", ":")) + "\n"
    if os.environ.get("UPDATE_FIELD_FIXTURES"):
        FIXTURES.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    assert path.exists(), f"{path} is missing; run with UPDATE_FIELD_FIXTURES=1"
    committed = json.loads(path.read_text(encoding="utf-8"))
    assert committed["expected"] == fixture["expected"], (
        f"{name} scores differently now; regenerate with UPDATE_FIELD_FIXTURES=1 and "
        "make the Kotlin port match"
    )


def test_every_threshold_matches_the_phone():
    kotlin = (
        REPO / "mobile/app/src/main/java/com/sai/sports/fieldtest/FieldThresholds.kt"
    ).read_text(encoding="utf-8")
    found = dict(re.findall(r"const val ([A-Z_]+)(?:: \w+)? = ([0-9.]+)", kotlin))
    names = [name for name in dir(T) if name.isupper()]
    assert names, "no thresholds found"
    for name in names:
        assert name in found, f"{name} is missing from FieldThresholds.kt"
        assert float(found[name].rstrip("L")) == float(getattr(T, name)), name


# ---------------------------------------------------------------------------
# Submission
# ---------------------------------------------------------------------------


@pytest.fixture
def battery(db):
    tests = {}
    for entry in cli.TEST_BATTERY:
        test = Test(**entry)
        db.add(test)
        tests[entry["code"]] = test
    db.commit()
    return tests


def make_athlete(db, name="Asha", phone="9000000001"):
    athlete = Athlete(
        id=uuid.uuid4(),
        name=name,
        dob=date(2010, 1, 1),
        gender="female",
        region="Kerala",
        phone=phone,
    )
    db.add(athlete)
    db.commit()
    return athlete


def admin_auth(official, settings):
    token = create_access_token(str(official.id), settings, role="sai_admin")
    return {"Authorization": f"Bearer {token}"}


def auth(athlete, settings):
    token = create_access_token(str(athlete.id), settings, role="athlete")
    return {"Authorization": f"Bearer {token}"}


def shuttle_body(**overrides):
    truth = shuttle_trace(**overrides.pop("trace", {}))
    result = score_shuttle(truth.samples)
    body = {
        "test_type": "SHUTTLE_RUN",
        "client_attempt_id": f"field_{uuid.uuid4().hex[:12]}",
        "provisional_score": result.score if result.outcome is Outcome.COMPLETE else None,
        "motion": [[s.t_ms, s.ax, s.ay, s.az, s.gx, s.gy, s.gz] for s in truth.samples],
    }
    body.update(overrides)
    return body


def endurance_body(**overrides):
    truth = endurance_trace(**overrides.pop("trace", {}))
    result = score_endurance(truth.fixes, truth.motion)
    body = {
        "test_type": "ENDURANCE_RUN",
        "client_attempt_id": f"field_{uuid.uuid4().hex[:12]}",
        "provisional_score": result.score if result.outcome is Outcome.COMPLETE else None,
        "motion": [[m.t_ms, m.ax, m.ay, m.az] for m in truth.motion],
        "fixes": [[f.t_ms, f.lat, f.lon, f.accuracy_m] for f in truth.fixes],
    }
    body.update(overrides)
    return body


def submit(client, athlete, settings, body, expect=201):
    response = client.post(
        "/api/field-tests/submit", json=body, headers=auth(athlete, settings)
    )
    assert response.status_code == expect, response.text
    return response.json()


def test_an_honest_shuttle_run_is_verified_by_the_server(client, db, settings, battery):
    athlete = make_athlete(db)
    body = shuttle_body()

    answer = submit(client, athlete, settings, body)

    assert answer["status"] == "verified"
    assert answer["server_score"] == body["provisional_score"]
    result = db.get(TestResult, uuid.UUID(answer["result_id"]))
    assert result.verification_verdict == "verified"
    assert result.server_result["field"] is True
    assert result.server_result["metrics"]["turns"] == 3
    assert result.telemetry_key.startswith("field-traces/")


def test_an_honest_endurance_run_is_verified_with_its_track(
    client, db, settings, battery
):
    athlete = make_athlete(db)
    answer = submit(client, athlete, settings, endurance_body())

    assert answer["status"] == "verified"
    result = db.get(TestResult, uuid.UUID(answer["result_id"]))
    assert result.server_result["metrics"]["distance_m"] == 600.0
    assert 10 < len(result.server_result["track"]) <= 300


def test_a_claim_the_trace_does_not_support_is_flagged(client, db, settings, battery):
    athlete = make_athlete(db)
    body = shuttle_body()
    body["provisional_score"] = round(body["provisional_score"] - 1.5, 2)

    answer = submit(client, athlete, settings, body)

    assert answer["status"] == "flagged"
    [flag] = db.execute(select(Flag)).scalars().all()
    assert flag.reason == "score_discrepancy"
    assert flag.severity.value == "high"


def test_the_same_trace_submitted_again_is_flagged(client, db, settings, battery):
    first, second = (
        make_athlete(db, "Asha", "9000000001"),
        make_athlete(db, "Ravi", "9000000002"),
    )
    body = shuttle_body()
    submit(client, first, settings, body)

    replay = {**body, "client_attempt_id": "replayed_attempt"}
    answer = submit(client, second, settings, replay)

    assert answer["status"] == "flagged"
    flags = db.execute(select(Flag)).scalars().all()
    assert [flag.reason for flag in flags] == ["duplicate_trace"]


def test_a_trace_that_cannot_be_scored_is_rejected_with_its_reason(
    client, db, settings, battery
):
    athlete = make_athlete(db)
    answer = submit(
        client,
        athlete,
        settings,
        endurance_body(trace={"speed_mps": 6, "step_amplitude": 0.8}),
    )

    assert answer["status"] == "rejected"
    assert answer["invalid_reason"] == "not_on_foot"
    result = db.get(TestResult, uuid.UUID(answer["result_id"]))
    assert result.server_score is None
    # The athlete's result screen says why.
    body = client.get(f"/api/results/{result.id}", headers=auth(athlete, settings)).json()
    assert body["server_invalid_reason"] == "not_on_foot"


def test_a_retried_submission_returns_the_first_result(client, db, settings, battery):
    athlete = make_athlete(db)
    body = shuttle_body()

    first = submit(client, athlete, settings, body)
    again = submit(client, athlete, settings, body)

    assert again["result_id"] == first["result_id"]
    assert len(db.execute(select(TestResult)).scalars().all()) == 1


def test_a_field_test_counts_once_per_session(client, db, settings, battery):
    athlete = make_athlete(db)
    session = AssessmentSession(
        id=uuid.uuid4(),
        name="District trials",
        enabled=True,
        starts_at=datetime.now(UTC) - timedelta(hours=1),
        ends_at=datetime.now(UTC) + timedelta(hours=5),
        allowed_tests=["SHUTTLE_RUN"],
    )
    db.add(session)
    db.commit()
    now_ms = int(datetime.now(UTC).timestamp() * 1000)

    first = submit(
        client,
        athlete,
        settings,
        shuttle_body(session_id=str(session.id), recorded_at_ms=now_ms),
    )
    assert db.get(TestResult, uuid.UUID(first["result_id"])).session_id == session.id
    submit(
        client,
        athlete,
        settings,
        shuttle_body(session_id=str(session.id), recorded_at_ms=now_ms),
        expect=409,
    )


def test_a_session_can_offer_field_tests(client, db, settings, battery):
    from app.models import Official, OfficialRole

    admin = Official(
        id=uuid.uuid4(), name="Admin", email="a@sai.example", role=OfficialRole.sai_admin
    )
    db.add(admin)
    db.commit()
    now = datetime.now(UTC)
    created = client.post(
        "/api/dashboard/sessions",
        json={
            "name": "Track day",
            "starts_at": now.isoformat(),
            "ends_at": (now + timedelta(days=1)).isoformat(),
            "enabled": True,
            "allowed_tests": ["shuttle_run", "ENDURANCE_RUN", "SIT_UPS"],
        },
        headers=admin_auth(admin, settings),
    )
    assert created.status_code == 201, created.text
    assert created.json()["allowed_tests"] == ["SHUTTLE_RUN", "ENDURANCE_RUN", "SIT_UPS"]


@pytest.mark.parametrize(
    ("change", "code"),
    [
        ({"test_type": "SIT_UPS"}, 422),
        ({"test_type": "MARATHON"}, 422),
        ({"motion": [[0, 1, 2]]}, 422),
        ({"client_attempt_id": "has spaces"}, 422),
    ],
)
def test_bad_field_submissions_are_refused(client, db, settings, battery, change, code):
    athlete = make_athlete(db)
    submit(client, athlete, settings, shuttle_body(**change), expect=code)


def test_field_tests_need_an_athlete(client, battery):
    assert client.post("/api/field-tests/submit", json=shuttle_body()).status_code in (
        401,
        403,
    )


def test_the_fastest_time_is_the_personal_best(db, battery):
    athlete = make_athlete(db)
    shuttle = battery["SHUTTLE_RUN"]
    for attempt, seconds in enumerate([11.2, 10.4, 12.0], start=1):
        db.add(
            TestResult(
                athlete_id=athlete.id,
                test_id=shuttle.id,
                attempt_number=attempt,
                server_score=seconds,
                status=TestResultStatus.verified,
            )
        )
    db.commit()
    rows = db.execute(
        select(TestResult, Test).join(Test, Test.id == TestResult.test_id)
    ).all()
    assert personal_bests(rows)[0].score == 10.4


def test_practice_can_hold_a_field_test(client, db, settings, battery):
    athlete = make_athlete(db)
    response = client.put(
        "/api/athletes/me/practice/field_practice_1",
        json={
            "test_type": "ENDURANCE_RUN",
            "score": 182.4,
            "unit": "seconds",
            "status": "COMPLETE",
            "confidence": 1.0,
            "recorded_at_ms": int(datetime.now(UTC).timestamp() * 1000),
            "events": [],
        },
        headers=auth(athlete, settings),
    )
    assert response.status_code == 200, response.text


def test_the_dashboard_catalog_offers_the_field_tests(client, db, settings, battery):
    from app.models import Official, OfficialRole

    admin = Official(
        id=uuid.uuid4(), name="Admin", email="b@sai.example", role=OfficialRole.sai_admin
    )
    db.add(admin)
    db.commit()
    catalog = client.get(
        "/api/dashboard/tests",
        headers=admin_auth(admin, settings),
    ).json()
    by_code = {test["code"]: test for test in catalog}
    assert by_code["SHUTTLE_RUN"]["higher_is_better"] is False
    assert by_code["ENDURANCE_RUN"]["unit"] == "seconds"


def test_the_trace_is_kept_for_the_reviewer(client, db, settings, battery):
    from app.storage import get_storage

    athlete = make_athlete(db)
    answer = submit(client, athlete, settings, shuttle_body())
    result = db.get(TestResult, uuid.UUID(answer["result_id"]))
    assert get_storage(settings).exists(result.telemetry_key)


def test_a_dataclass_round_trip_keeps_the_numbers():
    # The fixtures store what the generator made, to the same 4 decimals.
    sample = shuttle_trace().samples[200]
    assert all(round(v, 4) == v for v in asdict(sample).values() if isinstance(v, float))

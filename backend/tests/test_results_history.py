"""Sprint 15: results, history, personal bests and regional benchmarks.

What the athlete reads about their own results. Every rule here is one an
athlete would notice being broken: a rejected score shown as their best, a
flagged score ranked "top 10%", a practice-looking row that was official, a
neighbouring state's norms applied to them.
"""

from __future__ import annotations

import json
import os
import uuid
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.models import (
    AssessmentSession,
    Athlete,
    Benchmark,
    Official,
    OfficialRole,
    Test,
    TestResult,
    TestResultStatus,
)
from app.security import create_access_token
from app.services import athlete_results
from app.services.benchmark_seed import BenchmarkSeedError, seed_benchmarks
from app.services.benchmarks import find_benchmark
from app.verification.analyzers import (
    AnalyzerEvent,
    AnalyzerResult,
    AttemptStatus,
    TestType,
)
from app.verification.finalization import form_faults

NOW = datetime(2026, 10, 1, 9, 0, tzinfo=UTC)


def auth(athlete, settings) -> dict:
    token = create_access_token(str(athlete.id), settings, role="athlete")
    return {"Authorization": f"Bearer {token}"}


def add_result(db, athlete, test, *, attempt, minutes=0, **fields) -> TestResult:
    result = TestResult(
        id=uuid.uuid4(),
        athlete_id=athlete.id,
        test_id=test.id,
        attempt_number=attempt,
        created_at=NOW + timedelta(minutes=minutes),
        **fields,
    )
    db.add(result)
    db.commit()
    return result


def verified(score: float, **fields) -> dict:
    return {
        "provisional_score": score,
        "server_score": score,
        "status": TestResultStatus.verified,
        **fields,
    }


def approved(score: float, **fields) -> dict:
    return {
        "provisional_score": score,
        "server_score": score,
        "final_score": score,
        "status": TestResultStatus.approved,
        **fields,
    }


@pytest.fixture
def shuttle(db) -> Test:
    """A timed test: the lowest number wins."""
    test = Test(
        id=uuid.uuid4(),
        name="Shuttle Run",
        code="SHUTTLE_RUN",
        unit="seconds",
        description="4 x 10 m shuttle run",
        higher_is_better=False,
    )
    db.add(test)
    db.commit()
    return test


@pytest.fixture
def session_row(db) -> AssessmentSession:
    session = AssessmentSession(
        id=uuid.uuid4(),
        name="District trials",
        starts_at=NOW - timedelta(days=1),
        ends_at=NOW + timedelta(days=1),
        enabled=True,
        allowed_tests=["SIT_UPS", "VERTICAL_JUMP"],
    )
    db.add(session)
    db.commit()
    return session


# ---------------------------------------------------------------------------
# Personal bests: what counts
# ---------------------------------------------------------------------------


def bests_of(db, athlete) -> dict:
    rows = db.execute(
        select(TestResult, Test)
        .join(Test, Test.id == TestResult.test_id)
        .where(TestResult.athlete_id == athlete.id)
    ).all()
    return {best.test_type: best for best in athlete_results.personal_bests(rows)}


def test_only_verified_and_approved_scores_can_be_a_best(db, athlete, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    add_result(db, athlete, situps, attempt=1, **verified(20))
    # Every one of these is higher, and none of them counts.
    add_result(
        db,
        athlete,
        situps,
        attempt=2,
        provisional_score=60,
        status=TestResultStatus.processing,
    )
    add_result(
        db,
        athlete,
        situps,
        attempt=3,
        provisional_score=55,
        status=TestResultStatus.uploaded,
    )
    add_result(
        db, athlete, situps, attempt=4, **verified(50, status=TestResultStatus.flagged)
    )
    add_result(
        db, athlete, situps, attempt=5, **verified(45, status=TestResultStatus.rejected)
    )
    add_result(
        db,
        athlete,
        situps,
        attempt=6,
        **verified(40, status=TestResultStatus.pending_sync),
    )

    best = bests_of(db, athlete)["SIT_UPS"]
    assert best.score == 20
    assert best.official is False


def test_a_flagged_result_approved_later_becomes_an_official_best(
    db, athlete, seeded_tests
):
    situps = seeded_tests["SIT_UPS"]
    add_result(db, athlete, situps, attempt=1, **verified(20))
    flagged_then_approved = add_result(db, athlete, situps, attempt=2, **approved(31))

    best = bests_of(db, athlete)["SIT_UPS"]
    assert (best.score, best.official) == (31, True)
    assert best.result_id == flagged_then_approved.id


def test_the_official_score_is_the_one_that_counts_once_approved(
    db, athlete, seeded_tests
):
    # The reviewer approved a lower number than the machine measured.
    add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        attempt=1,
        provisional_score=30,
        server_score=30,
        final_score=28,
        status=TestResultStatus.approved,
    )
    assert bests_of(db, athlete)["SIT_UPS"].score == 28


def test_a_timed_test_is_won_by_the_lowest_time(db, athlete, shuttle):
    add_result(db, athlete, shuttle, attempt=1, **verified(12.4))
    fastest = add_result(db, athlete, shuttle, attempt=2, **verified(11.1))
    add_result(db, athlete, shuttle, attempt=3, **verified(13.0))

    best = bests_of(db, athlete)["SHUTTLE_RUN"]
    assert best.score == 11.1
    assert best.result_id == fastest.id


def test_equalling_a_best_does_not_take_it(db, athlete, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    first = add_result(db, athlete, situps, attempt=1, minutes=0, **verified(25))
    add_result(db, athlete, situps, attempt=2, minutes=10, **verified(25))

    assert bests_of(db, athlete)["SIT_UPS"].result_id == first.id


def test_an_approved_result_takes_a_tied_best_from_a_verified_one(
    db, athlete, seeded_tests
):
    situps = seeded_tests["SIT_UPS"]
    add_result(db, athlete, situps, attempt=1, minutes=0, **verified(25))
    official = add_result(db, athlete, situps, attempt=2, minutes=10, **approved(25))

    best = bests_of(db, athlete)["SIT_UPS"]
    assert best.result_id == official.id
    assert best.official is True


def test_bests_are_per_test(db, athlete, seeded_tests):
    add_result(db, athlete, seeded_tests["SIT_UPS"], attempt=1, **verified(25))
    add_result(db, athlete, seeded_tests["VERTICAL_JUMP"], attempt=1, **verified(41.5))

    bests = bests_of(db, athlete)
    assert bests["SIT_UPS"].score == 25
    assert bests["VERTICAL_JUMP"].score == 41.5
    assert bests["VERTICAL_JUMP"].unit == "cm"


def test_the_personal_bests_endpoint_uses_the_same_rules(
    client, db, athlete, settings, seeded_tests
):
    situps = seeded_tests["SIT_UPS"]
    best = add_result(db, athlete, situps, attempt=1, **verified(25))
    add_result(
        db, athlete, situps, attempt=2, **verified(70, status=TestResultStatus.flagged)
    )

    body = client.get(
        "/api/athletes/me/personal-bests", headers=auth(athlete, settings)
    ).json()

    assert [(item["test_type"], item["score"]) for item in body] == [("SIT_UPS", 25)]
    assert body[0]["result_id"] == str(best.id)


# ---------------------------------------------------------------------------
# History
# ---------------------------------------------------------------------------


def test_history_names_the_session_of_an_official_attempt(
    client, db, athlete, settings, seeded_tests, session_row
):
    situps = seeded_tests["SIT_UPS"]
    add_result(db, athlete, situps, attempt=1, minutes=0, **verified(20))
    add_result(
        db,
        athlete,
        situps,
        attempt=2,
        minutes=5,
        session_id=session_row.id,
        **verified(24),
    )

    items = client.get(
        "/api/athletes/me/history", headers=auth(athlete, settings)
    ).json()["items"]

    assert [item["session_name"] for item in items] == ["District trials", None]
    assert items[0]["session_id"] == str(session_row.id)
    summary = client.get(
        "/api/athletes/me/summary", headers=auth(athlete, settings)
    ).json()
    assert summary["history"][0]["session_name"] == "District trials"


def test_history_keeps_every_status_newest_first(
    client, db, athlete, settings, seeded_tests
):
    situps = seeded_tests["SIT_UPS"]
    statuses = [
        TestResultStatus.processing,
        TestResultStatus.verified,
        TestResultStatus.flagged,
        TestResultStatus.rejected,
        TestResultStatus.approved,
    ]
    for index, status in enumerate(statuses):
        add_result(
            db,
            athlete,
            situps,
            attempt=index + 1,
            minutes=index,
            provisional_score=10 + index,
            status=status,
        )

    items = client.get(
        "/api/athletes/me/history", headers=auth(athlete, settings)
    ).json()["items"]

    assert [item["status"] for item in items] == [s.value for s in reversed(statuses)]


def test_history_pages_backwards_without_gaps_or_repeats(
    client, db, athlete, settings, seeded_tests
):
    situps = seeded_tests["SIT_UPS"]
    for index in range(5):
        add_result(
            db, athlete, situps, attempt=index + 1, minutes=index, **verified(10 + index)
        )
    headers = auth(athlete, settings)

    seen: list[float] = []
    before = None
    for _ in range(4):
        params = {"limit": 2, **({"before": before} if before else {})}
        page = client.get(
            "/api/athletes/me/history", params=params, headers=headers
        ).json()
        seen += [item["server_score"] for item in page["items"]]
        before = page["next_before"]
        if before is None:
            break

    assert seen == [14, 13, 12, 11, 10]


def test_history_never_includes_another_athletes_results(
    client, db, athlete, settings, seeded_tests
):
    other = Athlete(
        id=uuid.uuid4(),
        name="Friend",
        dob=date(2006, 1, 1),
        gender="female",
        region="Tamil Nadu",
        phone="8888888888",
    )
    db.add(other)
    db.commit()
    add_result(db, other, seeded_tests["SIT_UPS"], attempt=1, **verified(40))

    headers = auth(athlete, settings)
    assert client.get("/api/athletes/me/history", headers=headers).json()["items"] == []
    assert client.get("/api/athletes/me/personal-bests", headers=headers).json() == []


# ---------------------------------------------------------------------------
# The result screen: one call, everything it shows
# ---------------------------------------------------------------------------


def result_body(client, athlete, settings, result) -> dict:
    response = client.get(f"/api/results/{result.id}", headers=auth(athlete, settings))
    assert response.status_code == 200, response.text
    return response.json()


def test_a_result_names_its_personal_best_and_previous_attempt(
    client, db, athlete, settings, seeded_tests, session_row
):
    situps = seeded_tests["SIT_UPS"]
    jump = seeded_tests["VERTICAL_JUMP"]
    best = add_result(db, athlete, situps, attempt=1, minutes=0, **approved(30))
    add_result(db, athlete, jump, attempt=1, minutes=1, **verified(40))
    previous = add_result(
        db,
        athlete,
        situps,
        attempt=2,
        minutes=2,
        **verified(26, status=TestResultStatus.rejected),
    )
    latest = add_result(
        db,
        athlete,
        situps,
        attempt=3,
        minutes=3,
        session_id=session_row.id,
        **verified(27),
    )

    body = result_body(client, athlete, settings, latest)

    assert body["personal_best"]["result_id"] == str(best.id)
    assert body["personal_best"]["score"] == 30
    assert body["personal_best"]["official"] is True
    # The attempt before at the SAME test, whatever became of it.
    assert body["previous_attempt"]["result_id"] == str(previous.id)
    assert body["previous_attempt"]["status"] == "rejected"
    assert body["session_name"] == "District trials"
    assert body["created_at"] is not None


def test_a_first_attempt_has_no_previous_attempt(
    client, db, athlete, settings, seeded_tests
):
    only = add_result(db, athlete, seeded_tests["SIT_UPS"], attempt=1, **verified(22))

    body = result_body(client, athlete, settings, only)

    assert body["previous_attempt"] is None
    # It is its own best.
    assert body["personal_best"]["result_id"] == str(only.id)


def test_a_result_with_nothing_counting_has_no_best(
    client, db, athlete, settings, seeded_tests
):
    pending = add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        attempt=1,
        provisional_score=40,
        status=TestResultStatus.processing,
    )

    assert result_body(client, athlete, settings, pending)["personal_best"] is None


def server_snapshot(form_score=None, faults=None, status="COMPLETE", invalid_reason=None):
    snapshot = {
        "authoritative": True,
        "status": status,
        "score": 5.0,
        "form_score": form_score,
        "invalid_reason": invalid_reason,
    }
    if faults is not None:
        snapshot["form_faults"] = faults
    return snapshot


def test_the_servers_form_verdict_reaches_the_athlete(
    client, db, athlete, settings, seeded_tests
):
    faults = [{"code": "KNEE_PAST_TOES", "reps_affected": 2}]
    result = add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        attempt=1,
        server_result=server_snapshot(form_score=60, faults=faults),
        **verified(5),
    )

    body = result_body(client, athlete, settings, result)

    assert body["server_form"] == {"score": 60, "faults": faults}
    verification = client.get(
        f"/api/verification/{result.id}", headers=auth(athlete, settings)
    ).json()
    assert verification["server_form"] == body["server_form"]


def test_an_older_result_has_a_form_score_without_a_fault_list(
    client, db, athlete, settings, seeded_tests
):
    # Verified before the breakdown was recorded: no list, not an empty one.
    result = add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        attempt=1,
        server_result=server_snapshot(form_score=80),
        **verified(5),
    )

    assert result_body(client, athlete, settings, result)["server_form"] == {
        "score": 80,
        "faults": None,
    }


def test_no_form_verdict_before_the_server_has_measured(
    client, db, athlete, settings, seeded_tests
):
    result = add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        attempt=1,
        provisional_score=5,
        status=TestResultStatus.processing,
        server_result=server_snapshot(form_score=60, faults=[]),
    )

    assert result_body(client, athlete, settings, result)["server_form"] is None


def test_the_reason_the_server_could_not_score_is_given(
    client, db, athlete, settings, seeded_tests
):
    result = add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        attempt=1,
        provisional_score=12,
        status=TestResultStatus.rejected,
        server_result=server_snapshot(
            status="INVALID", invalid_reason="No usable pose data"
        ),
    )

    body = result_body(client, athlete, settings, result)

    assert body["server_invalid_reason"] == "No usable pose data"
    assert body["server_form"] is None


# ---------------------------------------------------------------------------
# Form faults, as the worker records them
# ---------------------------------------------------------------------------


def analyzer_result(events, test_type=TestType.SQUATS) -> AnalyzerResult:
    return AnalyzerResult(
        test_type=test_type,
        score=3.0,
        unit=test_type.unit,
        status=AttemptStatus.COMPLETE,
        confidence=0.9,
        frames_analyzed=100,
        frames_rejected=0,
        events=events,
    )


def test_form_faults_count_reps_not_warnings():
    events = [
        AnalyzerEvent(100, "rep_counted"),
        AnalyzerEvent(100, "form_warning", "knee_past_toes"),
        AnalyzerEvent(100, "form_warning", "torso_lean"),
        AnalyzerEvent(200, "rep_counted"),
        AnalyzerEvent(200, "form_warning", "knee_past_toes"),
        AnalyzerEvent(300, "rep_rejected_form", "stance_too_narrow,knee_past_toes"),
        AnalyzerEvent(400, "rep_counted"),
    ]

    # In the analyzers' report order, which is FormIssue's declaration order.
    assert form_faults(analyzer_result(events)) == [
        {"code": "TORSO_LEAN", "reps_affected": 1},
        {"code": "STANCE_TOO_NARROW", "reps_affected": 1},
        {"code": "KNEE_PAST_TOES", "reps_affected": 3},
    ]


def test_clean_reps_record_an_empty_fault_list():
    events = [AnalyzerEvent(100, "rep_counted"), AnalyzerEvent(200, "rep_counted")]
    assert form_faults(analyzer_result(events)) == []


def test_a_jump_has_no_form_faults():
    events = [AnalyzerEvent(100, "jump_measured", "41.0")]
    assert form_faults(analyzer_result(events, TestType.VERTICAL_JUMP)) is None


# ---------------------------------------------------------------------------
# Benchmarks: only for results that count, and from the right region
# ---------------------------------------------------------------------------


@pytest.fixture
def benchmarked(db, seeded_tests):
    seed_benchmarks(db)
    return seeded_tests


@pytest.mark.parametrize(
    ("status", "benchmarked_for_athlete"),
    [
        (TestResultStatus.verified, True),
        (TestResultStatus.approved, True),
        (TestResultStatus.flagged, False),
        (TestResultStatus.rejected, False),
        (TestResultStatus.pending_sync, False),
    ],
)
def test_only_a_counting_result_is_benchmarked_for_the_athlete(
    client, db, athlete, settings, benchmarked, status, benchmarked_for_athlete
):
    result = add_result(
        db,
        athlete,
        benchmarked["SIT_UPS"],
        attempt=1,
        provisional_score=40,
        server_score=40,
        final_score=40 if status is TestResultStatus.approved else None,
        status=status,
    )

    body = result_body(client, athlete, settings, result)

    assert (body["benchmark"] is not None) is benchmarked_for_athlete
    assert (body["benchmark_unavailable"] is None) is benchmarked_for_athlete


def test_a_reviewer_still_sees_the_benchmark_of_a_flagged_result(
    client, db, athlete, settings, benchmarked
):
    official = Official(
        id=uuid.uuid4(),
        name="Admin",
        email="admin@sai.example",
        role=OfficialRole.sai_admin,
    )
    db.add(official)
    db.commit()
    result = add_result(
        db,
        athlete,
        benchmarked["SIT_UPS"],
        attempt=1,
        **verified(40, status=TestResultStatus.flagged),
    )
    token = create_access_token(str(official.id), settings, role="sai_admin")

    detail = client.get(
        f"/api/dashboard/reviews/{result.id}",
        headers={"Authorization": f"Bearer {token}"},
    ).json()

    assert detail["benchmark"] is not None


def add_benchmark(
    db, test, *, region=None, p50=30.0, source="PROVISIONAL test"
) -> Benchmark:
    row = Benchmark(
        id=uuid.uuid4(),
        test_id=test.id,
        gender="female",
        age_min=14,
        age_max=25,
        percentile_50=p50,
        percentile_75=p50 + 5,
        percentile_90=p50 + 10,
        region=region,
        source=source,
    )
    db.add(row)
    db.commit()
    return row


def test_the_athletes_own_region_wins_over_all_india(db, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    add_benchmark(db, situps, region=None, p50=30)
    tamil_nadu = add_benchmark(db, situps, region="Tamil Nadu", p50=28)
    add_benchmark(db, situps, region="Kerala", p50=26)

    found = find_benchmark(
        db, test_id=situps.id, gender="female", age_years=16, region="Tamil Nadu"
    )
    assert found.id == tamil_nadu.id


def test_all_india_norms_apply_where_a_state_has_none(db, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    national = add_benchmark(db, situps, region=None)
    add_benchmark(db, situps, region="Kerala", p50=26)

    found = find_benchmark(
        db, test_id=situps.id, gender="female", age_years=16, region="Punjab"
    )
    assert found.id == national.id


def test_another_states_norms_never_apply(db, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    add_benchmark(db, situps, region="Kerala")

    assert (
        find_benchmark(
            db, test_id=situps.id, gender="female", age_years=16, region="Punjab"
        )
        is None
    )


def test_the_result_says_whose_norms_were_used(
    client, db, athlete, settings, seeded_tests
):
    situps = seeded_tests["SIT_UPS"]
    add_benchmark(db, situps, region=None)
    add_benchmark(db, situps, region="Tamil Nadu")
    result = add_result(db, athlete, situps, attempt=1, **verified(33))

    benchmark = result_body(client, athlete, settings, result)["benchmark"]

    # The fixture athlete is registered in Tamil Nadu.
    assert benchmark["region"] == "Tamil Nadu"
    assert benchmark["provisional"] is True


def test_all_india_norms_say_so(client, db, athlete, settings, benchmarked):
    result = add_result(db, athlete, benchmarked["SIT_UPS"], attempt=1, **verified(33))

    assert result_body(client, athlete, settings, result)["benchmark"]["region"] is None


def test_one_cohort_has_at_most_one_all_india_row(db, seeded_tests):
    add_benchmark(db, seeded_tests["SIT_UPS"], region=None)
    with pytest.raises(IntegrityError):
        add_benchmark(db, seeded_tests["SIT_UPS"], region=None)
    db.rollback()


def test_one_cohort_has_at_most_one_row_per_region(db, seeded_tests):
    add_benchmark(db, seeded_tests["SIT_UPS"], region="Kerala")
    with pytest.raises(IntegrityError):
        add_benchmark(db, seeded_tests["SIT_UPS"], region="Kerala")
    db.rollback()


def write_csv(tmp_path, *rows: str):
    path = tmp_path / "norms.csv"
    path.write_text(
        "test_code,gender,age_min,age_max,p50,p75,p90,source,region\n"
        + "\n".join(rows)
        + "\n",
        encoding="utf-8",
    )
    return path


def test_regional_rows_load_beside_all_india_ones(db, seeded_tests, tmp_path):
    path = write_csv(
        tmp_path,
        "SIT_UPS,female,14,15,26,32,38,PROVISIONAL all-India,",
        "SIT_UPS,female,14,15,24,30,36,PROVISIONAL state,tamil nadu",
    )

    first = seed_benchmarks(db, path=path)
    again = seed_benchmarks(db, path=path)

    assert (first.inserted, again.inserted, again.updated) == (2, 0, 2)
    regions = sorted(str(row.region) for row in db.scalars(select(Benchmark)))
    # Stored in the canonical spelling athletes' regions use.
    assert regions == ["None", "Tamil Nadu"]


def test_a_region_that_is_not_a_state_is_refused(db, seeded_tests, tmp_path):
    path = write_csv(tmp_path, "SIT_UPS,female,14,15,26,32,38,PROVISIONAL,Atlantis")

    with pytest.raises(BenchmarkSeedError, match="Atlantis"):
        seed_benchmarks(db, path=path)


# ---------------------------------------------------------------------------
# The phone reads exactly what the server writes
# ---------------------------------------------------------------------------

API_FIXTURES = Path(__file__).parent / "fixtures" / "api"


def check_fixture(name: str, body: dict) -> None:
    """Compare a response with its committed fixture, which the phone's tests parse.

    `mobile/.../api/ResultContractTest.kt` reads these files, so the field
    names and values the result and history screens rely on are checked on
    both sides of the wire. Regenerate with UPDATE_API_FIXTURES=1.
    """
    path = API_FIXTURES / name
    text = json.dumps(body, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if os.environ.get("UPDATE_API_FIXTURES"):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    assert path.exists(), f"{path} is missing; run with UPDATE_API_FIXTURES=1"
    assert path.read_text(encoding="utf-8") == text, (
        f"{name} no longer matches the API; run with UPDATE_API_FIXTURES=1 and "
        "update ResultContractTest.kt to match"
    )


def fixed_id(number: int) -> uuid.UUID:
    return uuid.UUID(int=number)


def test_the_result_and_history_fixtures_match_the_api(
    client, db, settings, seeded_tests
):
    athlete = Athlete(
        id=fixed_id(1),
        name="Contract Athlete",
        dob=date(2010, 5, 5),
        gender="female",
        region="Tamil Nadu",
        phone="7777777777",
        height_cm=160,
    )
    session = AssessmentSession(
        id=fixed_id(2),
        name="District trials",
        starts_at=NOW,
        ends_at=NOW + timedelta(days=1),
        enabled=True,
        allowed_tests=["SIT_UPS"],
    )
    db.add_all([athlete, session])
    db.commit()
    situps = seeded_tests["SIT_UPS"]
    # Wide enough that the athlete's age on the day the test runs never matters.
    db.add(
        Benchmark(
            id=fixed_id(3),
            test_id=situps.id,
            gender="female",
            age_min=9,
            age_max=40,
            percentile_50=20,
            percentile_75=26,
            percentile_90=32,
            region="Tamil Nadu",
            source="PROVISIONAL contract fixture",
        )
    )
    db.commit()

    best = add_result(
        db,
        athlete,
        situps,
        attempt=1,
        minutes=0,
        verified_at=NOW + timedelta(minutes=3),
        **approved(30),
    )
    best.id = fixed_id(10)
    previous = add_result(
        db,
        athlete,
        situps,
        attempt=2,
        minutes=60,
        **verified(18, status=TestResultStatus.rejected),
    )
    previous.id = fixed_id(11)
    latest = add_result(
        db,
        athlete,
        situps,
        attempt=3,
        minutes=120,
        session_id=session.id,
        verified_at=NOW + timedelta(minutes=125),
        server_result=server_snapshot(
            form_score=75,
            faults=[{"code": "TORSO_LEAN", "reps_affected": 2}],
        ),
        **verified(24, provisional_score=25),
    )
    latest.id = fixed_id(12)
    db.commit()

    result = client.get(f"/api/results/{latest.id}", headers=auth(athlete, settings))
    history = client.get(
        "/api/athletes/me/history?limit=2", headers=auth(athlete, settings)
    )
    assert result.status_code == history.status_code == 200

    check_fixture("result_sprint15.json", result.json())
    check_fixture("history_sprint15.json", history.json())

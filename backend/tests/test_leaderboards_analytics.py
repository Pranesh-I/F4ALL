"""Sprint 16: leaderboard filters and analytics for officials.

The athlete leaderboard gains age band, state, session and paging filters; the
privacy rules (opt-in, first name and initial, you always see yourself) must
hold under every one of them. Analytics must be real counts from the database,
scoped to the official's region, with empty filters giving zeros rather than
errors.
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import event

from app.models import (
    AssessmentSession,
    Athlete,
    Official,
    OfficialRole,
    TestResult,
    TestResultStatus,
    UploadSession,
)
from app.routers.analytics import _histogram
from app.security import create_access_token

TODAY = date.today()


def years_ago(years: int, days: int = 30) -> date:
    """A birthday `years` years and a month ago: aged exactly `years` today."""
    return date(TODAY.year - years, TODAY.month, 1) - timedelta(days=days)


def bearer(subject, settings, role="athlete") -> dict:
    token = create_access_token(str(subject), settings, role=role)
    return {"Authorization": f"Bearer {token}"}


def make_athlete(db, name, *, region="Kerala", gender="female", age=15, opted_in=True):
    athlete = Athlete(
        id=uuid.uuid4(),
        name=name,
        dob=years_ago(age),
        gender=gender,
        region=region,
        phone=uuid.uuid4().hex[:10],
        leaderboard_opt_in=opted_in,
    )
    db.add(athlete)
    db.commit()
    return athlete


def add_result(
    db,
    athlete,
    test,
    score,
    *,
    attempt=1,
    status=TestResultStatus.approved,
    session=None,
    created_at=None,
    **fields,
):
    fields.setdefault("verified_at", datetime.now(UTC))
    result = TestResult(
        id=uuid.uuid4(),
        athlete_id=athlete.id,
        test_id=test.id,
        attempt_number=attempt,
        provisional_score=score,
        server_score=score,
        final_score=score if status is TestResultStatus.approved else None,
        status=status,
        session_id=session.id if session else None,
        created_at=created_at or datetime.now(UTC),
        **fields,
    )
    db.add(result)
    db.commit()
    return result


def make_session(db, name="District trials"):
    session = AssessmentSession(
        id=uuid.uuid4(),
        name=name,
        starts_at=datetime.now(UTC) - timedelta(days=1),
        ends_at=datetime.now(UTC) + timedelta(days=1),
        enabled=True,
        allowed_tests=["SIT_UPS", "VERTICAL_JUMP"],
    )
    db.add(session)
    db.commit()
    return session


def make_official(db, role=OfficialRole.sai_admin, region=None):
    official = Official(
        id=uuid.uuid4(),
        name="Official",
        email=f"{uuid.uuid4().hex[:8]}@sai.example",
        role=role,
        region=region,
    )
    db.add(official)
    db.commit()
    return official


def as_official(official, settings):
    return bearer(official.id, settings, role=official.role.value)


# ---------------------------------------------------------------------------
# Athlete leaderboard filters
# ---------------------------------------------------------------------------


@pytest.fixture
def board(db, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    viewer = make_athlete(db, "Anjali Menon", opted_in=False)
    for name, score in [("Asha Pillai", 44), ("Meera Nair", 25), ("Lakshmi Iyer", 33)]:
        add_result(db, make_athlete(db, name), situps, score)
    add_result(db, make_athlete(db, "Hidden Person", opted_in=False), situps, 50)
    add_result(db, make_athlete(db, "Divya Rao", region="Karnataka"), situps, 60)
    add_result(db, make_athlete(db, "Senior Athlete", age=20), situps, 80)
    add_result(db, viewer, situps, 30)
    return viewer


def get_board(client, settings, viewer, query=""):
    response = client.get(
        f"/api/athletes/leaderboard/SIT_UPS{query}", headers=bearer(viewer.id, settings)
    )
    assert response.status_code == 200, response.text
    return response.json()


def names(body):
    return [entry["display_name"] for entry in body["entries"]]


def test_the_default_board_is_unchanged(client, settings, board):
    body = get_board(client, settings, board)

    assert names(body) == ["Asha P.", "Lakshmi I.", "Meera N."]
    assert body["age_band"] == "14-15"
    assert body["you"]["rank"] == 3


def test_any_age_band_or_all_ages_can_be_chosen(client, settings, board):
    older = get_board(client, settings, board, "?age_band=18-25")
    assert names(older) == ["Senior A."]
    # The viewer is 15: not on the 18-25 board.
    assert older["you"] is None
    assert older["age_band"] == "18-25"

    everyone = get_board(client, settings, board, "?age_band=all")
    assert names(everyone)[0] == "Senior A."
    assert everyone["age_band"] is None
    assert everyone["cohort"] == "female, all ages"


def test_another_state_can_be_chosen(client, settings, board):
    body = get_board(client, settings, board, "?region=karnataka")

    assert names(body) == ["Divya R."]
    assert body["region"] == "Karnataka"
    assert body["scope"] == "region"
    # The viewer is from Kerala.
    assert body["you"] is None


def test_a_session_board_counts_only_results_from_that_session(
    client, db, settings, board, seeded_tests
):
    session = make_session(db)
    situps = seeded_tests["SIT_UPS"]
    nila = make_athlete(db, "Nila Varma")
    add_result(db, nila, situps, 21, session=session)
    add_result(db, board, situps, 19, attempt=2, session=session)

    body = get_board(client, settings, board, f"?session_id={session.id}")

    assert names(body) == ["Nila V."]
    assert body["session_name"] == "District trials"
    # The viewer's session score, not their overall best of 30.
    assert body["you"] == {"rank": 2, "score": 19.0, "visible_to_others": False}


def test_equal_scores_share_a_rank(client, db, settings, board, seeded_tests):
    add_result(db, make_athlete(db, "Zoya Khan"), seeded_tests["SIT_UPS"], 33)

    body = get_board(client, settings, board)

    ranks = {entry["display_name"]: entry["rank"] for entry in body["entries"]}
    assert ranks["Lakshmi I."] == ranks["Zoya K."] == 2
    assert ranks["Meera N."] == 4
    # 44, 33, 33 are ahead of the viewer's 30.
    assert body["you"]["rank"] == 4


def test_pages_continue_the_ranking(client, settings, board):
    first = get_board(client, settings, board, "?limit=2")
    second = get_board(client, settings, board, "?limit=2&offset=2")

    assert [e["rank"] for e in first["entries"]] == [1, 2]
    assert [e["rank"] for e in second["entries"]] == [3]
    assert first["total_ranked"] == second["total_ranked"] == 3
    assert second["offset"] == 2


@pytest.mark.parametrize(
    "query", ["", "?age_band=all", "?scope=national", "?region=Karnataka", "?limit=1"]
)
def test_privacy_holds_under_every_filter(client, settings, board, query):
    body = get_board(client, settings, board, query)

    assert "Hidden P." not in names(body)
    for entry in body["entries"]:
        assert set(entry) == {"rank", "display_name", "region", "score", "is_you"}


@pytest.mark.parametrize(
    ("query", "code"),
    [
        ("?age_band=7-8", 422),
        ("?age_band=old", 422),
        ("?region=Atlantis", 422),
        ("?limit=0", 422),
        (f"?session_id={uuid.uuid4()}", 404),
    ],
)
def test_bad_filters_are_refused(client, settings, board, query, code):
    response = client.get(
        f"/api/athletes/leaderboard/SIT_UPS{query}", headers=bearer(board.id, settings)
    )
    assert response.status_code == code


def test_the_options_list_the_athletes_own_sessions_only(
    client, db, settings, board, seeded_tests
):
    mine = make_session(db, "My trial")
    make_session(db, "Someone else's trial")
    add_result(db, board, seeded_tests["SIT_UPS"], 22, attempt=2, session=mine)

    body = client.get(
        "/api/athletes/leaderboard-options", headers=bearer(board.id, settings)
    ).json()

    assert body["your_age_band"] == "14-15"
    assert body["your_region"] == "Kerala"
    assert "18-25" in body["age_bands"]
    assert [s["name"] for s in body["sessions"]] == ["My trial"]


def test_a_national_board_costs_the_same_queries_however_many_athletes(
    client, db, settings, seeded_tests, db_session_factory
):
    situps = seeded_tests["SIT_UPS"]
    viewer = make_athlete(db, "Viewer Person")
    add_result(db, viewer, situps, 10)

    def queries_for_board() -> int:
        engine = db_session_factory.kw["bind"]
        statements: list[str] = []

        def record(*args):
            statements.append(args[2])

        event.listen(engine, "before_cursor_execute", record)
        try:
            get_board(client, settings, viewer, "?scope=national")
        finally:
            event.remove(engine, "before_cursor_execute", record)
        return len(statements)

    for index in range(3):
        add_result(db, make_athlete(db, f"Early {index}"), situps, 20 + index)
    few = queries_for_board()
    for index in range(40):
        add_result(db, make_athlete(db, f"Later {index}"), situps, 30 + index)
    many = queries_for_board()

    assert many == few


def test_the_officials_leaderboard_filters_by_session(client, db, settings, seeded_tests):
    session = make_session(db)
    situps = seeded_tests["SIT_UPS"]
    add_result(db, make_athlete(db, "In Session"), situps, 30, session=session)
    add_result(db, make_athlete(db, "Outside Session"), situps, 40)
    admin = make_official(db)

    body = client.get(
        f"/api/dashboard/leaderboard?test_type=SIT_UPS&session_id={session.id}",
        headers=as_official(admin, settings),
    ).json()

    assert [entry["athlete_name"] for entry in body["entries"]] == ["In Session"]


# ---------------------------------------------------------------------------
# Analytics
# ---------------------------------------------------------------------------


NOW = datetime.now(UTC).replace(microsecond=0)


def upload(db, athlete, *, completed, expires_in_hours, test_code="SIT_UPS"):
    db.add(
        UploadSession(
            id=uuid.uuid4().hex,
            athlete_id=athlete.id,
            test_code=test_code,
            file_size_bytes=1000,
            chunk_size_bytes=500,
            total_chunks=2,
            checksum_sha256="0" * 64,
            content_type="video/mp4",
            staging_path="unused",
            completed=completed,
            created_at=NOW - timedelta(hours=2),
            expires_at=NOW + timedelta(hours=expires_in_hours),
        )
    )
    db.commit()


@pytest.fixture
def dataset(db, seeded_tests):
    """A small, fully known picture: two regions, two tests, every status."""
    situps, jump = seeded_tests["SIT_UPS"], seeded_tests["VERTICAL_JUMP"]
    trial = make_session(db)
    kerala = [make_athlete(db, f"Kerala {i}") for i in range(3)]
    karnataka = make_athlete(db, "Karnataka 0", region="Karnataka")
    make_athlete(db, "Registered Only")  # Kerala, never submitted

    def at(days):
        return NOW - timedelta(days=days)

    timing = {"processing_started_at": NOW, "processing_duration_ms": 40_000}
    add_result(
        db,
        kerala[0],
        situps,
        30,
        created_at=at(1),
        session=trial,
        verified_at=at(1) + timedelta(seconds=60),
        **timing,
    )
    add_result(
        db,
        kerala[0],
        situps,
        34,
        attempt=2,
        created_at=at(1),
        status=TestResultStatus.verified,
        verified_at=at(1) + timedelta(seconds=120),
        **timing,
    )
    add_result(
        db, kerala[1], situps, 99, created_at=at(2), status=TestResultStatus.flagged
    )
    add_result(
        db,
        kerala[2],
        situps,
        5,
        created_at=at(2),
        status=TestResultStatus.rejected,
        verification_verdict="rejected",
    )
    add_result(db, kerala[2], jump, 40.0, created_at=at(3), session=trial)
    add_result(
        db, karnataka, situps, 20, created_at=at(5), status=TestResultStatus.processing
    )

    upload(db, kerala[0], completed=True, expires_in_hours=10)
    upload(db, kerala[1], completed=False, expires_in_hours=-1)  # abandoned
    upload(db, kerala[2], completed=False, expires_in_hours=5)  # still going
    upload(db, karnataka, completed=False, expires_in_hours=-1)
    return {"session": trial, "situps": situps, "jump": jump}


def get_analytics(client, settings, official, query="", expect=200):
    response = client.get(
        f"/api/dashboard/analytics{query}", headers=as_official(official, settings)
    )
    assert response.status_code == expect, response.text
    return response.json()


def test_totals_and_statuses_are_real_counts(client, db, settings, dataset):
    body = get_analytics(client, settings, make_official(db))

    assert body["totals"] == {
        "athletes": 5,  # 3 in Kerala, 1 in Karnataka, and one who never submitted
        "active_athletes": 4,
        "submissions": 6,
        "verified": 1,
        "approved": 2,
        "flagged": 1,
        "rejected": 1,
        "awaiting_verification": 1,
        "resubmission_requested": 0,
    }
    assert body["by_status"]["processing"] == 1
    assert sum(body["by_status"].values()) == 6


def test_averages_use_only_results_that_count(client, db, settings, dataset):
    tests = {
        t["test_type"]: t
        for t in get_analytics(client, settings, make_official(db))["tests"]
    }

    situps = tests["SIT_UPS"]
    # Approved 30 and verified 34. The flagged 99, rejected 5 and processing 20
    # are left out.
    assert situps["average_score"] == 32.0
    assert (situps["submissions"], situps["athletes"], situps["counted"]) == (5, 4, 2)
    assert tests["VERTICAL_JUMP"]["average_score"] == 40.0
    assert tests["VERTICAL_JUMP"]["unit"] == "cm"


def test_regions_and_sessions(client, db, settings, dataset):
    body = get_analytics(client, settings, make_official(db))

    regions = {r["region"]: r for r in body["regions"]}
    assert regions["Kerala"] == {
        "region": "Kerala",
        "athletes": 3,
        "submissions": 5,
        "counted": 3,
        "flagged": 1,
        "rejected": 1,
    }
    assert regions["Karnataka"]["submissions"] == 1
    assert body["sessions"] == [
        {
            "session_id": str(dataset["session"].id),
            "name": "District trials",
            "submissions": 2,
            "athletes": 2,
            "counted": 2,
        }
    ]
    assert body["outside_sessions"] == 4


def test_upload_failures(client, db, settings, dataset):
    uploads = get_analytics(client, settings, make_official(db))["uploads"]

    assert uploads == {
        "started": 4,
        "completed": 1,
        "in_progress": 1,
        "abandoned": 2,
        "rejected_by_verification": 1,
    }


def test_verification_time_is_measured(client, db, settings, dataset):
    timing = get_analytics(client, settings, make_official(db))["verification_time"]

    assert timing["measured"] == 2
    assert timing["turnaround_p50_seconds"] == 60
    assert timing["turnaround_p95_seconds"] == 120
    assert timing["processing_p50_seconds"] == 40
    assert timing["within_sla"] == 2


def test_participation_has_a_point_for_every_day(client, db, settings, dataset):
    start = (NOW - timedelta(days=6)).isoformat()
    body = get_analytics(
        client,
        settings,
        make_official(db),
        f"?submitted_from={start.replace('+', '%2B')}",
    )

    assert body["bucket"] == "day"
    points = body["participation"]
    assert len(points) == 7
    assert sum(point["submissions"] for point in points) == 6
    assert sum(point["counted"] for point in points) == 3
    assert any(point["submissions"] == 0 for point in points)


def test_a_long_window_is_bucketed_by_week(client, db, settings, dataset):
    start = (NOW - timedelta(days=200)).isoformat().replace("+", "%2B")
    body = get_analytics(client, settings, make_official(db), f"?submitted_from={start}")

    assert body["bucket"] == "week"
    starts = [date.fromisoformat(point["start"]) for point in body["participation"]]
    assert all(day.weekday() == 0 for day in starts)
    assert sum(point["submissions"] for point in body["participation"]) == 6


def test_filters_narrow_every_metric(client, db, settings, dataset):
    admin = make_official(db)

    by_test = get_analytics(client, settings, admin, "?test_type=VERTICAL_JUMP")
    assert by_test["totals"]["submissions"] == 1
    assert [t["test_type"] for t in by_test["tests"]] == ["VERTICAL_JUMP"]

    by_session = get_analytics(
        client, settings, admin, f"?session_id={dataset['session'].id}"
    )
    assert by_session["totals"]["submissions"] == 2
    assert by_session["outside_sessions"] == 0

    recent = (NOW - timedelta(days=1, hours=12)).isoformat().replace("+", "%2B")
    by_date = get_analytics(client, settings, admin, f"?submitted_from={recent}")
    assert by_date["totals"]["submissions"] == 2


def test_the_score_distribution_needs_a_test(client, db, settings, dataset):
    admin = make_official(db)
    assert get_analytics(client, settings, admin)["score_distribution"] is None

    bins = get_analytics(client, settings, admin, "?test_type=SIT_UPS")[
        "score_distribution"
    ]
    # The two counting sit-up results, 30 and 34.
    assert sum(b["count"] for b in bins) == 2
    assert bins[0]["low"] == 30 and bins[-1]["high"] >= 34


def test_a_regional_reviewer_sees_only_their_region(client, db, settings, dataset):
    reviewer = make_official(db, OfficialRole.regional_reviewer, region="Karnataka")

    body = get_analytics(client, settings, reviewer)

    assert body["totals"]["submissions"] == 1
    assert body["totals"]["athletes"] == 1
    assert [r["region"] for r in body["regions"]] == ["Karnataka"]
    assert body["uploads"]["started"] == 1
    assert body["filters"]["region"] == "Karnataka"
    get_analytics(client, settings, reviewer, "?region=Kerala", expect=403)


def test_a_reviewer_without_a_region_sees_nothing(client, db, settings, dataset):
    body = get_analytics(
        client, settings, make_official(db, OfficialRole.regional_reviewer, region=None)
    )
    assert body["totals"]["submissions"] == 0
    assert body["totals"]["athletes"] == 0


def test_analytics_are_for_officials_only(client, db, settings, dataset):
    athlete = make_athlete(db, "Curious Athlete")
    assert client.get("/api/dashboard/analytics").status_code == 401
    assert (
        client.get(
            "/api/dashboard/analytics", headers=bearer(athlete.id, settings)
        ).status_code
        == 403
    )


def test_no_data_gives_zeros_not_errors(client, db, settings, seeded_tests):
    body = get_analytics(client, settings, make_official(db), "?test_type=SIT_UPS")

    assert body["totals"]["submissions"] == 0
    assert body["tests"] == [] and body["regions"] == [] and body["sessions"] == []
    assert body["verification_time"]["turnaround_p50_seconds"] is None
    assert body["score_distribution"] == []
    assert body["participation"] == []


@pytest.mark.parametrize(
    ("query", "code"),
    [
        ("?test_type=NOPE", 404),
        (f"?session_id={uuid.uuid4()}", 404),
        ("?region=Atlantis", 422),
        ("?submitted_from=2026-05-02T00:00:00Z&submitted_to=2026-05-01T00:00:00Z", 422),
    ],
)
def test_bad_analytics_filters_are_refused(
    client, db, settings, seeded_tests, query, code
):
    get_analytics(client, settings, make_official(db), query, expect=code)


def test_histogram_bins_counts_as_whole_ranges():
    # 10 to 30 is 21 counts: ten bins would need a width of 2.1, so 3 it is.
    bins = _histogram([10, 11, 19, 30], integral=True)

    assert (bins[0].low, bins[0].high, bins[0].count) == (10, 12, 2)
    assert all(b.high - b.low == 2 for b in bins)
    assert sum(b.count for b in bins) == 4
    assert bins[-1].low <= 30 <= bins[-1].high


def test_histogram_of_measurements_includes_the_top_value():
    bins = _histogram([20.0, 25.5, 40.0], integral=False)

    assert len(bins) == 10
    assert bins[-1].count == 1 and bins[0].count == 1
    assert _histogram([30.0, 30.0], integral=False)[0].count == 2
    assert _histogram([], integral=True) == []


def test_percentiles_are_nearest_rank_like_postgres():
    """ceil(p * n)-th value, the definition Postgres's percentile_disc uses."""
    from app.routers.health import _percentile

    values = [float(v) for v in range(1, 2778)]  # n = 2777, as in the Postgres check
    assert _percentile(values, 0.95) == 2639  # ceil(2638.15)
    assert _percentile(values, 0.5) == 1389  # ceil(1388.5)
    assert _percentile([7.0], 0.95) == 7
    assert _percentile([], 0.5) is None

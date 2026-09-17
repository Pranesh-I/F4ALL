"""Sprint 9: badges, the athlete leaderboard, and privacy.

Two properties matter more than the arithmetic:

* nothing the system does not trust earns a badge or a ranking — not the phone's
  own score, not a rejected result;
* no athlete appears to other athletes unless they chose to, and when they do,
  only a first name, last initial and region are shown.
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta

import pytest

from app.models import Athlete, TestResult, TestResultStatus
from app.security import create_access_token
from app.services.athlete_leaderboard import age_band, display_name
from app.services.badges import IST, ResultFact, compute_badges

NOW = datetime(2026, 9, 17, 12, 0, tzinfo=UTC)  # a Thursday


def fact(
    days_ago: float, *, status="approved", score=None, test="SIT_UPS", higher=True
) -> ResultFact:
    at = NOW - timedelta(days=days_ago)
    trusted = score if status in ("approved", "verified") else None
    return ResultFact(
        test_code=test,
        status=status,
        created_at=at,
        trusted_score=trusted,
        trusted_at=at if trusted is not None else None,
        higher_is_better=higher,
    )


def badge(summary, code):
    return next(item for item in summary.badges if item.code == code)


# ---------------------------------------------------------------------------
# Badges
# ---------------------------------------------------------------------------


def test_a_new_athlete_has_no_badges():
    summary = compute_badges([], battery={"SIT_UPS"}, now=NOW)
    assert not any(item.earned for item in summary.badges)
    assert summary.current_streak_weeks == 0


def test_first_test_counts_effort_even_before_verification():
    summary = compute_badges([fact(1, status="processing")], battery={"SIT_UPS"}, now=NOW)
    assert badge(summary, "first_test").earned
    assert not badge(summary, "first_verified").earned


def test_a_rejected_result_earns_nothing():
    """Badges are computed, so a rejection a week later removes them."""
    summary = compute_badges(
        [fact(1, status="rejected", score=None)], battery={"SIT_UPS"}, now=NOW
    )
    assert not badge(summary, "first_test").earned
    assert summary.current_streak_weeks == 0


def test_personal_best_needs_an_improvement_not_a_first_score():
    summary = compute_badges([fact(3, score=20)], battery={"SIT_UPS"}, now=NOW)
    assert not badge(summary, "personal_best").earned

    summary = compute_badges(
        [fact(3, score=20), fact(1, score=18)], battery={"SIT_UPS"}, now=NOW
    )
    assert not badge(summary, "personal_best").earned

    summary = compute_badges(
        [fact(3, score=20), fact(1, score=24)], battery={"SIT_UPS"}, now=NOW
    )
    assert badge(summary, "personal_best").earned


def test_a_provisional_score_never_earns_a_personal_best():
    """The phone's own number is the one measurement the system does not trust."""
    facts = [fact(3, score=20), fact(1, status="flagged", score=None)]
    facts[1] = ResultFact(
        test_code="SIT_UPS",
        status="flagged",
        created_at=NOW,
        trusted_score=None,
        trusted_at=None,
    )
    summary = compute_badges(facts, battery={"SIT_UPS"}, now=NOW)
    assert not badge(summary, "personal_best").earned


def test_personal_best_respects_timed_tests():
    facts = [
        fact(3, score=14.0, test="SHUTTLE_RUN", higher=False),
        fact(1, score=12.5, test="SHUTTLE_RUN", higher=False),
    ]
    summary = compute_badges(facts, battery={"SHUTTLE_RUN"}, now=NOW)
    assert badge(summary, "personal_best").earned


def test_all_tests_tracks_progress_through_the_battery():
    battery = {"SIT_UPS", "VERTICAL_JUMP"}
    one = compute_badges([fact(2, score=20)], battery=battery, now=NOW)
    assert badge(one, "all_tests").progress == 1 and badge(one, "all_tests").target == 2
    assert not badge(one, "all_tests").earned

    both = compute_badges(
        [fact(2, score=20), fact(1, score=40, test="VERTICAL_JUMP")],
        battery=battery,
        now=NOW,
    )
    assert badge(both, "all_tests").earned


def test_weekly_streaks():
    # Tests in each of the last four weeks, including this one.
    facts = [fact(days) for days in (0, 7, 14, 21)]
    summary = compute_badges(facts, battery={"SIT_UPS"}, now=NOW)

    assert summary.current_streak_weeks == 4
    assert summary.longest_streak_weeks == 4
    assert badge(summary, "streak_2_weeks").earned
    assert badge(summary, "streak_4_weeks").earned
    assert not badge(summary, "streak_8_weeks").earned
    assert badge(summary, "streak_8_weeks").progress == 4


def test_several_tests_in_one_week_are_one_week():
    summary = compute_badges([fact(0), fact(1), fact(2)], battery={"SIT_UPS"}, now=NOW)
    assert summary.current_streak_weeks == 1


def test_a_streak_is_not_broken_before_the_athlete_had_a_chance_this_week():
    monday_morning = datetime(2026, 9, 21, 3, 0, tzinfo=IST)
    # Thursday 17th and Thursday 10th: last week and the week before, seen from
    # Monday 21st.
    facts = [fact(days) for days in (0, 7)]
    summary = compute_badges(facts, battery={"SIT_UPS"}, now=monday_morning)
    assert summary.current_streak_weeks == 2


def test_a_gap_ends_the_current_streak_but_not_the_longest():
    facts = [fact(days) for days in (28, 35, 42)]  # three weeks, a month ago
    summary = compute_badges(facts, battery={"SIT_UPS"}, now=NOW)
    assert summary.current_streak_weeks == 0
    assert summary.longest_streak_weeks == 3


def test_weeks_are_indian_weeks():
    """00:30 IST Monday is still Sunday in UTC; it must count as Monday's week."""
    sunday_utc = datetime(2026, 9, 13, 19, 0, tzinfo=UTC)  # Monday 00:30 IST
    previous_week = datetime(2026, 9, 7, 12, 0, tzinfo=UTC)
    facts = [
        ResultFact("SIT_UPS", "approved", previous_week, 20, previous_week),
        ResultFact("SIT_UPS", "approved", sunday_utc, 21, sunday_utc),
    ]
    summary = compute_badges(
        facts, battery={"SIT_UPS"}, now=datetime(2026, 9, 15, tzinfo=UTC)
    )
    assert summary.longest_streak_weeks == 2


# ---------------------------------------------------------------------------
# Leaderboard helpers
# ---------------------------------------------------------------------------


def test_display_name_shows_only_first_name_and_initial():
    assert display_name("Anjali Menon") == "Anjali M."
    assert display_name("Ravi Kumar Sharma") == "Ravi S."
    assert display_name("Priya") == "Priya"
    assert display_name("  ") == "Athlete"


def test_age_bands():
    assert age_band(9) == (9, 11)
    assert age_band(15) == (14, 15)
    assert age_band(40) == (26, 40)
    assert age_band(8) is None


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


def auth(athlete, settings):
    return {"Authorization": f"Bearer {create_access_token(str(athlete.id), settings)}"}


def make_athlete(
    db, name, *, region="Kerala", gender="female", dob=date(2011, 1, 1), opted_in=True
) -> Athlete:
    athlete = Athlete(
        id=uuid.uuid4(),
        name=name,
        dob=dob,
        gender=gender,
        region=region,
        phone=uuid.uuid4().hex[:10],
        leaderboard_opt_in=opted_in,
    )
    db.add(athlete)
    db.commit()
    return athlete


def approve(db, athlete, test, score, attempt=1, status=TestResultStatus.approved):
    db.add(
        TestResult(
            id=uuid.uuid4(),
            athlete_id=athlete.id,
            test_id=test.id,
            attempt_number=attempt,
            provisional_score=score,
            server_score=score,
            final_score=score if status is TestResultStatus.approved else None,
            status=status,
            verified_at=datetime.now(UTC),
        )
    )
    db.commit()


@pytest.fixture
def board(db, seeded_tests):
    situps = seeded_tests["SIT_UPS"]
    viewer = make_athlete(db, "Anjali Menon", opted_in=False)
    asha = make_athlete(db, "Asha Pillai")
    meera = make_athlete(db, "Meera Nair")
    hidden = make_athlete(db, "Hidden Person", opted_in=False)
    other_region = make_athlete(db, "Divya Rao", region="Karnataka")
    boy = make_athlete(db, "Arjun Das", gender="male")
    older = make_athlete(db, "Senior Athlete", dob=date(2001, 1, 1))

    approve(db, viewer, situps, 30)
    approve(db, asha, situps, 40)
    approve(db, asha, situps, 44, attempt=2)
    approve(db, meera, situps, 25)
    approve(db, hidden, situps, 50)
    approve(db, other_region, situps, 60)
    approve(db, boy, situps, 70)
    approve(db, older, situps, 80)
    # Unapproved numbers never rank.
    approve(db, meera, situps, 99, attempt=2, status=TestResultStatus.verified)
    return viewer


def test_regional_board_is_opt_in_cohort_scoped_and_minimal(client, settings, board):
    body = client.get(
        "/api/athletes/leaderboard/SIT_UPS", headers=auth(board, settings)
    ).json()

    assert [(e["display_name"], e["score"]) for e in body["entries"]] == [
        ("Asha P.", 44.0),
        ("Meera N.", 25.0),
    ]
    # No ids, no ages, no full names.
    assert set(body["entries"][0]) == {
        "rank",
        "display_name",
        "region",
        "score",
        "is_you",
    }
    # Born 2011: 15 this year, so the 14-15 band, girls only.
    assert body["cohort"] == "female, 14-15"


def test_a_hidden_athlete_still_sees_their_own_position(client, settings, board):
    body = client.get(
        "/api/athletes/leaderboard/SIT_UPS", headers=auth(board, settings)
    ).json()

    assert body["you"] == {"rank": 2, "score": 30.0, "visible_to_others": False}
    assert not any(entry["is_you"] for entry in body["entries"])


def test_opting_in_makes_the_athlete_visible(client, settings, board):
    headers = auth(board, settings)
    client.patch("/api/athletes/me", json={"leaderboard_opt_in": True}, headers=headers)

    body = client.get("/api/athletes/leaderboard/SIT_UPS", headers=headers).json()

    assert [e["display_name"] for e in body["entries"]] == [
        "Asha P.",
        "Anjali M.",
        "Meera N.",
    ]
    assert body["entries"][1]["is_you"] is True
    assert body["you"]["visible_to_others"] is True


def test_national_scope_includes_other_regions(client, settings, board):
    body = client.get(
        "/api/athletes/leaderboard/SIT_UPS?scope=national", headers=auth(board, settings)
    ).json()
    assert body["entries"][0]["display_name"] == "Divya R."
    assert body["region"] is None


def test_leaderboard_input_validation(client, settings, board):
    headers = auth(board, settings)
    assert (
        client.get("/api/athletes/leaderboard/NOPE", headers=headers).status_code == 404
    )
    assert (
        client.get(
            "/api/athletes/leaderboard/SIT_UPS?scope=world", headers=headers
        ).status_code
        == 422
    )


def test_new_athletes_are_hidden_by_default(client, settings, db):
    athlete = Athlete(
        id=uuid.uuid4(),
        name="New",
        dob=date(2010, 1, 1),
        gender="male",
        region="Kerala",
        phone="9123456780",
    )
    db.add(athlete)
    db.commit()

    body = client.get("/api/athletes/me", headers=auth(athlete, settings)).json()
    assert body["leaderboard_opt_in"] is False
    assert body["preferred_language"] == "en"


def test_language_preference_is_validated(client, settings, db, athlete):
    headers = auth(athlete, settings)
    ok = client.patch(
        "/api/athletes/me", json={"preferred_language": "hi"}, headers=headers
    )
    assert ok.status_code == 200 and ok.json()["preferred_language"] == "hi"
    assert (
        client.patch(
            "/api/athletes/me", json={"preferred_language": "xx"}, headers=headers
        ).status_code
        == 422
    )


def test_badges_endpoint(client, settings, db, athlete, seeded_tests):
    approve(db, athlete, seeded_tests["SIT_UPS"], 20)
    approve(db, athlete, seeded_tests["SIT_UPS"], 25, attempt=2)

    body = client.get("/api/athletes/me/badges", headers=auth(athlete, settings)).json()
    earned = {item["code"] for item in body["badges"] if item["earned"]}

    assert {"first_test", "first_verified", "personal_best"} <= earned
    assert "all_tests" not in earned
    assert body["current_streak_weeks"] >= 1

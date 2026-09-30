"""Sprint 14: submission review and verification.

The Definition of Done is one journey — a submission is verified by the real
worker code, an official finds it in the queue, inspects the evidence, decides
with a reason, and the decision is persisted and audited while the automated
evidence stays exactly as the machine left it. `test_the_sprint_14_journey`
walks that. The rest pin the state machine, the audit fields, concurrency,
authorization, and the queue's filters.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import event, select

from app.models import (
    Flag,
    FlagSeverity,
    OfficialRole,
    ReviewActionRecord,
    TestResultStatus,
)
from tests.test_dashboard import (
    add_flag,
    make_athlete,
    make_official,
    make_result,
    token_for,
)
from tests.test_server_verification import (  # noqa: F401 - fixtures
    all_tests,
    fixture_analysis,
    mp4_bytes,
    official_headers,
    reload,
    settings,
    submit,
    upload,
    use_fixture,
    worker,
)

pytest.importorskip("cv2", reason="opencv not installed")


def act(client, headers, result_id, **body):
    return client.post(
        f"/api/dashboard/reviews/{result_id}/action", json=body, headers=headers
    )


def detail(client, headers, result_id) -> dict:
    response = client.get(f"/api/dashboard/reviews/{result_id}", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


def machine_result(db, athlete, test, status=TestResultStatus.flagged, **extra):
    """A result as the worker leaves it: status and verdict agree."""
    result = make_result(db, athlete, test, status=status, **extra)
    result.verification_verdict = status.value
    db.add(result)
    db.commit()
    return result


# ---------------------------------------------------------------------------
# The Definition of Done
# ---------------------------------------------------------------------------


def test_the_sprint_14_journey(
    client, db, all_tests, worker, settings, mp4_bytes, monkeypatch
):
    headers = official_headers(db, settings)

    # The phone claims 9 squats; the server counts 5 in the same footage.
    use_fixture(monkeypatch, "squats_5_clean.csv")
    result_id = submit(client, "SQUATS", 9, upload(client, mp4_bytes, "SQUATS"))
    assert worker(result_id)["status"] == "flagged"

    before = reload(db, result_id)
    machine = {
        "verdict": before.verification_verdict,
        "reason": before.verification_reason,
        "server_score": float(before.server_score),
        "provisional_score": float(before.provisional_score),
        "server_result": before.server_result,
        "mobile_result": before.mobile_result,
        "checks": before.verification_checks,
        "flags": sorted(f.reason for f in before.flags),
    }
    assert machine["verdict"] == "flagged"
    assert "score_discrepancy" in machine["flags"]

    # It is in the default queue, as work waiting for a decision.
    queue = client.get("/api/dashboard/reviews", headers=headers)
    [row] = [r for r in queue.json() if r["result_id"] == result_id]
    assert row["review_status"] == "needs_review"
    assert row["verification_verdict"] == "flagged"
    assert row["open_flag_count"] == len(machine["flags"])

    # The detail and the evidence behind it.
    shown = detail(client, headers, result_id)
    assert shown["review_status"] == "needs_review"
    assert shown["review_version"] == 0
    assert set(shown["allowed_actions"]) == {
        "approved",
        "rejected",
        "requested_resubmission",
        "flagged",
    }
    discrepancy = next(f for f in shown["flags"] if f["reason"] == "score_discrepancy")
    assert discrepancy["evidence"]["mobile_value"] == 9
    assert discrepancy["evidence"]["server_value"] == 5
    assert discrepancy["evidence"]["tolerance"] == 2
    evidence = client.get(f"/api/verification/{result_id}", headers=headers).json()
    assert evidence["comparison"]["mobile_rep_count"] == 9
    assert evidence["comparison"]["server_rep_count"] == 5

    # A decision without a reason is refused; with one it is recorded.
    refused = act(client, headers, result_id, action="rejected", notes="Not 9")
    assert refused.status_code == 422
    decided = act(
        client,
        headers,
        result_id,
        action="rejected",
        reason="score_discrepancy",
        notes="The video shows 5 squats, not 9.",
        expected_version=0,
    )
    assert decided.status_code == 200, decided.text
    assert decided.json()["status"] == "rejected"
    assert decided.json()["review_status"] == "rejected"
    assert decided.json()["review_version"] == 1

    # Persisted, audited, and the machine's evidence untouched.
    after = reload(db, result_id)
    assert after.status is TestResultStatus.rejected
    assert after.verification_verdict == machine["verdict"]
    assert after.verification_reason == machine["reason"]
    assert float(after.server_score) == machine["server_score"]
    assert float(after.provisional_score) == machine["provisional_score"]
    assert after.server_result == machine["server_result"]
    assert after.mobile_result == machine["mobile_result"]
    assert after.verification_checks == machine["checks"]
    assert sorted(f.reason for f in after.flags) == machine["flags"]
    assert all(f.resolution is not None for f in after.flags)

    [entry] = detail(client, headers, result_id)["review_history"]
    assert entry["action"] == "rejected"
    assert entry["reason"] == "score_discrepancy"
    assert (entry["previous_status"], entry["new_status"]) == ("flagged", "rejected")
    assert entry["official_id"] and entry["official_name"] == "Admin"
    assert entry["notes"] == "The video shows 5 squats, not 9."
    assert entry["created_at"]

    # Settled: a second decision is refused, not applied.
    again = act(client, headers, result_id, action="approved")
    assert again.status_code == 409
    assert reload(db, result_id).status is TestResultStatus.rejected


# ---------------------------------------------------------------------------
# State machine
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "state", [TestResultStatus.uploaded, TestResultStatus.processing]
)
@pytest.mark.parametrize("action", ["approved", "rejected", "flagged"])
def test_nothing_is_decided_before_the_machine_verdict(
    client, db, settings, seeded_tests, state, action
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    result = make_result(db, make_athlete(db), seeded_tests["SIT_UPS"], status=state)

    shown = detail(client, token_for(admin, settings), result.id)
    assert shown["allowed_actions"] == []
    assert shown["review_status"] == "awaiting_verification"

    response = act(
        client,
        token_for(admin, settings),
        result.id,
        action=action,
        reason="other",
        notes="n",
        final_score=3,
    )
    assert response.status_code == 409
    assert "Verification has not finished" in response.json()["detail"]
    assert reload(db, result.id).status is state
    assert db.execute(select(ReviewActionRecord)).first() is None


def test_a_verified_result_can_be_flagged_then_decided(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    result = machine_result(
        db, make_athlete(db), seeded_tests["SIT_UPS"], status=TestResultStatus.verified
    )
    assert detail(client, headers, result.id)["review_status"] == "awaiting_approval"

    flagged = act(
        client,
        headers,
        result.id,
        action="flagged",
        reason="form_issue",
        severity="high",
        notes="Hips drop on every rep — needs a second look.",
    )
    assert flagged.status_code == 200, flagged.text
    assert flagged.json()["status"] == "flagged"
    assert flagged.json()["review_status"] == "needs_review"

    shown = detail(client, headers, result.id)
    manual = [f for f in shown["flags"] if f["source"] == "manual"]
    assert [(f["reason"], f["severity"], f["status"]) for f in manual] == [
        ("form_issue", "high", "open")
    ]
    assert manual[0]["detail"] == "Hips drop on every rep — needs a second look."
    # Flagging is not a decision: everything is still possible.
    assert "approved" in shown["allowed_actions"]
    # The machine said verified; the reviewer's flag does not rewrite that.
    assert shown["verification_verdict"] == "verified"

    approved = act(client, headers, result.id, action="approved", expected_version=1)
    assert approved.status_code == 200, approved.text
    final = reload(db, result.id)
    assert final.status is TestResultStatus.approved
    assert float(final.final_score) == float(final.server_score)
    history = detail(client, headers, result.id)["review_history"]
    assert [(h["action"], h["previous_status"], h["new_status"]) for h in history] == [
        ("flagged", "verified", "flagged"),
        ("approved", "flagged", "approved"),
    ]


def test_a_machine_rejection_can_only_be_confirmed_or_sent_back(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    result = machine_result(
        db,
        make_athlete(db),
        seeded_tests["SIT_UPS"],
        status=TestResultStatus.rejected,
        server=None,
    )
    result.verification_reason = "video_unreadable"
    db.add(result)
    db.commit()

    shown = detail(client, headers, result.id)
    assert shown["review_status"] == "invalid"
    assert shown["allowed_actions"] == ["rejected", "requested_resubmission"]

    for action in ("approved", "flagged"):
        refused = act(
            client,
            headers,
            result.id,
            action=action,
            reason="other",
            notes="n",
            final_score=5,
        )
        assert refused.status_code == 409, action
        assert "can only be rejected or sent back" in refused.json()["detail"]

    sent_back = act(
        client,
        headers,
        result.id,
        action="requested_resubmission",
        reason="invalid_video",
        notes="The file would not play. Please record again.",
    )
    assert sent_back.status_code == 200
    assert detail(client, headers, result.id)["review_status"] == "resubmission_requested"


@pytest.mark.parametrize(
    ("body", "code", "message"),
    [
        ({"action": "archive"}, 400, "Unknown action"),
        ({"action": "rejected", "notes": "x"}, 422, "Choose a reason"),
        (
            {"action": "rejected", "reason": "because", "notes": "x"},
            422,
            "Unknown reason",
        ),
        ({"action": "rejected", "reason": "other"}, 422, "athlete will see"),
        ({"action": "requested_resubmission", "notes": "x"}, 422, "Choose a reason"),
        ({"action": "flagged", "notes": "x"}, 422, "Choose a reason"),
        ({"action": "flagged", "reason": "other"}, 422, "next reviewer"),
        (
            {"action": "flagged", "reason": "other", "notes": "x", "severity": "huge"},
            422,
            "severity",
        ),
    ],
)
def test_invalid_decisions_are_refused_and_change_nothing(
    client, db, settings, seeded_tests, body, code, message
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    result = machine_result(db, make_athlete(db), seeded_tests["SIT_UPS"])

    response = act(client, token_for(admin, settings), result.id, **body)

    assert response.status_code == code
    assert message in response.json()["detail"]
    assert reload(db, result.id).status is TestResultStatus.flagged
    assert db.execute(select(ReviewActionRecord)).first() is None


def test_approving_needs_no_reason_but_a_hand_score_needs_a_note(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    scored = machine_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    assert act(client, headers, scored.id, action="approved").status_code == 200

    unscored = machine_result(db, make_athlete(db), seeded_tests["SIT_UPS"], server=None)
    assert (
        act(client, headers, unscored.id, action="approved", final_score=12).status_code
        == 422
    )
    ok = act(
        client,
        headers,
        unscored.id,
        action="approved",
        final_score=12,
        reason="technical_issue",
        notes="Counted 12 from the video; tracking failed.",
    )
    assert ok.status_code == 200
    [record] = db.execute(
        select(ReviewActionRecord).where(ReviewActionRecord.test_result_id == unscored.id)
    ).scalars()
    assert record.notes.startswith("[Score entered by reviewer: 12.0]")
    assert record.reason == "technical_issue"


# ---------------------------------------------------------------------------
# Concurrency
# ---------------------------------------------------------------------------


def test_a_stale_version_is_refused_with_what_happened_instead(
    client, db, settings, seeded_tests
):
    first = make_official(db, role=OfficialRole.sai_admin)
    first.name = "Asha"
    second = make_official(db, role=OfficialRole.sai_admin)
    db.commit()
    result = machine_result(db, make_athlete(db), seeded_tests["SIT_UPS"])

    # Both open it at version 0. Asha flags it; the second reviewer, still
    # looking at version 0, tries to approve.
    assert (
        act(
            client,
            token_for(first, settings),
            result.id,
            action="flagged",
            reason="identity_mismatch",
            notes="Face does not match the photo.",
            expected_version=0,
        ).status_code
        == 200
    )
    stale = act(
        client,
        token_for(second, settings),
        result.id,
        action="approved",
        expected_version=0,
    )

    assert stale.status_code == 409
    assert "changed while you had it open" in stale.json()["detail"]
    assert "Asha recorded 'flagged'" in stale.json()["detail"]
    assert reload(db, result.id).status is TestResultStatus.flagged

    # Having reloaded (version 1), the decision goes through.
    assert (
        act(
            client,
            token_for(second, settings),
            result.id,
            action="rejected",
            reason="identity_mismatch",
            notes="Not the registered athlete.",
            expected_version=1,
        ).status_code
        == 200
    )


def test_two_decisions_on_one_result_leave_one_decision(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    other = make_official(db, role=OfficialRole.sai_admin)
    result = machine_result(db, make_athlete(db), seeded_tests["SIT_UPS"])

    one = act(client, token_for(admin, settings), result.id, action="approved")
    two = act(
        client,
        token_for(other, settings),
        result.id,
        action="rejected",
        reason="other",
        notes="Too late",
    )

    assert (one.status_code, two.status_code) == (200, 409)
    assert "already approved" in two.json()["detail"]
    records = db.execute(select(ReviewActionRecord)).scalars().all()
    assert [r.action.value for r in records] == ["approved"]


# ---------------------------------------------------------------------------
# Authorization
# ---------------------------------------------------------------------------


def test_regional_reviewers_review_only_their_region(client, db, settings, seeded_tests):
    reviewer = make_official(db, region="Tamil Nadu")
    admin = make_official(db, role=OfficialRole.sai_admin)
    sit_ups = seeded_tests["SIT_UPS"]
    home = machine_result(db, make_athlete(db, region="Tamil Nadu"), sit_ups)
    away = machine_result(db, make_athlete(db, region="Kerala"), sit_ups)
    mine = token_for(reviewer, settings)

    queue = client.get("/api/dashboard/reviews", headers=mine).json()
    assert [row["result_id"] for row in queue] == [str(home.id)]

    for path in (
        f"/api/dashboard/reviews/{away.id}",
        f"/api/verification/{away.id}",
    ):
        assert client.get(path, headers=mine).status_code == 404, path
    refused = act(client, mine, away.id, action="rejected", reason="other", notes="x")
    assert refused.status_code == 404
    assert reload(db, away.id).status is TestResultStatus.flagged

    assert act(client, mine, home.id, action="approved").status_code == 200
    # A national admin reviews any region.
    assert (
        act(client, token_for(admin, settings), away.id, action="approved").status_code
        == 200
    )


def test_athletes_and_the_anonymous_never_reach_review(
    client, db, settings, seeded_tests, athlete
):
    from tests.test_sessions import athlete_auth

    result = machine_result(db, athlete, seeded_tests["SIT_UPS"])
    for method, path, body in (
        ("GET", "/api/dashboard/reviews", None),
        ("GET", f"/api/dashboard/reviews/{result.id}", None),
        ("POST", f"/api/dashboard/reviews/{result.id}/action", {"action": "approved"}),
        ("GET", "/api/dashboard/submissions", None),
    ):
        anonymous = client.request(method, path, json=body)
        as_athlete = client.request(
            method, path, json=body, headers=athlete_auth(athlete, settings)
        )
        assert (anonymous.status_code, as_athlete.status_code) == (401, 403), path
    assert reload(db, result.id).status is TestResultStatus.flagged


def test_the_athlete_sees_decisions_but_never_a_reviewers_flag(
    client, db, settings, seeded_tests, athlete
):
    from tests.test_sessions import athlete_auth

    admin = make_official(db, role=OfficialRole.sai_admin)
    result = machine_result(db, athlete, seeded_tests["SIT_UPS"])
    add_flag(db, result, severity=FlagSeverity.medium, reason="abrupt_cut")
    act(
        client,
        token_for(admin, settings),
        result.id,
        action="flagged",
        reason="identity_mismatch",
        notes="INTERNAL: compare with the photo",
    )

    mine = client.get(
        f"/api/results/{result.id}", headers=athlete_auth(athlete, settings)
    ).json()
    assert [flag["reason"] for flag in mine["flags"]] == ["abrupt_cut"]
    assert "INTERNAL" not in str(mine)
    assert mine["latest_review"] is None

    act(
        client,
        token_for(admin, settings),
        result.id,
        action="requested_resubmission",
        reason="identity_mismatch",
        notes="Please record again with your face visible.",
    )
    mine = client.get(
        f"/api/results/{result.id}", headers=athlete_auth(athlete, settings)
    ).json()
    assert mine["latest_review"]["action"] == "requested_resubmission"
    assert mine["latest_review"]["notes"] == "Please record again with your face visible."


# ---------------------------------------------------------------------------
# The queue
# ---------------------------------------------------------------------------


def test_the_queue_filters_on_the_server(client, db, settings, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    asha = make_athlete(db, name="Asha Kumari")
    ravi = make_athlete(db, name="Ravi Menon")
    now = datetime.now(UTC)
    flagged_high = machine_result(db, asha, seeded_tests["SIT_UPS"], created_at=now)
    add_flag(db, flagged_high, severity=FlagSeverity.high)
    flagged_low = machine_result(
        db, ravi, seeded_tests["VERTICAL_JUMP"], created_at=now - timedelta(days=3)
    )
    add_flag(db, flagged_low, severity=FlagSeverity.low)
    verified = machine_result(
        db,
        ravi,
        seeded_tests["SIT_UPS"],
        status=TestResultStatus.verified,
        created_at=now - timedelta(days=1),
    )
    invalid = machine_result(
        db,
        asha,
        seeded_tests["VERTICAL_JUMP"],
        status=TestResultStatus.rejected,
        server=None,
        created_at=now - timedelta(days=2),
    )
    invalid.verification_reason = "video_empty"
    db.add(invalid)
    db.commit()

    def ids(query: str = "") -> list[str]:
        response = client.get(f"/api/dashboard/reviews{query}", headers=headers)
        assert response.status_code == 200, response.text
        return [row["result_id"] for row in response.json()]

    # Default: waiting for a decision, most severe first.
    assert ids() == [str(flagged_high.id), str(flagged_low.id), str(invalid.id)]
    assert ids("?review_status=awaiting_approval") == [str(verified.id)]
    assert set(ids("?review_status=needs_review,awaiting_approval")) == {
        str(flagged_high.id),
        str(flagged_low.id),
        str(verified.id),
    }
    assert ids("?athlete=asha") == [str(flagged_high.id), str(invalid.id)]
    assert ids(f"?athlete={ravi.id}&review_status=needs_review") == [str(flagged_low.id)]
    assert ids("?flags=high") == [str(flagged_high.id)]
    assert ids("?flags=none") == [str(invalid.id)]
    assert ids("?test_type=VERTICAL_JUMP") == [str(flagged_low.id), str(invalid.id)]
    window = (
        f"?submitted_from={(now - timedelta(days=2, hours=1)).isoformat()}"
        f"&submitted_to={(now - timedelta(hours=1)).isoformat()}"
    ).replace("+", "%2B")
    assert ids(window) == [str(invalid.id)]

    for bad in ("?review_status=pending", "?flags=some"):
        assert (
            client.get(f"/api/dashboard/reviews{bad}", headers=headers).status_code == 422
        )


def test_queue_rows_carry_review_and_integrity_state(client, db, settings, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)
    result = machine_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    add_flag(db, result, severity=FlagSeverity.high)
    add_flag(db, result, severity=FlagSeverity.low, reason="abrupt_cut")

    [row] = client.get(
        "/api/dashboard/reviews", headers=token_for(admin, settings)
    ).json()
    assert row["review_status"] == "needs_review"
    assert row["verification_verdict"] == "flagged"
    assert (row["flag_count"], row["open_flag_count"], row["max_severity"]) == (
        2,
        2,
        "high",
    )


def test_listing_a_page_is_one_query_however_many_rows(
    client, db, settings, seeded_tests, db_session_factory
):
    """Flag counts and review state come from the same SELECT, not per row."""
    admin = make_official(db, role=OfficialRole.sai_admin)
    headers = token_for(admin, settings)
    engine = db_session_factory.kw["bind"]

    def statements_for_listing() -> int:
        seen = []
        listener = lambda *args: seen.append(1)  # noqa: E731
        event.listen(engine, "before_cursor_execute", listener)
        try:
            assert (
                client.get("/api/dashboard/reviews", headers=headers).status_code == 200
            )
        finally:
            event.remove(engine, "before_cursor_execute", listener)
        return len(seen)

    athlete = make_athlete(db)
    first = machine_result(db, athlete, seeded_tests["SIT_UPS"])
    add_flag(db, first)
    one_row = statements_for_listing()
    for attempt in range(2, 12):
        result = machine_result(db, athlete, seeded_tests["SIT_UPS"], attempt=attempt)
        add_flag(db, result)
    assert statements_for_listing() == one_row


# ---------------------------------------------------------------------------
# All six exercises
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("code", "sequence", "claim", "unit"),
    [
        ("SIT_UPS", "situps_5_clean.csv", 5.0, "reps"),
        ("SQUATS", "squats_5_clean.csv", 5.0, "reps"),
        ("PUSH_UPS", "pushups_4_clean.csv", 4.0, "reps"),
        ("BICEP_CURLS", "curls_4_right_arm.csv", 4.0, "reps"),
        ("LUNGES", "lunges_4_clean.csv", 4.0, "reps"),
        ("VERTICAL_JUMP", "jump_40cm.csv", 39.73, "cm"),
    ],
)
def test_every_exercise_goes_through_review_the_same_way(
    client,
    db,
    all_tests,
    worker,
    settings,
    mp4_bytes,
    monkeypatch,
    code,
    sequence,
    claim,
    unit,
):
    headers = official_headers(db, settings)
    use_fixture(monkeypatch, sequence)
    result_id = submit(client, code, claim, upload(client, mp4_bytes, code))
    worker(result_id)
    machine = reload(db, result_id)
    assert machine.verification_verdict in {"verified", "flagged"}

    shown = detail(client, headers, result_id)
    assert shown["unit"] == unit and shown["test_type"] == code
    evidence = client.get(f"/api/verification/{result_id}", headers=headers).json()
    comparison = evidence["comparison"]
    if unit == "reps":
        assert comparison["server_rep_count"] == int(claim)
    else:
        assert comparison["server_measurement"] == pytest.approx(claim, abs=0.01)

    decided = act(client, headers, result_id, action="approved", expected_version=0)
    assert decided.status_code == 200, decided.text
    final = reload(db, result_id)
    assert final.status is TestResultStatus.approved
    assert float(final.final_score) == pytest.approx(float(machine.server_score))
    assert final.verification_verdict == machine.verification_verdict


def test_the_worker_records_its_own_verdict(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    use_fixture(monkeypatch, "situps_5_clean.csv")
    result_id = submit(client, "SIT_UPS", 5, upload(client, mp4_bytes, "SIT_UPS"))
    outcome = worker(result_id)
    assert reload(db, result_id).verification_verdict == outcome["status"]


def test_manual_flags_are_stored_as_manual(client, db, settings, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)
    result = machine_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    act(
        client,
        token_for(admin, settings),
        result.id,
        action="flagged",
        reason="multiple_people",
        notes="Someone walks through at 0:03.",
    )
    [flag] = db.execute(select(Flag).where(Flag.test_result_id == result.id)).scalars()
    assert flag.source.value == "manual"
    assert flag.severity.value == "medium"
    assert flag.evidence["review_reason"] == "multiple_people"
    assert flag.evidence["raised_by"] == str(admin.id)

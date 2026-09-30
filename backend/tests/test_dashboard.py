"""Sprint 8: official sign-in, the review workflow, and what the athlete sees.

The Definition of Done is one journey — an official signs in, reviews a flagged
submission with full context, and approves or rejects it; the action is logged
and reflected back to the athlete. `test_the_sprint_8_journey` walks exactly
that. The rest pin the rules that make the journey safe.
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import select

from app.models import (
    Athlete,
    FaceVerification,
    FaceVerificationStatus,
    Flag,
    FlagSeverity,
    FlagSource,
    Official,
    OfficialRole,
    ReviewActionRecord,
    TestResult,
    TestResultStatus,
    Video,
)
from app.security import create_access_token
from app.services.passwords import hash_password, verify_password

PASSWORD = "correct horse battery staple"


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


def make_official(
    db,
    *,
    role=OfficialRole.regional_reviewer,
    region="Tamil Nadu",
    email=None,
    active=True,
) -> Official:
    official = Official(
        id=uuid.uuid4(),
        name=f"{role.value} {region}",
        email=email or f"{uuid.uuid4().hex[:8]}@sai.example",
        role=role,
        region=region if role is OfficialRole.regional_reviewer else None,
        password_hash=hash_password(PASSWORD),
        is_active=active,
    )
    db.add(official)
    db.commit()
    return official


def token_for(official: Official, settings) -> dict:
    token = create_access_token(str(official.id), settings, role=official.role.value)
    return {"Authorization": f"Bearer {token}"}


def make_athlete(
    db, *, region="Tamil Nadu", name="Athlete", gender="female", dob=date(2010, 3, 1)
) -> Athlete:
    athlete = Athlete(
        id=uuid.uuid4(),
        name=name,
        dob=dob,
        gender=gender,
        region=region,
        phone=uuid.uuid4().hex[:10],
        height_cm=160,
    )
    db.add(athlete)
    db.commit()
    return athlete


def make_result(
    db,
    athlete,
    test,
    *,
    status=TestResultStatus.flagged,
    provisional=30,
    server=28,
    final=None,
    attempt=1,
    created_at=None,
) -> TestResult:
    result = TestResult(
        id=uuid.uuid4(),
        athlete_id=athlete.id,
        test_id=test.id,
        attempt_number=attempt,
        provisional_score=provisional,
        server_score=server,
        final_score=final,
        status=status,
        created_at=created_at or datetime.now(UTC),
        verified_at=datetime.now(UTC) if server is not None else None,
    )
    db.add(result)
    db.commit()
    return result


def add_flag(db, result, severity=FlagSeverity.high, reason="looped_frames"):
    db.add(
        Flag(
            test_result_id=result.id,
            source=FlagSource.auto,
            reason=reason,
            detail="20 frames from 1.0s repeat at 4.0s",
            severity=severity,
        )
    )
    db.commit()


# ---------------------------------------------------------------------------
# The Definition of Done
# ---------------------------------------------------------------------------


def test_the_sprint_8_journey(client, db, settings, seeded_tests):
    """Sign in, review a flagged submission in context, decide, athlete sees it."""
    reviewer = make_official(db, email="reviewer@sai.example")
    athlete = make_athlete(db)
    result = make_result(db, athlete, seeded_tests["SIT_UPS"], provisional=40, server=22)
    add_flag(db, result)
    db.add(
        Video(test_result_id=result.id, s3_key="videos/x.mp4", checksum_sha256="0" * 64)
    )
    db.add(
        FaceVerification(
            test_result_id=result.id,
            verification_status=FaceVerificationStatus.manual_review,
            similarity_score=0.31,
        )
    )
    db.commit()

    # 1. Sign in.
    login = client.post(
        "/api/dashboard/auth/login",
        json={"email": "Reviewer@SAI.example ", "password": PASSWORD},
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    # 2. The flagged submission is in the queue.
    queue = client.get("/api/dashboard/reviews", headers=headers)
    assert [item["result_id"] for item in queue.json()] == [str(result.id)]
    assert queue.json()[0]["max_severity"] == "high"

    # 3. Full context.
    detail = client.get(f"/api/dashboard/reviews/{result.id}", headers=headers).json()
    assert detail["provisional_score"] == 40 and detail["server_score"] == 22
    assert detail["flags"][0]["reason"] == "looped_frames"
    assert detail["flags"][0]["severity"] == "high"
    assert detail["face_verification"]["status"] == "manual_review"
    assert detail["video_url"]
    assert detail["athlete_age_years"] >= 9
    assert "rejected" in detail["allowed_actions"]

    # 4. Decide.
    action = client.post(
        f"/api/dashboard/reviews/{result.id}/action",
        json={
            "action": "rejected",
            "reason": "duplicate_submission",
            "notes": "The same reps are shown twice.",
        },
        headers=headers,
    )
    assert action.status_code == 200, action.text

    # 5. Logged.
    records = db.execute(select(ReviewActionRecord)).scalars().all()
    assert len(records) == 1 and records[0].official_id == reviewer.id

    detail = client.get(f"/api/dashboard/reviews/{result.id}", headers=headers).json()
    assert detail["review_history"][0]["official_name"] == reviewer.name
    assert detail["allowed_actions"] == []
    assert detail["flags"][0]["resolution"] == "confirmed"

    # 6. Reflected back to the athlete.
    athlete_token = create_access_token(str(athlete.id), settings, role="athlete")
    seen = client.get(
        f"/api/results/{result.id}",
        headers={"Authorization": f"Bearer {athlete_token}"},
    ).json()
    assert seen["status"] == "rejected"
    assert seen["latest_review"]["action"] == "rejected"
    assert seen["latest_review"]["notes"] == "The same reps are shown twice."
    assert "official" not in str(seen["latest_review"]).lower()


# ---------------------------------------------------------------------------
# Sign-in
# ---------------------------------------------------------------------------


def test_passwords_are_hashed_and_verified():
    stored = hash_password(PASSWORD)
    assert PASSWORD not in stored
    assert verify_password(PASSWORD, stored)
    assert not verify_password("wrong password!!", stored)
    assert not verify_password(PASSWORD, None)
    assert not verify_password(PASSWORD, "garbage")


def test_wrong_password_and_unknown_email_look_identical(client, db):
    make_official(db, email="known@sai.example")

    wrong = client.post(
        "/api/dashboard/auth/login",
        json={"email": "known@sai.example", "password": "not the password"},
    )
    unknown = client.post(
        "/api/dashboard/auth/login",
        json={"email": "nobody@sai.example", "password": "not the password"},
    )

    assert wrong.status_code == unknown.status_code == 401
    assert wrong.json() == unknown.json()


def test_repeated_failures_lock_the_account(client, db):
    official = make_official(db, email="target@sai.example")

    for _ in range(5):
        client.post(
            "/api/dashboard/auth/login",
            json={"email": "target@sai.example", "password": "guess guess guess"},
        )

    # Even the right password is refused while locked.
    locked = client.post(
        "/api/dashboard/auth/login",
        json={"email": "target@sai.example", "password": PASSWORD},
    )
    assert locked.status_code == 423

    db.refresh(official)
    official.locked_until = datetime.now(UTC) - timedelta(seconds=1)
    db.commit()

    assert (
        client.post(
            "/api/dashboard/auth/login",
            json={"email": "target@sai.example", "password": PASSWORD},
        ).status_code
        == 200
    )


def test_a_deactivated_official_cannot_sign_in_or_use_a_live_token(
    client, db, settings, seeded_tests
):
    official = make_official(db, email="gone@sai.example")
    headers = token_for(official, settings)

    official.is_active = False
    db.commit()

    assert (
        client.post(
            "/api/dashboard/auth/login",
            json={"email": "gone@sai.example", "password": PASSWORD},
        ).status_code
        == 401
    )
    # An unexpired token stops working immediately.
    assert client.get("/api/dashboard/reviews", headers=headers).status_code == 401


def test_an_athlete_token_cannot_reach_the_dashboard(client, db, settings):
    athlete = make_athlete(db)
    token = create_access_token(str(athlete.id), settings, role="athlete")

    response = client.get(
        "/api/dashboard/reviews", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 403


def test_official_refresh_rotates_and_refuses_athlete_tokens(client, db, settings):
    from app.services import tokens as token_service

    make_official(db, email="refresh@sai.example")
    login = client.post(
        "/api/dashboard/auth/login",
        json={"email": "refresh@sai.example", "password": PASSWORD},
    ).json()

    refreshed = client.post(
        "/api/dashboard/auth/refresh", json={"refresh_token": login["refresh_token"]}
    )
    assert refreshed.status_code == 200
    assert refreshed.json()["official"]["email"] == "refresh@sai.example"

    athlete = make_athlete(db)
    athlete_pair = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )
    assert (
        client.post(
            "/api/dashboard/auth/refresh",
            json={"refresh_token": athlete_pair.refresh_token},
        ).status_code
        == 401
    )


def test_me_returns_the_signed_in_official(client, db, settings):
    official = make_official(db)
    body = client.get(
        "/api/dashboard/auth/me", headers=token_for(official, settings)
    ).json()
    assert body["official_id"] == str(official.id)
    assert body["region"] == "Tamil Nadu"


# ---------------------------------------------------------------------------
# Region scoping
# ---------------------------------------------------------------------------


def test_a_reviewer_without_a_region_sees_nothing(client, db, settings, seeded_tests):
    """Fails closed. The old filter showed such an account every region."""
    reviewer = make_official(db, region=None)
    make_result(db, make_athlete(db, region="Kerala"), seeded_tests["SIT_UPS"])

    response = client.get("/api/dashboard/reviews", headers=token_for(reviewer, settings))

    assert response.status_code == 200
    assert response.json() == []


def test_a_regional_reviewer_sees_only_their_region(client, db, settings, seeded_tests):
    reviewer = make_official(db, region="Tamil Nadu")
    mine = make_result(db, make_athlete(db, region="Tamil Nadu"), seeded_tests["SIT_UPS"])
    make_result(db, make_athlete(db, region="Kerala"), seeded_tests["SIT_UPS"])

    headers = token_for(reviewer, settings)
    ids = [
        item["result_id"]
        for item in client.get("/api/dashboard/reviews", headers=headers).json()
    ]
    assert ids == [str(mine.id)]

    # Asking for another region by filter does not widen the scope.
    assert (
        client.get("/api/dashboard/reviews?region=Kerala", headers=headers).json() == []
    )


def test_an_admin_sees_every_region(client, db, settings, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)
    make_result(db, make_athlete(db, region="Tamil Nadu"), seeded_tests["SIT_UPS"])
    make_result(db, make_athlete(db, region="Kerala"), seeded_tests["SIT_UPS"])

    assert (
        len(
            client.get(
                "/api/dashboard/reviews", headers=token_for(admin, settings)
            ).json()
        )
        == 2
    )


def test_out_of_region_actions_and_pose_are_not_found(client, db, settings, seeded_tests):
    reviewer = make_official(db, region="Tamil Nadu")
    other = make_result(db, make_athlete(db, region="Kerala"), seeded_tests["SIT_UPS"])
    headers = token_for(reviewer, settings)

    assert (
        client.post(
            f"/api/dashboard/reviews/{other.id}/action",
            json={"action": "rejected", "notes": "x"},
            headers=headers,
        ).status_code
        == 404
    )
    assert (
        client.get(f"/api/dashboard/reviews/{other.id}/pose", headers=headers).status_code
        == 404
    )


# ---------------------------------------------------------------------------
# Queue
# ---------------------------------------------------------------------------


def test_queue_puts_high_severity_first_then_oldest(client, db, settings, seeded_tests):
    admin = make_official(db, role=OfficialRole.sai_admin)
    athlete = make_athlete(db)
    old = datetime.now(UTC) - timedelta(days=3)

    low_old = make_result(db, athlete, seeded_tests["SIT_UPS"], attempt=1, created_at=old)
    add_flag(db, low_old, FlagSeverity.low)
    high_new = make_result(db, athlete, seeded_tests["SIT_UPS"], attempt=2)
    add_flag(db, high_new, FlagSeverity.high)

    response = client.get("/api/dashboard/reviews", headers=token_for(admin, settings))

    assert [item["result_id"] for item in response.json()] == [
        str(high_new.id),
        str(low_old.id),
    ]
    assert response.headers["X-Total-Count"] == "2"


def test_queue_filters_by_several_statuses_and_paginates(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    athlete = make_athlete(db)
    for attempt, state in enumerate(
        [TestResultStatus.verified, TestResultStatus.verified, TestResultStatus.approved],
        start=1,
    ):
        make_result(db, athlete, seeded_tests["SIT_UPS"], status=state, attempt=attempt)

    headers = token_for(admin, settings)
    page = client.get(
        "/api/dashboard/reviews?status=verified,approved&limit=2", headers=headers
    )

    assert len(page.json()) == 2
    assert page.headers["X-Total-Count"] == "3"
    assert (
        client.get("/api/dashboard/reviews?status=nonsense", headers=headers).status_code
        == 422
    )


# ---------------------------------------------------------------------------
# Actions
# ---------------------------------------------------------------------------


def act(client, headers, result, **body):
    return client.post(
        f"/api/dashboard/reviews/{result.id}/action", json=body, headers=headers
    )


def test_rejecting_or_requesting_resubmission_requires_a_reason(
    client, db, settings, seeded_tests
):
    """The athlete sees these notes. No reason leaves them nothing to fix."""
    reviewer = make_official(db)
    result = make_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    headers = token_for(reviewer, settings)

    assert act(client, headers, result, action="rejected").status_code == 422
    assert (
        act(
            client, headers, result, action="requested_resubmission", notes="  "
        ).status_code
        == 422
    )


def test_approval_never_promotes_the_phones_number(client, db, settings, seeded_tests):
    reviewer = make_official(db)
    result = make_result(
        db, make_athlete(db), seeded_tests["SIT_UPS"], provisional=55, server=None
    )
    headers = token_for(reviewer, settings)

    refused = act(client, headers, result, action="approved")
    assert refused.status_code == 422

    db.refresh(result)
    assert result.final_score is None
    assert result.status is TestResultStatus.flagged


def test_a_hand_entered_score_needs_a_note_and_is_labelled(
    client, db, settings, seeded_tests
):
    reviewer = make_official(db)
    result = make_result(
        db, make_athlete(db), seeded_tests["SIT_UPS"], provisional=55, server=None
    )
    headers = token_for(reviewer, settings)

    assert (
        act(client, headers, result, action="approved", final_score=31).status_code == 422
    )

    ok = act(
        client,
        headers,
        result,
        action="approved",
        final_score=31,
        notes="Counted 31 full reps in the recording",
    )
    assert ok.status_code == 200

    db.expire_all()
    assert float(db.get(TestResult, result.id).final_score) == 31
    record = db.execute(select(ReviewActionRecord)).scalars().one()
    assert record.notes.startswith("[Score entered by reviewer: 31")


def test_a_decided_result_cannot_be_decided_again(client, db, settings, seeded_tests):
    reviewer = make_official(db)
    result = make_result(
        db,
        make_athlete(db),
        seeded_tests["SIT_UPS"],
        status=TestResultStatus.approved,
        final=28,
    )

    response = act(
        client,
        token_for(reviewer, settings),
        result,
        action="rejected",
        notes="changed my mind",
    )

    assert response.status_code == 409


def test_requesting_resubmission_is_visible_to_the_athlete(
    client, db, settings, seeded_tests
):
    reviewer = make_official(db)
    athlete = make_athlete(db)
    result = make_result(db, athlete, seeded_tests["SIT_UPS"])

    act(
        client,
        token_for(reviewer, settings),
        result,
        action="requested_resubmission",
        reason="form_issue",
        notes="Your knees were out of frame.",
    )

    athlete_token = create_access_token(str(athlete.id), settings, role="athlete")
    seen = client.get(
        f"/api/results/{result.id}", headers={"Authorization": f"Bearer {athlete_token}"}
    ).json()
    assert seen["status"] == "pending_sync"
    assert seen["latest_review"]["action"] == "requested_resubmission"


def test_approving_dismisses_open_flags(client, db, settings, seeded_tests):
    reviewer = make_official(db)
    result = make_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    add_flag(db, result, FlagSeverity.low)

    act(client, token_for(reviewer, settings), result, action="approved")

    db.expire_all()
    flag = db.execute(select(Flag)).scalars().one()
    assert flag.resolution.value == "dismissed"
    assert flag.resolved_by == reviewer.id


# ---------------------------------------------------------------------------
# Pose overlay
# ---------------------------------------------------------------------------


def test_pose_sequence_round_trips_through_storage(client, db, settings, seeded_tests):
    from app.storage import get_storage
    from app.tasks import pose_sequence_json, pose_sequence_key_for
    from app.verification.pose import PoseFrame, PosePoint

    reviewer = make_official(db)
    result = make_result(db, make_athlete(db), seeded_tests["SIT_UPS"])
    frames = [
        PoseFrame(timestamp_ms=t, points=[PosePoint(0.123456, 0.5, 0.0, 0.98765)] * 33)
        for t in (0, 33)
    ]
    key = pose_sequence_key_for("videos/a/b.mp4")
    get_storage(settings).store_bytes(
        key, pose_sequence_json(frames), content_type="application/json"
    )
    db.add(
        Video(
            test_result_id=result.id,
            s3_key="videos/a/b.mp4",
            checksum_sha256="0" * 64,
            pose_sequence_key=key,
        )
    )
    db.commit()

    headers = token_for(reviewer, settings)
    assert client.get(f"/api/dashboard/reviews/{result.id}", headers=headers).json()[
        "has_pose_sequence"
    ]

    body = client.get(f"/api/dashboard/reviews/{result.id}/pose", headers=headers).json()
    assert key == "videos/a/b.pose.json"
    assert body["landmarks"] == 33
    assert body["frames"][1]["t"] == 33
    assert body["frames"][0]["p"][:3] == [0.1235, 0.5, 0.988]


# ---------------------------------------------------------------------------
# Leaderboard and stats
# ---------------------------------------------------------------------------


def test_leaderboard_ranks_best_approved_score_per_athlete(
    client, db, settings, seeded_tests
):
    admin = make_official(db, role=OfficialRole.sai_admin)
    situps = seeded_tests["SIT_UPS"]
    asha = make_athlete(db, name="Asha")
    ravi = make_athlete(db, name="Ravi", gender="male")

    make_result(db, asha, situps, status=TestResultStatus.approved, final=30, attempt=1)
    make_result(db, asha, situps, status=TestResultStatus.approved, final=41, attempt=2)
    make_result(db, ravi, situps, status=TestResultStatus.approved, final=35, attempt=1)
    # Unapproved numbers never rank, however high.
    make_result(db, ravi, situps, status=TestResultStatus.verified, server=99, attempt=2)

    body = client.get(
        "/api/dashboard/leaderboard?test_type=SIT_UPS", headers=token_for(admin, settings)
    ).json()

    assert [(e["athlete_name"], e["score"], e["rank"]) for e in body["entries"]] == [
        ("Asha", 41.0, 1),
        ("Ravi", 35.0, 2),
    ]


def test_leaderboard_filters_and_is_region_scoped(client, db, settings, seeded_tests):
    reviewer = make_official(db, region="Tamil Nadu")
    situps = seeded_tests["SIT_UPS"]
    make_result(
        db,
        make_athlete(db, region="Tamil Nadu", gender="male"),
        situps,
        status=TestResultStatus.approved,
        final=20,
    )
    make_result(
        db,
        make_athlete(db, region="Kerala", gender="male"),
        situps,
        status=TestResultStatus.approved,
        final=50,
    )

    headers = token_for(reviewer, settings)
    everyone = client.get(
        "/api/dashboard/leaderboard?test_type=SIT_UPS", headers=headers
    ).json()
    assert [e["region"] for e in everyone["entries"]] == ["Tamil Nadu"]

    women = client.get(
        "/api/dashboard/leaderboard?test_type=SIT_UPS&gender=female", headers=headers
    ).json()
    assert women["entries"] == []

    adults = client.get(
        "/api/dashboard/leaderboard?test_type=SIT_UPS&age_min=30", headers=headers
    ).json()
    assert adults["entries"] == []

    assert (
        client.get(
            "/api/dashboard/leaderboard?test_type=NOPE", headers=headers
        ).status_code
        == 404
    )


def test_stats_are_scoped(client, db, settings, seeded_tests):
    reviewer = make_official(db, region="Tamil Nadu")
    make_result(db, make_athlete(db, region="Tamil Nadu"), seeded_tests["SIT_UPS"])
    make_result(db, make_athlete(db, region="Kerala"), seeded_tests["SIT_UPS"])

    body = client.get(
        "/api/dashboard/stats", headers=token_for(reviewer, settings)
    ).json()
    assert body["by_status"]["flagged"] == 1


# ---------------------------------------------------------------------------
# Media URLs
# ---------------------------------------------------------------------------


def test_signed_media_url_serves_the_file(client, settings):
    from app.storage import get_storage

    storage = get_storage(settings)
    storage.store_bytes("videos/clip.mp4", b"fake video bytes", content_type="video/mp4")

    url = storage.signed_url("videos/clip.mp4")
    path = url.split(settings.public_base_url.rstrip("/"), 1)[1]

    response = client.get(path)
    assert response.status_code == 200
    assert response.content == b"fake video bytes"
    assert response.headers["content-type"] == "video/mp4"


def test_an_access_token_is_not_a_media_url(client, db, settings):
    """Both are JWTs under the same secret; the purpose claim keeps them apart."""
    athlete = make_athlete(db)
    token = create_access_token(str(athlete.id), settings, role="athlete")
    assert client.get(f"/api/media/{token}").status_code == 404


def test_an_expired_media_url_is_gone(client, settings):
    from app.storage import get_storage

    storage = get_storage(settings)
    storage.store_bytes("videos/old.mp4", b"x", content_type="video/mp4")
    url = storage.signed_url("videos/old.mp4", expires_seconds=-1)
    path = url.split(settings.public_base_url.rstrip("/"), 1)[1]

    assert client.get(path).status_code == 404


def test_storage_keys_cannot_escape_the_root(settings):
    from app.storage import get_storage

    with pytest.raises(ValueError):
        get_storage(settings).path_for("../../etc/passwd")


def test_cors_allows_the_dashboard_origin_only(client):
    allowed = client.options(
        "/api/dashboard/reviews",
        headers={
            "Origin": "http://localhost:5173",
            "Access-Control-Request-Method": "GET",
        },
    )
    denied = client.options(
        "/api/dashboard/reviews",
        headers={
            "Origin": "https://evil.example",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert allowed.headers.get("access-control-allow-origin") == "http://localhost:5173"
    assert "access-control-allow-origin" not in denied.headers


# ---------------------------------------------------------------------------
# Provisioning
# ---------------------------------------------------------------------------


def test_create_official_command(monkeypatch, db_session_factory):
    import contextlib

    import app.cli as cli

    @contextlib.contextmanager
    def scope():
        session = db_session_factory()
        try:
            yield session
            session.commit()
        finally:
            session.close()

    monkeypatch.setattr(cli, "session_scope", scope)

    assert (
        cli.create_official_command(
            "New@SAI.example",
            "Reviewer",
            "regional_reviewer",
            "tamil nadu",
            password=PASSWORD,
        )
        == 0
    )

    session = db_session_factory()
    official = session.execute(select(Official)).scalars().one()
    assert official.email == "new@sai.example"
    assert official.region == "Tamil Nadu"
    assert verify_password(PASSWORD, official.password_hash)
    session.close()

    # Weak passwords and region-less reviewers are refused.
    assert (
        cli.create_official_command(
            "a@b.c", "X", "regional_reviewer", "Kerala", password="short"
        )
        == 1
    )
    assert (
        cli.create_official_command(
            "a@b.c", "X", "regional_reviewer", None, password=PASSWORD
        )
        == 1
    )

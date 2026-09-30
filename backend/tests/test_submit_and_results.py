"""Submission, result retrieval and the dashboard review flow."""

from __future__ import annotations

import hashlib
import os
import uuid

from app.models import (
    Flag,
    FlagSeverity,
    FlagSource,
    TestResult,
    TestResultStatus,
)

CHUNK_SIZE = 1024


def upload_a_video(client, test_type: str = "SIT_UPS") -> str:
    video = os.urandom(2048)

    init = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": test_type,
            "file_size_bytes": len(video),
            "checksum_sha256": hashlib.sha256(video).hexdigest(),
            "chunk_size_bytes": CHUNK_SIZE,
        },
    ).json()

    for index in range(2):
        client.put(
            f"/api/videos/upload/{init['upload_id']}/chunks/{index}",
            content=video[index * CHUNK_SIZE : (index + 1) * CHUNK_SIZE],
        )

    return client.post(
        f"/api/videos/upload/{init['upload_id']}/complete"
    ).json()["video_id"]


def test_submission_creates_a_result_awaiting_verification(client, seeded_tests):
    video_id = upload_a_video(client)

    response = client.post(
        "/api/tests/submit",
        json={
            "test_id": "SIT_UPS",
            "provisional_score": 24,
            "video_id": video_id,
        },
    )

    assert response.status_code == 201, response.text

    body = response.json()

    # Never "verified" on submission. The device's number is provisional until
    # the server has independently re-scored the video; `uploaded` until a
    # worker claims it (Sprint 11 lifecycle).
    assert body["status"] == "uploaded"
    assert body["result_id"]


def test_submission_queues_verification(client, seeded_tests):
    video_id = upload_a_video(client)

    client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 24, "video_id": video_id},
    )

    assert len(client.queued_verifications) == 1


def test_submission_accepts_the_test_code_or_its_uuid(client, seeded_tests):
    video_id = upload_a_video(client)

    by_code = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 10, "video_id": video_id},
    )
    by_uuid = client.post(
        "/api/tests/submit",
        json={
            "test_id": str(seeded_tests["SIT_UPS"].id),
            "provisional_score": 11,
        },
    )

    assert by_code.status_code == 201
    assert by_uuid.status_code == 201


def test_unknown_test_is_rejected(client, seeded_tests):
    response = client.post(
        "/api/tests/submit",
        json={"test_id": "POLE_VAULT", "provisional_score": 5},
    )
    assert response.status_code == 400


def test_unknown_video_is_rejected(client, seeded_tests):
    response = client.post(
        "/api/tests/submit",
        json={
            "test_id": "SIT_UPS",
            "provisional_score": 5,
            "video_id": str(uuid.uuid4()),
        },
    )
    assert response.status_code == 400


def test_attempt_numbers_increment_per_athlete_and_test(client, seeded_tests, db):
    for score in (10, 12, 14):
        client.post(
            "/api/tests/submit",
            json={"test_id": "SIT_UPS", "provisional_score": score},
        )

    results = db.query(TestResult).order_by(TestResult.attempt_number).all()
    assert [r.attempt_number for r in results] == [1, 2, 3]


def test_result_endpoint_exposes_both_scores_and_flags(client, seeded_tests, db):
    submit = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 30},
    ).json()

    result = db.get(TestResult, uuid.UUID(submit["result_id"]))
    result.server_score = 18
    result.status = TestResultStatus.flagged
    db.add(result)
    db.add(
        Flag(
            test_result_id=result.id,
            source=FlagSource.auto,
            reason="score_discrepancy",
            detail="Device reported 30 reps, server measured 18 reps",
            severity=FlagSeverity.high,
        )
    )
    db.commit()

    response = client.get(f"/api/results/{submit['result_id']}")
    body = response.json()

    assert response.status_code == 200
    # Both numbers are surfaced. Hiding the disagreement would remove the very
    # thing that makes the final result defensible.
    assert body["provisional_score"] == 30
    assert body["server_score"] == 18
    assert body["status"] == "flagged"
    assert body["flags"][0]["reason"] == "score_discrepancy"
    assert "18" in body["flags"][0]["detail"]


def test_unknown_result_is_404(client, seeded_tests):
    assert client.get(f"/api/results/{uuid.uuid4()}").status_code == 404


def admin_headers(client, db) -> dict[str, str]:
    """The dashboard needs a signed-in official, even on a development server."""
    from app.config import get_settings
    from app.models import Official, OfficialRole
    from app.security import create_access_token

    admin = Official(
        id=uuid.uuid4(), name="Admin", email="admin@sai.example",
        role=OfficialRole.sai_admin,
    )
    db.add(admin)
    db.commit()
    token = create_access_token(
        str(admin.id), client.app.dependency_overrides[get_settings](), role="sai_admin"
    )
    return {"Authorization": f"Bearer {token}"}


def test_review_queue_defaults_to_work_needing_a_human(client, seeded_tests, db):
    flagged = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 30},
    ).json()
    verified = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 12},
    ).json()

    for result_id, status in (
        (flagged["result_id"], TestResultStatus.flagged),
        (verified["result_id"], TestResultStatus.verified),
    ):
        record = db.get(TestResult, uuid.UUID(result_id))
        record.status = status
        db.add(record)
    db.commit()

    queue = client.get(
        "/api/dashboard/reviews", headers=admin_headers(client, db)
    ).json()
    ids = {item["result_id"] for item in queue}

    assert flagged["result_id"] in ids
    assert verified["result_id"] not in ids


def test_review_queue_filters_by_test_type(client, seeded_tests, db):
    client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 30},
    )
    client.post(
        "/api/tests/submit",
        json={"test_id": "VERTICAL_JUMP", "provisional_score": 42},
    )

    for record in db.query(TestResult).all():
        record.status = TestResultStatus.flagged
        db.add(record)
    db.commit()

    queue = client.get(
        "/api/dashboard/reviews?test_type=VERTICAL_JUMP",
        headers=admin_headers(client, db),
    ).json()

    assert len(queue) == 1
    assert queue[0]["test_type"] == "VERTICAL_JUMP"


def test_approval_sets_the_final_score_from_the_server_number(
    client, seeded_tests, db, official
):
    submit = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 30},
    ).json()

    result = db.get(TestResult, uuid.UUID(submit["result_id"]))
    result.server_score = 18
    result.status = TestResultStatus.flagged
    athlete_id = result.athlete_id
    db.add(result)

    # Region must match the reviewer's for access.
    from app.models import Athlete

    athlete = db.get(Athlete, athlete_id)
    athlete.region = official.region
    db.add(athlete)
    db.commit()

    from app.config import get_settings
    from app.security import create_access_token

    token = create_access_token(
        str(official.id),
        client.app.dependency_overrides[get_settings](),
        role="regional_reviewer",
    )

    response = client.post(
        f"/api/dashboard/reviews/{submit['result_id']}/action",
        json={"action": "approved", "notes": "Verified against the recording"},
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 200, response.text

    db.expire_all()
    updated = db.get(TestResult, uuid.UUID(submit["result_id"]))

    assert updated.status == TestResultStatus.approved
    # The official number is the SERVER's, never the device's.
    assert float(updated.final_score) == 18.0


def test_review_action_is_recorded_for_audit(client, seeded_tests, db, official):
    from app.config import get_settings
    from app.models import Athlete, ReviewActionRecord
    from app.security import create_access_token

    submit = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 30},
    ).json()

    result = db.get(TestResult, uuid.UUID(submit["result_id"]))
    # A decision needs the machine verdict first (Sprint 14).
    result.status = TestResultStatus.flagged
    athlete = db.get(Athlete, result.athlete_id)
    athlete.region = official.region
    db.add_all([result, athlete])
    db.commit()

    token = create_access_token(
        str(official.id),
        client.app.dependency_overrides[get_settings](),
        role="regional_reviewer",
    )

    response = client.post(
        f"/api/dashboard/reviews/{submit['result_id']}/action",
        json={
            "action": "rejected",
            "reason": "identity_mismatch",
            "notes": "Wrong person in frame",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200, response.text

    records = db.query(ReviewActionRecord).all()

    assert len(records) == 1
    assert records[0].official_id == official.id
    assert records[0].notes == "Wrong person in frame"
    assert records[0].reason == "identity_mismatch"
    assert (records[0].previous_status, records[0].new_status) == ("flagged", "rejected")


def test_review_action_requires_an_identified_official(client, seeded_tests):
    """An audit trail with anonymous entries is not an audit trail."""
    submit = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 30},
    ).json()

    response = client.post(
        f"/api/dashboard/reviews/{submit['result_id']}/action",
        json={"action": "approved"},
    )

    assert response.status_code == 401


def test_regional_reviewer_cannot_reach_another_region(
    client, seeded_tests, db, official
):
    from app.config import get_settings
    from app.models import Athlete
    from app.security import create_access_token

    submit = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 30},
    ).json()

    result = db.get(TestResult, uuid.UUID(submit["result_id"]))
    athlete = db.get(Athlete, result.athlete_id)
    athlete.region = "Kerala"  # reviewer covers Tamil Nadu
    db.add(athlete)
    db.commit()

    token = create_access_token(
        str(official.id),
        client.app.dependency_overrides[get_settings](),
        role="regional_reviewer",
    )

    response = client.get(
        f"/api/dashboard/reviews/{submit['result_id']}",
        headers={"Authorization": f"Bearer {token}"},
    )

    # 404, not 403: confirming the result exists would leak information the
    # reviewer is not entitled to.
    assert response.status_code == 404


def test_health_reports_database_state(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["database"] == "up"


def test_verification_sla_endpoint_counts_backlog(client, seeded_tests):
    client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 20},
    )

    body = client.get("/health/verification").json()

    assert body["pending"] == 1
    assert body["sla_seconds"] == 300
    assert body["breaching_sla"] == 0

"""Athlete profile, photo, history and the benchmark attached to a result."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from app.models import TestResult, TestResultStatus
from app.security import create_access_token
from app.services.benchmark_seed import seed_benchmarks


def auth(athlete, settings) -> dict:
    token = create_access_token(str(athlete.id), settings, role="athlete")
    return {"Authorization": f"Bearer {token}"}


def consent_to_photos(client, headers) -> None:
    response = client.post(
        "/api/athletes/me/consents",
        json={"purpose": "face_verification", "version": "2026-09", "given_by": "self"},
        headers=headers,
    )
    assert response.status_code == 200, response.text


def add_result(db, athlete, test, **fields) -> TestResult:
    result = TestResult(
        id=uuid.uuid4(),
        athlete_id=athlete.id,
        test_id=test.id,
        attempt_number=fields.pop("attempt_number", 1),
        **fields,
    )
    db.add(result)
    db.commit()
    return result


def test_profile_is_returned_for_the_caller(client, athlete, settings):
    response = client.get("/api/athletes/me", headers=auth(athlete, settings))

    assert response.status_code == 200
    body = response.json()
    assert body["athlete_id"] == str(athlete.id)
    assert body["has_reference_photo"] is False
    assert body["age_years"] >= 9


def test_profile_requires_a_token(client, athlete):
    # The dev bypass returns no athlete, and this endpoint needs one.
    assert client.get("/api/athletes/me").status_code == 401


def test_profile_update_changes_only_mutable_fields(client, athlete, settings):
    response = client.patch(
        "/api/athletes/me",
        json={"height_cm": 170.5, "region": "Kerala", "gender": "male"},
        headers=auth(athlete, settings),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["height_cm"] == 170.5
    assert body["region"] == "Kerala"
    # Gender selects the benchmark cohort and is not self-service.
    assert body["gender"] == "female"


def test_implausible_height_is_refused(client, athlete, settings):
    response = client.patch(
        "/api/athletes/me", json={"height_cm": 900}, headers=auth(athlete, settings)
    )
    assert response.status_code == 422


def test_photo_upload_sets_the_reference_face(client, athlete, settings, db):
    headers = auth(athlete, settings)
    consent_to_photos(client, headers)
    response = client.post(
        "/api/athletes/me/photo",
        files={"file": ("face.jpg", b"\xff\xd8\xff fake jpeg", "image/jpeg")},
        headers=headers,
    )

    assert response.status_code == 200
    db.refresh(athlete)
    assert athlete.reference_face_key
    stored = settings.storage_local_path / athlete.reference_face_key
    assert stored.exists()
    # Encrypted before storage: the JPEG is nowhere in what was written.
    assert b"fake jpeg" not in stored.read_bytes()


def test_replacing_the_photo_deletes_the_old_one(client, athlete, settings, db):
    headers = auth(athlete, settings)
    consent_to_photos(client, headers)
    files = {"file": ("face.jpg", b"first", "image/jpeg")}
    client.post("/api/athletes/me/photo", files=files, headers=headers)
    db.refresh(athlete)
    first_key = athlete.reference_face_key

    files = {"file": ("face.jpg", b"second", "image/jpeg")}
    client.post("/api/athletes/me/photo", files=files, headers=headers)
    db.refresh(athlete)

    assert athlete.reference_face_key != first_key
    # Superseded biometric data is not retained.
    assert not (settings.storage_local_path / first_key).exists()


def test_non_image_photo_is_refused(client, athlete, settings):
    headers = auth(athlete, settings)
    consent_to_photos(client, headers)
    response = client.post(
        "/api/athletes/me/photo",
        files={"file": ("x.txt", b"hello", "text/plain")},
        headers=headers,
    )
    assert response.status_code == 415


def test_summary_lists_history_and_only_verified_bests(
    client, athlete, settings, db, seeded_tests
):
    situps = seeded_tests["SIT_UPS"]
    add_result(
        db, athlete, situps, attempt_number=1,
        provisional_score=50, status=TestResultStatus.processing,
    )
    add_result(
        db, athlete, situps, attempt_number=2,
        provisional_score=30, server_score=28,
        status=TestResultStatus.verified, verified_at=datetime.now(UTC),
    )
    add_result(
        db, athlete, situps, attempt_number=3,
        provisional_score=99, server_score=99, status=TestResultStatus.flagged,
    )

    response = client.get("/api/athletes/me/summary", headers=auth(athlete, settings))

    assert response.status_code == 200
    body = response.json()
    assert body["total_tests"] == 3
    bests = body["personal_bests"]
    assert len(bests) == 1
    # The unverified 50 and the flagged 99 are not achievements.
    assert bests[0]["score"] == 28
    assert bests[0]["official"] is False


def test_an_approved_result_is_an_official_best(
    client, athlete, settings, db, seeded_tests
):
    add_result(
        db, athlete, seeded_tests["SIT_UPS"],
        server_score=30, final_score=30, status=TestResultStatus.approved,
    )

    body = client.get(
        "/api/athletes/me/summary", headers=auth(athlete, settings)
    ).json()
    assert body["personal_bests"][0]["official"] is True


@pytest.fixture
def benchmarked(db, seeded_tests):
    seed_benchmarks(db)
    return seeded_tests


def test_result_carries_a_provisional_benchmark(
    client, athlete, settings, db, benchmarked
):
    result = add_result(
        db, athlete, benchmarked["SIT_UPS"],
        provisional_score=40, server_score=40, status=TestResultStatus.verified,
    )

    body = client.get(
        f"/api/results/{result.id}", headers=auth(athlete, settings)
    ).json()

    assert body["benchmark"] is not None
    assert body["benchmark"]["provisional"] is True
    assert "PROVISIONAL" in body["benchmark"]["source"]
    assert body["benchmark_unavailable"] is None


def test_an_unverified_result_is_not_benchmarked(
    client, athlete, settings, db, benchmarked
):
    """A phone's own number must never be ranked as though it were verified."""
    result = add_result(
        db, athlete, benchmarked["SIT_UPS"],
        provisional_score=60, status=TestResultStatus.processing,
    )

    body = client.get(
        f"/api/results/{result.id}", headers=auth(athlete, settings)
    ).json()

    assert body["benchmark"] is None
    assert body["benchmark_unavailable"]


def test_another_athletes_result_is_not_found(
    client, athlete, settings, db, benchmarked
):
    other_result = add_result(
        db, athlete, benchmarked["SIT_UPS"], status=TestResultStatus.processing
    )
    stranger = create_access_token(str(uuid.uuid4()), settings, role="athlete")

    response = client.get(
        f"/api/results/{other_result.id}",
        headers={"Authorization": f"Bearer {stranger}"},
    )
    # The stranger's token names no athlete at all.
    assert response.status_code == 401


# ---------------------------------------------------------------------------
# Submission idempotency and ownership
# ---------------------------------------------------------------------------


def _upload(client, headers) -> str:
    import hashlib
    import os

    video = os.urandom(1024)
    init = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": "SIT_UPS",
            "file_size_bytes": len(video),
            "checksum_sha256": hashlib.sha256(video).hexdigest(),
            "chunk_size_bytes": 1024,
        },
        headers=headers,
    ).json()
    client.put(
        f"/api/videos/upload/{init['upload_id']}/chunks/0",
        content=video,
        headers=headers,
    )
    return client.post(
        f"/api/videos/upload/{init['upload_id']}/complete", headers=headers
    ).json()["video_id"]


def test_resubmitting_the_same_video_returns_the_same_result(
    client, athlete, settings, seeded_tests
):
    """A retry after a lost response must not create a second attempt."""
    headers = auth(athlete, settings)
    video_id = _upload(client, headers)
    body = {"test_id": "SIT_UPS", "provisional_score": 20, "video_id": video_id}

    first = client.post("/api/tests/submit", json=body, headers=headers)
    second = client.post("/api/tests/submit", json=body, headers=headers)

    assert first.status_code == 201
    assert second.status_code in (200, 201)
    assert first.json()["result_id"] == second.json()["result_id"]
    # Verified once, not twice.
    assert len(client.queued_verifications) == 1


def test_an_athlete_cannot_submit_another_athletes_video(
    client, athlete, settings, seeded_tests, db
):
    from datetime import date

    from app.models import Athlete

    owner_headers = auth(athlete, settings)
    video_id = _upload(client, owner_headers)

    thief = Athlete(
        id=uuid.uuid4(), name="Other", dob=date(2007, 1, 1), gender="male",
        region="Tamil Nadu", phone="9111111111",
    )
    db.add(thief)
    db.commit()

    response = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 99, "video_id": video_id},
        headers=auth(thief, settings),
    )

    assert response.status_code == 400
    assert client.queued_verifications == []


# ---------------------------------------------------------------------------
# Regions
# ---------------------------------------------------------------------------


def test_region_is_normalised_to_its_canonical_spelling(client, athlete, settings):
    response = client.patch(
        "/api/athletes/me",
        json={"region": "  tamil   NADU "},
        headers=auth(athlete, settings),
    )
    assert response.status_code == 200
    assert response.json()["region"] == "Tamil Nadu"


def test_an_unknown_region_is_refused(client, athlete, settings):
    """A misspelt region hides the athlete from their regional reviewer."""
    response = client.patch(
        "/api/athletes/me",
        json={"region": "Tamilnadu"},
        headers=auth(athlete, settings),
    )
    assert response.status_code == 422


def test_mobile_region_list_matches_the_server():
    """The phone offers exactly the regions the server accepts."""
    import re
    from pathlib import Path

    from app.regions import STATES_AND_UNION_TERRITORIES

    kotlin = (
        Path(__file__).resolve().parents[2]
        / "mobile/app/src/main/java/com/sai/sports/data/Regions.kt"
    )
    if not kotlin.exists():
        pytest.skip("mobile source not present")

    mobile = re.findall(r'^\s*"([^"]+)",?\s*$', kotlin.read_text(encoding="utf-8"), re.M)
    assert tuple(mobile) == STATES_AND_UNION_TERRITORIES

"""Identity: registration, consent, the encrypted photo and the pre-test check.

The Sprint 8 Definition of Done, as a test: an athlete registers, verifies an
OTP, completes their profile, signs in again and passes identity verification
for an official test. Around it, the rules that make that trustworthy — a
guardian's consent for minors, a photo that is encrypted and only kept with
consent, and a check that routes doubt to a reviewer instead of refusing.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app import cli, tasks
from app.models import (
    AthleteConsent,
    ConsentPurpose,
    IdentityCheck,
    IdentityCheckOutcome,
    Official,
    OfficialRole,
    TestResult,
)
from app.routers import identity as identity_router
from app.security import create_access_token
from app.services import identity_crypto
from app.storage import get_storage
from app.verification.cheat.findings import CheatCheck, Severity
from app.verification.cheat.identity import identity_finding

from .test_sessions import athlete_auth, create_session, submit

PHONE = "9876543210"
CONSENT = {"version": "2026-09", "given_by": "self"}
JPEG = b"\xff\xd8\xff\xe0 registration photo bytes"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


@pytest.fixture
def admin(db) -> Official:
    record = Official(
        id=uuid.uuid4(),
        name="SAI Admin",
        email="admin@sai.example",
        role=OfficialRole.sai_admin,
        region=None,
    )
    db.add(record)
    db.commit()
    return record


@pytest.fixture(autouse=True)
def no_resend_cooldown(monkeypatch):
    """These tests sign the same phone in several times in a second; the resend
    cooldown that would refuse that is tested in test_auth.py."""
    from app.services.otp import OtpService

    monkeypatch.setattr(OtpService, "_enforce_request_limits", lambda *args: None)


@pytest.fixture
def matcher(monkeypatch):
    """Stands in for the face embedder, which needs models and real faces."""
    outcomes: list = []

    def compare(photo, reference):
        assert reference == JPEG, "the check must compare against the decrypted photo"
        outcome = outcomes.pop(0) if outcomes else IdentityCheckOutcome.match
        similarity = 0.9 if outcome is IdentityCheckOutcome.match else 0.2
        return outcome, similarity, None

    monkeypatch.setattr(identity_router, "compare", compare)
    return outcomes


def sign_in(client, phone=PHONE) -> dict:
    requested = client.post("/api/auth/request-otp", json={"phone": phone})
    assert requested.status_code == 200, requested.text
    otp = requested.json()["development_code"]
    response = client.post("/api/auth/verify-otp", json={"phone": phone, "otp": otp})
    assert response.status_code == 200, response.text
    return response.json()


def registration(**overrides) -> dict:
    body = {
        "name": "Meena Kumari",
        "dob": "2011-05-20",
        "gender": "female",
        "region": "Tamil Nadu",
        "city": "Madurai",
        "place": "Thirumangalam",
        "height_cm": 150,
        "weight_kg": 40,
        "achievements": "District 100m, second place",
        "consent": {
            "version": "2026-09",
            "given_by": "guardian",
            "guardian_name": "R. Kumar",
        },
    }
    body.update(overrides)
    return body


def register(client, **overrides):
    token = sign_in(client)["access_token"]
    return client.post(
        "/api/athletes/register",
        json=registration(**overrides),
        headers={"Authorization": f"Bearer {token}"},
    )


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def complete_profile(client, headers, *, given_by="guardian"):
    consent = {"purpose": "face_verification", "version": "2026-09", "given_by": given_by}
    if given_by == "guardian":
        consent["guardian_name"] = "R. Kumar"
    response = client.post("/api/athletes/me/consents", json=consent, headers=headers)
    assert response.status_code == 200, response.text
    response = client.post(
        "/api/athletes/me/photo",
        files={"file": ("face.jpg", JPEG, "image/jpeg")},
        headers=headers,
    )
    assert response.status_code == 200, response.text


def check(client, headers, **form):
    return client.post(
        "/api/athletes/me/identity-checks",
        files={"file": ("selfie.jpg", b"\xff\xd8\xff selfie", "image/jpeg")},
        data=form,
        headers=headers,
    )


# ---------------------------------------------------------------------------
# The Definition of Done
# ---------------------------------------------------------------------------


def test_register_verify_complete_sign_in_again_and_pass_identity_for_an_official_test(
    client, db, admin, settings, seeded_tests, matcher
):
    # Register: a verified phone, then the profile — with a guardian's consent,
    # because Meena is fifteen.
    created = register(client)
    assert created.status_code == 201, created.text
    profile = created.json()["profile"]
    assert profile["city"] == "Madurai"
    assert profile["place"] == "Thirumangalam"
    assert profile["achievements"] == "District 100m, second place"
    assert profile["consents"] == ["registration"]
    # Not yet ready for an official test.
    assert profile["missing"] == ["face_consent", "photo"]

    # Complete the profile: consent to face verification, then the photo.
    headers = bearer(created.json()["tokens"]["access_token"])
    complete_profile(client, headers)
    me = client.get("/api/athletes/me", headers=headers).json()
    assert me["missing"] == []
    assert me["has_reference_photo"] is True

    # Sign in again, on another phone for all the server knows.
    again = sign_in(client)
    assert again["registered"] is True
    headers = bearer(again["access_token"])

    # The photo check before the official test passes.
    session = create_session(client, admin, settings)
    passed = check(client, headers, session_id=session["id"])
    assert passed.status_code == 201, passed.text
    assert passed.json()["outcome"] == "match"

    # ...and the official attempt carries it.
    response = client.post(
        "/api/tests/submit",
        json={
            "test_id": "SIT_UPS",
            "provisional_score": 20,
            "session_id": session["id"],
            "recorded_at_ms": int(datetime.now(UTC).timestamp() * 1000),
            "identity_check_id": passed.json()["check_id"],
        },
        headers=headers,
    )
    assert response.status_code == 201, response.text
    result = db.get(TestResult, uuid.UUID(response.json()["result_id"]))
    assert str(result.identity_check_id) == passed.json()["check_id"]

    # Nothing about identity reaches the reviewer for a match.
    stored = db.get(IdentityCheck, result.identity_check_id)
    assert identity_finding(official=True, outcome=stored.outcome) is None


# ---------------------------------------------------------------------------
# Registration and consent
# ---------------------------------------------------------------------------


def test_a_minor_needs_a_named_guardian_to_consent(client):
    myself = register(client, consent=CONSENT)
    assert myself.status_code == 422
    assert "parent or guardian" in myself.json()["detail"]

    unnamed = register(client, consent={"version": "2026-09", "given_by": "guardian"})
    assert unnamed.status_code == 422
    assert "name" in unnamed.json()["detail"]


def test_an_adult_consents_for_themselves(client):
    adult = register(client, dob="2000-01-01", consent=CONSENT)
    assert adult.status_code == 201, adult.text


def test_registration_needs_a_city_and_consent(client):
    assert register(client, city="  ").status_code == 422
    token = sign_in(client)["access_token"]
    body = registration()
    del body["consent"]
    response = client.post("/api/athletes/register", json=body, headers=bearer(token))
    assert response.status_code == 422


def test_a_refused_consent_leaves_no_half_made_profile(client, db):
    assert register(client, consent=CONSENT).status_code == 422
    # The athlete can fix it and register — the failed attempt left nothing behind.
    assert register(client).status_code == 201


def test_athletes_from_before_consent_are_asked_for_it(client, athlete, settings):
    headers = athlete_auth(athlete, settings)
    me = client.get("/api/athletes/me", headers=headers).json()
    assert me["missing"] == ["city", "registration_consent", "face_consent", "photo"]

    client.patch("/api/athletes/me", json={"city": "Chennai"}, headers=headers)
    given = client.post(
        "/api/athletes/me/consents",
        json={"purpose": "registration", **CONSENT},
        headers=headers,
    )
    assert given.status_code == 200, given.text
    assert given.json()["missing"] == ["face_consent", "photo"]


def test_profile_edits_place_city_and_achievements(client, athlete, settings):
    headers = athlete_auth(athlete, settings)
    body = client.patch(
        "/api/athletes/me",
        json={"city": "Salem", "place": "Omalur", "achievements": "State relay team"},
        headers=headers,
    ).json()
    assert (body["city"], body["place"], body["achievements"]) == (
        "Salem",
        "Omalur",
        "State relay team",
    )

    cleared = client.patch(
        "/api/athletes/me", json={"place": "", "achievements": " "}, headers=headers
    ).json()
    assert cleared["place"] is None and cleared["achievements"] is None

    blank_city = client.patch("/api/athletes/me", json={"city": " "}, headers=headers)
    assert blank_city.status_code == 422


def test_giving_consent_again_supersedes_rather_than_duplicates(
    client, db, athlete, settings
):
    headers = athlete_auth(athlete, settings)
    for version in ("2026-09", "2026-12"):
        client.post(
            "/api/athletes/me/consents",
            json={"purpose": "face_verification", "version": version, "given_by": "self"},
            headers=headers,
        )

    rows = db.query(AthleteConsent).filter_by(athlete_id=athlete.id).all()
    live = [row for row in rows if row.withdrawn_at is None]
    assert len(rows) == 2
    assert [row.version for row in live] == ["2026-12"]


# ---------------------------------------------------------------------------
# The photo: consent, encryption, withdrawal
# ---------------------------------------------------------------------------


def test_no_photo_is_stored_without_consent_to_face_verification(
    client, athlete, settings
):
    response = client.post(
        "/api/athletes/me/photo",
        files={"file": ("face.jpg", JPEG, "image/jpeg")},
        headers=athlete_auth(athlete, settings),
    )
    assert response.status_code == 403


def test_the_photo_is_encrypted_and_bound_to_its_key(client, db, athlete, settings):
    complete_profile(client, athlete_auth(athlete, settings), given_by="self")
    db.refresh(athlete)
    key = athlete.reference_face_key
    storage = get_storage(settings)

    blob = (settings.storage_local_path / key).read_bytes()
    assert JPEG not in blob
    assert identity_crypto.load_photo(storage, settings, key) == JPEG

    # Copied under another athlete's key, it does not decrypt.
    with pytest.raises(identity_crypto.IdentityDecryptError):
        identity_crypto.decrypt(settings, key.replace(str(athlete.id), "someone"), blob)

    # Nor under a different encryption key.
    other = settings.model_copy(update={"jwt_secret": "another-deployment"})
    with pytest.raises(identity_crypto.IdentityDecryptError):
        identity_crypto.decrypt(other, key, blob)


def test_production_refuses_to_run_without_an_encryption_key(settings):
    production = settings.model_copy(update={"environment": "production"})
    with pytest.raises(RuntimeError):
        identity_crypto.encrypt(production, "reference-faces/x.jpg.enc", JPEG)


def test_photos_stored_before_encryption_are_still_read_and_can_be_converted(
    db, athlete, settings, monkeypatch
):
    storage = get_storage(settings)
    legacy = f"reference-faces/{athlete.id}/old.jpg"
    storage.store_bytes(legacy, JPEG, content_type="image/jpeg")
    athlete.reference_face_key = legacy
    db.commit()

    assert identity_crypto.load_photo(storage, settings, legacy) == JPEG

    from contextlib import contextmanager

    @contextmanager
    def scope():
        yield db
        db.commit()

    monkeypatch.setattr(cli, "session_scope", scope)
    monkeypatch.setattr(cli, "get_settings", lambda: settings)
    assert cli.encrypt_photos() == 0

    db.refresh(athlete)
    assert identity_crypto.is_encrypted_key(athlete.reference_face_key)
    converted = athlete.reference_face_key
    assert identity_crypto.load_photo(storage, settings, converted) == JPEG
    assert not storage.exists(legacy)


def test_withdrawing_face_consent_deletes_the_photo(client, db, athlete, settings):
    headers = athlete_auth(athlete, settings)
    complete_profile(client, headers, given_by="self")
    db.refresh(athlete)
    key = athlete.reference_face_key

    response = client.delete(
        "/api/athletes/me/consents/face_verification", headers=headers
    )
    assert response.status_code == 200, response.text
    assert response.json()["has_reference_photo"] is False
    assert "face_consent" in response.json()["missing"]
    assert not get_storage(settings).exists(key)

    # Holding the profile at all is not withdrawn from the app.
    kept = client.delete("/api/athletes/me/consents/registration", headers=headers)
    assert kept.status_code == 422


# ---------------------------------------------------------------------------
# The photo check before an official test
# ---------------------------------------------------------------------------


def test_the_check_needs_consent_and_a_photo(client, athlete, settings, matcher):
    headers = athlete_auth(athlete, settings)
    assert check(client, headers).status_code == 409

    client.post(
        "/api/athletes/me/consents",
        json={"purpose": "face_verification", **CONSENT},
        headers=headers,
    )
    no_photo = check(client, headers)
    assert no_photo.status_code == 409
    assert "registration photo" in no_photo.json()["detail"]


def test_a_failed_check_can_be_retried_and_nothing_of_the_photo_is_kept(
    client, db, athlete, settings, matcher
):
    headers = athlete_auth(athlete, settings)
    complete_profile(client, headers, given_by="self")
    matcher.extend([IdentityCheckOutcome.no_face, IdentityCheckOutcome.no_match])

    outcomes = [check(client, headers).json()["outcome"] for _ in range(3)]
    assert outcomes == ["no_face", "no_match", "match"]

    rows = db.query(IdentityCheck).filter_by(athlete_id=athlete.id).all()
    assert sorted(row.outcome.value for row in rows) == ["match", "no_face", "no_match"]
    # Only the registration photo is in storage; the checked photos never were.
    stored = [p for p in settings.storage_local_path.rglob("*") if p.is_file()]
    assert len(stored) == 1


def test_checks_are_capped_per_hour(client, athlete, settings, matcher):
    headers = athlete_auth(athlete, settings)
    complete_profile(client, headers, given_by="self")
    limit = settings.identity_checks_per_hour

    remaining = [
        check(client, headers).json()["remaining_this_hour"] for _ in range(limit)
    ]
    assert remaining[0] == limit - 1 and remaining[-1] == 0

    refused = check(client, headers)
    assert refused.status_code == 429
    assert refused.headers["Retry-After"]


def test_a_check_that_cannot_run_is_recorded_as_unavailable(
    client, athlete, settings, monkeypatch
):
    headers = athlete_auth(athlete, settings)
    complete_profile(client, headers, given_by="self")
    # The real comparison, with no face models in the test environment.
    monkeypatch.setattr(identity_router, "MODELS_DIR", settings.storage_local_path)

    response = check(client, headers)
    assert response.status_code == 201
    assert response.json()["outcome"] in {"unavailable", "no_face"}


def test_a_future_capture_time_is_dropped(client, db, athlete, settings, matcher):
    headers = athlete_auth(athlete, settings)
    complete_profile(client, headers, given_by="self")
    future = int((datetime.now(UTC) + timedelta(days=1)).timestamp() * 1000)

    body = check(client, headers, captured_at_ms=str(future)).json()
    assert db.get(IdentityCheck, uuid.UUID(body["check_id"])).captured_at is None


def test_an_attempt_cannot_borrow_someone_elses_check(
    client, db, admin, athlete, settings, seeded_tests, matcher
):
    other = register(client, dob="2000-01-01", consent=CONSENT)
    other_headers = bearer(other.json()["tokens"]["access_token"])
    complete_profile(client, other_headers, given_by="self")
    borrowed = check(client, other_headers).json()["check_id"]

    session = create_session(client, admin, settings)
    response = client.post(
        "/api/tests/submit",
        json={
            "test_id": "SIT_UPS",
            "provisional_score": 20,
            "session_id": session["id"],
            "recorded_at_ms": int(datetime.now(UTC).timestamp() * 1000),
            "identity_check_id": borrowed,
        },
        headers=athlete_auth(athlete, settings),
    )
    assert response.status_code == 400


def test_an_official_attempt_without_a_passed_check_still_lands(
    client, admin, athlete, settings, seeded_tests
):
    """Doubt costs a reviewer's attention, never the athlete's submission."""
    session = create_session(client, admin, settings)
    response = submit(client, athlete_auth(athlete, settings), session["id"])
    assert response.status_code == 201, response.text


# ---------------------------------------------------------------------------
# What the reviewer sees
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("official", "outcome", "severity"),
    [
        (True, None, Severity.LOW),
        (True, IdentityCheckOutcome.no_match, Severity.MEDIUM),
        (True, IdentityCheckOutcome.no_face, Severity.LOW),
        (True, IdentityCheckOutcome.unavailable, Severity.LOW),
        (True, IdentityCheckOutcome.match, None),
        (False, None, None),
        (False, IdentityCheckOutcome.no_match, None),
    ],
)
def test_identity_findings(official, outcome, severity):
    finding = identity_finding(official=official, outcome=outcome)
    if severity is None:
        assert finding is None
    else:
        assert finding.check is CheatCheck.IDENTITY_UNCONFIRMED
        # Never high: the matcher has not earned that confidence.
        assert finding.severity is severity


def test_verification_decrypts_the_photo_only_into_its_working_directory(
    client, db, athlete, settings, tmp_path
):
    complete_profile(client, athlete_auth(athlete, settings), given_by="self")
    db.refresh(athlete)

    path = tasks._materialise_reference_face(
        get_storage(settings), athlete.reference_face_key, tmp_path, settings
    )
    assert path.parent == tmp_path
    assert path.read_bytes() == JPEG


def test_the_reviewer_sees_the_check_and_a_decrypting_photo_link(
    client, db, admin, athlete, settings, seeded_tests, matcher
):
    headers = athlete_auth(athlete, settings)
    complete_profile(client, headers, given_by="self")
    matcher.append(IdentityCheckOutcome.no_match)
    check_id = check(client, headers).json()["check_id"]

    session = create_session(client, admin, settings)
    submitted = client.post(
        "/api/tests/submit",
        json={
            "test_id": "SIT_UPS",
            "provisional_score": 20,
            "session_id": session["id"],
            "recorded_at_ms": int(datetime.now(UTC).timestamp() * 1000),
            "identity_check_id": check_id,
        },
        headers=headers,
    ).json()

    official = {
        "Authorization": "Bearer "
        + create_access_token(str(admin.id), settings, role=admin.role.value)
    }
    detail = client.get(
        f"/api/dashboard/reviews/{submitted['result_id']}", headers=official
    ).json()
    assert detail["identity_check"] == "no_match"

    url = detail["reference_photo_url"]
    assert "/api/media/identity/" in url
    photo = client.get(url.replace(settings.public_base_url, ""))
    assert photo.status_code == 200
    assert photo.content == JPEG
    assert photo.headers["cache-control"] == "private, no-store"

    # A video link's token does not open a photo, nor the reverse.
    token = url.rsplit("/", 1)[1]
    assert client.get(f"/api/media/{token}").status_code == 404
    video_token = get_storage(settings).signed_url("videos/x.mp4").rsplit("/", 1)[1]
    assert client.get(f"/api/media/identity/{video_token}").status_code == 404


def test_consent_purposes_are_exactly_the_two_the_app_asks_for():
    assert {purpose.value for purpose in ConsentPurpose} == {
        "registration",
        "face_verification",
    }

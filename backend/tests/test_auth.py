"""OTP login, registration binding, and token rotation.

The properties worth defending here are not "the happy path works". They are:

* a six-digit code cannot be guessed at leisure,
* the endpoint cannot be used to discover who has an account,
* and a token cannot be used to create a profile against someone else's phone.

Each of those has a test that would fail loudly if the protection were removed.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.models import Athlete, OtpChallenge, RefreshToken
from app.services import tokens as token_service
from app.services.otp import (
    MAX_REQUESTS_PER_WINDOW,
    MAX_VERIFY_ATTEMPTS,
    RESEND_COOLDOWN_SECONDS,
    TTL_SECONDS,
    OtpService,
    RateLimited,
    generate_code,
    hash_code,
)

PHONE = "9876543210"


def later(seconds: int) -> datetime:
    return datetime.now(UTC) + timedelta(seconds=seconds)


# ---------------------------------------------------------------------------
# The OTP service
# ---------------------------------------------------------------------------


def test_a_code_is_never_stored_in_plaintext(db, settings):
    """A database dump must not be a list of live login codes."""
    OtpService(db, settings).request(PHONE)

    challenge = db.execute(select(OtpChallenge)).scalars().one()

    assert challenge.code_hash != "000000"
    assert "000000" not in challenge.code_hash
    assert challenge.code_hash == hash_code(PHONE, "000000", settings)


def test_the_hash_is_keyed_by_the_server_secret(db, settings):
    """Read access to the table alone must not be enough to recover a code.

    If the digest were unkeyed, a million-entry table would invert it instantly.
    """
    from app.config import Settings

    other = Settings(**{**settings.model_dump(), "jwt_secret": "a-different-secret"})

    assert hash_code(PHONE, "123456", settings) != hash_code(PHONE, "123456", other)


def test_the_hash_is_salted_by_phone(db, settings):
    """One rainbow table must not cover every row."""
    assert hash_code("111", "123456", settings) != hash_code("222", "123456", settings)


def test_generated_codes_are_the_right_shape():
    for _ in range(50):
        code = generate_code()
        assert len(code) == 6
        assert code.isdigit()


def test_a_correct_code_verifies(db, settings):
    service = OtpService(db, settings)
    service.request(PHONE)

    assert service.verify(PHONE, "000000") is True


def test_a_code_cannot_be_used_twice(db, settings):
    """Replay of a verified code must not mint a second session."""
    service = OtpService(db, settings)
    service.request(PHONE)

    assert service.verify(PHONE, "000000") is True
    assert service.verify(PHONE, "000000") is False


def test_a_wrong_code_is_refused(db, settings):
    service = OtpService(db, settings)
    service.request(PHONE)

    assert service.verify(PHONE, "999999") is False


def test_guesses_are_capped(db, settings):
    """The real protection on a six-digit code.

    Any hash of a million-item space falls to an offline sweep. What makes the
    code safe is that an attacker gets five online guesses, then the challenge
    is dead even for the correct code.
    """
    service = OtpService(db, settings)
    service.request(PHONE)

    for _ in range(MAX_VERIFY_ATTEMPTS):
        assert service.verify(PHONE, "111111") is False

    # Burned: even the right code no longer works.
    assert service.verify(PHONE, "000000") is False


def test_a_failed_attempt_still_counts(db, settings):
    """An attempt counter that only increments sometimes is not a limit."""
    service = OtpService(db, settings)
    service.request(PHONE)
    service.verify(PHONE, "111111")

    challenge = db.execute(select(OtpChallenge)).scalars().one()
    assert challenge.attempts == 1


def test_an_expired_code_is_refused(db, settings):
    service = OtpService(db, settings)
    service.request(PHONE)

    assert service.verify(PHONE, "000000", now=later(TTL_SECONDS + 1)) is False


def test_requesting_again_retires_the_previous_code(db, settings):
    """Two live codes for one number doubles an attacker's odds for no benefit.

    The athlete is only ever looking at the most recent message.
    """
    service = OtpService(db, settings)
    service.request(PHONE)
    first = db.execute(select(OtpChallenge)).scalars().one()

    service.request(PHONE, now=later(RESEND_COOLDOWN_SECONDS + 1))

    db.refresh(first)
    live = service._live_challenges(PHONE, later(RESEND_COOLDOWN_SECONDS + 2))
    assert len(live) == 1
    assert live[0].id != first.id


def test_resending_immediately_is_rate_limited(db, settings):
    service = OtpService(db, settings)
    service.request(PHONE)

    with pytest.raises(RateLimited) as caught:
        service.request(PHONE)

    # Retry-After is what turns a client that hammers into one that backs off.
    assert caught.value.retry_after_seconds > 0


def test_requests_are_capped_per_window(db, settings):
    service = OtpService(db, settings)

    now = datetime.now(UTC)
    for index in range(MAX_REQUESTS_PER_WINDOW):
        service.request(PHONE, now=now + timedelta(seconds=index * 120))

    with pytest.raises(RateLimited):
        service.request(
            PHONE, now=now + timedelta(seconds=MAX_REQUESTS_PER_WINDOW * 120)
        )


def test_the_limit_is_per_phone(db, settings):
    """One athlete being rate-limited must not lock out the whole camp."""
    service = OtpService(db, settings)
    service.request(PHONE)
    service.request("9000000001")  # no exception


# ---------------------------------------------------------------------------
# The HTTP flow
# ---------------------------------------------------------------------------


def test_request_otp_does_not_reveal_whether_a_number_is_registered(client, athlete):
    """This endpoint must not answer "does this person have an account".

    That is a question about a minor that anyone with a phone number could
    otherwise ask, and refusing to answer it costs nothing.
    """
    registered = client.post("/api/auth/request-otp", json={"phone": athlete.phone})
    unknown = client.post("/api/auth/request-otp", json={"phone": "9000000009"})

    assert registered.status_code == unknown.status_code == 200

    a, b = registered.json(), unknown.json()
    assert a["message"] == b["message"]
    assert set(a) == set(b)


def test_a_registered_athlete_gets_a_token_and_their_id(client, athlete):
    client.post("/api/auth/request-otp", json={"phone": athlete.phone})
    response = client.post(
        "/api/auth/verify-otp", json={"phone": athlete.phone, "otp": "000000"}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["registered"] is True
    assert body["athlete_id"] == str(athlete.id)
    assert body["access_token"] and body["refresh_token"]


def test_an_unknown_number_verifies_but_is_marked_unregistered(client):
    """A verified phone with no profile still gets a token.

    Registration is then an authenticated call by someone who has already
    proved they hold the phone — which is both safer and a better flow than
    refusing them at the door.
    """
    client.post("/api/auth/request-otp", json={"phone": "9000000009"})
    response = client.post(
        "/api/auth/verify-otp", json={"phone": "9000000009", "otp": "000000"}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["registered"] is False
    assert body["athlete_id"] is None
    assert body["access_token"]


def test_a_wrong_code_is_rejected_over_http(client, athlete):
    client.post("/api/auth/request-otp", json={"phone": athlete.phone})
    response = client.post(
        "/api/auth/verify-otp", json={"phone": athlete.phone, "otp": "123456"}
    )

    assert response.status_code == 400


def test_phone_formatting_does_not_create_a_second_identity(client, athlete):
    """Otherwise the per-phone rate limit is walked around by adding a space."""
    client.post("/api/auth/request-otp", json={"phone": "+91 98765 43210"})

    response = client.post(
        "/api/auth/verify-otp", json={"phone": "9876543210", "otp": "000000"}
    )
    assert response.status_code == 200


def test_rate_limiting_returns_429_with_retry_after(client):
    client.post("/api/auth/request-otp", json={"phone": "9000000002"})
    response = client.post("/api/auth/request-otp", json={"phone": "9000000002"})

    assert response.status_code == 429
    assert "Retry-After" in response.headers


# ---------------------------------------------------------------------------
# Refresh tokens
# ---------------------------------------------------------------------------


def test_a_refresh_token_is_not_stored_in_the_clear(db, settings, athlete):
    pair = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )
    record = db.execute(select(RefreshToken)).scalars().one()

    assert record.token_hash != pair.refresh_token
    assert record.token_hash == token_service.hash_refresh_token(pair.refresh_token)


def test_refreshing_rotates_the_token(db, settings, athlete):
    first = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )
    second = token_service.rotate_refresh_token(db, first.refresh_token, settings)

    assert second.refresh_token != first.refresh_token
    assert second.access_token


def test_a_rotated_token_stops_working(db, settings, athlete):
    """A token stolen from a phone dies the moment the real device refreshes."""
    first = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )
    token_service.rotate_refresh_token(db, first.refresh_token, settings)

    with pytest.raises(token_service.InvalidRefreshToken):
        token_service.rotate_refresh_token(db, first.refresh_token, settings)


def test_reusing_a_rotated_token_revokes_the_whole_chain(db, settings, athlete):
    """Reuse means two parties hold the token, and we cannot tell which is real.

    Forcing the athlete to log in once is a far smaller harm than leaving an
    attacker with a working session on a minor's account.
    """
    first = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )
    second = token_service.rotate_refresh_token(db, first.refresh_token, settings)

    with pytest.raises(token_service.InvalidRefreshToken):
        token_service.rotate_refresh_token(db, first.refresh_token, settings)

    # The legitimate device's token is revoked too — deliberately.
    with pytest.raises(token_service.InvalidRefreshToken):
        token_service.rotate_refresh_token(db, second.refresh_token, settings)


def test_an_expired_refresh_token_is_refused(db, settings, athlete):
    pair = token_service.issue_token_pair(
        db, subject_id=athlete.id, role="athlete", settings=settings
    )
    beyond = datetime.now(UTC) + timedelta(
        days=settings.refresh_token_expiry_days + 1
    )

    with pytest.raises(token_service.InvalidRefreshToken):
        token_service.rotate_refresh_token(db, pair.refresh_token, settings, now=beyond)


def test_revoking_all_devices_kills_every_token(db, settings, athlete):
    """A phone in this context is often shared, sold on, or lost."""
    pairs = [
        token_service.issue_token_pair(
            db, subject_id=athlete.id, role="athlete", settings=settings
        )
        for _ in range(3)
    ]

    assert token_service.revoke_all_for_subject(db, athlete.id) == 3

    for pair in pairs:
        with pytest.raises(token_service.InvalidRefreshToken):
            token_service.rotate_refresh_token(db, pair.refresh_token, settings)


def test_refresh_over_http_returns_a_new_pair(client, athlete):
    client.post("/api/auth/request-otp", json={"phone": athlete.phone})
    first = client.post(
        "/api/auth/verify-otp", json={"phone": athlete.phone, "otp": "000000"}
    ).json()

    response = client.post(
        "/api/auth/refresh", json={"refresh_token": first["refresh_token"]}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["refresh_token"] != first["refresh_token"]
    assert body["athlete_id"] == str(athlete.id)


def test_refreshing_with_a_bogus_token_is_401(client):
    response = client.post(
        "/api/auth/refresh", json={"refresh_token": "not-a-real-token"}
    )
    assert response.status_code == 401


# ---------------------------------------------------------------------------
# Registration binding
# ---------------------------------------------------------------------------


def register_payload(**overrides) -> dict:
    payload = {
        "name": "New Athlete",
        "dob": "2008-04-01",
        "gender": "male",
        "region": "Kerala",
        "height_cm": 172.0,
        "weight_kg": 60.0,
    }
    payload.update(overrides)
    return payload


def verified_token(client, phone: str) -> str:
    client.post("/api/auth/request-otp", json={"phone": phone})
    return client.post(
        "/api/auth/verify-otp", json={"phone": phone, "otp": "000000"}
    ).json()["access_token"]


def test_registration_binds_to_the_phone_in_the_token(client, db):
    """The body cannot name the phone; only the token can.

    Otherwise anyone holding a token for their own number could create an
    account against someone else's — and phone number is the identity this
    entire system hangs off.
    """
    token = verified_token(client, "9000000010")

    response = client.post(
        "/api/athletes/register",
        json=register_payload(phone="9999999999"),  # ignored: not a field
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 201
    assert response.json()["profile"]["phone"] == "9000000010"

    stored = db.execute(
        select(Athlete).where(Athlete.phone == "9000000010")
    ).scalar_one()
    assert stored.name == "New Athlete"


def test_registration_requires_a_verified_phone(client):
    response = client.post("/api/athletes/register", json=register_payload())
    assert response.status_code == 401


def test_an_athlete_token_cannot_be_used_to_register(client, athlete):
    """Only a `registering` token reaches this endpoint."""
    client.post("/api/auth/request-otp", json={"phone": athlete.phone})
    token = client.post(
        "/api/auth/verify-otp", json={"phone": athlete.phone, "otp": "000000"}
    ).json()["access_token"]

    response = client.post(
        "/api/athletes/register",
        json=register_payload(),
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 403


def test_registering_the_same_phone_twice_is_a_conflict(client):
    token = verified_token(client, "9000000011")
    headers = {"Authorization": f"Bearer {token}"}

    assert client.post(
        "/api/athletes/register", json=register_payload(), headers=headers
    ).status_code == 201

    second = client.post(
        "/api/athletes/register", json=register_payload(), headers=headers
    )
    assert second.status_code == 409


def test_an_implausible_date_of_birth_is_refused(client):
    token = verified_token(client, "9000000012")

    response = client.post(
        "/api/athletes/register",
        json=register_payload(dob="2024-01-01"),
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 422


def test_a_future_date_of_birth_is_refused(client):
    token = verified_token(client, "9000000013")

    response = client.post(
        "/api/athletes/register",
        json=register_payload(dob="2099-01-01"),
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 422


def test_registration_hands_back_a_session_that_actually_works(client):
    """The journey every new athlete takes, end to end.

    The registering token names a phone, not an athlete. If registration did
    not replace it, the very next request — the registration photo, the first
    video upload — would fail as "unknown athlete", and refreshing would not
    help because rotation preserves the subject.
    """
    client.post("/api/auth/request-otp", json={"phone": "9000000020"})
    verified = client.post(
        "/api/auth/verify-otp", json={"phone": "9000000020", "otp": "000000"}
    ).json()

    registered = client.post(
        "/api/athletes/register",
        json=register_payload(),
        headers={"Authorization": f"Bearer {verified['access_token']}"},
    ).json()

    tokens = registered["tokens"]
    assert tokens["registered"] is True
    assert tokens["athlete_id"] == registered["profile"]["athlete_id"]

    me = client.get(
        "/api/athletes/me",
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
    )
    assert me.status_code == 200

    # The new refresh token works...
    refreshed = client.post(
        "/api/auth/refresh", json={"refresh_token": tokens["refresh_token"]}
    )
    assert refreshed.status_code == 200
    assert refreshed.json()["athlete_id"] == registered["profile"]["athlete_id"]

    # ...and the registering one is dead.
    stale = client.post(
        "/api/auth/refresh", json={"refresh_token": verified["refresh_token"]}
    )
    assert stale.status_code == 401

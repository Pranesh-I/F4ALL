"""Sprint 17: notifications.

The properties that matter: every event is found, none twice; each athlete is
told in their own language and only about their own results; a dead token is
dropped and a failing provider is retried, never fatal; and nothing in the
assessment path depends on any of it.
"""

from __future__ import annotations

import json
import re
import uuid
import xml.etree.ElementTree as ElementTree
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.models import (
    AssessmentSession,
    Athlete,
    DeviceToken,
    Notification,
    TestResult,
    TestResultStatus,
)
from app.notifications import dispatch, events
from app.notifications.messages import LANGUAGES, TEMPLATES, TEST_NAMES, Event, render
from app.notifications.providers import (
    FcmPushProvider,
    InMemoryPushProvider,
    Outcome,
    Push,
    PushProvider,
    SendResult,
)
from app.security import create_access_token

NOW = datetime(2026, 10, 13, 6, 0, tzinfo=UTC)
REPO = Path(__file__).resolve().parents[2]


def make_athlete(db, name="Asha", *, region="Kerala", language="en"):
    athlete = Athlete(
        id=uuid.uuid4(),
        name=name,
        dob=date(2010, 1, 1),
        gender="female",
        region=region,
        phone=uuid.uuid4().hex[:10],
        preferred_language=language,
    )
    db.add(athlete)
    db.commit()
    return athlete


def add_result(db, athlete, test, status, *, attempt=1, session=None, changed=NOW):
    result = TestResult(
        id=uuid.uuid4(),
        athlete_id=athlete.id,
        test_id=test.id,
        attempt_number=attempt,
        provisional_score=10,
        status=status,
        session_id=session.id if session else None,
        created_at=changed,
        updated_at=changed,
    )
    db.add(result)
    db.commit()
    return result


def set_status(db, result, status, changed):
    result.status = status
    result.updated_at = changed
    db.commit()


def make_session(
    db,
    *,
    region=None,
    starts=NOW - timedelta(hours=1),
    hours=72,
    tests=("SIT_UPS", "VERTICAL_JUMP"),
    enabled=True,
):
    session = AssessmentSession(
        id=uuid.uuid4(),
        name="District trials",
        starts_at=starts,
        ends_at=starts + timedelta(hours=hours),
        enabled=enabled,
        allowed_tests=list(tests),
        region=region,
    )
    db.add(session)
    db.commit()
    return session


def notes(db, athlete=None):
    query = select(Notification).order_by(
        Notification.created_at, Notification.event_type
    )
    if athlete is not None:
        query = query.where(Notification.athlete_id == athlete.id)
    return db.execute(query).scalars().all()


def scan(db, settings, now=NOW):
    return events.scan(db, settings, now)


# ---------------------------------------------------------------------------
# Result events
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("status", "event"),
    [
        (TestResultStatus.uploaded, Event.SUBMISSION_RECEIVED),
        (TestResultStatus.processing, Event.SUBMISSION_RECEIVED),
        (TestResultStatus.verified, Event.VERIFICATION_COMPLETED),
        (TestResultStatus.flagged, Event.SUBMISSION_FLAGGED),
        (TestResultStatus.approved, Event.SUBMISSION_APPROVED),
        (TestResultStatus.rejected, Event.SUBMISSION_REJECTED),
        (TestResultStatus.pending_sync, Event.RESUBMISSION_REQUESTED),
    ],
)
def test_each_status_is_one_event(db, settings, seeded_tests, status, event):
    athlete = make_athlete(db)
    result = add_result(db, athlete, seeded_tests["SIT_UPS"], status)

    assert scan(db, settings) == 1
    [note] = notes(db)
    assert note.event_type == event
    assert note.data == {
        "result_id": str(result.id),
        "test_type": "SIT_UPS",
        "event": event,
    }
    assert note.delivery_status == "pending"


def test_the_same_event_is_stored_once(db, settings, seeded_tests):
    athlete = make_athlete(db)
    add_result(db, athlete, seeded_tests["SIT_UPS"], TestResultStatus.verified)

    assert scan(db, settings) == 1
    assert scan(db, settings, NOW + timedelta(seconds=30)) == 0
    assert len(notes(db)) == 1


def test_an_athlete_hears_each_new_status(db, settings, seeded_tests):
    athlete = make_athlete(db)
    result = add_result(db, athlete, seeded_tests["SIT_UPS"], TestResultStatus.processing)
    scan(db, settings)
    set_status(db, result, TestResultStatus.flagged, NOW + timedelta(minutes=2))
    scan(db, settings, NOW + timedelta(minutes=2))
    set_status(db, result, TestResultStatus.approved, NOW + timedelta(hours=3))
    scan(db, settings, NOW + timedelta(hours=3))

    assert [n.event_type for n in notes(db)] == [
        Event.SUBMISSION_RECEIVED,
        Event.SUBMISSION_FLAGGED,
        Event.SUBMISSION_APPROVED,
    ]


def test_a_quick_verdict_skips_the_received_message(db, settings, seeded_tests):
    athlete = make_athlete(db)
    add_result(db, athlete, seeded_tests["SIT_UPS"], TestResultStatus.verified)

    scan(db, settings)
    assert [n.event_type for n in notes(db)] == [Event.VERIFICATION_COMPLETED]


def test_history_from_before_the_first_run_is_not_news(db, settings, seeded_tests):
    athlete = make_athlete(db)
    add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        TestResultStatus.approved,
        changed=NOW - timedelta(hours=5),
    )
    recent = add_result(
        db,
        athlete,
        seeded_tests["VERTICAL_JUMP"],
        TestResultStatus.approved,
        changed=NOW - timedelta(minutes=10),
    )

    scan(db, settings)
    assert [n.data["result_id"] for n in notes(db)] == [str(recent.id)]


def test_a_change_made_while_the_notifier_was_down_is_found(db, settings, seeded_tests):
    athlete = make_athlete(db)
    scan(db, settings)  # sets the cursor
    # Down for two hours; meanwhile a result was approved.
    add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        TestResultStatus.approved,
        changed=NOW + timedelta(minutes=30),
    )

    scan(db, settings, NOW + timedelta(hours=2))
    assert [n.event_type for n in notes(db)] == [Event.SUBMISSION_APPROVED]


def test_athletes_are_told_in_their_own_language(db, settings, seeded_tests):
    hindi = make_athlete(db, "Priya", language="hi")
    add_result(db, hindi, seeded_tests["SIT_UPS"], TestResultStatus.approved)

    scan(db, settings)
    [note] = notes(db)
    assert note.language == "hi"
    assert note.title == "सिट-अप: मंज़ूर"


def test_a_flagged_message_says_nothing_about_the_check(db, settings, seeded_tests):
    athlete = make_athlete(db)
    add_result(db, athlete, seeded_tests["SIT_UPS"], TestResultStatus.flagged)

    scan(db, settings)
    [note] = notes(db)
    text = f"{note.title} {note.body}".lower()
    for word in ("flag", "cheat", "loop", "duplicate", "suspicious", "face"):
        assert word not in text


def test_dedupe_is_enforced_by_the_database(db, seeded_tests):
    athlete = make_athlete(db)
    for _ in range(2):
        db.add(
            Notification(
                athlete_id=athlete.id,
                event_type="x",
                dedupe_key="same",
                title="t",
                body="b",
                data={},
            )
        )
    with pytest.raises(IntegrityError):
        db.commit()
    db.rollback()


# ---------------------------------------------------------------------------
# Session events
# ---------------------------------------------------------------------------


def test_a_session_opening_is_announced_to_the_athletes_it_is_open_to(
    db, settings, seeded_tests
):
    kerala = make_athlete(db, "Asha", region="Kerala")
    punjab = make_athlete(db, "Simran", region="Punjab")
    make_session(db, region="Kerala")

    scan(db, settings)
    assert [n.event_type for n in notes(db, kerala)] == [Event.SESSION_OPENED]
    assert notes(db, punjab) == []
    assert "District trials" in notes(db, kerala)[0].title


def test_an_all_india_session_is_announced_to_everyone(db, settings, seeded_tests):
    make_athlete(db, "Asha", region="Kerala")
    make_athlete(db, "Simran", region="Punjab")
    make_session(db, region=None)

    assert scan(db, settings) == 2


@pytest.mark.parametrize(
    "kwargs",
    [
        {"enabled": False},
        {"starts": NOW + timedelta(hours=2)},  # scheduled
        {"starts": NOW - timedelta(hours=80), "hours": 72},  # ended
    ],
)
def test_sessions_that_are_not_open_are_not_announced(db, settings, seeded_tests, kwargs):
    make_athlete(db)
    make_session(db, **kwargs)
    assert scan(db, settings) == 0


def test_closing_soon_goes_to_athletes_with_tests_left(db, settings, seeded_tests):
    finished = make_athlete(db, "Done")
    halfway = make_athlete(db, "Half")
    session = make_session(db, starts=NOW - timedelta(hours=60), hours=72)
    add_result(
        db,
        finished,
        seeded_tests["SIT_UPS"],
        TestResultStatus.verified,
        session=session,
        changed=NOW - timedelta(days=2),
    )
    add_result(
        db,
        finished,
        seeded_tests["VERTICAL_JUMP"],
        TestResultStatus.approved,
        session=session,
        changed=NOW - timedelta(days=2),
    )
    add_result(
        db,
        halfway,
        seeded_tests["SIT_UPS"],
        TestResultStatus.verified,
        session=session,
        changed=NOW - timedelta(days=2),
    )

    scan(db, settings)  # 12 hours left: inside the 24-hour window

    assert {n.event_type for n in notes(db, halfway)} == {
        Event.SESSION_OPENED,
        Event.SESSION_ENDING_SOON,
    }
    # Someone who has recorded every test needs neither message.
    assert notes(db, finished) == []


def test_closing_soon_waits_for_its_window(db, settings, seeded_tests):
    athlete = make_athlete(db)
    make_session(db, starts=NOW - timedelta(hours=1), hours=72)

    scan(db, settings)
    assert [n.event_type for n in notes(db, athlete)] == [Event.SESSION_OPENED]


def test_a_short_session_warns_halfway_through(db, settings, seeded_tests):
    athlete = make_athlete(db)
    make_session(db, starts=NOW - timedelta(hours=2), hours=6)  # halfway is at 3 hours

    scan(db, settings)
    assert Event.SESSION_ENDING_SOON not in {n.event_type for n in notes(db, athlete)}
    scan(db, settings, NOW + timedelta(hours=1, minutes=1))
    assert Event.SESSION_ENDING_SOON in {n.event_type for n in notes(db, athlete)}


def test_a_resubmission_request_reopens_the_session_for_closing_soon(
    db, settings, seeded_tests
):
    athlete = make_athlete(db)
    session = make_session(
        db, starts=NOW - timedelta(hours=60), hours=72, tests=("SIT_UPS",)
    )
    add_result(
        db,
        athlete,
        seeded_tests["SIT_UPS"],
        TestResultStatus.pending_sync,
        session=session,
        changed=NOW - timedelta(days=2),
    )

    scan(db, settings)
    assert Event.SESSION_ENDING_SOON in {n.event_type for n in notes(db, athlete)}


# ---------------------------------------------------------------------------
# Delivery
# ---------------------------------------------------------------------------


def pending(db, athlete, event=Event.SUBMISSION_APPROVED):
    note = Notification(
        athlete_id=athlete.id,
        event_type=event,
        dedupe_key=uuid.uuid4().hex,
        title="Sit-ups: approved",
        body="Your result is now official.",
        data={"result_id": "r-1", "event": event},
        created_at=NOW,
        next_attempt_at=NOW,
    )
    db.add(note)
    db.commit()
    return note


def device(db, athlete, token="token-aaaaaaaa"):
    row = DeviceToken(athlete_id=athlete.id, token=token)
    db.add(row)
    db.commit()
    return row


def test_without_a_device_the_inbox_is_the_delivery(db, settings):
    athlete = make_athlete(db)
    note = pending(db, athlete)

    dispatch.deliver(db, InMemoryPushProvider(), settings, NOW)
    assert note.delivery_status == "inbox"


def test_inbox_mode_sends_no_pushes(db, settings):
    athlete = make_athlete(db)
    device(db, athlete)
    note = pending(db, athlete)

    dispatch.deliver(db, None, settings, NOW)
    assert note.delivery_status == "inbox"


def test_a_push_carries_what_the_phone_needs_to_open_the_right_screen(db, settings):
    athlete = make_athlete(db)
    device(db, athlete)
    note = pending(db, athlete)
    provider = InMemoryPushProvider()

    dispatch.deliver(db, provider, settings, NOW)

    assert note.delivery_status == "sent" and note.sent_at is not None
    [push] = provider.sent
    assert push.title == "Sit-ups: approved"
    assert push.data == {
        "result_id": "r-1",
        "event": Event.SUBMISSION_APPROVED,
        "notification_id": str(note.id),
    }


def test_a_dead_token_is_disabled_and_never_used_again(db, settings):
    athlete = make_athlete(db)
    dead = device(db, athlete, "dead-token-xx")
    alive = device(db, athlete, "live-token-xx")
    provider = InMemoryPushProvider({"dead-token-xx": Outcome.INVALID_TOKEN})

    first = pending(db, athlete)
    dispatch.deliver(db, provider, settings, NOW)
    assert first.delivery_status == "sent"
    assert dead.disabled_at is not None and alive.disabled_at is None

    pending(db, athlete)
    provider.sent.clear()
    dispatch.deliver(db, provider, settings, NOW)
    assert [push.token for push in provider.sent] == ["live-token-xx"]


def test_when_every_token_is_dead_the_inbox_still_has_it(db, settings):
    athlete = make_athlete(db)
    device(db, athlete)
    note = pending(db, athlete)

    dispatch.deliver(
        db, InMemoryPushProvider({"token-aaaaaaaa": Outcome.INVALID_TOKEN}), settings, NOW
    )
    assert note.delivery_status == "inbox"


def test_a_failing_provider_is_retried_with_backoff_then_given_up(db, settings):
    athlete = make_athlete(db)
    device(db, athlete)
    note = pending(db, athlete)
    provider = InMemoryPushProvider({"token-aaaaaaaa": Outcome.RETRY})

    dispatch.deliver(db, provider, settings, NOW)
    assert note.delivery_status == "retrying"
    assert note.next_attempt_at == NOW + timedelta(seconds=30)

    # Not due yet: nothing is sent.
    provider.sent.clear()
    dispatch.deliver(db, provider, settings, NOW + timedelta(seconds=10))
    assert provider.sent == []

    moment = NOW
    for _ in range(settings.notification_max_attempts):
        moment += timedelta(hours=1)
        dispatch.deliver(db, provider, settings, moment)
    assert note.delivery_status == "failed"
    assert note.attempts == settings.notification_max_attempts


class ExplodingProvider(PushProvider):
    def send(self, push: Push) -> SendResult:
        raise RuntimeError("provider bug")


def test_a_provider_that_crashes_is_retried_and_does_not_stop_the_others(db, settings):
    first, second = make_athlete(db, "One"), make_athlete(db, "Two")
    device(db, first)
    a, b = pending(db, first), pending(db, second)

    dispatch.deliver(db, ExplodingProvider(), settings, NOW)

    assert a.delivery_status == "retrying" and "provider bug" in a.last_error
    assert b.delivery_status == "inbox"


def test_a_notifier_pass_that_fails_does_not_stop_the_notifier(monkeypatch):
    def broken():
        raise RuntimeError("database down")

    monkeypatch.setattr(dispatch, "SessionLocal", broken)
    assert dispatch.main(["--once"]) == 0


def test_nothing_in_the_assessment_path_depends_on_notifications():
    """Submissions, verification and review never import the notifier, so a
    notification problem cannot fail them."""
    app_dir = REPO / "backend" / "app"
    paths = [
        app_dir / "tasks.py",
        app_dir / "worker.py",
        *app_dir.glob("verification/**/*.py"),
    ]
    paths += [
        p for p in (app_dir / "routers").glob("*.py") if p.name != "notifications.py"
    ]
    for path in paths:
        source = path.read_text(encoding="utf-8")
        assert (
            "notifications import" not in source and "from ..notifications" not in source
        ), path


# ---------------------------------------------------------------------------
# FCM HTTP v1
# ---------------------------------------------------------------------------


@pytest.fixture
def fcm_settings(settings, tmp_path):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    account = tmp_path / "fcm.json"
    account.write_text(
        json.dumps(
            {
                "client_email": "notifier@f4all.iam.gserviceaccount.com",
                "private_key": pem,
                "token_uri": "https://oauth.test/token",
            }
        )
    )
    settings.fcm_project_id = "f4all-sai"
    settings.fcm_service_account_file = str(account)
    settings.fcm_api_base = "https://fcm.test"
    return settings


class FakeFcm:
    def __init__(self, *answers: tuple[int, dict]):
        self.answers = list(answers)
        self.calls: list[tuple[str, dict, bytes]] = []
        self.tokens_issued = 0

    def __call__(self, url, headers, body):
        self.calls.append((url, headers, body))
        if url == "https://oauth.test/token":
            self.tokens_issued += 1
            return 200, json.dumps(
                {"access_token": f"at-{self.tokens_issued}", "expires_in": 3600}
            ).encode()
        if isinstance(self.answers[0], Exception):
            raise self.answers.pop(0)
        status, payload = self.answers.pop(0)
        return status, json.dumps(payload).encode()


PUSH = Push(
    token="device-1",
    title="Sit-ups: approved",
    body="Official now.",
    data={"result_id": "r-1"},
)


def test_fcm_sends_the_message_with_a_service_account_token(fcm_settings):
    fake = FakeFcm((200, {"name": "projects/f4all-sai/messages/1"}))
    result = FcmPushProvider(fcm_settings, transport=fake).send(PUSH)

    assert result.outcome is Outcome.DELIVERED
    token_call, send_call = fake.calls
    assert (
        b"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
        in token_call[2]
    )
    url, headers, body = send_call
    assert url == "https://fcm.test/v1/projects/f4all-sai/messages:send"
    assert headers["Authorization"] == "Bearer at-1"
    assert json.loads(body) == {
        "message": {
            "token": "device-1",
            "notification": {"title": "Sit-ups: approved", "body": "Official now."},
            "data": {"result_id": "r-1"},
            "android": {"priority": "high"},
        }
    }


def test_fcm_reuses_its_access_token_until_refused(fcm_settings):
    fake = FakeFcm((200, {}), (401, {"error": {"status": "UNAUTHENTICATED"}}), (200, {}))
    provider = FcmPushProvider(fcm_settings, transport=fake)

    assert provider.send(PUSH).outcome is Outcome.DELIVERED
    assert provider.send(PUSH).outcome is Outcome.RETRY
    assert provider.send(PUSH).outcome is Outcome.DELIVERED
    assert fake.tokens_issued == 2


def error(status, code=None, message=""):
    body = {"error": {"status": status, "message": message}}
    if code:
        body["error"]["details"] = [{"errorCode": code}]
    return body


@pytest.mark.parametrize(
    ("answer", "outcome"),
    [
        ((404, error("NOT_FOUND", "UNREGISTERED")), Outcome.INVALID_TOKEN),
        ((403, error("PERMISSION_DENIED", "SENDER_ID_MISMATCH")), Outcome.INVALID_TOKEN),
        (
            (
                400,
                error(
                    "INVALID_ARGUMENT",
                    "INVALID_ARGUMENT",
                    "The registration token is not a valid FCM registration token",
                ),
            ),
            Outcome.INVALID_TOKEN,
        ),
        # A malformed message is our bug, not the athlete's phone: never a dead token.
        (
            (400, error("INVALID_ARGUMENT", "INVALID_ARGUMENT", "Invalid JSON payload")),
            Outcome.RETRY,
        ),
        ((429, error("RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED")), Outcome.RETRY),
        ((503, error("UNAVAILABLE", "UNAVAILABLE")), Outcome.RETRY),
        (OSError("connection reset"), Outcome.RETRY),
    ],
)
def test_fcm_answers_map_to_outcomes(fcm_settings, answer, outcome):
    fake = FakeFcm(answer)
    assert FcmPushProvider(fcm_settings, transport=fake).send(PUSH).outcome is outcome


def test_fcm_refuses_to_start_without_credentials(settings):
    settings.fcm_project_id = ""
    with pytest.raises(ValueError):
        FcmPushProvider(settings)


# ---------------------------------------------------------------------------
# Messages
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("language", LANGUAGES)
@pytest.mark.parametrize("event", Event.ALL)
def test_every_event_renders_in_every_language(event, language):
    message = render(event, language, test_code="PUSH_UPS", session="Trial")
    assert message.title and message.body
    assert "{" not in message.title + message.body
    assert set(TEMPLATES) == set(Event.ALL)


def test_an_unsupported_language_falls_back_to_english():
    assert render(Event.SUBMISSION_APPROVED, "fr", test_code="SIT_UPS").language == "en"


def test_test_names_match_the_apps_translations():
    """A notification must name a test the way the app does."""
    folders = {"en": "values", "hi": "values-hi", "ta": "values-ta", "bn": "values-bn"}
    keys = {code: f"test_{code.lower()}" for code in TEST_NAMES["en"]}
    for language, folder in folders.items():
        path = REPO / "mobile" / "app" / "src" / "main" / "res" / folder / "strings.xml"
        strings = {
            node.get("name"): node.text
            for node in ElementTree.parse(path).getroot().iter("string")
        }
        for code, key in keys.items():
            assert TEST_NAMES[language][code] == strings[key], (language, code)


# ---------------------------------------------------------------------------
# The athlete's API
# ---------------------------------------------------------------------------


def auth(athlete, settings):
    token = create_access_token(str(athlete.id), settings, role="athlete")
    return {"Authorization": f"Bearer {token}"}


def test_a_device_is_addressed_to_whoever_signed_in_last(client, db, settings):
    asha, meera = make_athlete(db, "Asha"), make_athlete(db, "Meera")
    body = {"token": "shared-phone-token"}

    assert (
        client.post(
            "/api/athletes/me/devices", json=body, headers=auth(asha, settings)
        ).status_code
        == 204
    )
    client.post("/api/athletes/me/devices", json=body, headers=auth(meera, settings))

    rows = db.execute(select(DeviceToken)).scalars().all()
    db.refresh(rows[0])
    assert len(rows) == 1 and rows[0].athlete_id == meera.id


def test_registering_again_revives_a_disabled_token(client, db, settings):
    athlete = make_athlete(db)
    token = device(db, athlete, "revived-token")
    token.disabled_at, token.disabled_reason = NOW, "UNREGISTERED"
    db.commit()

    client.post(
        "/api/athletes/me/devices",
        json={"token": "revived-token"},
        headers=auth(athlete, settings),
    )
    db.refresh(token)
    assert token.disabled_at is None


def test_only_the_owner_can_remove_a_device(client, db, settings):
    asha, meera = make_athlete(db, "Asha"), make_athlete(db, "Meera")
    device(db, asha, "ashas-phone-token")

    assert (
        client.delete(
            "/api/athletes/me/devices/ashas-phone-token", headers=auth(meera, settings)
        ).status_code
        == 204
    )
    assert db.execute(select(DeviceToken)).scalars().all() != []
    client.delete(
        "/api/athletes/me/devices/ashas-phone-token", headers=auth(asha, settings)
    )
    db.expire_all()
    assert db.execute(select(DeviceToken)).scalars().all() == []


def test_a_bad_device_registration_is_refused(client, db, settings):
    athlete = make_athlete(db)
    for body in ({"token": "short"}, {"token": "x" * 20, "provider": "sms"}):
        assert (
            client.post(
                "/api/athletes/me/devices", json=body, headers=auth(athlete, settings)
            ).status_code
            == 422
        )


def test_the_inbox_lists_only_the_athletes_own_notifications(client, db, settings):
    asha, meera = make_athlete(db, "Asha"), make_athlete(db, "Meera")
    for minute in range(3):
        note = pending(db, asha)
        note.created_at = NOW + timedelta(minutes=minute)
    pending(db, meera)
    db.commit()

    first = client.get(
        "/api/athletes/me/notifications?limit=2", headers=auth(asha, settings)
    ).json()
    second = client.get(
        "/api/athletes/me/notifications",
        params={"before": first["next_before"]},
        headers=auth(asha, settings),
    ).json()

    assert len(first["items"]) == 2 and len(second["items"]) == 1
    assert first["unread_count"] == 3
    assert first["items"][0]["event_type"] == Event.SUBMISSION_APPROVED
    assert first["items"][0]["created_at"] > first["items"][1]["created_at"]


def test_notifications_can_be_marked_read(client, db, settings):
    asha, meera = make_athlete(db, "Asha"), make_athlete(db, "Meera")
    one, two = pending(db, asha), pending(db, asha)
    theirs = pending(db, meera)
    headers = auth(asha, settings)

    answer = client.post(
        "/api/athletes/me/notifications/read",
        json={"notification_ids": [str(one.id), str(theirs.id)]},
        headers=headers,
    )
    assert answer.json() == {"unread_count": 1}
    unread = client.get(
        "/api/athletes/me/notifications?unread_only=true", headers=headers
    ).json()
    assert [item["notification_id"] for item in unread["items"]] == [str(two.id)]

    assert client.post(
        "/api/athletes/me/notifications/read", json={"all": True}, headers=headers
    ).json() == {"unread_count": 0}
    db.refresh(theirs)
    # Someone else's notification is never touched.
    assert theirs.read_at is None


def test_the_inbox_needs_an_athlete(client, db, settings):
    assert client.get("/api/athletes/me/notifications").status_code in (401, 403)


def test_the_whole_path_from_approval_to_the_inbox(client, db, settings, seeded_tests):
    """Approve a result, run the notifier once, and the athlete sees it."""
    athlete = make_athlete(db, language="ta")
    device(db, athlete)
    result = add_result(
        db,
        athlete,
        seeded_tests["VERTICAL_JUMP"],
        TestResultStatus.verified,
        changed=datetime.now(UTC),
    )
    set_status(db, result, TestResultStatus.approved, datetime.now(UTC))
    provider = InMemoryPushProvider()

    dispatch.run_once(db, provider, settings)

    inbox = client.get(
        "/api/athletes/me/notifications", headers=auth(athlete, settings)
    ).json()
    [item] = inbox["items"]
    assert item["event_type"] == Event.SUBMISSION_APPROVED
    assert item["title"] == "செங்குத்துத் தாவல்: ஒப்புதல் பெற்றது"
    assert item["data"]["result_id"] == str(result.id)
    assert [push.title for push in provider.sent] == [item["title"]]
    assert re.fullmatch(r"[0-9a-f-]{36}", provider.sent[0].data["notification_id"])


def test_the_app_words_each_event_as_the_server_does():
    """A push (server text) and the inbox (app text) must say the same thing."""
    folders = {"en": "values", "hi": "values-hi", "ta": "values-ta", "bn": "values-bn"}
    app_keys = {
        Event.SESSION_OPENED: "session_opened",
        Event.SESSION_ENDING_SOON: "session_ending",
        Event.SUBMISSION_RECEIVED: "received",
        Event.VERIFICATION_COMPLETED: "verified",
        Event.SUBMISSION_FLAGGED: "flagged",
        Event.SUBMISSION_APPROVED: "approved",
        Event.SUBMISSION_REJECTED: "rejected",
        Event.RESUBMISSION_REQUESTED: "resubmission",
    }
    for language, folder in folders.items():
        path = REPO / "mobile" / "app" / "src" / "main" / "res" / folder / "strings.xml"
        strings = {
            node.get("name"): node.text
            for node in ElementTree.parse(path).getroot().iter("string")
        }
        for event, key in app_keys.items():
            title, body = TEMPLATES[event][language]
            as_app = title.replace("{test}", "%1$s").replace("{session}", "%1$s")
            assert strings[f"notif_{key}_title"] == as_app, (language, event)
            assert strings[f"notif_{key}_body"] == body, (language, event)

"""Sprint 23: the consistency check finds each forbidden state, and only those."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from app.models import AssessmentSession, TestResult, TestResultStatus, Video
from app.services import consistency
from app.storage import get_storage
from tests.test_dashboard import make_athlete, make_result

NOW = datetime.now(UTC)


def video_for(db, storage, result, *, stored=True) -> Video:
    key = f"videos/{uuid.uuid4().hex}.mp4"
    if stored:
        storage.store_bytes(key, b"mp4", content_type="video/mp4")
    video = Video(test_result_id=result.id, s3_key=key, checksum_sha256="0" * 64)
    db.add(video)
    db.commit()
    return video


def problems(db, settings, **kwargs) -> dict[str, list[str]]:
    report = consistency.check(
        db, get_storage(settings), sla_seconds=300, now=NOW, **kwargs
    )
    return report.problems


def healthy(db, athlete, test, attempt, status=TestResultStatus.verified):
    result = make_result(db, athlete, test, status=status, attempt=attempt)
    result.verification_verdict = "verified"
    result.server_result = {"score": 28}
    db.commit()
    return result


def test_a_healthy_database_has_no_problems(db, settings, seeded_tests):
    athlete, test = make_athlete(db), seeded_tests["SIT_UPS"]
    storage = get_storage(settings)
    for attempt, status in enumerate(
        [TestResultStatus.verified, TestResultStatus.flagged, TestResultStatus.approved],
        start=1,
    ):
        video_for(db, storage, healthy(db, athlete, test, attempt, status))
    # Waiting, but within twice the SLA: normal.
    make_result(db, athlete, test, status=TestResultStatus.uploaded, attempt=9)

    assert problems(db, settings) == {}


def test_each_forbidden_state_is_named(db, settings, seeded_tests):
    athlete, test = make_athlete(db), seeded_tests["SIT_UPS"]
    storage = get_storage(settings)

    missing = healthy(db, athlete, test, 1)
    video_for(db, storage, missing, stored=False)

    no_verdict = make_result(
        db, athlete, test, status=TestResultStatus.verified, attempt=2
    )
    no_measurement = make_result(
        db, athlete, test, status=TestResultStatus.verified, attempt=3
    )
    no_measurement.verification_verdict = "verified"
    approved_unverified = make_result(
        db, athlete, test, status=TestResultStatus.approved, attempt=4
    )
    stuck = make_result(db, athlete, test, status=TestResultStatus.processing, attempt=5)
    db.commit()
    db.execute(
        TestResult.__table__.update()
        .where(TestResult.id == stuck.id)
        .values(updated_at=NOW - timedelta(minutes=11))
    )
    db.commit()

    found = problems(db, settings)

    assert found["uploaded_but_missing"] == [str(missing.id)]
    assert set(found["verified_but_no_result"]) == {
        str(no_verdict.id),
        str(no_measurement.id),
    }
    assert found["decided_without_verification"] == [str(approved_unverified.id)]
    assert found["stuck_in_verification"] == [str(stuck.id)]


def test_a_flagged_result_without_a_measurement_is_allowed(db, settings, seeded_tests):
    # An unreadable video goes to a human with an error and no measurement.
    result = make_result(
        db, make_athlete(db), seeded_tests["SIT_UPS"], status=TestResultStatus.flagged
    )
    result.verification_verdict = "flagged"
    result.verification_error = "The first frame could not be decoded"
    db.commit()
    assert problems(db, settings, check_storage=False) == {}


def test_a_duplicate_official_result_is_found(db, settings, seeded_tests):
    # The database refuses this through its partial unique index, so the row
    # is written past it, as a restore from an old backup might.
    athlete, test = make_athlete(db), seeded_tests["SIT_UPS"]
    session = AssessmentSession(
        name="Trials",
        starts_at=NOW - timedelta(hours=1),
        ends_at=NOW + timedelta(hours=1),
        allowed_tests=["SIT_UPS"],
    )
    db.add(session)
    db.commit()
    first = healthy(db, athlete, test, 1)
    first.session_id = session.id
    db.commit()
    db.execute(TestResult.__table__.delete().where(TestResult.id == first.id))
    db.commit()
    db.connection().exec_driver_sql("DROP INDEX uq_session_submission")
    for attempt in (1, 2):
        result = healthy(db, athlete, test, attempt)
        result.session_id = session.id
    db.commit()

    found = problems(db, settings, check_storage=False)

    assert found == {
        "duplicate_official_result": [f"{athlete.id}/{session.id}/{test.id}"]
    }


def test_a_retired_video_is_not_missing(db, settings, seeded_tests):
    result = healthy(db, make_athlete(db), seeded_tests["SIT_UPS"], 1)
    db.add(
        Video(
            test_result_id=result.id,
            s3_key="retired:2027-01-01",
            checksum_sha256="0" * 64,
        )
    )
    db.commit()
    assert problems(db, settings) == {}

"""The identity check must leave a record, and the record must be honest.

`face_verifications` is the table anyone asks when they want to know whether an
athlete's identity was actually confirmed for a given submission. The property
worth protecting is that it can distinguish three states — checked and matched,
checked and unclear, never checked — because collapsing the third into the first
turns a gap in our data into a false assurance about a real person.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select

from app.models import (
    Athlete,
    FaceVerification,
    FaceVerificationStatus,
    Test,
    TestResult,
    TestResultStatus,
)
from app.tasks import _record_face_verification
from app.verification.cheat.findings import (
    CheatCheck,
    CheatReport,
    FaceOutcome,
    FaceVerdict,
    Severity,
)


@pytest.fixture
def result(db, athlete: Athlete, seeded_tests: dict[str, Test]) -> TestResult:
    record = TestResult(
        id=uuid.uuid4(),
        athlete_id=athlete.id,
        test_id=seeded_tests["SIT_UPS"].id,
        attempt_number=1,
        provisional_score=12,
        status=TestResultStatus.processing,
    )
    db.add(record)
    db.commit()
    return record


def rows_for(db, result: TestResult) -> list[FaceVerification]:
    return list(
        db.execute(
            select(FaceVerification).where(
                FaceVerification.test_result_id == result.id
            )
        ).scalars()
    )


def test_a_match_is_recorded(db, result):
    _record_face_verification(
        db,
        result.id,
        FaceOutcome(
            verdict=FaceVerdict.PASS,
            similarity=0.8123456,
            frames_with_face=9,
            frames_sampled=12,
        ),
    )
    db.commit()

    rows = rows_for(db, result)
    assert len(rows) == 1
    assert rows[0].verification_status is FaceVerificationStatus.passed
    assert float(rows[0].similarity_score) == pytest.approx(0.8123, abs=1e-4)
    assert rows[0].verified_at is not None


def test_a_mismatch_is_recorded_as_manual_review_never_as_a_failure(db, result):
    """The machine routes the pair to a human; it does not reach a verdict.

    A cosine distance over a generic embedder, on the population this platform
    serves, is not evidence that an athlete submitted someone else's test. If
    this ever writes `fail` automatically, an unvalidated heuristic has been
    given the authority to accuse a child.
    """
    _record_face_verification(
        db,
        result.id,
        FaceOutcome(verdict=FaceVerdict.MANUAL_REVIEW, similarity=0.21),
    )
    db.commit()

    rows = rows_for(db, result)
    assert len(rows) == 1
    assert rows[0].verification_status is FaceVerificationStatus.manual_review
    assert rows[0].verification_status is not FaceVerificationStatus.failed


def test_a_check_that_did_not_run_writes_no_row(db, result):
    """"We did not look" must never be stored as "we looked and it was fine".

    No registration photo on file, no face models on the server, no face found
    in the recording — all of these leave the athlete unverified. An absent row
    says exactly that; a `pass` row would be a lie about a person.
    """
    _record_face_verification(db, result.id, None)
    db.commit()

    assert rows_for(db, result) == []


def test_the_skipped_reason_survives_to_the_report():
    """A reviewer needs to know why the check did not run, not just that it did not."""
    report = CheatReport()
    report.skip(CheatCheck.FACE_MISMATCH, "No registration photo on file")

    assert report.face is None
    assert report.skipped[CheatCheck.FACE_MISMATCH.value]
    # A skipped check is not a finding: it must not flag the athlete.
    assert report.is_clean


def test_extend_carries_the_face_outcome():
    """The pipeline runs checks into one report; the outcome must not be dropped."""
    outcome = FaceOutcome(verdict=FaceVerdict.PASS, similarity=0.9)

    combined = CheatReport()
    other = CheatReport(face=outcome)
    combined.extend(other)

    assert combined.face is outcome


def test_a_face_mismatch_is_never_high_severity():
    """Severity drives how a reviewer weights what they are looking at.

    This check has not earned the confidence that HIGH implies, so a mismatch
    must never outrank a looped-frame finding in the queue.
    """
    from app.verification.cheat.face import (
        STRONG_MISMATCH_THRESHOLD,
        FaceComparison,
        check_face,
    )

    assert STRONG_MISMATCH_THRESHOLD < 1.0

    # Exercised through the real reporting path with a stubbed comparison.
    import app.verification.cheat.face as face_module

    report = CheatReport()
    original = face_module.compare_faces
    try:
        face_module.compare_faces = lambda *args, **kwargs: FaceComparison(
            similarity=0.05, frames_with_face=8, frames_sampled=12
        )
        check_face("video.mp4", "reference.jpg", "models", report=report)
    finally:
        face_module.compare_faces = original

    findings = [f for f in report.findings if f.check is CheatCheck.FACE_MISMATCH]
    assert findings
    assert findings[0].severity is not Severity.HIGH
    assert report.face is not None
    assert report.face.verdict is FaceVerdict.MANUAL_REVIEW


# ---------------------------------------------------------------------------
# Result ids arrive from Celery as strings
# ---------------------------------------------------------------------------


def test_task_helpers_accept_the_string_ids_celery_delivers(
    db, db_session_factory, result, monkeypatch
):
    """Regression: every verification job used to crash on its first lookup.

    `_enqueue_verification` passes `str(result.id)` through Celery, and the ORM's
    Uuid column rejects a str. The error handler repeated the same lookup and
    crashed too, leaving every real submission in `processing` forever.
    """
    import contextlib

    import app.tasks as tasks
    from app.models import Flag, FlagSeverity

    @contextlib.contextmanager
    def scope():
        session = db_session_factory()
        try:
            yield session
            session.commit()
        finally:
            session.close()

    monkeypatch.setattr(tasks, "session_scope", scope)

    tasks._flag_and_finish(
        str(result.id),
        reason="server_could_not_score",
        detail="boom",
        severity=FlagSeverity.high,
    )

    db.expire_all()
    assert db.get(TestResult, result.id).status is TestResultStatus.flagged
    assert db.execute(select(Flag)).scalars().one().reason == "server_could_not_score"

    _record_face_verification(
        db, str(result.id), FaceOutcome(verdict=FaceVerdict.PASS, similarity=0.9)
    )
    db.commit()
    assert rows_for(db, result)


def test_a_malformed_result_id_is_reported_not_crashed():
    from app.tasks import verify_test_result

    outcome = verify_test_result.run("not-a-uuid")
    assert outcome["status"] == "missing"

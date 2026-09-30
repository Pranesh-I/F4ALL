"""Sprint 11: server-side verification, from upload to a stored, explained verdict.

    upload (HTTP) -> storage -> queued -> worker -> AI -> comparison -> DB -> API

The integration tests below drive that whole path. The upload is real chunked
HTTP; the stored file is a real MP4 the worker opens, probes and decodes with
OpenCV. Where a test needs a *known* score, the pixel-to-landmark step is
replaced by one of the parity fixtures — landmark sequences whose scores the
Kotlin analyzers produced — so the verdict is deterministic. Those fixtures
are synthetic, not recordings of real athletes: they prove the server scores
what the phone scores, not that either is accurate on real bodies.

One test runs real MediaPipe over a rendered video, end to end, to prove the
pixel path is wired in too.
"""

from __future__ import annotations

import contextlib
import hashlib
import uuid
from datetime import date
from pathlib import Path

import pytest
from sqlalchemy import select
from sqlalchemy.exc import OperationalError

import app.tasks as tasks
from app.models import (
    Athlete,
    Flag,
    Official,
    OfficialRole,
    Test,
    TestResult,
    TestResultStatus,
    Video,
)
from app.security import create_access_token
from app.verification import discrepancy, finalization, validation
from app.verification.analyzers import (
    AnalyzerEvent,
    AnalyzerResult,
    AttemptStatus,
    TestType,
    analyze_sequence,
    build_analyzer,
)
from app.verification.cheat.findings import (
    CheatCheck,
    CheatFinding,
    CheatReport,
    Severity,
)
from app.verification.cheat.metadata import VideoMetadata
from app.verification.extractor import (
    ExtractionError,
    VideoAnalysis,
    VideoUnreadableError,
    model_path,
)
from app.verification.pose import decode_sequence
from app.verification.validation import ValidationCode, ValidationFailure

cv2 = pytest.importorskip("cv2", reason="opencv not installed")
np = pytest.importorskip("numpy", reason="numpy not installed")

FIXTURES = Path(__file__).parent / "fixtures" / "parity"
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def settings(settings):
    # Real MP4s are tens of kilobytes; 1KB chunks would mean dozens of requests.
    settings.upload_chunk_size_bytes = 64 * 1024
    return settings


@pytest.fixture
def all_tests(db, seeded_tests) -> dict[str, Test]:
    extra = {
        "SQUATS": "Squats",
        "PUSH_UPS": "Push-ups",
        "BICEP_CURLS": "Bicep Curls",
        "LUNGES": "Lunges",
        # A test SAI might add before a scorer exists for it.
        "SHUTTLE_RUN": "Shuttle Run",
    }
    tests_by_code = dict(seeded_tests)
    for code, name in extra.items():
        unit = "seconds" if code == "SHUTTLE_RUN" else "reps"
        record = Test(id=uuid.uuid4(), name=name, code=code, unit=unit)
        db.add(record)
        tests_by_code[code] = record
    db.commit()
    return tests_by_code


@pytest.fixture
def worker(db_session_factory, settings, monkeypatch):
    """The Celery task's code, running against the test database."""

    @contextlib.contextmanager
    def scope():
        session = db_session_factory()
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    monkeypatch.setattr(tasks, "session_scope", scope)
    monkeypatch.setattr(tasks, "get_settings", lambda: settings)

    def run(result_id, **options):
        return tasks.run_verification(result_id, None, settings, **options)

    return run


def render_mp4(path: Path, frames: int = 12) -> Path:
    writer = cv2.VideoWriter(
        str(path), cv2.VideoWriter_fourcc(*"mp4v"), 30, (480, 640)
    )
    for index in range(frames):
        image = np.full((640, 480, 3), 30 + index * 5, dtype=np.uint8)
        cv2.circle(image, (240, 200 + index * 4), 40, (220, 220, 220), -1)
        writer.write(image)
    writer.release()
    return path


def upload(client, data: bytes, test_type: str, headers=None) -> str:
    """The mobile upload protocol: init, chunks, complete."""
    init = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": test_type,
            "file_size_bytes": len(data),
            "checksum_sha256": hashlib.sha256(data).hexdigest(),
            "content_type": "video/mp4",
        },
        headers=headers,
    )
    assert init.status_code == 200, init.text
    session = init.json()
    size = session["chunk_size_bytes"]
    for index in range(0, (len(data) + size - 1) // size):
        chunk = data[index * size : (index + 1) * size]
        response = client.put(
            f"/api/videos/upload/{session['upload_id']}/chunks/{index}",
            content=chunk,
            headers=headers,
        )
        assert response.status_code == 200, response.text
    done = client.post(
        f"/api/videos/upload/{session['upload_id']}/complete", headers=headers
    )
    assert done.status_code == 200, done.text
    return done.json()["video_id"]


def submit(client, test_code, score, video_id=None, headers=None, **extra) -> str:
    body = {"test_id": test_code, "provisional_score": score, **extra}
    if video_id is not None:
        body["video_id"] = video_id
    response = client.post("/api/tests/submit", json=body, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()["result_id"]


def fixture_analysis(sequence: str) -> VideoAnalysis:
    """A decode pass that 'saw' a parity fixture's landmarks."""
    frames = decode_sequence((FIXTURES / sequence).read_text(encoding="utf-8"))
    return VideoAnalysis(
        frames=frames,
        pose_counts=[1] * len(frames),
        metadata=VideoMetadata(
            duration_seconds=len(frames) / 30.0,
            width=480,
            height=640,
            fps=30.0,
            file_size_bytes=2_000_000,
            frame_count=len(frames),
        ),
        declared_frame_count=len(frames),
        decode_ms=0,
    )


def use_fixture(monkeypatch, sequence: str) -> None:
    monkeypatch.setattr(
        tasks, "analyze_video", lambda path, models_dir: fixture_analysis(sequence)
    )


def official_headers(db, settings, role=OfficialRole.sai_admin) -> dict:
    official = Official(
        id=uuid.uuid4(),
        name="Admin",
        email=f"{uuid.uuid4().hex[:8]}@sai.example",
        role=role,
        region=None if role is OfficialRole.sai_admin else "Development",
    )
    db.add(official)
    db.commit()
    token = create_access_token(str(official.id), settings, role=role.value)
    return {"Authorization": f"Bearer {token}"}


def athlete_headers(athlete, settings) -> dict:
    token = create_access_token(str(athlete.id), settings, role="athlete")
    return {"Authorization": f"Bearer {token}"}


def reload(db, result_id) -> TestResult:
    db.expire_all()
    return db.get(TestResult, uuid.UUID(str(result_id)))


def flags_of(db, result_id) -> list[Flag]:
    return list(
        db.execute(
            select(Flag).where(Flag.test_result_id == uuid.UUID(str(result_id)))
        ).scalars()
    )


@pytest.fixture
def mp4_bytes(tmp_path) -> bytes:
    return render_mp4(tmp_path / "clip.mp4").read_bytes()


# ---------------------------------------------------------------------------
# Integration: upload -> storage -> worker -> AI -> DB -> API
# ---------------------------------------------------------------------------


def test_agreeing_submission_is_verified_end_to_end(
    client, db, all_tests, worker, settings, mp4_bytes, monkeypatch
):
    use_fixture(monkeypatch, "situps_5_clean.csv")
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id, provisional_form_score=100)

    # Submission queued the job and left the result awaiting it.
    assert [queued for queued, _ in client.queued_verifications] == [result_id]
    assert reload(db, result_id).status is TestResultStatus.uploaded

    outcome = worker(result_id)
    assert outcome["status"] == "verified"

    result = reload(db, result_id)
    assert result.status is TestResultStatus.verified
    assert float(result.server_score) == 5
    assert result.final_score is None  # official only after approval
    assert result.verification_reason == finalization.REASON_AGREED
    assert result.pipeline_version == finalization.PIPELINE_VERSION
    assert result.processing_started_at is not None
    assert result.verified_at is not None
    assert result.processing_duration_ms is not None
    assert result.verification_attempts == 1
    assert flags_of(db, result_id) == []

    assert result.mobile_result["authoritative"] is False
    assert result.mobile_result["rep_count"] == 5
    assert result.mobile_result["form_score"] == 100
    assert result.server_result["authoritative"] is True
    assert result.server_result["rep_count"] == 5
    assert result.server_result["form_score"] == 100
    assert result.server_result["video"]["container"] == "mp4"
    assert result.server_result["video"]["frames_decoded"] == 465
    assert {c["name"]: c["outcome"] for c in result.verification_checks} == {
        "validation": "passed",
        "processing": "passed",
        "server_scoring": "passed",
        "comparison": "passed",
        "integrity": "passed",
    }

    # The stored video's measured duration and the reviewer's overlay.
    video = db.get(Video, uuid.UUID(video_id))
    assert float(video.duration_seconds) == pytest.approx(15.5, abs=0.1)
    assert video.pose_sequence_key

    status = client.get(
        f"/api/verification/{result_id}", headers=official_headers(db, settings)
    ).json()
    assert status["status"] == "verified"
    assert status["processing_completed_at"] is not None
    assert status["processing_duration_ms"] is not None
    assert status["verification_reason"] == "server_and_device_agree"
    assert status["comparison"]["mobile_rep_count"] == 5
    assert status["comparison"]["server_rep_count"] == 5
    assert status["comparison"]["tolerance"] == settings.discrepancy_tolerance_reps
    assert status["pipeline_version"] == finalization.PIPELINE_VERSION
    assert len(status["checks"]) == 5


def test_inflated_device_score_is_flagged_end_to_end(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    use_fixture(monkeypatch, "squats_5_clean.csv")
    video_id = upload(client, mp4_bytes, "SQUATS")
    result_id = submit(client, "SQUATS", 12, video_id)

    assert worker(result_id)["status"] == "flagged"

    result = reload(db, result_id)
    assert result.status is TestResultStatus.flagged
    # The server's own number is stored; the phone's is not promoted.
    assert float(result.server_score) == 5
    assert float(result.provisional_score) == 12
    assert result.verification_reason == "score_discrepancy"

    [flag] = flags_of(db, result_id)
    assert flag.reason == "score_discrepancy"
    assert _value(flag.severity) == "high"  # 7 apart, over twice the tolerance
    comparison = result.server_result["comparison"]
    assert comparison["difference"] == 7
    assert comparison["tolerance"] == 2


@pytest.mark.parametrize(
    ("code", "sequence", "expected"),
    [
        ("SIT_UPS", "situps_5_clean.csv", 5.0),
        ("SQUATS", "squats_5_clean.csv", 5.0),
        ("PUSH_UPS", "pushups_4_clean.csv", 4.0),
        ("BICEP_CURLS", "curls_4_right_arm.csv", 4.0),
        ("LUNGES", "lunges_4_clean.csv", 4.0),
        ("VERTICAL_JUMP", "jump_40cm.csv", 39.73),
    ],
)
def test_every_exercise_is_scored_independently_by_the_worker(
    client, db, all_tests, worker, mp4_bytes, monkeypatch, code, sequence, expected
):
    """All six exercises go through the same worker path to a server score.

    Only the server's scoring and comparison are asserted: several of these
    synthetic fixtures are shorter than a real test and trip the (separate)
    duration integrity check, which is that check doing its job.
    """
    use_fixture(monkeypatch, sequence)
    video_id = upload(client, mp4_bytes, code)
    result_id = submit(client, code, expected, video_id)

    worker(result_id)

    result = reload(db, result_id)
    assert float(result.server_score) == pytest.approx(expected, abs=0.01)
    checks = {c["name"]: c["outcome"] for c in result.verification_checks}
    assert checks["server_scoring"] == "passed"
    assert checks["comparison"] == "passed"


@pytest.mark.skipif(
    not model_path(MODELS_DIR).exists(),
    reason="Pose model not fetched (python -m scripts.fetch_model)",
)
def test_real_mediapipe_pass_over_an_uploaded_video(
    client, db, all_tests, worker, tmp_path
):
    """No stubs: the worker decodes the upload and runs MediaPipe on it.

    The rendered figure is not a person, and MediaPipe does not detect one in
    it (as test_extraction.py also shows). So the honest outcome is a flag —
    the server could not score it — never a verified score from nothing. A
    verified verdict on real pixels needs footage of a real athlete, which the
    repository does not hold (docs/reference-videos is empty).
    """
    from tests.test_tampered_videos import honest_frames, write_video

    data = write_video(tmp_path / "honest.mp4", honest_frames(90)).read_bytes()
    video_id = upload(client, data, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 3, video_id)

    outcome = worker(result_id)

    result = reload(db, result_id)
    assert outcome["status"] == "flagged"
    assert result.status is TestResultStatus.flagged
    assert result.verification_reason == "server_could_not_score"
    assert result.server_score is None
    video = result.server_result["video"]
    assert video["frames_decoded"] == 90
    assert video["frames_with_subject"] == 0
    assert video["decode_ms"] is not None
    pipeline = result.server_result["pipeline"]
    assert pipeline["mediapipe"]
    assert pipeline["pose_model_sha256"]


# ---------------------------------------------------------------------------
# Failure paths: explicit, persisted, observable
# ---------------------------------------------------------------------------


def test_corrupt_video_is_rejected_without_retrying(
    client, db, all_tests, worker, mp4_bytes, settings
):
    # A real MP4 header followed by garbage: passes the container sniff and
    # fails to decode — a corrupt file, not a server fault.
    corrupt = mp4_bytes[:32] + bytes(4096)
    video_id = upload(client, corrupt, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    outcome = worker(result_id)

    result = reload(db, result_id)
    assert result.status is TestResultStatus.rejected
    assert result.verification_reason in {"video_unreadable", "no_decodable_frames"}
    assert outcome["reason"] == result.verification_reason
    assert result.verification_error
    assert result.server_score is None
    [flag] = flags_of(db, result_id)
    assert flag.reason == result.verification_reason
    checks = {c["name"]: c["outcome"] for c in result.verification_checks}
    assert checks["validation"] == "failed"
    assert checks["server_scoring"] == "skipped"


def test_unsupported_format_is_rejected(client, db, all_tests, worker):
    video_id = upload(client, b"GIF89a" + bytes(2000), "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    worker(result_id)

    result = reload(db, result_id)
    assert result.status is TestResultStatus.rejected
    assert result.verification_reason == "unsupported_format"


def test_submission_without_a_video_is_rejected_not_left_processing(
    client, db, all_tests, worker
):
    result_id = submit(client, "SIT_UPS", 5)
    # Queued like any other submission, instead of a log line and nothing.
    assert client.queued_verifications[-1][0] == result_id

    worker(result_id)

    result = reload(db, result_id)
    assert result.status is TestResultStatus.rejected
    assert result.verification_reason == "video_not_submitted"


def test_video_missing_from_storage_is_flagged_for_a_human(
    client, db, all_tests, worker, mp4_bytes, settings
):
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)
    key = db.get(Video, uuid.UUID(video_id)).s3_key
    (Path(settings.storage_local_path) / key).unlink()

    worker(result_id)

    result = reload(db, result_id)
    # Lost after a checksum-verified upload: our failure, so never rejected.
    assert result.status is TestResultStatus.flagged
    assert result.verification_reason == "video_missing_from_storage"
    assert _value(flags_of(db, result_id)[0].severity) == "high"


def test_empty_frame_extraction_is_rejected(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    def nothing_decodes(path, models_dir):
        raise VideoUnreadableError("No frames", code="no_decodable_frames")

    monkeypatch.setattr(tasks, "analyze_video", nothing_decodes)
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    worker(result_id)

    result = reload(db, result_id)
    assert result.status is TestResultStatus.rejected
    assert result.verification_reason == "no_decodable_frames"


def test_test_without_a_scorer_is_flagged_not_crashed(client, db, all_tests, worker):
    result_id = submit(client, "SHUTTLE_RUN", 11.2)

    # The missing video is checked first; give it one to reach the test check.
    result = reload(db, result_id)
    video = Video(s3_key="videos/x/shuttle.mp4", checksum_sha256="0" * 64)
    db.add(video)
    db.flush()
    video.test_result_id = result.id
    db.commit()

    worker(result_id)

    result = reload(db, result_id)
    assert result.status is TestResultStatus.flagged
    assert result.verification_reason == "test_type_not_verifiable"


def test_jump_without_a_height_is_flagged(client, db, all_tests, worker, mp4_bytes):
    athlete = Athlete(
        id=uuid.uuid4(),
        name="No Height",
        dob=date(2007, 1, 1),
        gender="male",
        region="Development",
        phone="9000000001",
    )
    db.add(athlete)
    db.commit()

    # Submitted as the development athlete (who has a height), then moved to
    # one without: the worker must use the owner's profile, not a guess.
    video_id = upload(client, mp4_bytes, "VERTICAL_JUMP")
    result_id = submit(client, "VERTICAL_JUMP", 40, video_id)
    result = reload(db, result_id)
    result.athlete_id = athlete.id
    db.commit()

    worker(result_id)

    result = reload(db, result_id)
    assert result.status is TestResultStatus.flagged
    assert result.verification_reason == "athlete_height_unknown"


def test_worker_exception_becomes_a_flag_with_the_error_recorded(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    def explode(analyzer, frames):
        raise RuntimeError("analyzer blew up")

    use_fixture(monkeypatch, "situps_5_clean.csv")
    monkeypatch.setattr(tasks, "analyze_sequence", explode)
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    outcome = tasks.verify_test_result.run(result_id)

    assert outcome["status"] == "flagged"
    result = reload(db, result_id)
    assert result.status is TestResultStatus.flagged
    assert result.verification_reason == finalization.REASON_PROCESSING_ERROR
    assert "analyzer blew up" in result.verification_error
    assert flags_of(db, result_id)[0].reason == "server_could_not_score"


def test_infrastructure_failure_is_retried_then_flagged(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    def no_model(path, models_dir):
        raise ExtractionError("Pose model not found")

    retries = []

    def fake_retry(exc=None, countdown=None, **_):
        retries.append(countdown)
        raise tasks.verify_test_result.MaxRetriesExceededError()

    monkeypatch.setattr(tasks, "analyze_video", no_model)
    monkeypatch.setattr(tasks.verify_test_result, "retry", fake_retry)
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    outcome = tasks.verify_test_result.run(result_id)

    assert retries, "an infrastructure failure must be retried"
    assert outcome["status"] == "flagged"
    result = reload(db, result_id)
    assert result.verification_reason == finalization.REASON_PROCESSING_ERROR
    assert "Pose model not found" in result.verification_error


def test_unreadable_video_is_not_retried(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    def unreadable(path, models_dir):
        raise VideoUnreadableError("corrupt")

    retries = []
    monkeypatch.setattr(tasks, "analyze_video", unreadable)
    monkeypatch.setattr(
        tasks.verify_test_result, "retry", lambda **kw: retries.append(kw)
    )
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    tasks.verify_test_result.run(result_id)

    assert retries == []
    assert reload(db, result_id).status is TestResultStatus.rejected


def test_database_failure_leaves_the_result_observable(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    """With the database down nothing can be written — so nothing is claimed.

    The result stays awaiting verification, which the SLA backlog reports and
    `reverify-pending` re-queues, rather than vanishing.
    """
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    @contextlib.contextmanager
    def database_down():
        raise OperationalError("SELECT 1", {}, Exception("connection refused"))
        yield  # pragma: no cover

    retries = []

    def fake_retry(exc=None, countdown=None, **_):
        retries.append(exc)
        raise tasks.verify_test_result.MaxRetriesExceededError()

    monkeypatch.setattr(tasks, "session_scope", database_down)
    monkeypatch.setattr(tasks.verify_test_result, "retry", fake_retry)

    outcome = tasks.verify_test_result.run(result_id)

    assert isinstance(retries[0], OperationalError)
    assert outcome["status"] == "error"
    assert reload(db, result_id).status is TestResultStatus.uploaded
    backlog = client.get("/health/verification").json()
    assert backlog["pending"] == 1


# ---------------------------------------------------------------------------
# Duplicate processing
# ---------------------------------------------------------------------------


def test_a_redelivered_job_does_not_duplicate_the_verdict(
    client, db, all_tests, worker, mp4_bytes, monkeypatch
):
    use_fixture(monkeypatch, "squats_5_clean.csv")
    video_id = upload(client, mp4_bytes, "SQUATS")
    result_id = submit(client, "SQUATS", 12, video_id)

    first = worker(result_id)
    second = worker(result_id)

    assert first["status"] == "flagged"
    assert second["status"] == "already_finalized"
    assert len(flags_of(db, result_id)) == 1
    assert reload(db, result_id).verification_attempts == 1


def test_only_the_latest_claim_may_write_a_verdict(client, db, all_tests, worker):
    """Two runs of one job overlap: the superseded one's verdict is discarded."""
    result_id = uuid.UUID(submit(client, "SIT_UPS", 5))

    assert isinstance(tasks._claim(result_id, "run-a", None), tasks.ClaimedJob)
    assert isinstance(tasks._claim(result_id, "run-b", None), tasks.ClaimedJob)

    decision = finalization.decide(processing_error="from run a")
    written_a = tasks._finalize(
        result_id, "run-a", decision, mobile=None, server=None,
        server_score=None, face=None,
    )
    written_b = tasks._finalize(
        result_id, "run-b", decision, mobile=None, server=None,
        server_score=None, face=None,
    )

    assert (written_a, written_b) == (False, True)
    assert len(flags_of(db, result_id)) == 1
    assert reload(db, result_id).verification_attempts == 2


def test_a_finished_result_cannot_be_claimed_again(client, db, all_tests, worker):
    result_id = uuid.UUID(submit(client, "SIT_UPS", 5))
    worker(result_id)  # rejected: no video

    assert tasks._claim(result_id, "late", None) == "already_finalized"


# ---------------------------------------------------------------------------
# Submission-time validation and lifecycle
# ---------------------------------------------------------------------------


def test_video_uploaded_for_another_test_is_refused_with_a_code(
    client, all_tests, mp4_bytes
):
    video_id = upload(client, mp4_bytes, "SQUATS")

    response = client.post(
        "/api/tests/submit",
        json={"test_id": "SIT_UPS", "provisional_score": 5, "video_id": video_id},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "test_type_mismatch"
    assert "SQUATS" in response.json()["detail"]


def test_upload_of_an_unsupported_type_is_refused_before_any_bytes(client):
    response = client.post(
        "/api/videos/upload/init",
        json={
            "test_type": "SIT_UPS",
            "file_size_bytes": 1000,
            "checksum_sha256": "0" * 64,
            "content_type": "video/webm",
        },
    )

    assert response.status_code == 415
    assert response.json()["code"] == "unsupported_format"


def test_form_score_outside_the_range_is_refused(client, all_tests):
    response = client.post(
        "/api/tests/submit",
        json={
            "test_id": "SIT_UPS",
            "provisional_score": 5,
            "provisional_form_score": 140,
        },
    )
    assert response.status_code == 422


def test_reprocess_is_allowed_while_awaiting_and_refused_once_decided(
    client, db, all_tests, worker, settings, mp4_bytes
):
    headers = official_headers(db, settings)
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)
    assert reload(db, result_id).status is TestResultStatus.uploaded

    queued = client.post(f"/api/verification/{result_id}/process", headers=headers)
    assert queued.status_code == 202, queued.text

    worker(result_id)
    refused = client.post(f"/api/verification/{result_id}/process", headers=headers)
    assert refused.status_code == 409


def test_athlete_sees_a_rejection_reason_but_not_check_details(
    client, db, all_tests, worker, athlete, settings
):
    headers = athlete_headers(athlete, settings)
    result_id = submit(client, "SIT_UPS", 5, headers=headers)
    worker(result_id)

    body = client.get(f"/api/verification/{result_id}", headers=headers).json()

    assert body["status"] == "rejected"
    assert body["verification_reason"] == "video_not_submitted"
    assert body["checks"] is None
    assert body["flags"] is None
    assert body["pipeline_version"] is None


def test_athlete_never_sees_the_tolerance_or_why_a_result_was_flagged(
    client, db, all_tests, worker, athlete, settings, mp4_bytes, monkeypatch
):
    use_fixture(monkeypatch, "situps_5_clean.csv")
    headers = athlete_headers(athlete, settings)
    video_id = upload(client, mp4_bytes, "SIT_UPS", headers=headers)
    result_id = submit(client, "SIT_UPS", 12, video_id, headers=headers)
    worker(result_id)

    body = client.get(f"/api/verification/{result_id}", headers=headers).json()

    assert body["status"] == "flagged"
    assert body["verification_reason"] is None
    assert body["comparison"]["mobile_rep_count"] == 12
    assert body["comparison"]["server_rep_count"] == 5
    assert body["comparison"]["tolerance"] is None
    assert body["comparison"]["difference"] is None


def test_machine_rejection_can_still_be_sent_back_for_resubmission(
    client, db, all_tests, worker, settings
):
    headers = official_headers(db, settings)
    result_id = submit(client, "SIT_UPS", 5)
    worker(result_id)
    assert reload(db, result_id).status is TestResultStatus.rejected

    detail = client.get(f"/api/dashboard/reviews/{result_id}", headers=headers).json()
    assert "requested_resubmission" in detail["allowed_actions"]

    response = client.post(
        f"/api/dashboard/reviews/{result_id}/action",
        json={
            "action": "requested_resubmission",
            "reason": "invalid_video",
            "notes": "Please record again",
        },
        headers=headers,
    )
    assert response.status_code == 200, response.text
    assert reload(db, result_id).status is TestResultStatus.pending_sync

    # Once an official has decided, the machine exception no longer applies.
    again = client.post(
        f"/api/dashboard/reviews/{result_id}/action",
        json={"action": "rejected", "notes": "no"},
        headers=headers,
    )
    assert again.status_code == 409


def test_health_reports_measured_turnaround_against_the_sla(
    client, db, all_tests, worker, mp4_bytes, monkeypatch, settings
):
    use_fixture(monkeypatch, "situps_5_clean.csv")
    video_id = upload(client, mp4_bytes, "SIT_UPS")
    result_id = submit(client, "SIT_UPS", 5, video_id)

    assert client.get("/health/verification").json()["pending"] == 1
    worker(result_id)

    body = client.get("/health/verification").json()
    assert body["pending"] == 0
    assert body["recent"]["completed"] == 1
    assert body["recent"]["within_sla"] == 1
    assert body["recent"]["turnaround_p95_seconds"] <= settings.verification_sla_seconds
    assert body["recent"]["processing_p50_seconds"] is not None


# ---------------------------------------------------------------------------
# Unit: validation
# ---------------------------------------------------------------------------


def test_container_sniff_accepts_mp4_and_nothing_else(tmp_path, mp4_bytes):
    real = tmp_path / "real.mp4"
    real.write_bytes(mp4_bytes)
    fake = tmp_path / "fake.mp4"
    fake.write_bytes(b"\x1a\x45\xdf\xa3" + bytes(64))  # WebM/Matroska

    assert validation.sniff_container(real) == "mp4"
    assert validation.sniff_container(fake) is None


@pytest.mark.parametrize(
    ("content", "code"),
    [
        (b"", ValidationCode.VIDEO_EMPTY),
        (b"not a video at all", ValidationCode.UNSUPPORTED_FORMAT),
        (b"\x00\x00\x00\x18ftypmp42" + bytes(5000), ValidationCode.VIDEO_TOO_LARGE),
    ],
)
def test_check_file_failures(tmp_path, content, code):
    path = tmp_path / "video.mp4"
    path.write_bytes(content)
    with pytest.raises(ValidationFailure) as caught:
        validation.check_file(path, max_bytes=1000)
    assert caught.value.code is code
    assert caught.value.rejects


def test_check_file_missing_is_flagged_not_rejected(tmp_path):
    with pytest.raises(ValidationFailure) as caught:
        validation.check_file(tmp_path / "gone.mp4", max_bytes=1000)
    assert caught.value.code is ValidationCode.VIDEO_MISSING_FROM_STORAGE
    assert not caught.value.rejects


def test_every_validation_code_has_a_disposition():
    assert set(validation.DISPOSITIONS) == set(ValidationCode)


def test_unknown_test_and_missing_height_are_flags():
    with pytest.raises(ValidationFailure) as unknown:
        validation.verifiable_test_type("SHUTTLE_RUN")
    assert not unknown.value.rejects

    with pytest.raises(ValidationFailure) as height:
        validation.require_height(TestType.VERTICAL_JUMP, None)
    assert not height.value.rejects
    validation.require_height(TestType.SIT_UPS, None)  # only the jump needs it


def test_probe_does_not_accept_a_file_that_only_looks_like_mp4(tmp_path):
    """Either the container will not open or no frame decodes; never a pass."""
    from app.verification.extractor import probe_video

    path = tmp_path / "broken.mp4"
    path.write_bytes(b"\x00\x00\x00\x18ftypmp42" + bytes(2048))
    try:
        header = probe_video(path)
    except VideoUnreadableError:
        return
    assert not header.first_frame_decoded


# ---------------------------------------------------------------------------
# Unit: form score, comparison thresholds, finalization
# ---------------------------------------------------------------------------


def _events(*labels: tuple[int, str]) -> list[AnalyzerEvent]:
    return [AnalyzerEvent(timestamp, label) for timestamp, label in labels]


def _result(test_type=TestType.SQUATS, score=5.0, status=AttemptStatus.COMPLETE,
            confidence=0.95, events=None) -> AnalyzerResult:
    return AnalyzerResult(
        test_type=test_type,
        score=score,
        unit=test_type.unit,
        status=status,
        confidence=confidence,
        frames_analyzed=100,
        frames_rejected=0,
        invalid_reason=None if status is AttemptStatus.COMPLETE else "no pose",
        events=events or [],
    )


def test_form_score_matches_the_mobile_formula():
    # 3 counted (one with two warnings at the same timestamp), 1 partial.
    events = _events(
        (100, "rep_counted"),
        (200, "rep_counted"),
        (200, "form_warning"),
        (200, "form_warning"),
        (300, "rep_rejected_partial"),
        (400, "rep_counted"),
    )
    # good = 3 counted - 1 warned rep = 2; attempted = 4; 2 * 100 // 4
    assert _result(events=events).form_score == 50
    assert _result(TestType.VERTICAL_JUMP, events=[]).form_score is None


def test_form_score_on_a_parity_fixture():
    frames = decode_sequence((FIXTURES / "curls_wrong_arm.csv").read_text())
    result = analyze_sequence(build_analyzer(TestType.BICEP_CURLS, None), frames)
    # 2 counted, 3 refused for the wrong arm.
    assert result.form_score == 40


def test_low_confidence_threshold_is_configurable(settings):
    shaky = _result(confidence=0.5)
    assert discrepancy.evaluate(
        server_result=shaky, device_score=5, settings=settings
    ).verdict is discrepancy.Verdict.FLAGGED

    settings.verification_min_confidence = 0.4
    assert discrepancy.evaluate(
        server_result=shaky, device_score=5, settings=settings
    ).verdict is discrepancy.Verdict.VERIFIED


def test_rep_tolerance_is_configurable(settings):
    settings.discrepancy_tolerance_reps = 0
    outcome = discrepancy.evaluate(
        server_result=_result(score=5), device_score=6, settings=settings
    )
    assert outcome.reason is discrepancy.FlagReason.SCORE_DISCREPANCY


def _compare(settings, server, device):
    return discrepancy.evaluate(
        server_result=server, device_score=device, settings=settings
    )


def test_decide_verified_needs_every_check_to_pass(settings):
    server = _result()
    decision = finalization.decide(
        server_result=server, comparison=_compare(settings, server, 5),
        cheat_report=CheatReport(),
    )
    assert decision.verdict is discrepancy.Verdict.VERIFIED
    assert decision.reason == finalization.REASON_AGREED
    assert decision.flags == []
    assert all(check.outcome.value == "passed" for check in decision.checks)


def test_decide_integrity_finding_flags_even_when_scores_agree(settings):
    server = _result()
    report = CheatReport()
    report.add(CheatFinding(check=CheatCheck.LOOPED_FRAMES, severity=Severity.HIGH,
                            detail="looped", at_ms=2500))
    decision = finalization.decide(
        server_result=server, comparison=_compare(settings, server, 5),
        cheat_report=report,
    )
    assert decision.verdict is discrepancy.Verdict.FLAGGED
    assert decision.reason == "looped_frames"
    assert decision.flags[0].detail == "looped (at 2.5s)"
    checks = {check.name.value: check.outcome.value for check in decision.checks}
    assert checks["comparison"] == "passed"
    assert checks["integrity"] == "failed"


def test_decide_discrepancy_and_integrity_both_recorded(settings):
    server = _result(score=5)
    report = CheatReport()
    report.add(CheatFinding(check=CheatCheck.MULTIPLE_PEOPLE, severity=Severity.MEDIUM,
                            detail="two people"))
    decision = finalization.decide(
        server_result=server, comparison=_compare(settings, server, 12),
        cheat_report=report,
    )
    assert decision.reason == "score_discrepancy"
    assert [flag.reason for flag in decision.flags] == [
        "score_discrepancy", "multiple_people"
    ]


def test_decide_server_could_not_score(settings):
    server = _result(status=AttemptStatus.INVALID, score=0)
    decision = finalization.decide(
        server_result=server, comparison=_compare(settings, server, 5),
        cheat_report=CheatReport(),
    )
    assert decision.verdict is discrepancy.Verdict.FLAGGED
    assert decision.reason == "server_could_not_score"
    checks = {check.name.value: check.outcome.value for check in decision.checks}
    assert checks["server_scoring"] == "failed"
    assert checks["comparison"] == "skipped"
    assert len(decision.flags) == 1


def test_decide_validation_reject_and_flag():
    reject = finalization.decide(validation_failure=ValidationFailure(
        ValidationCode.VIDEO_UNREADABLE, "corrupt"))
    flag = finalization.decide(validation_failure=ValidationFailure(
        ValidationCode.VIDEO_MISSING_FROM_STORAGE, "gone"))

    assert reject.verdict is discrepancy.Verdict.REJECTED
    assert flag.verdict is discrepancy.Verdict.FLAGGED
    assert flag.flags[0].severity == "high"
    assert [c.outcome.value for c in reject.checks] == ["failed"] + ["skipped"] * 4


def test_decide_processing_error():
    decision = finalization.decide(processing_error="worker died")
    assert decision.verdict is discrepancy.Verdict.FLAGGED
    assert decision.reason == finalization.REASON_PROCESSING_ERROR
    assert decision.flags[0].reason == "server_could_not_score"


def test_verdicts_are_valid_result_statuses():
    for verdict in discrepancy.Verdict:
        assert TestResultStatus(verdict.value)


def test_snapshots_name_reps_and_measurements_apart():
    reps = finalization.mobile_snapshot(
        test_code="SQUATS", unit="reps", provisional_score=7, form_score=80
    )
    jump = finalization.mobile_snapshot(
        test_code="VERTICAL_JUMP", unit="cm", provisional_score=41.5, form_score=None
    )
    assert (reps["rep_count"], reps["measurement"]) == (7, None)
    assert (jump["rep_count"], jump["measurement"]) == (None, 41.5)


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)

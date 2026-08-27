"""Server-side re-verification.

The job this whole backend exists for. An uploaded video is independently
re-scored here, and the server's number — not the phone's — becomes the basis
for the official result.
"""

from __future__ import annotations

import logging
import tempfile
from datetime import UTC, datetime
from pathlib import Path

from .config import get_settings
from .database import session_scope
from .models import (
    Athlete,
    Flag,
    FlagSeverity,
    FlagSource,
    Test,
    TestResult,
    TestResultStatus,
)
from .storage import get_storage
from .verification import discrepancy
from .verification.cheat import pipeline as cheat_pipeline
from .verification.cheat.findings import CheatReport
from .verification.analyzers import (
    AnalyzerResult,
    AttemptStatus,
    TestType,
    analyze_sequence,
    build_analyzer,
)
from .verification.extractor import ExtractionError, analyze_video
from .worker import celery_app

logger = logging.getLogger(__name__)

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


@celery_app.task(name="app.tasks.verify_test_result", bind=True, max_retries=3)
def verify_test_result(
    self, result_id: str, athlete_height_cm: float | None = None
) -> dict:
    """Re-score one submission from its uploaded video.

    Retries only on infrastructure failures (storage unreachable, model
    missing). A video the pipeline genuinely cannot score is NOT retried — it is
    flagged for a human, because retrying it forever would leave the athlete
    waiting on something that will never resolve itself.
    """
    settings = get_settings()

    try:
        return _run_verification(result_id, athlete_height_cm, settings)

    except (ExtractionError, FileNotFoundError) as exc:
        logger.exception("Infrastructure failure verifying %s", result_id)

        try:
            raise self.retry(exc=exc, countdown=60 * (2**self.request.retries))
        except self.MaxRetriesExceededError:
            _flag_and_finish(
                result_id,
                reason=discrepancy.FlagReason.SERVER_COULD_NOT_SCORE.value,
                detail=f"Verification could not run: {exc}",
                severity=FlagSeverity.high,
            )
            return {"result_id": result_id, "status": "flagged"}

    except Exception as exc:  # pragma: no cover - defensive
        logger.exception("Unexpected failure verifying %s", result_id)
        _flag_and_finish(
            result_id,
            reason=discrepancy.FlagReason.SERVER_COULD_NOT_SCORE.value,
            detail=f"Unexpected verification error: {exc}",
            severity=FlagSeverity.high,
        )
        return {"result_id": result_id, "status": "flagged"}


def _run_verification(
    result_id: str, athlete_height_cm: float | None, settings
) -> dict:
    with session_scope() as db:
        result = db.get(TestResult, result_id)
        if result is None:
            logger.error("Result %s no longer exists", result_id)
            return {"result_id": result_id, "status": "missing"}

        test = db.get(Test, result.test_id)
        video = next(iter(result.videos), None)

        if test is None or video is None:
            _apply_flag(
                db,
                result,
                reason=discrepancy.FlagReason.SERVER_COULD_NOT_SCORE.value,
                detail="Submission has no video to verify",
                severity=FlagSeverity.high,
            )
            result.status = TestResultStatus.flagged
            result.verified_at = datetime.now(UTC)
            return {"result_id": result_id, "status": "flagged"}

        test_code = test.code
        storage_key = video.s3_key
        video_id = video.id
        device_score = (
            float(result.provisional_score)
            if result.provisional_score is not None
            else None
        )

        athlete = db.get(Athlete, result.athlete_id)

        height_cm = athlete_height_cm
        if height_cm is None and athlete is not None and athlete.height_cm is not None:
            height_cm = float(athlete.height_cm)

        # Sprint 7 captures this at registration; until then it is absent and
        # the face check reports itself as "not run" rather than "passed".
        reference_face_key = athlete.reference_face_key if athlete else None

    test_type = TestType(test_code)

    # Vertical jump cannot be scored without a real-world reference. Guessing a
    # height would produce a confidently wrong number, which is worse than
    # admitting the measurement cannot be made.
    if test_type is TestType.VERTICAL_JUMP and height_cm is None:
        _flag_and_finish(
            result_id,
            reason=discrepancy.FlagReason.SERVER_COULD_NOT_SCORE.value,
            detail="Athlete height unknown; jump height cannot be calibrated",
            severity=FlagSeverity.medium,
        )
        return {"result_id": result_id, "status": "flagged"}

    storage = get_storage(settings)

    with tempfile.TemporaryDirectory(prefix="f4all-verify-") as temporary:
        working = Path(temporary)
        local_video = working / "video.mp4"
        storage.fetch_to(storage_key, local_video)

        # One decode pass yields landmarks, frame hashes, person counts and
        # container metadata. Decoding a multi-megabyte video once per check
        # would multiply straight into the verification SLA.
        analysis = analyze_video(local_video, MODELS_DIR)

        reference_face = _materialise_reference_face(
            storage, reference_face_key, working
        )

        cheat_report = cheat_pipeline.run_checks(
            video_path=local_video,
            test_code=test_code,
            pose_frames=analysis.frames,
            frame_hashes=analysis.frame_hashes,
            frame_signatures=analysis.frame_signatures,
            pose_counts=analysis.pose_counts,
            video_metadata=analysis.metadata,
            reference_face_path=reference_face,
            models_dir=MODELS_DIR,
        )

    frames = analysis.frames

    analyzer = build_analyzer(test_type, height_cm)
    server_result = analyze_sequence(analyzer, frames)

    outcome = discrepancy.evaluate(
        server_result=server_result,
        device_score=device_score,
        settings=settings,
    )

    _persist_outcome(result_id, server_result, outcome, cheat_report)
    _persist_video_duration(video_id, analysis.metadata)

    logger.info(
        "Verified %s: device=%s server=%s verdict=%s integrity=%s",
        result_id,
        device_score,
        server_result.score,
        outcome.verdict.value,
        "clean" if cheat_report.is_clean else cheat_report.summary(),
    )

    return {
        "result_id": result_id,
        "status": _final_status(outcome, cheat_report),
        "server_score": outcome.server_score,
        "device_score": outcome.device_score,
        "integrity_findings": [f.check.value for f in cheat_report.findings],
    }


def _final_status(
    outcome: discrepancy.DiscrepancyOutcome, cheat_report: CheatReport
) -> str:
    """A clean score with a failed integrity check is still flagged.

    The two are independent: a tampered video can be scored perfectly and agree
    with the device exactly, because both were measuring the same fake.
    """
    if outcome.verdict is not discrepancy.Verdict.VERIFIED:
        return outcome.verdict.value
    return "flagged" if not cheat_report.is_clean else "verified"


def _persist_outcome(
    result_id: str,
    server_result: AnalyzerResult,
    outcome: discrepancy.DiscrepancyOutcome,
    cheat_report: CheatReport | None = None,
) -> None:
    cheat_report = cheat_report or CheatReport()

    with session_scope() as db:
        result = db.get(TestResult, result_id)
        if result is None:
            return

        if server_result.status is AttemptStatus.COMPLETE:
            result.server_score = server_result.score

        result.verified_at = datetime.now(UTC)

        score_disagrees = outcome.verdict is not discrepancy.Verdict.VERIFIED

        if score_disagrees:
            _apply_flag(
                db,
                result,
                reason=(outcome.reason.value if outcome.reason else "unknown"),
                detail=outcome.detail,
                severity=FlagSeverity(outcome.severity),
            )

        # Integrity findings are recorded as their own flags, each carrying its
        # own reason and severity. A reviewer needs to know WHICH check fired
        # and where in the video to look — "flagged" alone is unactionable.
        for finding in cheat_report.findings:
            detail = finding.detail
            if finding.at_ms is not None:
                detail = f"{detail} (at {finding.at_ms / 1000:.1f}s)"

            _apply_flag(
                db,
                result,
                reason=finding.check.value,
                detail=detail,
                severity=FlagSeverity(finding.severity.value),
            )

        result.status = (
            TestResultStatus.verified
            if not score_disagrees and cheat_report.is_clean
            else TestResultStatus.flagged
        )

        # final_score is deliberately NOT set here. It is set only when an
        # official approves in Sprint 8 — a machine result must never be
        # presented as the official one.
        db.add(result)


def _persist_video_duration(video_id, video_metadata) -> None:
    """Store the measured duration for the Sprint 8 review screen."""
    if video_metadata is None or video_metadata.duration_seconds is None:
        return

    with session_scope() as db:
        video = db.get(Video, video_id)
        if video is None:
            return
        video.duration_seconds = round(video_metadata.duration_seconds, 2)
        db.add(video)


def _materialise_reference_face(storage, reference_face_key, working_dir: Path):
    """Fetch the registration photo, if there is one.

    Returns None rather than raising: no photo on file is a gap in our data, not
    evidence against the athlete, and the face check reports it as "not run".
    """
    if not reference_face_key:
        return None

    try:
        destination = working_dir / "reference_face.jpg"
        storage.fetch_to(reference_face_key, destination)
        return destination
    except Exception:
        logger.warning(
            "Could not fetch reference face %s", reference_face_key, exc_info=True
        )
        return None


def _apply_flag(
    db, result: TestResult, *, reason: str, detail: str, severity: FlagSeverity
) -> None:
    db.add(
        Flag(
            test_result_id=result.id,
            source=FlagSource.auto,
            reason=reason,
            detail=detail,
            severity=severity,
        )
    )


def _flag_and_finish(
    result_id: str, *, reason: str, detail: str, severity: FlagSeverity
) -> None:
    with session_scope() as db:
        result = db.get(TestResult, result_id)
        if result is None:
            return
        result.status = TestResultStatus.flagged
        result.verified_at = datetime.now(UTC)
        result.verification_error = detail
        _apply_flag(db, result, reason=reason, detail=detail, severity=severity)
        db.add(result)

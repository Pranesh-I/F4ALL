"""Server-side re-verification.

The job this whole backend exists for. An uploaded video is independently
re-scored here, and the server's number — not the phone's — becomes the basis
for the official result.

    claim -> validate -> extract -> score -> compare -> integrity -> decide -> persist

Each run first *claims* the result (``uploaded``/``processing`` ->
``processing``) under a fresh run id, and writes its verdict only if it still
holds the latest claim. Celery redelivers a job whose worker died (late acks),
and ``reverify-pending`` re-queues stale ones, so the same result can be
processed twice; the claim is what stops that producing two sets of flags.

The verdict itself comes from ``verification/finalization.py``; this module
fetches, measures and persists.
"""

from __future__ import annotations

import hashlib
import logging
import tempfile
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path

from sqlalchemy import or_, select
from sqlalchemy.exc import InterfaceError, OperationalError

from .config import get_settings
from .database import session_scope
from .models import (
    AWAITING_VERIFICATION,
    Athlete,
    FaceVerification,
    FaceVerificationStatus,
    Flag,
    FlagSeverity,
    FlagSource,
    IdentityCheck,
    Test,
    TestResult,
    TestResultStatus,
    Video,
)
from .services import identity_crypto
from .storage import get_storage
from .verification import discrepancy, finalization, validation
from .verification.analyzers import analyze_sequence, build_analyzer
from .verification.cheat import duplicates
from .verification.cheat import pipeline as cheat_pipeline
from .verification.cheat.findings import CheatCheck, FaceOutcome
from .verification.cheat.identity import identity_finding, identity_summary
from .verification.cheat.limits import IntegrityLimits
from .verification.extractor import (
    DEFAULT_MODEL_FILENAME,
    ExtractionError,
    VideoUnreadableError,
    analyze_video,
    model_path,
    probe_video,
)
from .verification.validation import ValidationCode, ValidationFailure
from .worker import celery_app

logger = logging.getLogger(__name__)

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"

# States a worker may claim. `processing` is claimable because a run that died
# mid-job leaves the result there; the new claim supersedes it.
CLAIMABLE = AWAITING_VERIFICATION


class StorageUnavailable(RuntimeError):
    """Storage could not be reached. Transient: retried, never blamed on the video."""


# Failures of our infrastructure rather than of the submission. Retried with
# backoff; flagged for a human only once the retries are spent.
RETRYABLE = (ExtractionError, StorageUnavailable, OperationalError, InterfaceError)


def as_result_uuid(value: str | uuid.UUID) -> uuid.UUID:
    """Celery serialises the result id to a string; the ORM needs a UUID.

    Without this every job crashed on its first lookup, and the error handler
    crashed on the same lookup — leaving every submission in `processing`
    forever. Normalised once here so no call site can reintroduce it.
    """
    return value if isinstance(value, uuid.UUID) else uuid.UUID(str(value))


@celery_app.task(name="app.tasks.verify_test_result", bind=True, max_retries=3)
def verify_test_result(
    self, result_id: str, athlete_height_cm: float | None = None
) -> dict:
    """Re-score one submission from its uploaded video.

    Retries only on infrastructure failures (storage or database unreachable,
    model missing). A video that genuinely cannot be scored is NOT retried —
    it is decided at once, because retrying it would leave the athlete waiting
    on something that will never resolve itself.
    """
    try:
        result_uuid = as_result_uuid(result_id)
    except ValueError:
        logger.error("Verification requested for malformed result id %r", result_id)
        return {"result_id": str(result_id), "status": "missing"}

    run_id = f"{self.request.id or 'direct'}:{uuid.uuid4().hex[:12]}"

    try:
        return run_verification(
            result_uuid, athlete_height_cm, get_settings(), run_id=run_id
        )

    except RETRYABLE as exc:
        logger.exception("Infrastructure failure verifying %s", result_uuid)
        try:
            raise self.retry(exc=exc, countdown=60 * (2**self.request.retries))
        except self.MaxRetriesExceededError:
            return _finish_with_error(
                result_uuid, run_id, f"Verification could not run: {exc}"
            )

    except Exception as exc:
        logger.exception("Unexpected failure verifying %s", result_uuid)
        return _finish_with_error(
            result_uuid, run_id, f"Unexpected verification error: {exc}"
        )


def _finish_with_error(result_uuid: uuid.UUID, run_id: str, detail: str) -> dict:
    """Record a processing failure as a flag — or, if even that fails, say so.

    When the database itself is what failed, nothing can be written: the
    result stays in `uploaded`/`processing`, which `/health/verification`
    reports as a backlog breaching the SLA and `reverify-pending` re-queues.
    """
    try:
        _flag_and_finish(result_uuid, detail=detail, run_id=run_id)
        return {"result_id": str(result_uuid), "status": "flagged"}
    except Exception:
        logger.exception(
            "Could not record the failure for %s; it stays unverified and "
            "appears in the /health/verification backlog",
            result_uuid,
        )
        return {"result_id": str(result_uuid), "status": "error"}


# ---------------------------------------------------------------------------
# The run
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ClaimedJob:
    """What the run needs from the database, read once under the claim."""

    test_code: str
    unit: str
    storage_key: str | None
    video_id: uuid.UUID | None
    device_score: float | None
    mobile_result: dict | None
    height_cm: float | None
    reference_face_key: str | None
    official: bool
    identity_outcome: object | None
    athlete_id: uuid.UUID | None = None
    session_id: uuid.UUID | None = None
    checksum_sha256: str | None = None
    # Other submissions this video could be a copy of (cheat/duplicates.py).
    duplicate_candidates: tuple = ()


def run_verification(
    result_id: str | uuid.UUID,
    athlete_height_cm: float | None,
    settings,
    *,
    run_id: str | None = None,
    models_dir: Path = MODELS_DIR,
) -> dict:
    result_uuid = as_result_uuid(result_id)
    result_id = str(result_uuid)
    run_id = run_id or f"direct:{uuid.uuid4().hex[:12]}"

    claim = _claim(result_uuid, run_id, athlete_height_cm)
    if isinstance(claim, str):
        # Duplicate delivery of a job already decided: nothing to do, and
        # doing it again would add a second set of flags.
        logger.info("Skipping verification of %s: %s", result_id, claim)
        return {"result_id": result_id, "status": claim}

    mobile = claim.mobile_result or finalization.mobile_snapshot(
        test_code=claim.test_code,
        unit=claim.unit,
        provisional_score=claim.device_score,
        form_score=None,
    )

    storage = get_storage(settings)
    probe: dict | None = None

    with tempfile.TemporaryDirectory(prefix="f4all-verify-") as temporary:
        working = Path(temporary)
        try:
            if claim.video_id is None or not claim.storage_key:
                raise ValidationFailure(
                    ValidationCode.VIDEO_NOT_SUBMITTED,
                    "The submission has no video to verify",
                )
            test_type = validation.verifiable_test_type(claim.test_code)
            validation.require_height(test_type, claim.height_cm)

            local_video = _fetch(storage, claim.storage_key, working / "video.mp4")
            probe = validation.check_file(
                local_video, max_bytes=settings.upload_max_file_bytes
            )

            # Decode one frame before loading the pose model, so an unreadable
            # file is reported as exactly that.
            header = probe_video(local_video)
            probe.update(
                fps=header.fps,
                frames_declared=header.declared_frame_count,
                width=header.width,
                height=header.height,
            )
            if not header.first_frame_decoded:
                raise VideoUnreadableError(
                    "The first frame could not be decoded", code="no_decodable_frames"
                )

            # One decode pass yields landmarks, frame hashes, person counts and
            # container metadata. Decoding a multi-megabyte video once per
            # check would multiply straight into the verification SLA.
            analysis = analyze_video(local_video, models_dir)

        except VideoUnreadableError as exc:
            failure = ValidationFailure(ValidationCode(exc.code), str(exc))
            return _decide_invalid(
                result_uuid, run_id, failure, mobile, probe, models_dir
            )
        except ValidationFailure as failure:
            return _decide_invalid(
                result_uuid, run_id, failure, mobile, probe, models_dir
            )

        reference_face = _materialise_reference_face(
            storage, claim.reference_face_key, working, settings
        )

        limits = IntegrityLimits.from_settings(settings)
        cheat_report = cheat_pipeline.run_checks(
            video_path=local_video,
            test_code=claim.test_code,
            pose_frames=analysis.frames,
            frame_hashes=analysis.frame_hashes,
            frame_signatures=analysis.frame_signatures,
            pose_counts=analysis.pose_counts,
            video_metadata=analysis.metadata,
            reference_face_path=reference_face,
            models_dir=models_dir,
            frame_changes=analysis.frame_changes,
            limits=limits,
        )

    finding = identity_finding(official=claim.official, outcome=claim.identity_outcome)
    if finding is not None:
        cheat_report.add(finding)
    cheat_report.summarise(
        "identity",
        identity_summary(
            official=claim.official,
            pre_test=claim.identity_outcome,
            face=cheat_report.face,
            face_skipped=cheat_report.skipped.get(CheatCheck.FACE_MISMATCH.value),
        ),
    )

    analyzer = build_analyzer(test_type, claim.height_cm)
    server_result = analyze_sequence(analyzer, analysis.frames)

    # Anti-cheat checks that read what the scorer measured, then the one that
    # needs other submissions. All add to the same report.
    cheat_pipeline.run_analysis_checks(
        cheat_report,
        pose_frames=analysis.frames,
        server_result=server_result,
        video_metadata=analysis.metadata,
        limits=limits,
    )
    video_fingerprint = duplicates.fingerprint(analysis.frame_signatures)
    duplicates.check_duplicates(
        athlete_id=claim.athlete_id,
        session_id=claim.session_id,
        checksum_sha256=claim.checksum_sha256,
        video_fingerprint=video_fingerprint,
        candidates=list(claim.duplicate_candidates),
        report=cheat_report,
        near_duplicate_mae=limits.near_duplicate_mae,
    )

    comparison = discrepancy.evaluate(
        server_result=server_result,
        device_score=claim.device_score,
        settings=settings,
    )

    decision = finalization.decide(
        server_result=server_result,
        comparison=comparison,
        cheat_report=cheat_report,
    )

    server = finalization.server_snapshot(
        server_result,
        comparison,
        video={**(probe or {}), **analysis.processing_metadata()},
        pipeline=pipeline_components(models_dir),
    )
    server["integrity"] = integrity_record(cheat_report, limits)

    written = _finalize(
        result_uuid,
        run_id,
        decision,
        mobile=mobile,
        server=server,
        server_score=comparison.server_score,
        face=cheat_report.face,
        video_id=claim.video_id,
        fingerprint=video_fingerprint,
    )
    if not written:
        return {"result_id": result_id, "status": "superseded"}

    _persist_video_duration(claim.video_id, analysis.metadata)
    _persist_pose_sequence(storage, claim.video_id, claim.storage_key, analysis.frames)

    logger.info(
        "Verified %s: device=%s server=%s verdict=%s reason=%s integrity=%s",
        result_id,
        claim.device_score,
        server_result.score,
        decision.verdict.value,
        decision.reason,
        "clean" if cheat_report.is_clean else cheat_report.summary(),
    )

    return {
        "result_id": result_id,
        "status": decision.status_value,
        "reason": decision.reason,
        "server_score": comparison.server_score,
        "device_score": comparison.device_score,
        "integrity_findings": [f.check.value for f in cheat_report.findings],
    }


def _decide_invalid(
    result_uuid: uuid.UUID,
    run_id: str,
    failure: ValidationFailure,
    mobile: dict,
    probe: dict | None,
    models_dir: Path = MODELS_DIR,
) -> dict:
    decision = finalization.decide(validation_failure=failure)
    server = {
        "authoritative": True,
        "status": "NOT_SCORED",
        "validation": {"code": failure.code.value, "detail": failure.detail},
        "video": probe,
        "pipeline": pipeline_components(models_dir),
    }
    written = _finalize(
        result_uuid,
        run_id,
        decision,
        mobile=mobile,
        server=server,
        server_score=None,
        face=None,
        error=failure.detail,
    )
    logger.info(
        "Verification of %s stopped at validation: %s (%s)",
        result_uuid,
        failure.code.value,
        decision.verdict.value,
    )
    return {
        "result_id": str(result_uuid),
        "status": decision.status_value if written else "superseded",
        "reason": decision.reason,
    }


def _fetch(storage, key: str, destination: Path) -> Path:
    """The stored video as a local file, telling "gone" apart from "unreachable"."""
    try:
        present = storage.exists(key)
    except Exception as exc:
        raise StorageUnavailable(f"Storage check failed: {exc}") from exc

    if not present:
        raise ValidationFailure(
            ValidationCode.VIDEO_MISSING_FROM_STORAGE,
            "The uploaded video is no longer in storage",
        )

    try:
        return storage.fetch_to(key, destination)
    except Exception as exc:
        raise StorageUnavailable(f"Could not fetch the video: {exc}") from exc


# ---------------------------------------------------------------------------
# Claim and finalize: the only two writes to the result's status
# ---------------------------------------------------------------------------


def _claim(
    result_uuid: uuid.UUID, run_id: str, athlete_height_cm: float | None
) -> ClaimedJob | str:
    with session_scope() as db:
        result = db.execute(
            select(TestResult).where(TestResult.id == result_uuid).with_for_update()
        ).scalar_one_or_none()
        if result is None:
            logger.error("Result %s no longer exists", result_uuid)
            return "missing"

        if result.status not in CLAIMABLE:
            return "already_finalized"

        if result.status is TestResultStatus.processing:
            logger.warning(
                "Result %s was already claimed (run %s); taking it over",
                result_uuid,
                result.verification_run_id,
            )

        result.status = TestResultStatus.processing
        result.processing_started_at = datetime.now(UTC)
        result.verification_attempts = (result.verification_attempts or 0) + 1
        result.verification_run_id = run_id

        test = db.get(Test, result.test_id)
        video = next(iter(result.videos), None)
        athlete = db.get(Athlete, result.athlete_id)

        height_cm = athlete_height_cm
        if athlete is not None and athlete.height_cm is not None:
            # The profile wins over a height passed with the job, as at submit.
            height_cm = float(athlete.height_cm)

        identity_check = (
            db.get(IdentityCheck, result.identity_check_id)
            if result.identity_check_id is not None
            else None
        )

        return ClaimedJob(
            test_code=test.code if test else "UNKNOWN",
            unit=test.unit if test else "",
            storage_key=video.s3_key if video else None,
            video_id=video.id if video else None,
            device_score=(
                float(result.provisional_score)
                if result.provisional_score is not None
                else None
            ),
            mobile_result=result.mobile_result,
            height_cm=height_cm,
            # Absent until the athlete registers a photo; the face check then
            # reports itself as "not run" rather than "passed".
            reference_face_key=athlete.reference_face_key if athlete else None,
            # An official attempt should come with a passed photo check; one
            # that does not is routed to a reviewer (never refused).
            official=result.session_id is not None,
            identity_outcome=identity_check.outcome if identity_check else None,
            athlete_id=result.athlete_id,
            session_id=result.session_id,
            checksum_sha256=video.checksum_sha256 if video else None,
            duplicate_candidates=tuple(_duplicate_candidates(db, result, video)),
        )


# Candidates compared per submission. Bounded so the check costs the same for
# an athlete with a long history; the most recent are the likely copies.
DUPLICATE_CANDIDATE_LIMIT = 200


def _duplicate_candidates(
    db, result: TestResult, video: Video | None
) -> list[duplicates.Candidate]:
    """Other official submissions this video could be a copy of.

    The athlete's own submissions and everything in the same session, plus
    any video anywhere with the same bytes. Only ``test_results`` are read:
    practice attempts have no server-side video and are never compared.
    """
    if video is None:
        return []

    scope = TestResult.athlete_id == result.athlete_id
    if result.session_id is not None:
        scope = or_(scope, TestResult.session_id == result.session_id)
    scope = or_(scope, Video.checksum_sha256 == video.checksum_sha256)

    rows = db.execute(
        select(TestResult, Video, Test)
        .join(Video, Video.test_result_id == TestResult.id)
        .join(Test, Test.id == TestResult.test_id)
        .where(TestResult.id != result.id, Video.id != video.id, scope)
        .order_by(TestResult.created_at.desc())
        .limit(DUPLICATE_CANDIDATE_LIMIT)
    ).all()

    return [
        duplicates.Candidate(
            result_id=other.id,
            athlete_id=other.athlete_id,
            session_id=other.session_id,
            test_code=test.code,
            status=other.status.value,
            checksum_sha256=other_video.checksum_sha256,
            fingerprint=other_video.fingerprint,
        )
        for other, other_video, test in rows
    ]


def integrity_record(report, limits: IntegrityLimits) -> dict:
    """Everything the integrity checks measured, flagged or not, for the verdict.

    Includes the limits in force, so a later reader knows what "flagged"
    meant at the time. Officials only (``server_result`` is not shown to
    athletes).
    """
    return {
        "summaries": report.summaries,
        "findings": [finding.check.value for finding in report.findings],
        "skipped": report.skipped,
        "errors": report.errors,
        "limits": limits.as_dict(),
    }


def _finalize(
    result_uuid: uuid.UUID,
    run_id: str | None,
    decision: finalization.FinalDecision,
    *,
    mobile: dict | None,
    server: dict | None,
    server_score: float | None,
    face: FaceOutcome | None,
    error: str | None = None,
    video_id: uuid.UUID | None = None,
    fingerprint: dict | None = None,
) -> bool:
    """Write the verdict, if this run still holds the claim. True when written.

    Flags (each with its evidence), the face record, the video fingerprint, the
    status and the verdict's record are one transaction: a verdict
    half-written would be a result claiming to be verified with no score, or
    flags on a result still processing.
    """
    with session_scope() as db:
        result = db.execute(
            select(TestResult).where(TestResult.id == result_uuid).with_for_update()
        ).scalar_one_or_none()
        if result is None:
            return False

        if result.status is not TestResultStatus.processing or (
            run_id is not None and result.verification_run_id != run_id
        ):
            logger.warning(
                "Discarding verdict for %s from run %s: superseded by run %s (%s)",
                result_uuid,
                run_id,
                result.verification_run_id,
                result.status.value,
            )
            return False

        for flag in decision.flags:
            _apply_flag(
                db,
                result,
                reason=flag.reason,
                detail=flag.detail,
                severity=FlagSeverity(flag.severity),
                evidence=flag.evidence,
            )
        _record_face_verification(db, result_uuid, face)

        if video_id is not None and fingerprint is not None:
            video = db.get(Video, video_id)
            if video is not None:
                video.fingerprint = fingerprint

        now = datetime.now(UTC)
        started = result.processing_started_at or now
        if started.tzinfo is None:
            # SQLite hands back naive datetimes; everything stored is UTC.
            started = started.replace(tzinfo=UTC)

        if server_score is not None:
            result.server_score = server_score

        result.status = TestResultStatus(decision.status_value)
        # Kept apart from `status`, which a reviewer's decision later replaces.
        result.verification_verdict = decision.status_value
        result.verified_at = now
        elapsed_ms = int((now - started).total_seconds() * 1000)
        result.processing_duration_ms = max(elapsed_ms, 0)
        result.verification_reason = decision.reason
        result.verification_error = error
        result.pipeline_version = finalization.PIPELINE_VERSION
        result.verification_checks = decision.checks_record()
        if mobile is not None:
            result.mobile_result = mobile
        if server is not None:
            result.server_result = server

        # final_score is deliberately NOT set here. It is set only when an
        # official approves — a machine result must never be presented as the
        # official one.
        db.add(result)
        return True


def _flag_and_finish(
    result_id: str | uuid.UUID, *, detail: str, run_id: str | None = None
) -> bool:
    """Record a processing failure that survived its retries."""
    return _finalize(
        as_result_uuid(result_id),
        run_id,
        finalization.decide(processing_error=detail),
        mobile=None,
        server=None,
        server_score=None,
        face=None,
        error=detail,
    )


# ---------------------------------------------------------------------------
# Records
# ---------------------------------------------------------------------------


@lru_cache(maxsize=4)
def pipeline_components(models_dir: Path = MODELS_DIR) -> dict:
    """The versions a verdict was produced with.

    The model's hash is included because `scripts/fetch_model.py` downloads
    MediaPipe's "latest" build: the same file name is not the same model.
    """
    from importlib import metadata

    def version(*packages: str) -> str | None:
        for package in packages:
            try:
                return metadata.version(package)
            except metadata.PackageNotFoundError:
                continue
        return None

    model = model_path(models_dir)
    model_sha256 = None
    if model.exists():
        model_sha256 = hashlib.sha256(model.read_bytes()).hexdigest()[:16]

    return {
        "version": finalization.PIPELINE_VERSION,
        "pose_model": DEFAULT_MODEL_FILENAME,
        "pose_model_sha256": model_sha256,
        "mediapipe": version("mediapipe"),
        "opencv": version("opencv-python-headless", "opencv-python"),
    }


def _record_face_verification(
    db, result_id: str | uuid.UUID, outcome: FaceOutcome | None
) -> None:
    """Write the identity check to `face_verifications`.

    No row when the comparison did not run. An absent row means "not checked",
    and that must stay distinguishable from a recorded `pass` — an athlete with
    no registration photo on file has not been identity-verified, and a row
    saying otherwise would be a false assurance in the one table an official
    would consult to ask whether they had been.
    """
    if outcome is None:
        return

    db.add(
        FaceVerification(
            test_result_id=as_result_uuid(result_id),
            verification_status=FaceVerificationStatus(outcome.verdict.value),
            similarity_score=(
                round(outcome.similarity, 4) if outcome.similarity is not None else None
            ),
            verified_at=datetime.now(UTC),
        )
    )


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


def pose_sequence_json(frames) -> bytes:
    """The server's landmarks, compact enough to send to a browser.

    Rounded to four decimals — a tenth of a pixel on a 1080p frame — which
    roughly halves the payload for a reviewer on a government office connection
    without any visible change to the overlay.
    """
    import json

    return json.dumps(
        {
            "version": 1,
            "landmarks": len(frames[0].points) if frames else 0,
            "frames": [
                {
                    "t": frame.timestamp_ms,
                    "p": [
                        value
                        for point in frame.points
                        for value in (
                            round(point.x, 4),
                            round(point.y, 4),
                            round(point.visibility, 3),
                        )
                    ],
                }
                for frame in frames
            ],
        },
        separators=(",", ":"),
    ).encode()


def pose_sequence_key_for(video_key: str) -> str:
    return video_key.rsplit(".", 1)[0] + ".pose.json"


def _persist_pose_sequence(storage, video_id, video_key: str, frames) -> None:
    """Store what the server saw, for the reviewer's skeleton overlay.

    Best effort. A failure here costs the reviewer an overlay, never the
    athlete a verified result, so it is logged and swallowed.
    """
    if not frames:
        return

    try:
        key = pose_sequence_key_for(video_key)
        storage.store_bytes(
            key, pose_sequence_json(frames), content_type="application/json"
        )
        with session_scope() as db:
            video = db.get(Video, video_id)
            if video is not None:
                video.pose_sequence_key = key
                db.add(video)
    except Exception:
        logger.warning(
            "Could not store pose sequence for video %s", video_id, exc_info=True
        )


def _materialise_reference_face(
    storage, reference_face_key, working_dir: Path, settings
):
    """Fetch the registration photo, if there is one.

    Returns None rather than raising: no photo on file is a gap in our data, not
    evidence against the athlete, and the face check reports it as "not run".
    """
    if not reference_face_key:
        return None

    try:
        # Decrypted only into this job's temporary directory, which is removed
        # when verification finishes.
        destination = working_dir / "reference_face.jpg"
        destination.write_bytes(
            identity_crypto.load_photo(storage, settings, reference_face_key)
        )
        return destination
    except Exception:
        logger.warning(
            "Could not fetch reference face %s", reference_face_key, exc_info=True
        )
        return None


def _apply_flag(
    db,
    result: TestResult,
    *,
    reason: str,
    detail: str,
    severity: FlagSeverity,
    evidence: dict | None = None,
) -> None:
    db.add(
        Flag(
            test_result_id=result.id,
            source=FlagSource.auto,
            reason=reason,
            detail=detail,
            severity=severity,
            evidence=evidence,
        )
    )

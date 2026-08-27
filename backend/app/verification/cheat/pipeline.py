"""Runs every integrity check and aggregates the findings."""

from __future__ import annotations

import logging
from pathlib import Path

from ..pose import PoseFrame
from . import frames as frame_checks
from . import metadata as metadata_checks
from . import subject as subject_checks
from .face import check_face
from .findings import CheatReport, Severity
from .metadata import VideoMetadata

logger = logging.getLogger(__name__)


def run_checks(
    *,
    video_path: Path,
    test_code: str,
    pose_frames: list[PoseFrame],
    frame_hashes: list[int],
    frame_signatures: list[bytes],
    pose_counts: list[int] | None,
    video_metadata: VideoMetadata,
    reference_face_path: Path | None,
    models_dir: Path,
    include_face_check: bool = True,
) -> CheatReport:
    """Run all checks. One failing check never stops the others.

    An exception in the face embedder must not cost the reviewer the looped-frame
    finding that would actually have caught the tampering.
    """
    report = CheatReport()

    _safely(
        report,
        "metadata",
        lambda: metadata_checks.check_metadata(
            video_metadata, test_code, report=report
        ),
    )

    _safely(
        report,
        "frames",
        lambda: frame_checks.check_frames(
            frame_hashes,
            frame_signatures,
            [frame.timestamp_ms for frame in pose_frames],
            report=report,
        ),
    )

    _safely(
        report,
        "subject",
        lambda: subject_checks.check_subject(
            pose_frames, pose_counts, report=report
        ),
    )

    if include_face_check:
        _safely(
            report,
            "face",
            lambda: check_face(
                video_path, reference_face_path, models_dir, report=report
            ),
        )

    return report


def _safely(report: CheatReport, name: str, run) -> None:
    try:
        run()
    except Exception:
        logger.exception("Integrity check '%s' failed", name)
        report.skipped[name] = "Check failed to run"


def severity_to_flag_severity(severity: Severity) -> str:
    return severity.value

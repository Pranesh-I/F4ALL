"""Runs every integrity check and aggregates the findings.

Two stages, both inside the one verification job (``tasks.py``):

1. ``run_checks`` — the video itself: metadata, frames (loops, cuts, duplicated
   frames, timestamps), who is in it, and the face. Needs the video file.
2. ``run_analysis_checks`` — what the server's scorer measured: impossible
   movement and playback-speed physics. Needs the scorer's result.

Duplicate submissions need the database and are added by the worker
(``duplicates.check_duplicates``). Every stage adds to one ``CheatReport``;
nothing overwrites another stage's findings.
"""

from __future__ import annotations

import logging
from pathlib import Path

from ..analyzers import AnalyzerResult
from ..pose import PoseFrame
from . import frames as frame_checks
from . import metadata as metadata_checks
from . import movement as movement_checks
from . import subject as subject_checks
from . import timing as timing_checks
from .face import check_face
from .findings import CheatReport, Severity
from .limits import IntegrityLimits
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
    frame_changes: list[float | None] | None = None,
    limits: IntegrityLimits | None = None,
) -> CheatReport:
    """Run all checks. One failing check never stops the others.

    An exception in the face embedder must not cost the reviewer the looped-frame
    finding that would actually have caught the tampering.
    """
    limits = limits or IntegrityLimits()
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
            frame_changes=frame_changes,
            duplicate_frame_ratio=limits.duplicate_frame_ratio,
        ),
    )

    _safely(
        report,
        "subject",
        lambda: subject_checks.check_subject(
            pose_frames,
            pose_counts,
            report=report,
            multi_person_fraction=limits.multi_person_fraction,
            min_subject_fraction=limits.min_subject_fraction,
            confident_subject_fraction=limits.confident_subject_fraction,
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


def run_analysis_checks(
    report: CheatReport,
    *,
    pose_frames: list[PoseFrame],
    server_result: AnalyzerResult | None,
    video_metadata: VideoMetadata | None,
    limits: IntegrityLimits | None = None,
) -> CheatReport:
    """Checks that read the server scorer's result, added to ``report``."""
    limits = limits or IntegrityLimits()
    aspect_ratio = (
        video_metadata.width / video_metadata.height
        if video_metadata and video_metadata.width and video_metadata.height
        else 1.0
    )

    _safely(
        report,
        "movement",
        lambda: movement_checks.check_movement(
            pose_frames,
            server_result,
            aspect_ratio=aspect_ratio,
            report=report,
            fast_rep_fraction=limits.fast_rep_fraction,
            max_hip_speed=limits.max_hip_speed,
        ),
    )
    _safely(
        report,
        "timing",
        lambda: timing_checks.check_timing(
            [frame.timestamp_ms for frame in pose_frames],
            video_metadata,
            server_result,
            report=report,
            jump_time_scale_limit=limits.jump_time_scale_limit,
        ),
    )
    return report


def _safely(report: CheatReport, name: str, run) -> None:
    try:
        run()
    except Exception as exc:
        logger.exception("Integrity check '%s' failed", name)
        report.skipped[name] = "Check failed to run"
        report.errors[name] = f"{type(exc).__name__}: {exc}"


def severity_to_flag_severity(severity: Severity) -> str:
    return severity.value

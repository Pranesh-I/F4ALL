"""Extracts pose landmarks and integrity signals from an uploaded video.

The one place in the verification pipeline that needs mediapipe/opencv. It is
imported lazily and isolated here on purpose: the scoring algorithms in
``analyzers.py`` and the checks in ``cheat/`` stay pure Python, so the test
suite — including the parity checks against the mobile implementation — runs
without these heavy native dependencies installed.

This is also where the server's independence comes from. It re-derives landmarks
from the video rather than trusting anything the device reported. A modified
client can send whatever landmarks it likes; it cannot make the video show a
sit-up that did not happen.

Everything is produced in a **single decode pass**. Sprint 6's frame hashing and
person counting would otherwise mean decoding a multi-megabyte video two or
three times per submission, which multiplies straight into the verification SLA.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path

from .cheat.metadata import VideoMetadata
from .pose import LANDMARK_COUNT, PoseFrame, PosePoint

logger = logging.getLogger(__name__)

# Pose model used server-side. "full" rather than the phone's "lite": the server
# has no battery or thermal budget to respect, so it uses the more accurate
# variant. That asymmetry is intentional — the server is meant to be the better
# measurement, not merely a second opinion of equal quality.
DEFAULT_MODEL_FILENAME = "pose_landmarker_full.task"

# Enough to notice a second person without paying for many-person tracking.
MAX_POSES_DETECTED = 3

# dHash working size: 17x16 greyscale gives 256 comparisons.
#
# 9x8 (the usual 64-bit dHash) was measured against rendered test footage and
# could not resolve the subject moving AT ALL — mean adjacent-frame distance was
# exactly 0. A person occupies a modest share of a portrait test video, and at
# 8 rows the whole athlete lands in one or two of them. 256 bits restores enough
# spatial resolution to see the movement that loop detection depends on.
HASH_WIDTH = 17
HASH_HEIGHT = 16

# Total comparisons, for scaling distance thresholds.
HASH_BITS = (HASH_WIDTH - 1) * HASH_HEIGHT

# Greyscale signature size. 16x16 = 256 bytes per frame — about 460KB for a
# 60-second recording, which is nothing against the video itself.
#
# The signature exists because the hash alone cannot survive the 480p transcode
# the mobile app applies before upload; see cheat/frames.py for the measurements.
SIGNATURE_SIZE = 16


class ExtractionError(RuntimeError):
    pass


@dataclass
class VideoAnalysis:
    """Everything one decode pass yields."""

    frames: list[PoseFrame] = field(default_factory=list)

    # Perceptual hash per frame — the cheap prefilter for loop detection.
    frame_hashes: list[int] = field(default_factory=list)

    # Downsampled greyscale signature per frame — what actually confirms a loop.
    frame_signatures: list[bytes] = field(default_factory=list)

    # People detected per frame.
    pose_counts: list[int] = field(default_factory=list)

    metadata: VideoMetadata | None = None


def model_path(models_dir: Path) -> Path:
    return Path(models_dir) / DEFAULT_MODEL_FILENAME


def perceptual_hash(image_bgr) -> int:
    """256-bit difference hash.

    Chosen over a cryptographic hash because re-encoding changes every byte of a
    frame while leaving it visually identical — which is exactly the case loop
    detection has to see through.
    """
    import cv2

    grey = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    resized = cv2.resize(grey, (HASH_WIDTH, HASH_HEIGHT), interpolation=cv2.INTER_AREA)

    value = 0
    bit = 0
    for row in range(HASH_HEIGHT):
        for column in range(HASH_WIDTH - 1):
            if resized[row][column] > resized[row][column + 1]:
                value |= 1 << bit
            bit += 1

    return value


def frame_signature(image_bgr) -> bytes:
    """Downsampled greyscale signature.

    Keeps magnitude, unlike a difference hash, which is what makes it robust to
    the re-encoding every uploaded video has been through.
    """
    import cv2

    grey = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    resized = cv2.resize(
        grey, (SIGNATURE_SIZE, SIGNATURE_SIZE), interpolation=cv2.INTER_AREA
    )
    return bytes(resized.astype("uint8").ravel())


def analyze_video(
    video_path: Path,
    models_dir: Path,
    *,
    max_frames: int | None = None,
    collect_hashes: bool = True,
) -> VideoAnalysis:
    """Decode once; produce pose frames, hashes, person counts and metadata."""
    try:
        import cv2
        import mediapipe as mp
        from mediapipe.tasks import python as mp_python
        from mediapipe.tasks.python import vision
    except ImportError as exc:  # pragma: no cover - depends on install
        raise ExtractionError(
            "mediapipe and opencv are required for server-side pose extraction"
        ) from exc

    task_file = model_path(models_dir)
    if not task_file.exists():
        raise ExtractionError(
            f"Pose model not found at {task_file}. "
            "See backend/models/README.md for how to fetch it."
        )

    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(task_file)),
        # VIDEO mode, not LIVE_STREAM: offline processing should use the
        # temporally-aware path and is not racing a camera.
        running_mode=vision.RunningMode.VIDEO,
        num_poses=MAX_POSES_DETECTED,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )

    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise ExtractionError(f"Could not open video {video_path}")

    analysis = VideoAnalysis()

    try:
        declared_fps = capture.get(cv2.CAP_PROP_FPS) or 0.0
        declared_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)

        with vision.PoseLandmarker.create_from_options(options) as landmarker:
            frame_index = 0

            while True:
                ok, image = capture.read()
                if not ok:
                    break

                if max_frames is not None and frame_index >= max_frames:
                    break

                timestamp_ms = int(capture.get(cv2.CAP_PROP_POS_MSEC))

                # Some containers report 0 for every frame; fall back to the
                # declared frame rate so the analyzers' duration guards (which
                # reject impossibly fast reps) still mean something.
                if timestamp_ms <= 0 and frame_index > 0:
                    fps = declared_fps or 30.0
                    timestamp_ms = int(frame_index * (1000.0 / fps))

                if collect_hashes:
                    analysis.frame_hashes.append(perceptual_hash(image))
                    analysis.frame_signatures.append(frame_signature(image))

                rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
                mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

                result = landmarker.detect_for_video(mp_image, timestamp_ms)

                detected = len(result.pose_landmarks) if result.pose_landmarks else 0
                analysis.pose_counts.append(detected)

                if detected:
                    # The first pose is the tracked subject. Scoring follows one
                    # athlete; the count feeds the multiple-people check.
                    points = [
                        PosePoint(
                            x=landmark.x,
                            y=landmark.y,
                            z=landmark.z,
                            visibility=getattr(landmark, "visibility", 0.0) or 0.0,
                        )
                        for landmark in result.pose_landmarks[0]
                    ]
                else:
                    # An empty frame still advances time. Dropping it entirely
                    # would hide gaps from the quality gate, which is what
                    # detects that tracking was lost mid-attempt.
                    points = [PosePoint(0.0, 0.0, 0.0, 0.0)] * LANDMARK_COUNT

                analysis.frames.append(
                    PoseFrame(timestamp_ms=timestamp_ms, points=points)
                )
                frame_index += 1

        actual_count = len(analysis.frames)
        duration_seconds = None

        if declared_fps and declared_fps > 0:
            source_count = declared_count if declared_count > 0 else actual_count
            duration_seconds = source_count / declared_fps
        elif analysis.frames:
            duration_seconds = analysis.frames[-1].timestamp_ms / 1000.0

        try:
            file_size = Path(video_path).stat().st_size
        except OSError:
            file_size = None

        analysis.metadata = VideoMetadata(
            duration_seconds=duration_seconds,
            width=width or None,
            height=height or None,
            fps=declared_fps or None,
            file_size_bytes=file_size,
            frame_count=actual_count,
        )

    finally:
        capture.release()

    logger.info(
        "Analysed %s: %d frames, %d with a subject",
        Path(video_path).name,
        len(analysis.frames),
        sum(1 for count in analysis.pose_counts if count > 0),
    )

    return analysis


def extract_pose_sequence(
    video_path: Path,
    models_dir: Path,
    *,
    max_frames: int | None = None,
) -> list[PoseFrame]:
    """Pose frames only. Kept for callers that do not need integrity signals."""
    return analyze_video(
        video_path, models_dir, max_frames=max_frames, collect_hashes=False
    ).frames


def video_duration_seconds(video_path: Path) -> float | None:
    """Container duration, without decoding every frame."""
    try:
        import cv2
    except ImportError:  # pragma: no cover
        return None

    capture = cv2.VideoCapture(str(video_path))
    try:
        if not capture.isOpened():
            return None
        frame_count = capture.get(cv2.CAP_PROP_FRAME_COUNT)
        fps = capture.get(cv2.CAP_PROP_FPS)
        if fps and frame_count and fps > 0:
            return float(frame_count) / float(fps)
        return None
    finally:
        capture.release()

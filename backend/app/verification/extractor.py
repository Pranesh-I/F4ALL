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
import time
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
    """The pose pipeline could not run: missing model, missing native libraries.

    An infrastructure failure. Retrying may help; the video is not at fault.
    """


class VideoUnreadableError(ExtractionError):
    """The file itself cannot be decoded — corrupt, truncated, or not a video.

    A property of the submission, not of the server. Retrying the same bytes
    cannot fix it, so the worker must not treat it like ``ExtractionError``.
    ``code`` is the validation code the verdict records.
    """

    def __init__(self, message: str, code: str = "video_unreadable") -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class VideoProbe:
    """What the container says, plus whether a first frame actually decodes."""

    fps: float | None
    declared_frame_count: int | None
    width: int | None
    height: int | None
    first_frame_decoded: bool

    @property
    def duration_seconds(self) -> float | None:
        if self.fps and self.declared_frame_count and self.fps > 0:
            return self.declared_frame_count / self.fps
        return None


def probe_video(video_path: Path) -> VideoProbe:
    """Open the video and decode one frame, without loading the pose model.

    Cheap enough to run before the full pass, so an unreadable file is
    reported as such instead of costing a model load first.
    """
    try:
        import cv2
    except ImportError as exc:  # pragma: no cover - depends on install
        raise ExtractionError("opencv is required to read uploaded videos") from exc

    capture = cv2.VideoCapture(str(video_path))
    try:
        if not capture.isOpened():
            raise VideoUnreadableError(f"Could not open video {Path(video_path).name}")
        ok, _ = capture.read()
        return VideoProbe(
            fps=capture.get(cv2.CAP_PROP_FPS) or None,
            declared_frame_count=int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0) or None,
            width=int(capture.get(cv2.CAP_PROP_FRAME_WIDTH) or 0) or None,
            height=int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0) or None,
            first_frame_decoded=bool(ok),
        )
    finally:
        capture.release()


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

    # Largest change in any cell of a 64x64 greyscale grid since the previous
    # frame (None for the first). What duplicated-frame detection reads — the
    # 16x16 signature averages away a small, slowly moving athlete.
    frame_changes: list[float | None] = field(default_factory=list)

    metadata: VideoMetadata | None = None

    # How the pass went, recorded with the verdict: frames the container
    # declared against frames that decoded, and how long decoding took.
    declared_frame_count: int | None = None
    decode_ms: int | None = None

    @property
    def frames_with_subject(self) -> int:
        return sum(1 for count in self.pose_counts if count > 0)

    def processing_metadata(self) -> dict:
        metadata = self.metadata
        return {
            "frames_decoded": len(self.frames),
            "frames_declared": self.declared_frame_count,
            "frames_with_subject": self.frames_with_subject,
            "fps": metadata.fps if metadata else None,
            "width": metadata.width if metadata else None,
            "height": metadata.height if metadata else None,
            "duration_seconds": (
                round(metadata.duration_seconds, 2)
                if metadata and metadata.duration_seconds is not None
                else None
            ),
            "file_size_bytes": metadata.file_size_bytes if metadata else None,
            "decode_ms": self.decode_ms,
        }


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


CHANGE_GRID_SIZE = 64


def change_grid(image_bgr):
    """Greyscale 64x64 downsample used for frame-to-frame change."""
    import cv2

    grey = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    return cv2.resize(
        grey, (CHANGE_GRID_SIZE, CHANGE_GRID_SIZE), interpolation=cv2.INTER_AREA
    ).astype("int16")


def largest_change(previous, current) -> float:
    return float(abs(current - previous).max())


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
        raise VideoUnreadableError(f"Could not open video {Path(video_path).name}")

    analysis = VideoAnalysis()
    started = time.perf_counter()

    try:
        declared_fps = capture.get(cv2.CAP_PROP_FPS) or 0.0
        declared_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)

        with vision.PoseLandmarker.create_from_options(options) as landmarker:
            frame_index = 0
            previous_grid = None

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
                    grid = change_grid(image)
                    analysis.frame_changes.append(
                        largest_change(previous_grid, grid)
                        if previous_grid is not None
                        else None
                    )
                    previous_grid = grid

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

        # The container opened but not one frame decoded: a corrupt or
        # truncated file. An empty analysis here would reach the analyzers as
        # "no athlete visible", which blames the athlete for a broken file.
        if actual_count == 0 and max_frames != 0:
            raise VideoUnreadableError(
                f"No frames could be decoded from {Path(video_path).name}",
                code="no_decodable_frames",
            )

        analysis.declared_frame_count = declared_count or None
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

    analysis.decode_ms = int((time.perf_counter() - started) * 1000)

    logger.info(
        "Analysed %s: %d frames, %d with a subject, in %dms",
        Path(video_path).name,
        len(analysis.frames),
        analysis.frames_with_subject,
        analysis.decode_ms,
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

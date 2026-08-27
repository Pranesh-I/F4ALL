"""Pose extraction from a real video file.

Skipped when mediapipe/opencv are missing or the model has not been fetched, so
the rest of the suite still runs on a clean checkout. When it does run it proves
the plumbing the whole verification pipeline stands on: video decoding, model
loading, timestamps, and the exact PoseFrame shape the analyzers consume.

It deliberately does not assert scoring accuracy. A synthetic figure is not a
person, and real accuracy is measured against reference video in Sprint 3.4.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.verification.analyzers import SitUpAnalyzer, analyze_sequence
from app.verification.extractor import (
    ExtractionError,
    extract_pose_sequence,
    model_path,
    video_duration_seconds,
)
from app.verification.pose import LANDMARK_COUNT

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"

cv2 = pytest.importorskip("cv2", reason="opencv not installed")
pytest.importorskip("mediapipe", reason="mediapipe not installed")

model_available = pytest.mark.skipif(
    not model_path(MODELS_DIR).exists(),
    reason="Pose model not fetched (python -m scripts.fetch_model)",
)

FPS = 30
FRAME_COUNT = 30
WIDTH, HEIGHT = 480, 640


@pytest.fixture
def synthetic_video(tmp_path: Path) -> Path:
    import numpy as np

    destination = tmp_path / "synthetic.mp4"
    writer = cv2.VideoWriter(
        str(destination), cv2.VideoWriter_fourcc(*"mp4v"), FPS, (WIDTH, HEIGHT)
    )

    for index in range(FRAME_COUNT):
        frame = np.full((HEIGHT, WIDTH, 3), 40, dtype=np.uint8)
        offset = int(30 * np.sin(index / FRAME_COUNT * 2 * np.pi))
        cx, cy = WIDTH // 2, HEIGHT // 2 + offset
        cv2.circle(frame, (cx, cy - 90), 26, (200, 200, 200), -1)
        cv2.line(frame, (cx, cy - 64), (cx, cy + 30), (200, 200, 200), 18)
        cv2.line(frame, (cx, cy + 30), (cx - 35, cy + 130), (200, 200, 200), 14)
        cv2.line(frame, (cx, cy + 30), (cx + 35, cy + 130), (200, 200, 200), 14)
        writer.write(frame)

    writer.release()
    return destination


@model_available
def test_extraction_yields_one_frame_per_video_frame(synthetic_video):
    frames = extract_pose_sequence(synthetic_video, MODELS_DIR)
    assert len(frames) == FRAME_COUNT


@model_available
def test_every_frame_carries_the_full_landmark_set(synthetic_video):
    """The analyzers index landmarks by position.

    A frame with fewer than 33 points would silently produce wrong angles rather
    than an error, so the extractor pads undetected frames instead of dropping
    or shortening them.
    """
    frames = extract_pose_sequence(synthetic_video, MODELS_DIR)
    assert all(len(frame.points) == LANDMARK_COUNT for frame in frames)


@model_available
def test_timestamps_are_monotonic(synthetic_video):
    """The analyzers' duration guards depend on real elapsed time.

    Non-monotonic timestamps would make a rep look impossibly fast and get it
    silently rejected.
    """
    frames = extract_pose_sequence(synthetic_video, MODELS_DIR)
    timestamps = [frame.timestamp_ms for frame in frames]
    assert timestamps == sorted(timestamps)
    assert timestamps[-1] > timestamps[0]


@model_available
def test_undetected_frames_are_rejected_not_scored(synthetic_video):
    """A stick figure is not a person, and the pipeline should say so.

    The failure that matters here is the opposite one: inventing landmarks for
    frames where nothing was detected would produce a confident score from a
    video containing no athlete.
    """
    frames = extract_pose_sequence(synthetic_video, MODELS_DIR)
    result = analyze_sequence(SitUpAnalyzer(), frames)

    assert result.frames_rejected == len(frames)
    assert result.frames_analyzed == 0
    assert not result.is_usable


@model_available
def test_max_frames_bounds_the_work(synthetic_video):
    frames = extract_pose_sequence(synthetic_video, MODELS_DIR, max_frames=10)
    assert len(frames) == 10


@model_available
def test_duration_is_reported(synthetic_video):
    duration = video_duration_seconds(synthetic_video)
    assert duration == pytest.approx(FRAME_COUNT / FPS, abs=0.2)


def test_missing_model_raises_a_clear_error(tmp_path):
    """The error must name the fix. A bare file-not-found on a .task path is
    not something an operator can act on."""
    with pytest.raises(ExtractionError) as caught:
        extract_pose_sequence(tmp_path / "nothing.mp4", tmp_path / "empty-models")

    assert "models/README.md" in str(caught.value) or "model" in str(caught.value).lower()


@model_available
def test_unreadable_video_raises_extraction_error(tmp_path):
    broken = tmp_path / "broken.mp4"
    broken.write_bytes(b"this is not a video")

    with pytest.raises(ExtractionError):
        extract_pose_sequence(broken, MODELS_DIR)

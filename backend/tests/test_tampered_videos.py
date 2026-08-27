"""Sprint 6 Definition of Done, against real rendered video.

> A deliberately tampered test video (e.g. looped clip, wrong person) gets
> correctly auto-flagged in your test set.

Videos are rendered here rather than committed — a handful of MP4s in git that
nobody can regenerate is worse than a few seconds of ffmpeg-free OpenCV drawing.
Each case is built by tampering with an honest recording in a specific way, so
the honest version acts as its own control.

The honest control matters as much as the tampered cases. A detector that flags
everything passes every tampering test and is useless.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.verification.cheat.findings import CheatCheck
from app.verification.cheat.frames import check_frames
from app.verification.cheat.metadata import VideoMetadata, check_metadata
from app.verification.cheat.subject import check_subject
from app.verification.extractor import analyze_video, model_path, perceptual_hash

cv2 = pytest.importorskip("cv2", reason="opencv not installed")
np = pytest.importorskip("numpy", reason="numpy not installed")

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"

model_available = pytest.mark.skipif(
    not model_path(MODELS_DIR).exists(),
    reason="Pose model not fetched (python -m scripts.fetch_model)",
)

FPS = 30
WIDTH, HEIGHT = 480, 854


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def draw_scene(
    index: int,
    *,
    subject_scale: float = 1.0,
    people: int = 1,
    sensor_noise: bool = True,
    rep_jitter: bool = True,
):
    """A frame with a textured background and a figure performing sit-ups.

    Two properties are essential for this fixture to mean anything:

    **Sensor noise.** Every real camera adds it, so two genuine recordings of
    the same motionless scene are never bit-identical. A copy-pasted segment is
    exactly identical. That gap IS the signal loop detection reads, and a
    noiseless fixture would make the detector look far better than it is.

    **Per-rep variation.** No athlete repeats a movement identically. An earlier
    version of this fixture drove the figure with a pure sine wave, which made
    the "honest" recording a literal pixel-perfect loop — the detector was
    correctly flagging a fixture that really was looped.
    """
    frame = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)

    # Static textured background — a wall, in effect. A flat colour would
    # produce near-identical hashes everywhere.
    rng = np.random.default_rng(seed=99)
    texture = rng.integers(30, 70, size=(HEIGHT // 8, WIDTH // 8, 3), dtype=np.uint8)
    frame[:, :] = cv2.resize(
        texture, (WIDTH, HEIGHT), interpolation=cv2.INTER_NEAREST
    )

    for person in range(people):
        rep = index // 36
        # Each rep differs in amplitude and timing, and the athlete drifts
        # across the mat and tires as the test goes on. All three are true of
        # real footage and all three are what stop consecutive reps from being
        # pixel-identical.
        jitter = np.random.default_rng(seed=1000 + rep) if rep_jitter else None
        fatigue = max(0.0, 1.0 - index / 600.0) if rep_jitter else 1.0
        amplitude = (0.45 + (0.10 * jitter.random() if jitter is not None else 0.0)) * fatigue
        period = 18.0 + (3.0 * jitter.random() if jitter is not None else 0.0)
        drift_x = int(18 * jitter.random()) if jitter is not None else 0
        drift_y = int(12 * jitter.random()) if jitter is not None else 0

        phase = np.sin(index / period * np.pi)
        base_x = WIDTH // 2 + (person * 130) - (60 if people > 1 else 0) + drift_x
        base_y = int(HEIGHT * 0.85) + drift_y

        # The athlete fills a realistic share of a portrait frame — they are
        # told to keep their whole body visible.
        full = HEIGHT * 0.55 * subject_scale
        head_r = int(full * 0.09)
        torso = int(full * (0.30 + amplitude * abs(phase)))

        head_y = base_y - torso - head_r
        cv2.circle(frame, (base_x, head_y), head_r, (225, 225, 215), -1)
        cv2.line(
            frame, (base_x, head_y + head_r), (base_x, base_y),
            (215, 215, 205), int(full * 0.12),
        )
        cv2.line(
            frame, (base_x, base_y),
            (base_x - int(full * 0.22), base_y + int(full * 0.10)),
            (215, 215, 205), int(full * 0.09),
        )
        cv2.line(
            frame, (base_x, base_y),
            (base_x + int(full * 0.22), base_y + int(full * 0.10)),
            (215, 215, 205), int(full * 0.09),
        )

    if sensor_noise:
        noise_rng = np.random.default_rng(seed=50_000 + index)
        noise = noise_rng.integers(-6, 7, size=frame.shape, dtype=np.int16)
        frame = np.clip(frame.astype(np.int16) + noise, 0, 255).astype(np.uint8)

    return frame


def write_video(path: Path, frames: list) -> Path:
    writer = cv2.VideoWriter(
        str(path), cv2.VideoWriter_fourcc(*"mp4v"), FPS, (WIDTH, HEIGHT)
    )
    for frame in frames:
        writer.write(frame)
    writer.release()
    return path


def honest_frames(count: int = 150) -> list:
    return [draw_scene(index) for index in range(count)]


def hashes_of(frames: list) -> list[int]:
    return [perceptual_hash(frame) for frame in frames]


def timestamps_for(count: int) -> list[int]:
    return [int(index * 1000 / FPS) for index in range(count)]


# ---------------------------------------------------------------------------
# The control: an honest recording must pass
# ---------------------------------------------------------------------------


def test_honest_recording_is_not_flagged():
    """The control case.

    A detector that flags everything passes every tampering test below and is
    worse than no detector, because it destroys the review queue's signal.
    """
    frames = honest_frames()
    hashes = hashes_of(frames)

    report = check_frames(hashes, timestamps_for(len(hashes)))

    assert report.is_clean, f"Honest recording was flagged: {report.summary()}"


def test_honest_metadata_passes():
    metadata = VideoMetadata(
        duration_seconds=150 / FPS + 40,
        width=WIDTH,
        height=HEIGHT,
        fps=float(FPS),
        file_size_bytes=2_000_000,
    )
    assert check_metadata(metadata, "SIT_UPS").is_clean


# ---------------------------------------------------------------------------
# Tampered case 1: a looped clip
# ---------------------------------------------------------------------------


def test_looped_clip_is_flagged(tmp_path):
    """The classic: record five reps, paste them in again to claim ten."""
    original = honest_frames(90)
    looped = original + original  # the whole segment repeated verbatim

    path = write_video(tmp_path / "looped.mp4", looped)
    assert path.exists()

    hashes = hashes_of(looped)
    report = check_frames(hashes, timestamps_for(len(hashes)))

    findings = [f for f in report.findings if f.check is CheatCheck.LOOPED_FRAMES]
    assert findings, f"Looped video was not flagged: {report.summary()}"
    assert findings[0].at_ms is not None


def test_partially_looped_clip_is_flagged():
    """More subtle: only part of the recording is copied."""
    original = honest_frames(120)
    tampered = original[:80] + original[20:60] + original[80:]

    report = check_frames(hashes_of(tampered), timestamps_for(len(tampered)))

    assert any(f.check is CheatCheck.LOOPED_FRAMES for f in report.findings)


# ---------------------------------------------------------------------------
# Tampered case 2: spliced footage
# ---------------------------------------------------------------------------


def test_spliced_footage_is_flagged():
    """Two different recordings joined together produce a hard cut."""
    first = honest_frames(60)

    # A visibly different scene: inverted background, different subject size.
    second = [
        cv2.bitwise_not(draw_scene(index, subject_scale=1.4))
        for index in range(60)
    ]

    report = check_frames(
        hashes_of(first + second), timestamps_for(120)
    )

    assert any(f.check is CheatCheck.ABRUPT_CUT for f in report.findings)


# ---------------------------------------------------------------------------
# Tampered case 3: a still image, not a test
# ---------------------------------------------------------------------------


def test_camera_pointed_at_a_still_image_is_flagged():
    frozen = [draw_scene(10)] * 120

    report = check_frames(hashes_of(frozen), timestamps_for(120))

    assert any(f.check is CheatCheck.STATIC_VIDEO for f in report.findings)


# ---------------------------------------------------------------------------
# Tampered case 4: metadata that cannot be right
# ---------------------------------------------------------------------------


def test_clip_too_short_to_be_a_sit_up_test_is_flagged():
    metadata = VideoMetadata(
        duration_seconds=2.5,
        width=WIDTH,
        height=HEIGHT,
        fps=float(FPS),
        file_size_bytes=200_000,
    )

    report = check_metadata(metadata, "SIT_UPS")

    assert any(
        f.check is CheatCheck.DURATION_IMPLAUSIBLE for f in report.findings
    )


def test_sped_up_footage_is_flagged():
    metadata = VideoMetadata(
        duration_seconds=45.0,
        width=WIDTH,
        height=HEIGHT,
        fps=240.0,
        file_size_bytes=2_000_000,
    )

    report = check_metadata(metadata, "SIT_UPS")

    assert any(
        f.check is CheatCheck.FRAMERATE_IMPLAUSIBLE for f in report.findings
    )


# ---------------------------------------------------------------------------
# End to end, through the real extraction pass
# ---------------------------------------------------------------------------


@model_available
def test_extraction_produces_the_signals_the_checks_need(tmp_path):
    """One decode pass must yield landmarks, hashes, counts and metadata.

    Decoding a multi-megabyte video once per check would multiply straight into
    the verification SLA, so this asserts the single-pass contract holds.
    """
    path = write_video(tmp_path / "honest.mp4", honest_frames(90))

    analysis = analyze_video(path, MODELS_DIR)

    assert len(analysis.frames) == 90
    assert len(analysis.frame_hashes) == 90
    assert len(analysis.pose_counts) == 90
    assert analysis.metadata is not None
    assert analysis.metadata.width == WIDTH
    assert analysis.metadata.height == HEIGHT
    assert analysis.metadata.fps == pytest.approx(FPS, abs=1)
    assert analysis.metadata.file_size_bytes > 0


@model_available
def test_looped_real_video_survives_re_encoding(tmp_path):
    """The check must see through the transcode the mobile app applies.

    A cryptographic hash would fail here — re-encoding changes every byte while
    leaving the picture identical. That is why this uses a perceptual hash.
    """
    original = honest_frames(90)
    path = write_video(tmp_path / "looped_encoded.mp4", original + original)

    analysis = analyze_video(path, MODELS_DIR)

    report = check_frames(
        analysis.frame_hashes,
        [frame.timestamp_ms for frame in analysis.frames],
    )

    assert any(f.check is CheatCheck.LOOPED_FRAMES for f in report.findings), (
        "Loop survived encoding but was not detected: " + report.summary()
    )


@model_available
def test_honest_real_video_survives_re_encoding(tmp_path):
    path = write_video(tmp_path / "honest_encoded.mp4", honest_frames(150))

    analysis = analyze_video(path, MODELS_DIR)

    report = check_frames(
        analysis.frame_hashes,
        [frame.timestamp_ms for frame in analysis.frames],
    )

    assert report.is_clean, f"Honest encoded video was flagged: {report.summary()}"


@model_available
def test_empty_scene_reports_no_subject(tmp_path):
    """A recording with nobody in it cannot evidence a test."""
    background = [draw_scene(0, people=0) for _ in range(60)]
    path = write_video(tmp_path / "empty.mp4", background)

    analysis = analyze_video(path, MODELS_DIR)
    report = check_subject(analysis.frames, analysis.pose_counts)

    assert any(f.check is CheatCheck.NO_SUBJECT for f in report.findings)

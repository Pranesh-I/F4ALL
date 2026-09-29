"""Metadata sanity checks.

The cheapest checks available — no decoding, no models — and they catch the
laziest tampering: a clip far too short to contain the claimed test, a
resolution that no build of this app produces, a frame rate that means the
footage was sped up.

Bounds are deliberately generous. These run before anyone looks at the video, so
a false positive here costs a reviewer's time on an honest athlete. Anything
inside the range of "a real phone recording a real test" must pass.
"""

from __future__ import annotations

from dataclasses import dataclass

from .findings import CheatCheck, CheatFinding, CheatReport, Severity

# The mobile app transcodes to 480 on the short side before upload, but
# encoders round dimensions to macroblock boundaries and older devices report
# odd sizes. Wide enough to accept any of that.
MIN_SHORT_SIDE = 240
MAX_SHORT_SIDE = 1080

# A phone recording at less than this is not producing usable pose data; above
# it, the footage has been sped up or the container lies.
MIN_FPS = 10.0
MAX_FPS = 121.0

# Per-test duration windows, in seconds.
DURATION_BOUNDS: dict[str, tuple[float, float]] = {
    # A sit-up test is scored over a fixed window; a few seconds of recording
    # cannot contain one, and half an hour is a forgotten camera.
    "SIT_UPS": (8.0, 300.0),
    # A jump needs a still calibration period plus the jump itself.
    "VERTICAL_JUMP": (3.0, 180.0),
    # Rep tests need a held start position plus at least one rep.
    "SQUATS": (5.0, 300.0),
    "PUSH_UPS": (5.0, 300.0),
    "BICEP_CURLS": (5.0, 300.0),
    "LUNGES": (5.0, 300.0),
}

DEFAULT_DURATION_BOUNDS = (3.0, 600.0)

# Bytes per second below which a "video" is too small to hold real 480p footage.
# Catches a heavily re-encoded or near-empty file dressed up with a plausible
# duration.
MIN_BYTES_PER_SECOND = 4_000


@dataclass(frozen=True)
class VideoMetadata:
    duration_seconds: float | None
    width: int | None
    height: int | None
    fps: float | None
    file_size_bytes: int | None
    frame_count: int | None = None


def check_metadata(
    metadata: VideoMetadata,
    test_code: str,
    *,
    report: CheatReport | None = None,
) -> CheatReport:
    report = report or CheatReport()

    _check_duration(metadata, test_code, report)
    _check_resolution(metadata, report)
    _check_framerate(metadata, report)
    _check_bitrate(metadata, report)

    return report


def _check_duration(
    metadata: VideoMetadata, test_code: str, report: CheatReport
) -> None:
    if metadata.duration_seconds is None:
        report.skip(CheatCheck.DURATION_IMPLAUSIBLE, "Duration unavailable")
        return

    low, high = DURATION_BOUNDS.get(test_code.upper(), DEFAULT_DURATION_BOUNDS)
    duration = metadata.duration_seconds

    if duration < low:
        report.add(
            CheatFinding(
                check=CheatCheck.DURATION_IMPLAUSIBLE,
                severity=Severity.HIGH,
                detail=(
                    f"The recording is {duration:.1f}s, too short to contain a "
                    f"{test_code.replace('_', ' ').lower()} test "
                    f"(expected at least {low:.0f}s)"
                ),
                evidence={"duration_seconds": duration, "minimum": low},
            )
        )
    elif duration > high:
        report.add(
            CheatFinding(
                check=CheatCheck.DURATION_IMPLAUSIBLE,
                # Long is far less suspicious than short — usually someone
                # forgot to press stop.
                severity=Severity.LOW,
                detail=(
                    f"The recording is {duration:.0f}s, longer than expected "
                    f"for this test (up to {high:.0f}s)"
                ),
                evidence={"duration_seconds": duration, "maximum": high},
            )
        )


def _check_resolution(metadata: VideoMetadata, report: CheatReport) -> None:
    if not metadata.width or not metadata.height:
        report.skip(CheatCheck.RESOLUTION_UNEXPECTED, "Resolution unavailable")
        return

    short_side = min(metadata.width, metadata.height)

    if short_side < MIN_SHORT_SIDE or short_side > MAX_SHORT_SIDE:
        report.add(
            CheatFinding(
                check=CheatCheck.RESOLUTION_UNEXPECTED,
                severity=Severity.MEDIUM,
                detail=(
                    f"Resolution {metadata.width}x{metadata.height} is outside "
                    "the range this app produces, so the file may not have come "
                    "from the app"
                ),
                evidence={
                    "width": float(metadata.width),
                    "height": float(metadata.height),
                },
            )
        )


def _check_framerate(metadata: VideoMetadata, report: CheatReport) -> None:
    if not metadata.fps:
        report.skip(CheatCheck.FRAMERATE_IMPLAUSIBLE, "Frame rate unavailable")
        return

    if metadata.fps < MIN_FPS or metadata.fps > MAX_FPS:
        report.add(
            CheatFinding(
                check=CheatCheck.FRAMERATE_IMPLAUSIBLE,
                severity=Severity.MEDIUM,
                detail=(
                    f"Frame rate is {metadata.fps:.1f}fps, outside the plausible "
                    "range for a phone recording"
                ),
                evidence={"fps": metadata.fps},
            )
        )


def _check_bitrate(metadata: VideoMetadata, report: CheatReport) -> None:
    if not metadata.file_size_bytes or not metadata.duration_seconds:
        return
    if metadata.duration_seconds <= 0:
        return

    bytes_per_second = metadata.file_size_bytes / metadata.duration_seconds

    if bytes_per_second < MIN_BYTES_PER_SECOND:
        report.add(
            CheatFinding(
                check=CheatCheck.RESOLUTION_UNEXPECTED,
                severity=Severity.LOW,
                detail=(
                    f"The file holds only {bytes_per_second / 1000:.1f}KB per "
                    "second of video, which is low for real 480p footage and can "
                    "indicate heavy re-encoding"
                ),
                evidence={"bytes_per_second": bytes_per_second},
            )
        )

"""Validation of a submission's video before it is scored.

Runs first in the worker, so a file that cannot be a test recording is reported
as exactly that — with a code the athlete and the reviewer can act on — instead
of surfacing later as "no athlete visible" or a generic processing error.

Every failure carries a disposition, and the distinction is deliberate:

* ``REJECT`` — nobody, machine or human, could verify this submission: there
  is no video, or the file is not a readable video. Retrying the same bytes
  cannot change that.
* ``FLAG`` — the server could not verify it automatically, but the recording
  may be perfectly valid and a reviewer can still judge it (the stored object
  has gone missing on our side, a test with no automatic scorer, a jump with
  no height to calibrate against).

Nothing here judges the athlete's performance. Duration, resolution and frame
rate that look implausible are integrity signals and stay in ``cheat.metadata``
as flags; they never reject.
"""

from __future__ import annotations

from enum import Enum
from pathlib import Path

from .analyzers import TestType


class Disposition(str, Enum):
    REJECT = "reject"
    FLAG = "flag"


class ValidationCode(str, Enum):
    VIDEO_NOT_SUBMITTED = "video_not_submitted"
    VIDEO_MISSING_FROM_STORAGE = "video_missing_from_storage"
    VIDEO_EMPTY = "video_empty"
    VIDEO_TOO_LARGE = "video_too_large"
    UNSUPPORTED_FORMAT = "unsupported_format"
    VIDEO_UNREADABLE = "video_unreadable"
    NO_DECODABLE_FRAMES = "no_decodable_frames"
    TEST_TYPE_NOT_VERIFIABLE = "test_type_not_verifiable"
    ATHLETE_HEIGHT_UNKNOWN = "athlete_height_unknown"


DISPOSITIONS: dict[ValidationCode, Disposition] = {
    ValidationCode.VIDEO_NOT_SUBMITTED: Disposition.REJECT,
    # Uploaded with a verified checksum and then lost: our failure, not theirs.
    ValidationCode.VIDEO_MISSING_FROM_STORAGE: Disposition.FLAG,
    ValidationCode.VIDEO_EMPTY: Disposition.REJECT,
    ValidationCode.VIDEO_TOO_LARGE: Disposition.REJECT,
    ValidationCode.UNSUPPORTED_FORMAT: Disposition.REJECT,
    ValidationCode.VIDEO_UNREADABLE: Disposition.REJECT,
    ValidationCode.NO_DECODABLE_FRAMES: Disposition.REJECT,
    ValidationCode.TEST_TYPE_NOT_VERIFIABLE: Disposition.FLAG,
    ValidationCode.ATHLETE_HEIGHT_UNKNOWN: Disposition.FLAG,
}


class ValidationFailure(Exception):
    def __init__(self, code: ValidationCode, detail: str) -> None:
        super().__init__(f"{code.value}: {detail}")
        self.code = code
        self.detail = detail

    @property
    def disposition(self) -> Disposition:
        return DISPOSITIONS[self.code]

    @property
    def rejects(self) -> bool:
        return self.disposition is Disposition.REJECT


# ISO base media file format (MP4, 3GP, MOV) starts with a box whose type is
# at bytes 4..8. Almost always `ftyp`; QuickTime writers may lead with others.
_ISO_BMFF_LEADING_BOXES = {b"ftyp", b"moov", b"mdat", b"wide", b"free", b"skip"}


def sniff_container(path: Path) -> str | None:
    """"mp4" for an ISO-BMFF file, else None. Reads 12 bytes, decodes nothing."""
    with Path(path).open("rb") as handle:
        header = handle.read(12)
    if len(header) >= 8 and header[4:8] in _ISO_BMFF_LEADING_BOXES:
        return "mp4"
    return None


def check_file(path: Path, *, max_bytes: int) -> dict:
    """Existence, size and container of the fetched video.

    Returns what was checked, for the verdict's record; raises
    ``ValidationFailure`` on the first problem.
    """
    path = Path(path)
    if not path.exists():
        raise ValidationFailure(
            ValidationCode.VIDEO_MISSING_FROM_STORAGE,
            "The stored video could not be found",
        )

    size = path.stat().st_size
    if size == 0:
        raise ValidationFailure(ValidationCode.VIDEO_EMPTY, "The video file is empty")
    if size > max_bytes:
        raise ValidationFailure(
            ValidationCode.VIDEO_TOO_LARGE,
            f"The video is {size} bytes, above the {max_bytes} byte limit",
        )

    container = sniff_container(path)
    if container is None:
        raise ValidationFailure(
            ValidationCode.UNSUPPORTED_FORMAT,
            "The file is not an MP4 video",
        )

    return {"file_size_bytes": size, "container": container}


def verifiable_test_type(test_code: str) -> TestType:
    """The scorer for this test, or a FLAG failure when there is none.

    A test SAI adds before its scorer exists is still a legitimate submission;
    a reviewer can score it by hand.
    """
    try:
        return TestType(test_code)
    except ValueError:
        raise ValidationFailure(
            ValidationCode.TEST_TYPE_NOT_VERIFIABLE,
            f"No automatic scorer exists for test '{test_code}'",
        ) from None


def require_height(test_type: TestType, height_cm: float | None) -> None:
    # Guessing a height would produce a confidently wrong jump, which is worse
    # than admitting the measurement cannot be made.
    if test_type is TestType.VERTICAL_JUMP and height_cm is None:
        raise ValidationFailure(
            ValidationCode.ATHLETE_HEIGHT_UNKNOWN,
            "Athlete height unknown; jump height cannot be calibrated",
        )

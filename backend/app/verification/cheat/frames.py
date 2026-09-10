"""Frame-consistency checks: looped segments, abrupt cuts, dead video.

## Why two representations

Each frame is reduced to two things during the single decode pass:

* a **256-bit perceptual hash** — one integer, compared with a XOR and a
  popcount, used as a cheap prefilter;
* a **256-byte greyscale signature** (16x16 downsample) — compared with mean
  absolute difference, used to confirm.

The hash alone is not enough, and that was established by measurement rather
than assumed. The mobile app transcodes to 480p H.264 before upload, so the
server only ever sees re-encoded footage. On rendered test video:

| | true copied frames | honest frames one rep apart |
|---|---|---|
| hash distance | 8.4 mean, 16 max | 11.4 mean, **3 min** |
| signature MAE | 0.09 mean, **0.12 max** | **1.55 min** |

The hash distributions overlap almost completely — there is no threshold that
separates a copied segment from ordinary repetitive-exercise footage. Signature
MAE separates them by more than tenfold. The reason is that a difference hash
*binarises* each comparison, discarding magnitude, and magnitude is precisely
what lossy re-encoding perturbs.

The signature is too expensive to compare across every candidate offset in a
long video, hence the two-stage approach: the hash proposes, the signature
disposes.

Pure Python — no opencv, no numpy. Both representations are produced once during
video decoding, so everything here is testable with hand-built fixtures.
"""

from __future__ import annotations

from dataclasses import dataclass

from .findings import CheatCheck, CheatFinding, CheatReport, Severity

# ---------------------------------------------------------------------------
# Thresholds
# ---------------------------------------------------------------------------

HASH_BITS = 256

# Prefilter only. Deliberately loose — its job is to discard obvious non-matches
# cheaply, not to decide anything. Every survivor is confirmed by signature MAE.
PREFILTER_DISTANCE = int(HASH_BITS * 0.12)  # 30 bits

# Mean absolute difference (0-255 scale) below which two frames are the same
# picture. Measured true copies reach 0.12 after re-encoding; honest frames a
# rep apart never fall below 1.55. This sits between them with a wide margin on
# both sides.
IDENTICAL_MAE = 0.5

# A cut is a discontinuity between ADJACENT frames.
CUT_MAE = 28.0

# A repeated run must be at least this long — roughly two-thirds of a second at
# 30fps.
#
# Measured against rendered footage, 8 frames (~0.27s) produced false positives:
# consecutive reps match closely at the same point in the movement, because the
# athlete really is in nearly the same position. Sub-second coincidental matches
# are the NORMAL state of repetitive-exercise video. Real tampering copies
# seconds, not fractions of one, so this costs almost no detection power.
MIN_LOOP_RUN = 20

# ...and the repeat must start at least this far away, so consecutive near-still
# frames are not mistaken for a loop of themselves.
MIN_LOOP_GAP = 15

# Candidate start positions are sampled rather than exhaustive. A run of
# MIN_LOOP_RUN frames still contains several sampled starts, so nothing of the
# required length is missed.
START_STRIDE = 5

# Mean adjacent-frame MAE below which nothing is moving.
MIN_MEAN_ADJACENT_MOTION = 0.25

# A matched run must contain this much internal movement to count as a loop.
# A repeat of a motionless scene is a motionless scene — reported separately by
# the static check — not evidence of copied footage.
MIN_RUN_INTERNAL_VARIATION = 1.5


@dataclass(frozen=True)
class LoopMatch:
    source_index: int
    repeat_index: int
    length: int
    mean_difference: float


def hamming(first: int, second: int) -> int:
    return bin(first ^ second).count("1")


def signature_difference(first: bytes, second: bytes) -> float:
    """Mean absolute difference between two greyscale signatures."""
    if not first or len(first) != len(second):
        return 255.0
    total = 0
    # Lengths are checked above, so strict= would only re-test what the guard
    # already established — but state it explicitly rather than leave the
    # silent-truncation behaviour implied.
    for a, b in zip(first, second, strict=True):
        total += a - b if a > b else b - a
    return total / len(first)


def find_looped_runs(
    hashes: list[int],
    signatures: list[bytes],
    *,
    identical_mae: float = IDENTICAL_MAE,
    min_run: int = MIN_LOOP_RUN,
    min_gap: int = MIN_LOOP_GAP,
) -> list[LoopMatch]:
    """Find runs of frames that reappear later in the video.

    Looks for *runs*, not individual similar frames. A sit-up test is full of
    frames that resemble each other — the athlete returns to the same position
    twenty times and the wall behind them does not move. Flagging pairwise
    similarity would flag every honest recording.
    """
    matches: list[LoopMatch] = []
    total = min(len(hashes), len(signatures))
    consumed_until = -1

    for start in range(0, total, START_STRIDE):
        if start <= consumed_until:
            continue

        for repeat in range(start + min_gap, total):
            # Cheap rejection first: if the opening frames are not even close by
            # hash, no amount of signature comparison will make them a run.
            if hamming(hashes[start], hashes[repeat]) > PREFILTER_DISTANCE:
                continue

            if (
                signature_difference(signatures[start], signatures[repeat])
                > identical_mae
            ):
                continue

            run = 0
            total_difference = 0.0
            while repeat + run < total and start + run < repeat:
                difference = signature_difference(
                    signatures[start + run], signatures[repeat + run]
                )
                if difference > identical_mae:
                    break
                total_difference += difference
                run += 1

            if run >= min_run and _has_internal_variation(
                signatures[start : start + run]
            ):
                matches.append(
                    LoopMatch(
                        source_index=start,
                        repeat_index=repeat,
                        length=run,
                        mean_difference=total_difference / run,
                    )
                )
                # Skip past the matched region so one long loop reports once
                # rather than once per offset within it.
                consumed_until = start + run - 1
                break

    return matches


def _has_internal_variation(
    run: list[bytes], *, minimum: float = MIN_RUN_INTERNAL_VARIATION
) -> bool:
    """True when the run actually contains movement."""
    if not run:
        return False
    first = run[0]
    return any(signature_difference(first, value) >= minimum for value in run)


def find_abrupt_cuts(
    signatures: list[bytes], *, cut_mae: float = CUT_MAE
) -> list[int]:
    """Indices where the picture changes discontinuously between two frames."""
    return [
        index
        for index in range(1, len(signatures))
        if signature_difference(signatures[index - 1], signatures[index]) >= cut_mae
    ]


def mean_adjacent_motion(signatures: list[bytes]) -> float:
    """Average difference between consecutive frames.

    A direct measure of how much the picture is changing. Near zero means the
    camera is pointed at something that is not moving.
    """
    if len(signatures) < 2:
        return 0.0
    differences = [
        signature_difference(signatures[index - 1], signatures[index])
        for index in range(1, len(signatures))
    ]
    return sum(differences) / len(differences)


def check_frames(
    hashes: list[int],
    signatures: list[bytes],
    timestamps_ms: list[int],
    *,
    report: CheatReport | None = None,
) -> CheatReport:
    report = report or CheatReport()

    if len(signatures) < MIN_LOOP_RUN * 2:
        report.skip(CheatCheck.LOOPED_FRAMES, "Too few frames to analyse")
        return report

    def at(index: int) -> int | None:
        return timestamps_ms[index] if index < len(timestamps_ms) else None

    for match in find_looped_runs(hashes, signatures):
        report.add(
            CheatFinding(
                check=CheatCheck.LOOPED_FRAMES,
                severity=Severity.HIGH,
                detail=(
                    f"{match.length} frames from "
                    f"{_seconds(at(match.source_index))} repeat at "
                    f"{_seconds(at(match.repeat_index))}"
                ),
                at_ms=at(match.repeat_index),
                evidence={
                    "run_length_frames": float(match.length),
                    "source_index": float(match.source_index),
                    "repeat_index": float(match.repeat_index),
                    "mean_difference": match.mean_difference,
                },
            )
        )

    for index in find_abrupt_cuts(signatures):
        report.add(
            CheatFinding(
                check=CheatCheck.ABRUPT_CUT,
                severity=Severity.MEDIUM,
                detail=(
                    f"The picture changes abruptly at {_seconds(at(index))}, "
                    "which can mean the recording was edited"
                ),
                at_ms=at(index),
                evidence={"frame_index": float(index)},
            )
        )

    motion = mean_adjacent_motion(signatures)
    if motion < MIN_MEAN_ADJACENT_MOTION:
        report.add(
            CheatFinding(
                check=CheatCheck.STATIC_VIDEO,
                severity=Severity.MEDIUM,
                detail=(
                    "Almost nothing moves in this recording — the camera may "
                    "have been pointed at a still image rather than an athlete"
                ),
                evidence={"mean_adjacent_motion": motion},
            )
        )

    return report


def _seconds(timestamp_ms: int | None) -> str:
    if timestamp_ms is None:
        return "an unknown point"
    return f"{timestamp_ms / 1000:.1f}s"

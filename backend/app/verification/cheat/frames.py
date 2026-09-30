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
from itertools import pairwise

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

# --- Duplicated frames (Sprint 12) -----------------------------------------
#
# Measured with the per-frame change the extractor records: the largest
# difference in any cell of a 64x64 greyscale grid between a frame and the one
# before it (0-255). The 16x16 signature above cannot do this job — on a real
# squat recording half of all adjacent pairs fall under IDENTICAL_MAE, because
# a slowly moving athlete is a small part of a heavily averaged frame.
#
# | footage                              | largest-cell change          |
# |--------------------------------------|------------------------------|
# | rendered honest (sensor noise)        | min 14, median 176           |
# | rendered, every frame written twice   | repeats <= 2                 |
# | real squat clip, athlete standing     | many pairs <= 3 (true stills)|
#
# So "changed by at most this much" is a repeat...
DUPLICATE_MAX_CHANGE = 3.0

# ...but only counts when the frames either side ARE changing. A still athlete
# produces long runs of near-identical frames honestly; a repeated frame in the
# middle of movement is what re-timed, slowed or padded footage looks like.
DUPLICATE_MOTION_CONTEXT = 12.0

# Share of in-motion frames that are repeats before it is flagged. Measured:
# 0.00 on rendered honest footage, 0.17 with every fifth frame repeated, 0.50
# with every frame doubled (2x slow motion). A real web clip that had been
# frame-rate converted before upload measured 0.30 — genuinely repeated
# pixels, with an innocent cause, which is why the flag is LOW and says so.
DUPLICATE_FRAME_RATIO = 0.15
MIN_DUPLICATE_FRAMES = 10

# Systematic doubling (half of all moving frames repeated) is 2x slow motion,
# worth a reviewer's earlier attention.
DUPLICATE_MEDIUM_RATIO = 0.4

# --- Timestamp anomalies (Sprint 12) ---------------------------------------
#
# A gap between frame timestamps this many times the median interval, and at
# least TIMESTAMP_MIN_GAP_MS, means a stretch of the recording is missing.
# Phones under load drop a frame or two (66-100 ms gaps at 30 fps), which stays
# well under the absolute floor.
TIMESTAMP_GAP_FACTOR = 4.0
TIMESTAMP_MIN_GAP_MS = 250


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


@dataclass(frozen=True)
class DuplicateFrames:
    indices: list[int]
    moving_frames: int

    @property
    def ratio(self) -> float:
        context = self.moving_frames + len(self.indices)
        return len(self.indices) / context if context else 0.0


def find_duplicated_frames(
    changes: list[float | None],
    *,
    max_change: float = DUPLICATE_MAX_CHANGE,
    motion_context: float = DUPLICATE_MOTION_CONTEXT,
) -> DuplicateFrames:
    """Frames that repeat the previous one while the scene around them moves.

    ``changes[i]`` is the change from frame i-1 to frame i (None for the first
    frame). Frame i is a repeat when it barely changed and a neighbouring
    transition is clearly moving; a run of still frames is not.
    """

    def change(index: int) -> float | None:
        return changes[index] if 0 <= index < len(changes) else None

    moving = 0
    repeats: list[int] = []
    for index, value in enumerate(changes):
        if value is None:
            continue
        if value >= motion_context:
            moving += 1
            continue
        if value > max_change:
            continue
        before, after = change(index - 1), change(index + 1)
        if (before is not None and before >= motion_context) or (
            after is not None and after >= motion_context
        ):
            repeats.append(index)
    return DuplicateFrames(indices=repeats, moving_frames=moving)


@dataclass(frozen=True)
class TimestampAnomaly:
    index: int
    gap_ms: int
    kind: str  # "gap" or "backwards"


def find_timestamp_anomalies(
    timestamps_ms: list[int],
    *,
    gap_factor: float = TIMESTAMP_GAP_FACTOR,
    min_gap_ms: int = TIMESTAMP_MIN_GAP_MS,
) -> tuple[list[TimestampAnomaly], float | None]:
    """Missing stretches and time running backwards; also the median interval."""
    intervals = [b - a for a, b in pairwise(timestamps_ms)]
    positive = sorted(value for value in intervals if value > 0)
    if not positive:
        return [], None
    median = positive[len(positive) // 2]

    anomalies = []
    for offset, interval in enumerate(intervals):
        index = offset + 1
        if interval <= 0:
            anomalies.append(TimestampAnomaly(index, interval, "backwards"))
        elif interval >= max(gap_factor * median, min_gap_ms):
            anomalies.append(TimestampAnomaly(index, interval, "gap"))
    return anomalies, float(median)


def check_frames(
    hashes: list[int],
    signatures: list[bytes],
    timestamps_ms: list[int],
    *,
    report: CheatReport | None = None,
    frame_changes: list[float | None] | None = None,
    duplicate_frame_ratio: float = DUPLICATE_FRAME_RATIO,
) -> CheatReport:
    report = report or CheatReport()

    def at(index: int) -> int | None:
        return timestamps_ms[index] if index < len(timestamps_ms) else None

    summary: dict = {"frames": len(signatures)}
    _check_timestamps(timestamps_ms, report, summary, at)
    _check_duplicates(frame_changes, report, summary, at, duplicate_frame_ratio)

    if len(signatures) < MIN_LOOP_RUN * 2:
        report.skip(CheatCheck.LOOPED_FRAMES, "Too few frames to analyse")
        report.summarise("frames", summary)
        return report

    loops = find_looped_runs(hashes, signatures)
    for match in loops:
        source_range = [match.source_index, match.source_index + match.length - 1]
        repeat_range = [match.repeat_index, match.repeat_index + match.length - 1]
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
                    "run_length_frames": match.length,
                    "source_index": match.source_index,
                    "repeat_index": match.repeat_index,
                    "affected_frame_range": {
                        "source": source_range,
                        "repeat": repeat_range,
                    },
                    "mean_difference": round(match.mean_difference, 3),
                    "threshold": IDENTICAL_MAE,
                },
            )
        )
    summary["loop_detected"] = bool(loops)
    summary["loops"] = [
        {
            "source": [m.source_index, m.source_index + m.length - 1],
            "repeat": [m.repeat_index, m.repeat_index + m.length - 1],
        }
        for m in loops
    ]

    cuts = find_abrupt_cuts(signatures)
    for index in cuts:
        report.add(
            CheatFinding(
                check=CheatCheck.ABRUPT_CUT,
                severity=Severity.MEDIUM,
                detail=(
                    f"The picture changes abruptly at {_seconds(at(index))}, "
                    "which can mean the recording was edited"
                ),
                at_ms=at(index),
                evidence={
                    "frame_index": index,
                    "difference": round(
                        signature_difference(signatures[index - 1], signatures[index]),
                        2,
                    ),
                    "threshold": CUT_MAE,
                },
            )
        )
    summary["abrupt_cuts"] = cuts

    motion = mean_adjacent_motion(signatures)
    summary["mean_adjacent_motion"] = round(motion, 3)
    if motion < MIN_MEAN_ADJACENT_MOTION:
        report.add(
            CheatFinding(
                check=CheatCheck.STATIC_VIDEO,
                severity=Severity.MEDIUM,
                detail=(
                    "Almost nothing moves in this recording — the camera may "
                    "have been pointed at a still image rather than an athlete"
                ),
                evidence={
                    "mean_adjacent_motion": round(motion, 3),
                    "threshold": MIN_MEAN_ADJACENT_MOTION,
                },
            )
        )

    report.summarise("frames", summary)
    return report


def _check_duplicates(
    frame_changes: list[float | None] | None,
    report: CheatReport,
    summary: dict,
    at,
    ratio_threshold: float,
) -> None:
    if not frame_changes:
        report.skip(CheatCheck.DUPLICATE_FRAMES, "Per-frame change unavailable")
        summary["duplicate_ratio"] = None
        return

    found = find_duplicated_frames(frame_changes)
    ratio = found.ratio
    affected = [found.indices[0], found.indices[-1]] if found.indices else None
    summary["duplicate_ratio"] = round(ratio, 3)
    summary["duplicate_frames"] = len(found.indices)
    summary["duplicate_frame_range"] = affected

    if len(found.indices) < MIN_DUPLICATE_FRAMES or ratio < ratio_threshold:
        return

    report.add(
        CheatFinding(
            check=CheatCheck.DUPLICATE_FRAMES,
            severity=(
                Severity.MEDIUM if ratio >= DUPLICATE_MEDIUM_RATIO else Severity.LOW
            ),
            detail=(
                f"{ratio:.0%} of the frames where the athlete is moving repeat "
                "the previous frame exactly. That is what slowed-down or padded "
                "footage looks like, but it also happens when a phone duplicates "
                "frames in low light or a video was converted between frame "
                "rates — compare the movement with the timer"
            ),
            at_ms=at(found.indices[0]),
            evidence={
                "duplicate_ratio": round(ratio, 3),
                "duplicate_frames": len(found.indices),
                "moving_frames": found.moving_frames,
                "affected_frame_range": affected,
                "threshold": ratio_threshold,
            },
        )
    )


def _check_timestamps(
    timestamps_ms: list[int], report: CheatReport, summary: dict, at
) -> None:
    anomalies, median = find_timestamp_anomalies(timestamps_ms)
    summary["median_frame_interval_ms"] = median
    summary["timestamp_anomalies"] = [
        {"frame_index": a.index, "gap_ms": a.gap_ms, "kind": a.kind} for a in anomalies
    ]
    if not anomalies:
        return

    first = anomalies[0]
    report.add(
        CheatFinding(
            check=CheatCheck.TIMESTAMP_ANOMALY,
            # Low: containers written by some phones and converters carry odd
            # timestamps with nothing edited.
            severity=Severity.LOW,
            detail=(
                f"The recording's frame timing jumps {len(anomalies)} time(s), "
                f"first at {_seconds(at(first.index))}: part of it may have been "
                "removed or the video re-assembled"
            ),
            at_ms=at(first.index),
            evidence={
                "anomalies": len(anomalies),
                "first_frame_index": first.index,
                "first_gap_ms": first.gap_ms,
                "first_kind": first.kind,
                "median_frame_interval_ms": median,
                "gap_factor": TIMESTAMP_GAP_FACTOR,
                "min_gap_ms": TIMESTAMP_MIN_GAP_MS,
            },
        )
    )


def _seconds(timestamp_ms: int | None) -> str:
    if timestamp_ms is None:
        return "an unknown point"
    return f"{timestamp_ms / 1000:.1f}s"

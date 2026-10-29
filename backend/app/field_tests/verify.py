"""The server's verdict on a field test, from the trace the phone sent.

The phone scores the run and sends the score with the raw trace. The server
scores the trace again with its own copy of the algorithm (the parity fixtures
hold the two to the same answer), checks it for plausibility, and decides:

* **rejected** — the trace cannot be a valid run: a false start, the wrong
  number of turns, a GPS track too poor to measure, no steps (a vehicle);
* **flagged** — scorable, but a reviewer must look: the phone claimed a
  different time, or the very same trace was submitted before;
* **verified** — otherwise. As with video tests, only an official's approval
  makes the score official.

A trace is easier to fabricate than a video of a person. These checks catch
replays, edits and impossible physics; they do not prove who carried the
phone, which is why official sessions keep the pre-test identity check and
why a reviewer sees every field result before it becomes official.
"""

from __future__ import annotations

import gzip
import hashlib
import json
from dataclasses import dataclass, field

from .endurance import score_endurance
from .models import FieldResult, FieldTest, Fix, Motion, Outcome
from .shuttle import score_shuttle

PIPELINE_VERSION = "f4all-field/1.0"

# The phone and the server run the same algorithm on the same numbers, so the
# two scores agree to the rounding. A bigger gap means the claim was not what
# the trace says.
TOLERANCE_SECONDS = {FieldTest.SHUTTLE_RUN: 0.05, FieldTest.ENDURANCE_RUN: 0.5}

# Enough of the GPS track to draw for a reviewer, not the whole thing.
TRACK_POINTS = 300
EVENT_LIMIT = 60


@dataclass
class Verdict:
    status: str  # verified | flagged | rejected
    reason: str
    result: FieldResult
    checks: list[dict] = field(default_factory=list)
    # (reason, detail, severity, evidence)
    flags: list[tuple[str, str, str, dict]] = field(default_factory=list)
    difference: float | None = None


def parse_trace(
    test: FieldTest, motion: list[list[float]], fixes: list[list[float]]
) -> tuple[list[Motion], list[Fix]]:
    """Rows as the phone sends them: [t, ax, ay, az(, gx, gy, gz)], [t, lat, lon, acc]."""
    samples = []
    for row in motion:
        if len(row) not in (4, 7):
            raise ValueError("motion rows have 4 or 7 values")
        samples.append(Motion(int(row[0]), *(float(v) for v in row[1:])))
    points = []
    for row in fixes:
        if len(row) != 4:
            raise ValueError("fix rows have 4 values")
        points.append(Fix(int(row[0]), float(row[1]), float(row[2]), float(row[3])))
    return samples, points


def score(test: FieldTest, motion: list[Motion], fixes: list[Fix]) -> FieldResult:
    if test is FieldTest.SHUTTLE_RUN:
        return score_shuttle(motion)
    return score_endurance(fixes, motion)


def decide(
    test: FieldTest,
    result: FieldResult,
    *,
    device_score: float | None,
    duplicate_of: str | None,
) -> Verdict:
    checks = [
        {
            "name": "trace",
            "outcome": "passed" if result.outcome is Outcome.COMPLETE else "failed",
            "code": result.invalid.value if result.invalid else None,
            "detail": _trace_detail(test, result),
        }
    ]
    if result.outcome is not Outcome.COMPLETE:
        return Verdict("rejected", result.invalid.value, result, checks)

    verdict = Verdict("verified", "server_and_device_agree", result, checks)
    tolerance = TOLERANCE_SECONDS[test]
    if device_score is None:
        verdict.flags.append(
            ("device_score_missing", "The phone sent no score of its own", "low", {})
        )
        checks.append(
            {"name": "comparison", "outcome": "flagged", "code": "device_score_missing"}
        )
    else:
        verdict.difference = round(abs(device_score - result.score), 2)
        agreed = verdict.difference <= tolerance
        checks.append(
            {
                "name": "comparison",
                "outcome": "passed" if agreed else "flagged",
                "detail": f"device {device_score:g} s, server {result.score:g} s",
            }
        )
        if not agreed:
            verdict.flags.append(
                (
                    "score_discrepancy",
                    f"The phone claimed {device_score:g} s; its own trace gives "
                    f"{result.score:g} s",
                    "high",
                    {
                        "device": device_score,
                        "server": result.score,
                        "tolerance": tolerance,
                    },
                )
            )

    if duplicate_of is not None:
        verdict.flags.append(
            (
                "duplicate_trace",
                "This exact sensor trace was submitted before",
                "high",
                {"earlier_result_id": duplicate_of},
            )
        )
        checks.append(
            {"name": "duplicate", "outcome": "flagged", "code": "duplicate_trace"}
        )
    else:
        checks.append({"name": "duplicate", "outcome": "passed"})

    if verdict.flags:
        verdict.status = "flagged"
        verdict.reason = verdict.flags[0][0]
    return verdict


def server_snapshot(
    verdict: Verdict, test: FieldTest, fixes: list[Fix], samples: int
) -> dict:
    result = verdict.result
    snapshot = {
        "authoritative": True,
        "field": True,
        "status": result.outcome.value,
        "score": result.score if result.outcome is Outcome.COMPLETE else None,
        "unit": result.unit,
        "invalid_reason": result.invalid.value if result.invalid else None,
        "metrics": result.metrics,
        "events": [list(event) for event in result.events[:EVENT_LIMIT]],
        "samples": {"motion": samples, "fixes": len(fixes)},
        "comparison": {
            "verdict": verdict.status,
            "difference": verdict.difference,
            "tolerance": TOLERANCE_SECONDS[test],
        },
        "pipeline": {"version": PIPELINE_VERSION},
    }
    if test is FieldTest.ENDURANCE_RUN and fixes:
        stride = max(1, len(fixes) // TRACK_POINTS)
        snapshot["track"] = [[f.lat, f.lon] for f in fixes[::stride]]
    return snapshot


def trace_bytes(
    test: FieldTest, motion: list[list[float]], fixes: list[list[float]]
) -> bytes:
    """The trace exactly as received, in one canonical form."""
    return json.dumps(
        {"test": test.value, "motion": motion, "fixes": fixes}, separators=(",", ":")
    ).encode()


def telemetry_key(canonical: bytes) -> str:
    return f"field-traces/{hashlib.sha256(canonical).hexdigest()}.json.gz"


def compress(canonical: bytes) -> bytes:
    # mtime=0: the same trace always compresses to the same bytes.
    return gzip.compress(canonical, mtime=0)


def _trace_detail(test: FieldTest, result: FieldResult) -> str:
    metrics = result.metrics
    if test is FieldTest.SHUTTLE_RUN:
        return f"{metrics.get('turns', 0)} turns"
    distance = metrics.get("distance_m")
    return f"{distance} m by GPS" if distance is not None else "no GPS distance"

"""The same recording submitted more than once.

Two athletes submitting one video, or one athlete re-using an old recording for
a new official attempt, looks perfectly clean from inside a single video. It is
only visible by comparing submissions, so this check is given its candidates by
the worker (the athlete's own official submissions, and every submission in
the same assessment session) and decides nothing about which one is genuine.

What counts as a duplicate:

* **Identical** — the same SHA-256 over the uploaded bytes.
* **Near-identical** — the same footage re-encoded: 16 frames sampled at the
  same fractions of each recording, compared by their 16x16 greyscale
  signatures. Measured: a re-encoded copy differs by a near-uniform 1.9-2.1 at
  every sample (rendered and real footage); a different recording in the same
  room reaches 4.5-6 at its 90th-percentile sample, because the athlete is
  somewhere else at the same moment. A trimmed copy shifts the samples and is
  NOT detected; that is a known gap.

Only official submissions reach the server with a video — practice videos never
leave the phone — so practice can never be caught here. Repeating an official
test in the same session is already refused by the database
(``uq_session_submission``); what this adds is the video itself coming back.

The fingerprint is 16 frames of 16x16 greyscale: far too coarse to show a face
or identify anyone, and derived from data the verification pass already holds.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from .findings import CheatCheck, CheatFinding, CheatReport, Severity
from .frames import signature_difference

FINGERPRINT_VERSION = 1
FINGERPRINT_SAMPLES = 16

# 90th-percentile signature difference at or below which two recordings are
# the same footage. Between the measured 2.1 (copy) and 4.5 (different take).
NEAR_DUPLICATE_MAE = 3.0


@dataclass(frozen=True)
class Candidate:
    """Another submission's video, as the worker found it."""

    result_id: uuid.UUID
    athlete_id: uuid.UUID
    session_id: uuid.UUID | None
    test_code: str
    status: str
    checksum_sha256: str | None
    fingerprint: dict | None


def fingerprint(signatures: list[bytes]) -> dict | None:
    """Evenly spaced frame signatures, hex-encoded for a JSON column."""
    if len(signatures) < FINGERPRINT_SAMPLES:
        return None
    last = len(signatures) - 1
    picks = [
        signatures[round(i * last / (FINGERPRINT_SAMPLES - 1))]
        for i in range(FINGERPRINT_SAMPLES)
    ]
    return {"version": FINGERPRINT_VERSION, "signatures": [p.hex() for p in picks]}


def fingerprint_distance(first: dict | None, second: dict | None) -> float | None:
    """90th-percentile sample difference, or None when not comparable."""
    if not first or not second:
        return None
    if first.get("version") != second.get("version"):
        return None
    a, b = first.get("signatures") or [], second.get("signatures") or []
    if len(a) != len(b) or not a:
        return None
    differences = sorted(
        signature_difference(bytes.fromhex(x), bytes.fromhex(y))
        for x, y in zip(a, b, strict=True)
    )
    return differences[min(len(differences) - 1, int(0.9 * len(differences)))]


def check_duplicates(
    *,
    athlete_id: uuid.UUID,
    session_id: uuid.UUID | None,
    checksum_sha256: str | None,
    video_fingerprint: dict | None,
    candidates: list[Candidate],
    report: CheatReport | None = None,
    near_duplicate_mae: float = NEAR_DUPLICATE_MAE,
) -> CheatReport:
    report = report or CheatReport()
    matches: list[dict] = []

    for candidate in candidates:
        kind = None
        distance = None
        if checksum_sha256 and candidate.checksum_sha256 == checksum_sha256:
            kind = "identical"
        else:
            distance = fingerprint_distance(video_fingerprint, candidate.fingerprint)
            if distance is not None and distance <= near_duplicate_mae:
                kind = "near_identical"
        if kind is None:
            continue

        same_athlete = candidate.athlete_id == athlete_id
        matches.append(
            {
                "result_id": str(candidate.result_id),
                "match": kind,
                "same_athlete": same_athlete,
                "same_session": (
                    session_id is not None and candidate.session_id == session_id
                ),
                "test_type": candidate.test_code,
                "status": candidate.status,
                "distance": round(distance, 3) if distance is not None else None,
            }
        )

    report.summarise(
        "duplicates",
        {
            "compared_with": len(candidates),
            "matches": matches,
            "fingerprint": video_fingerprint is not None,
        },
    )

    for match in matches:
        other_athlete = not match["same_athlete"]
        report.add(
            CheatFinding(
                check=CheatCheck.DUPLICATE_SUBMISSION,
                # Someone else's recording is the stronger signal; the same
                # athlete re-using their own may be a mistaken re-upload.
                severity=Severity.HIGH if other_athlete else Severity.MEDIUM,
                detail=(
                    ("The same" if match["match"] == "identical" else "Nearly the same")
                    + " video was already submitted "
                    + ("by another athlete" if other_athlete else "by this athlete")
                    + f" (result {match['result_id']}). Check which recording, "
                    "if either, is genuine"
                ),
                evidence={
                    "signal": match["match"],
                    "other_result_id": match["result_id"],
                    "other_athlete": other_athlete,
                    "same_session": match["same_session"],
                    "other_test_type": match["test_type"],
                    "other_status": match["status"],
                    "distance": match["distance"],
                    "threshold": near_duplicate_mae,
                },
            )
        )
    return report

"""What a cheat check reports.

Every check produces findings rather than a verdict. Nothing in this package
decides that an athlete cheated — it reports observations, with enough detail
for a human to judge. That separation is deliberate:

* These signals are heuristics with real false-positive rates. A looped-frame
  detector fires on a genuinely static scene; a face embedder fires on a change
  of haircut or lighting.
* The people being judged are often minors, and a wrong accusation costs them a
  place they may have travelled a long way to earn.

So the vocabulary here is "suspicious", never "fraudulent", and the pipeline's
job is to route work to a reviewer, not to punish.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class CheatCheck(str, Enum):
    """Stable identifiers, stored in `flags.reason`."""

    LOOPED_FRAMES = "looped_frames"
    ABRUPT_CUT = "abrupt_cut"
    STATIC_VIDEO = "static_video"
    MULTIPLE_PEOPLE = "multiple_people"
    NO_SUBJECT = "no_subject"
    SUBJECT_SWAPPED = "subject_swapped"
    DURATION_IMPLAUSIBLE = "duration_implausible"
    RESOLUTION_UNEXPECTED = "resolution_unexpected"
    FRAMERATE_IMPLAUSIBLE = "framerate_implausible"
    FACE_MISMATCH = "face_mismatch"
    FACE_NOT_FOUND = "face_not_found"


class Severity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


@dataclass(frozen=True)
class CheatFinding:
    check: CheatCheck
    severity: Severity

    # Written for a reviewer, not a developer. This lands in `flags.detail` and
    # is the only thing telling them what to look for in the video.
    detail: str

    # Where in the recording to look. A reviewer given a 60-second video and
    # "looped frames" has to watch all of it; given a timestamp, they do not.
    at_ms: int | None = None

    # Raw numbers behind the finding, for tuning thresholds against pilot data
    # in Sprint 13-14.
    evidence: dict[str, float] = field(default_factory=dict)


class FaceVerdict(str, Enum):
    """What the face check concluded, in `face_verifications.verification_status`.

    `FAIL` is deliberately never produced by the automatic path. This check is a
    generic image embedder over a face crop, with an unvalidated error rate on
    exactly the population the platform serves (see the module docstring in
    `face.py`). A machine here can say "these two do not obviously match" and
    route the pair to a human; it cannot say "this is not the athlete". The value
    exists because a reviewer, having compared the photographs themselves, can.
    """

    PASS = "pass"
    FAIL = "fail"
    MANUAL_REVIEW = "manual_review"


@dataclass(frozen=True)
class FaceOutcome:
    """The face comparison, kept so it can be persisted.

    Separate from `CheatFinding` because a *passing* comparison is also worth
    recording — `face_verifications` is the evidence that the check ran and what
    it saw, and a row only written on mismatch could not distinguish "compared
    and matched" from "never compared".
    """

    verdict: FaceVerdict
    similarity: float | None
    frames_with_face: int = 0
    frames_sampled: int = 0


@dataclass
class CheatReport:
    findings: list[CheatFinding] = field(default_factory=list)

    # None means the comparison did not happen at all — no registration photo,
    # no models, no face found. Distinct from a recorded `PASS`, and the reason
    # is in `skipped`.
    face: FaceOutcome | None = None

    # Checks that could not run — a missing model, no registration photo on
    # file. Recorded explicitly, because "we did not look" must never be
    # presented to a reviewer as "we looked and found nothing".
    skipped: dict[str, str] = field(default_factory=dict)

    def add(self, finding: CheatFinding) -> None:
        self.findings.append(finding)

    def skip(self, check: CheatCheck, reason: str) -> None:
        self.skipped[check.value] = reason

    def extend(self, other: CheatReport) -> None:
        self.findings.extend(other.findings)
        self.skipped.update(other.skipped)
        if other.face is not None:
            self.face = other.face

    @property
    def is_clean(self) -> bool:
        return not self.findings

    @property
    def highest_severity(self) -> Severity | None:
        if not self.findings:
            return None
        order = {Severity.LOW: 0, Severity.MEDIUM: 1, Severity.HIGH: 2}
        return max((f.severity for f in self.findings), key=lambda s: order[s])

    def summary(self) -> str:
        if self.is_clean:
            return "No integrity concerns detected"
        return "; ".join(
            f"{finding.check.value}: {finding.detail}" for finding in self.findings
        )

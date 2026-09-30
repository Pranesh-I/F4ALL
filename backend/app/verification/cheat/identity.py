"""Turns the pre-test identity check into something a reviewer sees.

The check itself runs when the athlete takes a photo before an official test
(`routers/identity.py`). Whatever it concluded, it never stops a submission:
an athlete who could not get a match after retrying may still record, and the
attempt carries this finding to a human instead. The matcher is a generic
image embedder with an unmeasured error rate on the athletes this platform
serves (see `face.py`), so "the machine was not sure" must cost a reviewer's
attention, never an athlete's place.
"""

from __future__ import annotations

from enum import Enum

from ...models import IdentityCheckOutcome
from .findings import CheatCheck, CheatFinding, FaceOutcome, FaceVerdict, Severity


class IdentityOutcome(str, Enum):
    IDENTITY_MATCH = "IDENTITY_MATCH"
    # "Not obviously the same person — a human must compare", never "not the
    # athlete": neither matcher is validated enough to say that (face.py).
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    IDENTITY_UNCERTAIN = "IDENTITY_UNCERTAIN"


def identity_summary(
    *,
    official: bool,
    pre_test: IdentityCheckOutcome | None,
    face: FaceOutcome | None,
    face_skipped: str | None,
) -> dict:
    """One identity outcome from the two checks the platform already runs.

    * the photo taken before an official test (``routers/identity.py``);
    * the test video's face against the registration photo (``face.py``).

    Any "does not match" from either is a mismatch for a human to compare;
    otherwise any match is a match; with neither (no photo on file, no face
    found, models missing, no pre-test photo) it is uncertain — never a match
    by default. Only outcomes and the similarity already stored in
    ``face_verifications`` are recorded; no image or embedding is kept.
    """
    mismatch = pre_test is IdentityCheckOutcome.no_match or (
        face is not None and face.verdict is not FaceVerdict.PASS
    )
    match = pre_test is IdentityCheckOutcome.match or (
        face is not None and face.verdict is FaceVerdict.PASS
    )
    if mismatch:
        outcome = IdentityOutcome.IDENTITY_MISMATCH
    elif match:
        outcome = IdentityOutcome.IDENTITY_MATCH
    else:
        outcome = IdentityOutcome.IDENTITY_UNCERTAIN

    return {
        "outcome": outcome.value,
        "official": official,
        "pre_test_check": pre_test.value if pre_test is not None else None,
        "video_face_check": face.verdict.value if face is not None else None,
        "video_face_similarity": (
            round(face.similarity, 4)
            if face is not None and face.similarity is not None
            else None
        ),
        "video_face_not_run": face_skipped if face is None else None,
    }

_DETAIL = {
    None: (
        Severity.LOW,
        "No photo check was taken before this official test. "
        "Compare the athlete in the video with the registration photo.",
    ),
    IdentityCheckOutcome.no_match: (
        Severity.MEDIUM,
        "The photo taken before this test did not clearly match the registration "
        "photo, and the athlete continued after retrying. This check is "
        "approximate — please compare the photos yourself.",
    ),
    IdentityCheckOutcome.no_face: (
        Severity.LOW,
        "The photo taken before this test showed no clear face, and the athlete "
        "continued after retrying. Compare the athlete in the video with the "
        "registration photo.",
    ),
    IdentityCheckOutcome.unavailable: (
        Severity.LOW,
        "The photo check before this test could not run. Compare the athlete in "
        "the video with the registration photo.",
    ),
}


def identity_finding(
    *, official: bool, outcome: IdentityCheckOutcome | None
) -> CheatFinding | None:
    """A finding for an official attempt without a confirmed identity, else None.

    Practice and pre-session submissions are not official, and are not asked
    for a photo check.
    """
    if not official or outcome is IdentityCheckOutcome.match:
        return None
    severity, detail = _DETAIL[outcome]
    return CheatFinding(
        check=CheatCheck.IDENTITY_UNCONFIRMED, severity=severity, detail=detail
    )

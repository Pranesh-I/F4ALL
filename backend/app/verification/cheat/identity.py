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

from ...models import IdentityCheckOutcome
from .findings import CheatCheck, CheatFinding, Severity

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

"""What an athlete's official results add up to.

The profile, the history, the result screen and the badges all ask the same
questions of the same rows — which score counts, which one is the best, what
came before — and an athlete who sees two answers stops trusting both. So the
rules live here once.

## Which score counts

Only a score SAI stands behind:

* **approved** — the official's ``final_score``, marked official;
* **verified** — the server's own ``server_score``, not yet official.

Nothing else is an achievement. The phone's provisional number has not been
checked; a flagged result is in doubt until a reviewer decides; a rejected one
was refused; one sent back for resubmission (``pending_sync``) was not
accepted. All of them stay in the history, with their status, and none of them
can be a personal best or be benchmarked for the athlete.

## What a personal best is

The best counting score per test: the highest for a rep count or a jump, the
lowest for a timed test (``Test.higher_is_better``). Equalling a best does not
set a new one, so the earlier result keeps it — except that an approved result
replaces a merely verified one with the same score, because "official" is the
stronger claim and the athlete should see it.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AWAITING_VERIFICATION, Test, TestResult, TestResultStatus
from ..schemas import PersonalBest, ServerForm, ServerFormFault, TestHistoryItem

TRUSTED_STATUSES = frozenset({TestResultStatus.verified, TestResultStatus.approved})


def trusted_score(result: TestResult) -> tuple[float, bool] | None:
    """The score SAI stands behind and whether an official approved it, or None."""
    status = _status(result)
    if status is TestResultStatus.approved and result.final_score is not None:
        return float(result.final_score), True
    if status is TestResultStatus.verified and result.server_score is not None:
        return float(result.server_score), False
    return None


def is_better(candidate: float, current: float, *, higher_is_better: bool) -> bool:
    """Strictly better: equalling a score is not beating it."""
    return candidate > current if higher_is_better else candidate < current


def personal_bests(rows: list[tuple[TestResult, Test]]) -> list[PersonalBest]:
    """Best counting score per test, by test code. See the module docstring."""
    best: dict[str, tuple[PersonalBest, bool]] = {}

    # Oldest first, so on a tie the earlier result keeps the best. Attempt
    # numbers count up per athlete and test, which is all a best compares.
    for result, test in sorted(rows, key=lambda row: row[0].attempt_number):
        trusted = trusted_score(result)
        if trusted is None:
            continue
        score, official = trusted

        current = best.get(test.code)
        if current is not None:
            held, held_official = current
            beaten = is_better(score, held.score, higher_is_better=test.higher_is_better)
            upgraded = score == held.score and official and not held_official
            if not (beaten or upgraded):
                continue

        best[test.code] = (
            PersonalBest(
                test_type=test.code,
                unit=test.unit,
                score=score,
                achieved_at=result.verified_at or result.created_at,
                official=official,
                result_id=result.id,
            ),
            official,
        )

    return sorted((item for item, _ in best.values()), key=lambda item: item.test_type)


def personal_best_for(db: Session, result: TestResult, test: Test) -> PersonalBest | None:
    """The athlete's best at ``test``, which may be ``result`` itself."""
    rows = db.execute(
        select(TestResult, Test)
        .join(Test, Test.id == TestResult.test_id)
        .where(TestResult.athlete_id == result.athlete_id)
        .where(TestResult.test_id == test.id)
    ).all()
    bests = personal_bests(rows)
    return bests[0] if bests else None


def previous_attempt(db: Session, result: TestResult) -> TestResult | None:
    """The athlete's attempt at the same test just before ``result``, whatever its status.

    Attempt numbers count up per athlete and test as submissions arrive, so they
    order attempts exactly, where two timestamps could tie.
    """
    return db.execute(
        select(TestResult)
        .where(TestResult.athlete_id == result.athlete_id)
        .where(TestResult.test_id == result.test_id)
        .where(TestResult.attempt_number < result.attempt_number)
        .order_by(TestResult.attempt_number.desc())
        .limit(1)
    ).scalar_one_or_none()


def history_item(
    result: TestResult, test: Test, session_name: str | None = None
) -> TestHistoryItem:
    """One official result as the athlete's history lists it."""
    return TestHistoryItem(
        result_id=result.id,
        test_type=test.code,
        unit=test.unit,
        status=_status(result).value,
        provisional_score=_as_float(result.provisional_score),
        server_score=_as_float(result.server_score),
        final_score=_as_float(result.final_score),
        created_at=result.created_at,
        session_id=result.session_id,
        session_name=session_name,
    )


def server_form(result: TestResult) -> ServerForm | None:
    """The server's form verdict, once it has measured the video; None for a jump."""
    if _status(result) in AWAITING_VERIFICATION:
        return None
    measured = result.server_result or {}
    score = measured.get("form_score")
    if score is None:
        return None
    faults = measured.get("form_faults")
    return ServerForm(
        score=int(score),
        faults=(
            [ServerFormFault(**fault) for fault in faults] if faults is not None else None
        ),
    )


def server_invalid_reason(result: TestResult) -> str | None:
    """Why the server could not score the video at all, in the analyzer's words."""
    measured = result.server_result or {}
    if measured.get("status") != "INVALID":
        return None
    return measured.get("invalid_reason")


def _as_float(value) -> float | None:
    return float(value) if value is not None else None


def _status(result: TestResult) -> TestResultStatus:
    status = result.status
    return status if isinstance(status, TestResultStatus) else TestResultStatus(status)

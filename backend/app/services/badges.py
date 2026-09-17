"""Progress badges.

## Computed, never stored

Badges are derived from the athlete's results every time they are asked for.
A stored badge would outlive the evidence for it: an official rejects a looped
video a week later, and the athlete still holds "personal best" for a score that
never happened. Computing them means a rejection removes the badge with no extra
code, and there is no table to drift out of step with `test_results`.

## What earns what

Badges reward two different things, and they are counted differently:

* **Effort** — first test, weekly streaks — counts any submission that has not
  been rejected. An athlete who records a test on a Sunday deserves the streak
  whether or not SAI has finished checking it.
* **Achievement** — personal bests, a result SAI has checked, completing the
  battery — counts only scores the server measured or an official approved. The
  phone's provisional number never earns an achievement; that would reward the
  one measurement the system does not trust.

Weeks are Indian Standard Time weeks. A test recorded at 1am on a Monday in
Kerala is a Monday test, not the previous Sunday's in UTC.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta, timezone

# India has no daylight saving, so a fixed offset is exact — and it avoids
# depending on a tz database, which Windows Python does not ship.
IST = timezone(timedelta(hours=5, minutes=30), name="IST")

STREAK_TARGETS = (2, 4, 8)


@dataclass(frozen=True)
class ResultFact:
    """The minimum about a result that badges need."""

    test_code: str
    status: str
    created_at: datetime
    # Server-measured or official score; None when neither exists yet.
    trusted_score: float | None
    trusted_at: datetime | None
    higher_is_better: bool = True


@dataclass(frozen=True)
class Badge:
    code: str
    earned: bool
    earned_at: datetime | None = None
    progress: int = 0
    target: int = 1


@dataclass(frozen=True)
class BadgeSummary:
    badges: list[Badge]
    current_streak_weeks: int
    longest_streak_weeks: int


EFFORT_STATUSES = {"processing", "verified", "flagged", "approved", "pending_sync"}
TRUSTED_STATUSES = {"verified", "approved"}


def compute_badges(
    facts: list[ResultFact],
    *,
    battery: set[str],
    now: datetime | None = None,
) -> BadgeSummary:
    now = now or datetime.now(UTC)
    effort = sorted(
        (fact for fact in facts if fact.status in EFFORT_STATUSES),
        key=lambda fact: fact.created_at,
    )
    trusted = sorted(
        (
            fact
            for fact in facts
            if fact.status in TRUSTED_STATUSES and fact.trusted_score is not None
        ),
        key=lambda fact: fact.trusted_at or fact.created_at,
    )

    badges: list[Badge] = []

    # -- first test ----------------------------------------------------------
    badges.append(
        Badge(
            code="first_test",
            earned=bool(effort),
            earned_at=effort[0].created_at if effort else None,
            progress=min(len(effort), 1),
        )
    )

    # -- first result SAI checked ---------------------------------------------
    first_trusted = trusted[0] if trusted else None
    badges.append(
        Badge(
            code="first_verified",
            earned=bool(trusted),
            earned_at=(
                (first_trusted.trusted_at or first_trusted.created_at)
                if first_trusted
                else None
            ),
            progress=min(len(trusted), 1),
        )
    )

    # -- personal bests --------------------------------------------------------
    improvements = _personal_best_breaks(trusted)
    badges.append(
        Badge(
            code="personal_best",
            earned=bool(improvements),
            earned_at=improvements[0] if improvements else None,
            progress=min(len(improvements), 1),
        )
    )

    # -- whole battery -----------------------------------------------------------
    done = {fact.test_code for fact in trusted if fact.test_code in battery}
    completed_at = None
    if battery and done >= battery:
        seen: set[str] = set()
        for fact in trusted:
            seen.add(fact.test_code)
            if seen >= battery:
                completed_at = fact.trusted_at or fact.created_at
                break
    badges.append(
        Badge(
            code="all_tests",
            earned=completed_at is not None,
            earned_at=completed_at,
            progress=len(done),
            target=max(len(battery), 1),
        )
    )

    # -- streaks -------------------------------------------------------------------
    weeks = sorted({_week_start(fact.created_at) for fact in effort})
    longest, longest_reached = _longest_streak(weeks)
    current = _current_streak(weeks, now)

    for target in STREAK_TARGETS:
        badges.append(
            Badge(
                code=f"streak_{target}_weeks",
                earned=longest >= target,
                earned_at=longest_reached.get(target),
                progress=min(max(current, longest), target),
                target=target,
            )
        )

    return BadgeSummary(
        badges=badges, current_streak_weeks=current, longest_streak_weeks=longest
    )


def _personal_best_breaks(trusted: list[ResultFact]) -> list[datetime]:
    """When each test's best was beaten. The first result sets a baseline only."""
    best: dict[str, float] = {}
    breaks: list[datetime] = []

    for fact in trusted:
        score = fact.trusted_score
        assert score is not None
        previous = best.get(fact.test_code)

        if previous is None:
            best[fact.test_code] = score
            continue

        improved = score > previous if fact.higher_is_better else score < previous
        if improved:
            best[fact.test_code] = score
            breaks.append(fact.trusted_at or fact.created_at)

    return breaks


def _week_start(moment: datetime) -> date:
    aware = moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)
    local = aware.astimezone(IST).date()
    return local - timedelta(days=local.weekday())


def _longest_streak(weeks: list[date]) -> tuple[int, dict[int, datetime]]:
    longest = 0
    run = 0
    previous: date | None = None
    reached: dict[int, datetime] = {}

    for week in weeks:
        consecutive = previous is not None and week - previous == timedelta(days=7)
        run = run + 1 if consecutive else 1
        previous = week
        longest = max(longest, run)
        for target in STREAK_TARGETS:
            if run >= target and target not in reached:
                reached[target] = datetime.combine(week, datetime.min.time(), IST)

    return longest, reached


def _current_streak(weeks: list[date], now: datetime) -> int:
    """Consecutive weeks ending this week — or last week.

    Last week counts so a streak is not shown as broken on Monday morning, before
    the athlete has had any chance to test this week.
    """
    if not weeks:
        return 0

    this_week = _week_start(now)
    latest = weeks[-1]
    if latest not in (this_week, this_week - timedelta(days=7)):
        return 0

    run = 1
    for earlier, later in zip(reversed(weeks[:-1]), reversed(weeks[1:]), strict=True):
        if later - earlier != timedelta(days=7):
            break
        run += 1
    return run

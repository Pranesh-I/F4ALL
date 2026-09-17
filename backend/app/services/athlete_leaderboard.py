"""The leaderboard athletes see.

Different from the officials' leaderboard in three deliberate ways:

1. **Opt-in only.** An athlete appears to other athletes only if they switched
   leaderboard visibility on. Most are minors.
2. **Minimal identity.** First name and last initial, and region. No full name,
   no age, no id — together those identify a child.
3. **You always see yourself.** The caller's own best and rank are returned even
   when they have not opted in, computed among the visible athletes plus
   themselves. Seeing where you stand does not require being seen.

Ranking uses approved results only, best attempt per athlete, compared within
the athlete's own age band and gender, so a 10-year-old is not ranked against
17-year-olds.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Athlete, Gender, Test, TestResult, TestResultStatus
from .benchmarks import age_on

# The same bands the benchmark table uses, extended to the registration ceiling.
AGE_BANDS: tuple[tuple[int, int], ...] = (
    (9, 11),
    (12, 13),
    (14, 15),
    (16, 17),
    (18, 25),
    (26, 40),
)

TOP_N = 20


def age_band(age: int) -> tuple[int, int] | None:
    for low, high in AGE_BANDS:
        if low <= age <= high:
            return low, high
    return None


def display_name(full_name: str) -> str:
    """"Anjali Menon" -> "Anjali M."; a single name is shown as given."""
    parts = full_name.split()
    if not parts:
        return "Athlete"
    if len(parts) == 1:
        return parts[0]
    return f"{parts[0]} {parts[-1][0].upper()}."


@dataclass(frozen=True)
class Ranked:
    athlete_id: uuid.UUID
    name: str
    region: str
    score: float
    opted_in: bool


@dataclass(frozen=True)
class Board:
    cohort: str
    entries: list[Ranked]
    you: tuple[int, Ranked] | None
    total_ranked: int


def build_board(
    db: Session,
    *,
    viewer: Athlete,
    test: Test,
    region: str | None,
    today: date | None = None,
) -> Board:
    today = today or date.today()
    band = age_band(age_on(viewer.dob, today=today))

    query = (
        select(TestResult, Athlete)
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .where(TestResult.test_id == test.id)
        .where(TestResult.status == TestResultStatus.approved)
        .where(TestResult.final_score.is_not(None))
    )

    viewer_gender = _value(viewer.gender)
    # Male and female athletes are ranked within their gender. Which board an
    # athlete registered as "other" belongs on is SAI's policy to set; until it
    # is, they see — and are ranked on — a board across all genders in their
    # age band, rather than being placed on one by default.
    if viewer_gender in (Gender.male.value, Gender.female.value):
        query = query.where(Athlete.gender == viewer_gender)
        gender_label = viewer_gender
    else:
        gender_label = "all"

    if region:
        query = query.where(Athlete.region == region)

    best: dict[uuid.UUID, Ranked] = {}
    for result, athlete in db.execute(query).all():
        if band is not None:
            athlete_band = age_band(age_on(athlete.dob, today=today))
            if athlete_band != band:
                continue

        # Only athletes who opted in — and the viewer themselves — take part.
        if not athlete.leaderboard_opt_in and athlete.id != viewer.id:
            continue

        score = float(result.final_score)
        current = best.get(athlete.id)
        better = current is None or (
            score > current.score if test.higher_is_better else score < current.score
        )
        if better:
            best[athlete.id] = Ranked(
                athlete_id=athlete.id,
                name=athlete.name,
                region=athlete.region,
                score=score,
                opted_in=bool(athlete.leaderboard_opt_in),
            )

    ordered = sorted(
        best.values(),
        key=lambda item: -item.score if test.higher_is_better else item.score,
    )

    you = None
    public: list[Ranked] = []
    for item in ordered:
        if item.athlete_id == viewer.id:
            # Rank among everyone visible plus the viewer.
            you = (len(public) + 1, item)
        if item.opted_in:
            public.append(item)

    cohort_age = f"{band[0]}-{band[1]}" if band else "all ages"
    return Board(
        cohort=f"{gender_label}, {cohort_age}",
        entries=public[:TOP_N],
        you=you,
        total_ranked=len(public),
    )


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)

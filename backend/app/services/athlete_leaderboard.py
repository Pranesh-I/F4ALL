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
one gender and one age band — by default the athlete's own, so a 10-year-old is
not ranked against 17-year-olds. Sprint 16 lets the athlete choose another age
band (or all ages), a state, and an assessment session; the privacy rules above
hold for every combination.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import date

from sqlalchemy import func, or_, select
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
    rank: int = 0


@dataclass(frozen=True)
class Board:
    cohort: str
    entries: list[Ranked]
    you: tuple[int, Ranked] | None
    total_ranked: int


# `band` for the viewer's own age band, as opposed to None for every age.
VIEWER_BAND = "viewer"


def build_board(
    db: Session,
    *,
    viewer: Athlete,
    test: Test,
    region: str | None,
    band: tuple[int, int] | str | None = VIEWER_BAND,
    session_id: uuid.UUID | None = None,
    limit: int = TOP_N,
    offset: int = 0,
    today: date | None = None,
) -> Board:
    """One page of the board for ``test``, filtered, plus the viewer's own standing.

    Filtering and the best score per athlete are done by the database, so a
    national board costs one aggregate query rather than every approved result
    in the country loaded into memory. Athletes on the same score share a rank.
    """
    today = today or date.today()
    if band == VIEWER_BAND:
        band = age_band(age_on(viewer.dob, today=today))

    filters = [
        TestResult.test_id == test.id,
        TestResult.status == TestResultStatus.approved,
        TestResult.final_score.is_not(None),
    ]

    viewer_gender = _value(viewer.gender)
    # Male and female athletes are ranked within their gender. Which board an
    # athlete registered as "other" belongs on is SAI's policy to set; until it
    # is, they see — and are ranked on — a board across all genders in their
    # age band, rather than being placed on one by default.
    if viewer_gender in (Gender.male.value, Gender.female.value):
        filters.append(Athlete.gender == viewer_gender)
        gender_label = viewer_gender
    else:
        gender_label = "all"

    if region:
        filters.append(Athlete.region == region)
    if session_id is not None:
        filters.append(TestResult.session_id == session_id)
    if band is not None:
        low, high = band
        # Aged `low` or more today, and not yet `high + 1`.
        filters.append(Athlete.dob <= _years_before(today, low))
        filters.append(Athlete.dob > _years_before(today, high + 1))

    pick = func.max if test.higher_is_better else func.min
    best = (
        select(
            Athlete.id.label("athlete_id"),
            Athlete.name.label("name"),
            Athlete.region.label("region"),
            Athlete.leaderboard_opt_in.label("opted_in"),
            pick(TestResult.final_score).label("score"),
        )
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .where(*filters)
        # Only athletes who opted in — and the viewer themselves — take part.
        .where(or_(Athlete.leaderboard_opt_in.is_(True), Athlete.id == viewer.id))
        .group_by(Athlete.id, Athlete.name, Athlete.region, Athlete.leaderboard_opt_in)
        .subquery()
    )
    public = best.c.opted_in.is_(True)

    def beats(score):
        return best.c.score > score if test.higher_is_better else best.c.score < score

    def rank_of(score, *, excluding: uuid.UUID | None = None) -> int:
        query = select(func.count()).select_from(best).where(public, beats(score))
        if excluding is not None:
            query = query.where(best.c.athlete_id != excluding)
        return db.execute(query).scalar_one() + 1

    total = db.execute(select(func.count()).select_from(best).where(public)).scalar_one()

    order = best.c.score.desc() if test.higher_is_better else best.c.score.asc()
    rows = db.execute(
        select(best).where(public).order_by(order, best.c.name, best.c.athlete_id)
        .limit(limit).offset(offset)
    ).all()

    entries: list[Ranked] = []
    for row in rows:
        score = float(row.score)
        if entries and entries[-1].score == score:
            rank = entries[-1].rank
        else:
            rank = rank_of(row.score) if not entries else offset + len(entries) + 1
        entries.append(_ranked(row, rank))

    you = None
    mine = db.execute(select(best).where(best.c.athlete_id == viewer.id)).first()
    if mine is not None:
        # Rank among everyone visible plus the viewer.
        you = (rank_of(mine.score, excluding=viewer.id), _ranked(mine, 0))

    cohort_age = f"{band[0]}-{band[1]}" if band else "all ages"
    return Board(
        cohort=f"{gender_label}, {cohort_age}",
        entries=entries,
        you=you,
        total_ranked=total,
    )


def parse_band(value: str) -> tuple[int, int] | None:
    """"12-13" as one of AGE_BANDS; anything else is not a band."""
    for low, high in AGE_BANDS:
        if value == f"{low}-{high}":
            return low, high
    return None


def _ranked(row, rank: int) -> Ranked:
    return Ranked(
        athlete_id=row.athlete_id,
        name=row.name,
        region=row.region,
        score=float(row.score),
        opted_in=bool(row.opted_in),
        rank=rank,
    )


def _years_before(day: date, years: int) -> date:
    try:
        return day.replace(year=day.year - years)
    except ValueError:  # 29 February in a year without one
        return day.replace(year=day.year - years, day=28)


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)

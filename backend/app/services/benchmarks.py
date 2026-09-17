"""Where an athlete's score stands against their age and gender cohort.

## The honesty problem at the centre of this module

The brief says to encode SAI's published benchmarks. Those norms, as published
for Khelo India, cover a **different test battery** from this project's MVP: the
9-18 assessment measures a 600m run, a 50m dash, sit-and-reach, push-ups and
partial curl-ups. It does not include a standing vertical jump, and its
abdominal item is partial curl-ups rather than the classic sit-up this app
scores.

So there is no official SAI norm table for either of the two tests built here.

What that leaves is a choice, and only one defensible option:

1. Invent numbers and label them "SAI benchmarks". An athlete told they are in
   the 40th percentile of Indian teenagers, on the strength of a number someone
   made up, has been misinformed about themselves by a government platform.
2. Build the engine correctly, seed it with clearly-labelled provisional norms,
   and make every row carry its own `source` string so nothing can present a
   placeholder as official.

This module does the second. `BenchmarkComparison.provisional` is true for every
row that is not from an official table, it is exposed through the API, and the
mobile and dashboard surfaces show it. When SAI supplies real norms, they load
through the same path and `provisional` becomes false without a code change.

## Why bands rather than a precise percentile

The table stores three anchors — the 50th, 75th and 90th percentiles. Between
them, linear interpolation gives a percentile that is defensible.

**Below the median it does not**, because nothing in the table describes the
shape of the lower half of the distribution. A score under p50 could be the 49th
percentile or the 5th and these three numbers cannot tell the difference.
Reporting an interpolated number there would be inventing precision.

So `percentile` is None below the median, and `band` — which is always present —
carries the feedback. An athlete gets a true statement either way.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import date
from enum import Enum

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Benchmark, Gender

logger = logging.getLogger(__name__)


class Band(str, Enum):
    """Plain-language standing, always available even when a percentile is not."""

    TOP_10 = "top_10"
    TOP_25 = "top_25"
    ABOVE_AVERAGE = "above_average"
    BELOW_AVERAGE = "below_average"


BAND_LABELS = {
    Band.TOP_10: "Top 10% for your age group",
    Band.TOP_25: "Top 25% for your age group",
    Band.ABOVE_AVERAGE: "Above average for your age group",
    # Deliberately not "bottom" or "poor". This is the first thing many
    # first-time athletes will read about themselves on this platform, and the
    # point of the product is to bring people in, not to rank them out.
    Band.BELOW_AVERAGE: "Keep training — you're building towards the average "
    "for your age group",
}


@dataclass(frozen=True)
class BenchmarkComparison:
    band: Band
    label: str

    # None below the median: the table's three anchors say nothing about the
    # shape of the lower half, and an interpolated number there would be made up.
    percentile: int | None

    percentile_50: float
    percentile_75: float
    percentile_90: float

    age_years: int
    cohort: str
    unit: str

    # Where these numbers came from. Surfaced to the athlete and the reviewer.
    source: str
    provisional: bool

    # The next anchor up, so feedback can be actionable rather than just a
    # ranking: "four more reps reaches the top 25%".
    next_target: float | None = None


def age_on(dob: date, *, today: date | None = None) -> int:
    """Completed years, counting birthdays rather than dividing by 365.25."""
    today = today or date.today()
    had_birthday = (today.month, today.day) >= (dob.month, dob.day)
    return today.year - dob.year - (0 if had_birthday else 1)


def find_benchmark(
    db: Session, *, test_id: uuid.UUID, gender: Gender | str, age_years: int
) -> Benchmark | None:
    """The cohort row for this athlete, or None when none applies.

    Returns None for `other` rather than assigning the athlete to the male or
    female table. Which cohort a non-binary athlete is assessed against is a
    policy question with real consequences for selection, and it belongs to SAI
    — not to a silent default in a lookup function. The caller reports the
    absence honestly instead of guessing.
    """
    value = gender.value if isinstance(gender, Gender) else str(gender)

    if value not in {Gender.male.value, Gender.female.value}:
        logger.info("No benchmark cohort defined for gender '%s'", value)
        return None

    return db.execute(
        select(Benchmark)
        .where(Benchmark.test_id == test_id)
        .where(Benchmark.gender == value)
        .where(Benchmark.age_min <= age_years)
        .where(Benchmark.age_max >= age_years)
    ).scalars().first()


def compare(
    benchmark: Benchmark,
    score: float,
    *,
    age_years: int,
    unit: str,
    higher_is_better: bool = True,
) -> BenchmarkComparison:
    """Place `score` against one cohort row.

    `higher_is_better` is False for timed tests, where a lower number is a
    better performance. Sprint 10's shuttle and endurance runs are the first
    tests where that matters, and getting the comparison backwards there would
    tell the fastest athletes they were the slowest.
    """
    p50 = float(benchmark.percentile_50)
    p75 = float(benchmark.percentile_75)
    p90 = float(benchmark.percentile_90)

    def at_least(threshold: float) -> bool:
        return score <= threshold if not higher_is_better else score >= threshold

    if at_least(p90):
        band, percentile, next_target = Band.TOP_10, 90, None
    elif at_least(p75):
        band = Band.TOP_25
        percentile = _interpolate(score, p75, 75, p90, 90)
        next_target = p90
    elif at_least(p50):
        band = Band.ABOVE_AVERAGE
        percentile = _interpolate(score, p50, 50, p75, 75)
        next_target = p75
    else:
        # No percentile here — see the module docstring.
        band, percentile, next_target = Band.BELOW_AVERAGE, None, p50

    source = getattr(benchmark, "source", "") or "unspecified"

    return BenchmarkComparison(
        band=band,
        label=BAND_LABELS[band],
        percentile=percentile,
        percentile_50=p50,
        percentile_75=p75,
        percentile_90=p90,
        age_years=age_years,
        cohort=f"{benchmark.gender}, {benchmark.age_min}-{benchmark.age_max}",
        unit=unit,
        source=source,
        provisional=is_provisional(source),
        next_target=next_target,
    )


PROVISIONAL_MARKER = "PROVISIONAL"


def is_provisional(source: str) -> bool:
    """True unless the row declares an official source.

    Defaults to provisional, including for an empty string. A row with no
    provenance is not evidence that the numbers are official — it is evidence
    that nobody recorded where they came from, which is the same thing from the
    athlete's point of view.
    """
    if not source.strip() or source == "unspecified":
        return True
    return PROVISIONAL_MARKER in source.upper()


def _interpolate(
    score: float, low: float, low_pct: int, high: float, high_pct: int
) -> int:
    """Linear placement between two known anchors."""
    if high == low:
        return low_pct
    fraction = (score - low) / (high - low)
    fraction = max(0.0, min(1.0, fraction))
    return round(low_pct + fraction * (high_pct - low_pct))

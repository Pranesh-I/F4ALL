"""Load benchmark norms from a CSV.

Kept separate from `cli.py` so the same loader serves the CLI, the test suite
and whatever ships the official tables when SAI provides them. The file format
is deliberately plain CSV rather than a migration full of INSERTs: these numbers
are data that someone at SAI will want to revise without a deployment.
"""

from __future__ import annotations

import csv
import logging
import uuid
from dataclasses import dataclass
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from ..models import Benchmark, Test

logger = logging.getLogger(__name__)

DEFAULT_FILE = (
    Path(__file__).resolve().parent.parent / "data" / "benchmarks_provisional.csv"
)

COMMENT_PREFIX = "!"


class BenchmarkSeedError(RuntimeError):
    pass


@dataclass
class SeedReport:
    inserted: int = 0
    updated: int = 0
    skipped_unknown_test: list[str] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.skipped_unknown_test is None:
            self.skipped_unknown_test = []

    def summary(self) -> str:
        parts = [f"{self.inserted} inserted", f"{self.updated} updated"]
        if self.skipped_unknown_test:
            unknown = ", ".join(sorted(set(self.skipped_unknown_test)))
            parts.append(f"skipped unknown tests: {unknown}")
        return "; ".join(parts)


def _rows(path: Path):
    with path.open(newline="", encoding="utf-8") as handle:
        lines = [
            line for line in handle if not line.lstrip().startswith(COMMENT_PREFIX)
        ]
    yield from csv.DictReader(lines)


REQUIRED_COLUMNS = {
    "test_code",
    "gender",
    "age_min",
    "age_max",
    "p50",
    "p75",
    "p90",
    "source",
}


def seed_benchmarks(
    db: Session, *, path: Path | None = None, replace: bool = False
) -> SeedReport:
    """Load `path` into the benchmarks table.

    Idempotent: a cohort that already exists is updated in place rather than
    duplicated, so re-running never changes which row an athlete is measured
    against. `replace=True` clears the table first, for swapping a provisional
    set out for an official one.
    """
    path = path or DEFAULT_FILE
    if not path.exists():
        raise BenchmarkSeedError(f"Benchmark file not found: {path}")

    tests = {
        test.code: (test.id, test.higher_is_better)
        for test in db.execute(select(Test)).scalars()
    }
    if not tests:
        raise BenchmarkSeedError(
            "No tests in the database. Run `python -m app.cli seed` first."
        )

    if replace:
        db.execute(delete(Benchmark))

    report = SeedReport()

    for index, row in enumerate(_rows(path), start=2):
        missing = REQUIRED_COLUMNS - set(row)
        if missing:
            raise BenchmarkSeedError(
                f"{path}:{index} is missing columns: {sorted(missing)}"
            )

        code = row["test_code"].strip()
        entry = tests.get(code)
        if entry is None:
            # Not fatal: an official file may well carry norms for tests this
            # deployment has not enabled yet. Recorded rather than silent.
            report.skipped_unknown_test.append(code)
            continue

        test_id, higher_is_better = entry

        gender = row["gender"].strip()
        age_min = int(row["age_min"])
        age_max = int(row["age_max"])

        if age_min > age_max:
            raise BenchmarkSeedError(
                f"{path}:{index} has age_min {age_min} above age_max {age_max}"
            )

        values = _percentiles(row, path, index, higher_is_better)

        existing = db.execute(
            select(Benchmark)
            .where(Benchmark.test_id == test_id)
            .where(Benchmark.gender == gender)
            .where(Benchmark.age_min == age_min)
            .where(Benchmark.age_max == age_max)
        ).scalar_one_or_none()

        if existing is None:
            db.add(
                Benchmark(
                    id=uuid.uuid4(),
                    test_id=test_id,
                    gender=gender,
                    age_min=age_min,
                    age_max=age_max,
                    percentile_50=values[0],
                    percentile_75=values[1],
                    percentile_90=values[2],
                    source=row["source"].strip(),
                )
            )
            report.inserted += 1
        else:
            existing.percentile_50 = values[0]
            existing.percentile_75 = values[1]
            existing.percentile_90 = values[2]
            existing.source = row["source"].strip()
            db.add(existing)
            report.updated += 1

    db.commit()
    logger.info("Seeded benchmarks from %s: %s", path, report.summary())
    return report


def _percentiles(
    row: dict, path: Path, line: int, higher_is_better: bool
) -> tuple[float, float, float]:
    p50, p75, p90 = float(row["p50"]), float(row["p75"]), float(row["p90"])

    # A non-monotonic row is not a rounding quirk, it is a transcription error,
    # and it would silently produce nonsense standings for a whole cohort.
    #
    # "Monotonic" points the other way for a timed test: a 75th-percentile
    # 600m time is FASTER, so a smaller number. Validating everything as
    # ascending would reject every correct row for those tests.
    ordered = p50 <= p75 <= p90 if higher_is_better else p50 >= p75 >= p90
    if not ordered:
        direction = "ascending" if higher_is_better else "descending"
        raise BenchmarkSeedError(
            f"{path}:{line} percentiles are not {direction}: {p50}, {p75}, {p90}"
        )

    return p50, p75, p90

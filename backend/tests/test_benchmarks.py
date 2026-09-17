"""Benchmark comparison and seeding.

The load-bearing property in this file is not that percentiles are computed
correctly — it is that the system never tells an athlete something it does not
know. Two ways that could go wrong, both tested:

* presenting placeholder numbers as though they were SAI's published norms,
* inventing a percentile below the median, where the stored anchors describe
  nothing at all.
"""

from __future__ import annotations

from datetime import date

import pytest
from sqlalchemy import select

from app.models import Benchmark, Test
from app.services.benchmark_seed import (
    DEFAULT_FILE,
    BenchmarkSeedError,
    seed_benchmarks,
)
from app.services.benchmarks import (
    Band,
    age_on,
    compare,
    find_benchmark,
    is_provisional,
)


@pytest.fixture
def seeded_benchmarks(db, seeded_tests):
    seed_benchmarks(db)
    return seeded_tests


# ---------------------------------------------------------------------------
# Age
# ---------------------------------------------------------------------------


def test_age_counts_birthdays_not_days():
    """365.25 arithmetic puts athletes in the wrong cohort near their birthday."""
    assert age_on(date(2010, 6, 15), today=date(2026, 6, 14)) == 15
    assert age_on(date(2010, 6, 15), today=date(2026, 6, 15)) == 16
    assert age_on(date(2010, 6, 15), today=date(2026, 6, 16)) == 16


def test_leap_day_birthdays_do_not_break():
    assert age_on(date(2008, 2, 29), today=date(2026, 3, 1)) == 18


# ---------------------------------------------------------------------------
# Seeding
# ---------------------------------------------------------------------------


def test_the_bundled_file_loads(db, seeded_tests):
    report = seed_benchmarks(db)

    assert report.inserted == 20
    assert report.updated == 0
    assert not report.skipped_unknown_test


def test_seeding_is_idempotent(db, seeded_tests):
    """Re-running must never change which row an athlete is measured against."""
    seed_benchmarks(db)
    second = seed_benchmarks(db)

    assert second.inserted == 0
    assert second.updated == 20
    assert len(list(db.execute(select(Benchmark)).scalars())) == 20


def test_every_bundled_row_is_marked_provisional(db, seeded_tests):
    """The whole point of the `source` column.

    These numbers are placeholders. If any row could load without saying so,
    an athlete could be shown a percentile against invented norms with no
    caveat attached.
    """
    seed_benchmarks(db)

    for row in db.execute(select(Benchmark)).scalars():
        assert row.source
        assert is_provisional(row.source), row.source


def test_a_row_with_no_source_is_treated_as_provisional():
    """Absent provenance is not evidence the numbers are official."""
    assert is_provisional("") is True
    assert is_provisional("unspecified") is True


def test_an_official_source_is_not_provisional():
    assert is_provisional("SAI Annexure A, 2026 revision") is False


def test_non_ascending_percentiles_are_refused(db, seeded_tests, tmp_path):
    """A transcription error here produces nonsense standings for a whole cohort."""
    bad = tmp_path / "bad.csv"
    bad.write_text(
        "test_code,gender,age_min,age_max,p50,p75,p90,source\n"
        "SIT_UPS,male,14,15,40,30,20,SAI official\n",
        encoding="utf-8",
    )

    with pytest.raises(BenchmarkSeedError, match="not ascending"):
        seed_benchmarks(db, path=bad)


def test_an_inverted_age_range_is_refused(db, seeded_tests, tmp_path):
    bad = tmp_path / "bad.csv"
    bad.write_text(
        "test_code,gender,age_min,age_max,p50,p75,p90,source\n"
        "SIT_UPS,male,18,14,20,30,40,SAI official\n",
        encoding="utf-8",
    )

    with pytest.raises(BenchmarkSeedError, match="age_min"):
        seed_benchmarks(db, path=bad)


def test_an_unknown_test_is_recorded_not_fatal(db, seeded_tests, tmp_path):
    """An official file may carry norms for tests this deployment has not enabled."""
    partial = tmp_path / "partial.csv"
    partial.write_text(
        "test_code,gender,age_min,age_max,p50,p75,p90,source\n"
        "SHUTTLE_RUN,male,14,15,12,11,10,SAI official\n"
        "SIT_UPS,male,14,15,30,36,42,SAI official\n",
        encoding="utf-8",
    )

    report = seed_benchmarks(db, path=partial)

    assert report.inserted == 1
    assert "SHUTTLE_RUN" in report.skipped_unknown_test


def test_replace_swaps_a_provisional_table_for_an_official_one(
    db, seeded_tests, tmp_path
):
    seed_benchmarks(db)

    official = tmp_path / "official.csv"
    official.write_text(
        "test_code,gender,age_min,age_max,p50,p75,p90,source\n"
        "SIT_UPS,male,14,15,33,39,45,SAI Annexure A 2026\n",
        encoding="utf-8",
    )
    seed_benchmarks(db, path=official, replace=True)

    rows = list(db.execute(select(Benchmark)).scalars())
    assert len(rows) == 1
    assert is_provisional(rows[0].source) is False


def test_comment_lines_in_the_bundled_file_are_ignored():
    """The header carries the explanation of why these numbers are placeholders."""
    text = DEFAULT_FILE.read_text(encoding="utf-8")
    assert text.lstrip().startswith("!")
    assert "PROVISIONAL" in text


# ---------------------------------------------------------------------------
# Cohort lookup
# ---------------------------------------------------------------------------


def test_the_right_cohort_is_found(db, seeded_benchmarks):
    situps = seeded_benchmarks["SIT_UPS"]

    row = find_benchmark(db, test_id=situps.id, gender="male", age_years=14)

    assert row is not None
    assert row.age_min <= 14 <= row.age_max
    assert row.gender == "male"


def test_an_age_outside_every_band_finds_nothing(db, seeded_benchmarks):
    situps = seeded_benchmarks["SIT_UPS"]
    assert find_benchmark(db, test_id=situps.id, gender="male", age_years=60) is None


def test_gender_other_gets_no_cohort_rather_than_a_guessed_one(db, seeded_benchmarks):
    """Silently assigning a non-binary athlete to the male or female table would
    be a selection decision made by a lookup function.

    Which cohort applies is a policy question with real consequences, and it
    belongs to SAI. The caller reports the absence honestly.
    """
    situps = seeded_benchmarks["SIT_UPS"]
    assert find_benchmark(db, test_id=situps.id, gender="other", age_years=14) is None


# ---------------------------------------------------------------------------
# Comparison
# ---------------------------------------------------------------------------


def comparison_for(db, tests, code, score, gender="male", age=14, higher=True):
    test: Test = tests[code]
    benchmark = find_benchmark(db, test_id=test.id, gender=gender, age_years=age)
    assert benchmark is not None
    return compare(
        benchmark, score, age_years=age, unit=test.unit, higher_is_better=higher
    )


def test_a_score_at_the_top_anchor_is_top_ten_percent(db, seeded_benchmarks):
    result = comparison_for(db, seeded_benchmarks, "SIT_UPS", 46)

    assert result.band is Band.TOP_10
    assert result.percentile == 90
    # Nothing above the top anchor to aim at.
    assert result.next_target is None


def test_a_score_between_anchors_interpolates(db, seeded_benchmarks):
    # p75 = 40, p90 = 46 for males 14-15. Halfway is 43 -> ~82nd.
    result = comparison_for(db, seeded_benchmarks, "SIT_UPS", 43)

    assert result.band is Band.TOP_25
    assert result.percentile == pytest.approx(82, abs=1)
    assert result.next_target == 46


def test_a_score_just_above_the_median_is_above_average(db, seeded_benchmarks):
    result = comparison_for(db, seeded_benchmarks, "SIT_UPS", 35)

    assert result.band is Band.ABOVE_AVERAGE
    assert 50 <= result.percentile < 75


def test_below_the_median_no_percentile_is_invented(db, seeded_benchmarks):
    """The single most important assertion in this file.

    Nothing in a p50/p75/p90 table describes the shape of the lower half. A
    score below the median could be the 49th percentile or the 5th, and
    reporting a number there would be manufacturing precision the data does
    not contain.
    """
    result = comparison_for(db, seeded_benchmarks, "SIT_UPS", 10)

    assert result.band is Band.BELOW_AVERAGE
    assert result.percentile is None
    # A target to aim at is still useful, and is a real number from the table.
    assert result.next_target == 34


def test_the_below_average_message_is_not_discouraging(db, seeded_benchmarks):
    """This is the first thing many first-time athletes read about themselves."""
    result = comparison_for(db, seeded_benchmarks, "SIT_UPS", 5)

    lowered = result.label.lower()
    assert "bottom" not in lowered
    assert "poor" not in lowered
    assert "fail" not in lowered


def test_a_provisional_comparison_says_so(db, seeded_benchmarks):
    result = comparison_for(db, seeded_benchmarks, "SIT_UPS", 43)

    assert result.provisional is True
    assert "PROVISIONAL" in result.source


def _timed_test(db, code: str) -> Test:
    import uuid

    test = Test(
        id=uuid.uuid4(),
        name=code.title(),
        code=code,
        unit="seconds",
        description="timed fixture",
        higher_is_better=False,
    )
    db.add(test)
    db.commit()
    return test


def test_lower_is_better_inverts_the_comparison(db, seeded_tests, tmp_path):
    """Getting this backwards would tell the fastest athletes they were slowest."""
    timed_test = _timed_test(db, "SHUTTLE_RUN")
    timed = tmp_path / "timed.csv"
    timed.write_text(
        "test_code,gender,age_min,age_max,p50,p75,p90,source\n"
        "SHUTTLE_RUN,male,14,15,14.0,13.0,12.0,test fixture\n",
        encoding="utf-8",
    )
    seed_benchmarks(db, path=timed, replace=True)

    tests = {"SHUTTLE_RUN": timed_test}
    fast = comparison_for(db, tests, "SHUTTLE_RUN", 11.5, higher=False)
    slow = comparison_for(db, tests, "SHUTTLE_RUN", 20.0, higher=False)

    assert fast.band is Band.TOP_10
    assert slow.band is Band.BELOW_AVERAGE


def test_a_timed_test_rejects_ascending_percentiles(db, seeded_tests, tmp_path):
    _timed_test(db, "ENDURANCE_RUN")
    wrong = tmp_path / "wrong.csv"
    wrong.write_text(
        "test_code,gender,age_min,age_max,p50,p75,p90,source\n"
        "ENDURANCE_RUN,male,14,15,120,140,160,test fixture\n",
        encoding="utf-8",
    )

    with pytest.raises(BenchmarkSeedError, match="not descending"):
        seed_benchmarks(db, path=wrong)


def test_the_cohort_is_named_in_the_comparison(db, seeded_benchmarks):
    """A reviewer or athlete must be able to see what they were compared to."""
    result = comparison_for(db, seeded_benchmarks, "SIT_UPS", 43)

    assert "male" in result.cohort
    assert "14-15" in result.cohort
    assert result.unit == "reps"

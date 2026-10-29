"""Analytics for officials (Sprint 16).

One endpoint, ``GET /api/dashboard/analytics``, returns every metric and chart
series the dashboard's analytics page shows, for one set of filters. Everything
is counted by the database or, where the two dialects differ (percentiles,
India-time day buckets), over a single narrow column in Python — never by
shipping rows to the browser.

Scoped like every dashboard endpoint: a regional reviewer sees only their own
region and is refused another one outright, rather than being shown a page of
zeros that looks like a real answer.

Which results "count" follows ``services/athlete_results.py``: verified or
approved, with the official score once approved and the server's measurement
while verified. Averages and the score distribution use only those.

Not cached. Each request is a handful of grouped queries over indexed columns;
whether that needs Redis in front of it is a question for the Sprint 23 load
test, answered with numbers rather than in advance.
"""

from __future__ import annotations

import math
import uuid
from datetime import UTC, date, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..database import get_db
from ..models import (
    AWAITING_VERIFICATION,
    AssessmentSession,
    Athlete,
    Official,
    Test,
    TestResult,
    TestResultStatus,
    UploadSession,
)
from ..regions import canonical_region
from ..schemas import (
    AnalyticsBucket,
    AnalyticsFilters,
    AnalyticsRegion,
    AnalyticsResponse,
    AnalyticsScoreBin,
    AnalyticsSession,
    AnalyticsTest,
    AnalyticsTotals,
    AnalyticsUploads,
    AnalyticsVerificationTime,
)
from ..security import current_official
from ..services.badges import IST
from .dashboard import _is_admin, _scope
from .health import _percentile

router = APIRouter(prefix="/api/dashboard", tags=["Dashboard"])

COUNTED = (TestResultStatus.verified, TestResultStatus.approved)
SCORE_BINS = 10
# Buckets by span: a day per point up to two months, then weeks, then months.
DAILY_UP_TO = timedelta(days=62)
# Nothing was submitted before the platform existed; an earlier start would
# only produce years of empty buckets.
EARLIEST = datetime(2024, 1, 1, tzinfo=UTC)
WEEKLY_UP_TO = timedelta(days=731)


@router.get("/analytics", response_model=AnalyticsResponse)
def analytics(
    submitted_from: datetime | None = Query(default=None),
    submitted_to: datetime | None = Query(default=None),
    region: str | None = Query(default=None, max_length=100),
    test_type: str | None = Query(default=None, max_length=40),
    session_id: uuid.UUID | None = Query(default=None),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    official: Official = Depends(current_official),
):
    submitted_from = max(_utc(submitted_from), EARLIEST) if submitted_from else None
    submitted_to = _utc(submitted_to) if submitted_to else None
    if submitted_from and submitted_to and submitted_from >= submitted_to:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "submitted_from must be before submitted_to",
        )

    if region is not None:
        region = canonical_region(region)
        if region is None:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "region must be an Indian state or union territory",
            )
        if not _is_admin(official) and region != official.region:
            raise HTTPException(
                status.HTTP_403_FORBIDDEN, "That region is outside your scope"
            )

    test = None
    if test_type:
        test = db.execute(
            select(Test).where(Test.code == test_type.strip().upper())
        ).scalar_one_or_none()
        if test is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, f"Unknown test '{test_type}'")

    if session_id is not None and db.get(AssessmentSession, session_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session")

    conditions = []
    if submitted_from:
        conditions.append(TestResult.created_at >= submitted_from)
    if submitted_to:
        conditions.append(TestResult.created_at < submitted_to)
    if region:
        conditions.append(Athlete.region == region)
    if test is not None:
        conditions.append(TestResult.test_id == test.id)
    if session_id is not None:
        conditions.append(TestResult.session_id == session_id)

    def results(*columns):
        """Results in scope and matching the filters, with athlete and test joined."""
        query = (
            select(*columns)
            .select_from(TestResult)
            .join(Athlete, Athlete.id == TestResult.athlete_id)
            .join(Test, Test.id == TestResult.test_id)
            .where(*conditions)
        )
        return _scope(query, official)

    trusted = case(
        (TestResult.status == TestResultStatus.approved, TestResult.final_score),
        (TestResult.status == TestResultStatus.verified, TestResult.server_score),
        else_=None,
    )
    counted = func.count(case((TestResult.status.in_(COUNTED), 1)))
    athletes_in = func.count(func.distinct(TestResult.athlete_id))

    by_status = {member.value: 0 for member in TestResultStatus}
    for value, count in db.execute(
        results(TestResult.status, func.count()).group_by(TestResult.status)
    ).all():
        by_status[_value(value)] = count

    registered = select(func.count()).select_from(Athlete)
    if region:
        registered = registered.where(Athlete.region == region)
    athletes_total = db.execute(_scope(registered, official)).scalar_one()
    active = db.execute(results(athletes_in)).scalar_one()

    totals = AnalyticsTotals(
        athletes=athletes_total,
        active_athletes=active,
        submissions=sum(by_status.values()),
        verified=by_status["verified"],
        approved=by_status["approved"],
        flagged=by_status["flagged"],
        rejected=by_status["rejected"],
        awaiting_verification=sum(by_status[s.value] for s in AWAITING_VERIFICATION),
        resubmission_requested=by_status["pending_sync"],
    )

    per_test = db.execute(
        results(
            Test.code,
            Test.name,
            Test.unit,
            Test.higher_is_better,
            func.count(),
            athletes_in,
            counted,
            func.avg(trusted),
        )
        .group_by(Test.code, Test.name, Test.unit, Test.higher_is_better)
        .order_by(Test.code)
    ).all()
    tests = [
        AnalyticsTest(
            test_type=row[0],
            name=row[1],
            unit=row[2],
            higher_is_better=bool(row[3]),
            submissions=row[4],
            athletes=row[5],
            counted=row[6],
            average_score=round(float(row[7]), 2) if row[7] is not None else None,
        )
        for row in per_test
    ]

    regions = [
        AnalyticsRegion(
            region=name,
            athletes=athletes,
            submissions=submissions,
            counted=counted_count,
            flagged=flagged,
            rejected=rejected,
        )
        for name, athletes, submissions, counted_count, flagged, rejected in db.execute(
            results(
                Athlete.region,
                athletes_in,
                func.count(),
                counted,
                func.count(case((TestResult.status == TestResultStatus.flagged, 1))),
                func.count(case((TestResult.status == TestResultStatus.rejected, 1))),
            )
            .group_by(Athlete.region)
            .order_by(func.count().desc(), Athlete.region)
        ).all()
    ]

    sessions = [
        AnalyticsSession(
            session_id=sid,
            name=name,
            submissions=submissions,
            athletes=athletes,
            counted=counted_count,
        )
        for sid, name, submissions, athletes, counted_count in db.execute(
            results(
                AssessmentSession.id,
                AssessmentSession.name,
                func.count(),
                athletes_in,
                counted,
            )
            .join(AssessmentSession, AssessmentSession.id == TestResult.session_id)
            .group_by(AssessmentSession.id, AssessmentSession.name)
            .order_by(func.count().desc(), AssessmentSession.name)
        ).all()
    ]
    outside_sessions = db.execute(
        results(func.count()).where(TestResult.session_id.is_(None))
    ).scalar_one()

    timings = db.execute(
        results(
            TestResult.created_at,
            TestResult.verified_at,
            TestResult.processing_duration_ms,
        )
        .where(TestResult.verified_at.is_not(None))
        .where(TestResult.processing_started_at.is_not(None))
    ).all()
    turnaround = sorted(
        (_utc(done) - _utc(created)).total_seconds() for created, done, _ in timings
    )
    processing = sorted(ms / 1000 for _, _, ms in timings if ms is not None)
    verification_time = AnalyticsVerificationTime(
        measured=len(timings),
        turnaround_p50_seconds=_percentile(turnaround, 0.5),
        turnaround_p95_seconds=_percentile(turnaround, 0.95),
        processing_p50_seconds=_percentile(processing, 0.5),
        processing_p95_seconds=_percentile(processing, 0.95),
        sla_seconds=settings.verification_sla_seconds,
        within_sla=sum(
            1 for seconds in turnaround if seconds <= settings.verification_sla_seconds
        ),
    )

    submitted = db.execute(results(TestResult.created_at, TestResult.status)).all()
    bucket, participation = _participation(
        [
            (_utc(created), _value(state) in _COUNTED_VALUES)
            for created, state in submitted
        ],
        submitted_from,
        submitted_to,
    )

    score_distribution = None
    if test is not None:
        scores = [
            float(score)
            for (score,) in db.execute(
                results(trusted).where(TestResult.status.in_(COUNTED))
            ).all()
            if score is not None
        ]
        score_distribution = _histogram(scores, integral=test.unit == "reps")

    return AnalyticsResponse(
        filters=AnalyticsFilters(
            submitted_from=submitted_from,
            submitted_to=submitted_to,
            # A reviewer's view is their region whether or not they asked.
            region=region or (None if _is_admin(official) else official.region),
            test_type=test.code if test else None,
            session_id=session_id,
        ),
        generated_at=datetime.now(UTC),
        totals=totals,
        by_status=by_status,
        tests=tests,
        regions=regions,
        sessions=sessions,
        outside_sessions=outside_sessions,
        uploads=_uploads(db, official, region, test, submitted_from, submitted_to),
        verification_time=verification_time,
        bucket=bucket,
        participation=participation,
        score_distribution=score_distribution,
    )


_COUNTED_VALUES = {member.value for member in COUNTED}


def _uploads(
    db, official, region, test, submitted_from, submitted_to
) -> AnalyticsUploads:
    """Upload sessions in the window, and uploads the automatic check refused.

    The session filter does not apply here: an upload belongs to a session only
    once it has been submitted, and an abandoned one never is.
    """
    query = (
        select(
            func.count(),
            func.count(case((UploadSession.completed.is_(True), 1))),
            func.count(
                case(
                    (
                        UploadSession.completed.is_(False)
                        & (UploadSession.expires_at < datetime.now(UTC)),
                        1,
                    )
                )
            ),
        )
        .select_from(UploadSession)
        .outerjoin(Athlete, Athlete.id == UploadSession.athlete_id)
    )
    if submitted_from:
        query = query.where(UploadSession.created_at >= submitted_from)
    if submitted_to:
        query = query.where(UploadSession.created_at < submitted_to)
    if region:
        query = query.where(Athlete.region == region)
    if test is not None:
        query = query.where(UploadSession.test_code == test.code)
    started, completed, abandoned = db.execute(_scope(query, official)).one()

    refused = (
        select(func.count())
        .select_from(TestResult)
        .join(Athlete, Athlete.id == TestResult.athlete_id)
        .where(TestResult.verification_verdict == TestResultStatus.rejected.value)
    )
    if submitted_from:
        refused = refused.where(TestResult.created_at >= submitted_from)
    if submitted_to:
        refused = refused.where(TestResult.created_at < submitted_to)
    if region:
        refused = refused.where(Athlete.region == region)
    if test is not None:
        refused = refused.where(TestResult.test_id == test.id)

    return AnalyticsUploads(
        started=started,
        completed=completed,
        in_progress=started - completed - abandoned,
        abandoned=abandoned,
        rejected_by_verification=db.execute(_scope(refused, official)).scalar_one(),
    )


def _participation(
    submissions: list[tuple[datetime, bool]],
    submitted_from: datetime | None,
    submitted_to: datetime | None,
) -> tuple[str, list[AnalyticsBucket]]:
    """Submissions per India-time day, week or month, with empty buckets filled.

    A gap in the line has to mean nobody submitted, not that the bucket was
    left out.
    """
    first = submitted_from or (min(at for at, _ in submissions) if submissions else None)
    last = submitted_to or datetime.now(UTC)
    if first is None:
        return "day", []

    span = last - first
    bucket = "day" if span <= DAILY_UP_TO else "week" if span <= WEEKLY_UP_TO else "month"

    def start_of(moment: datetime) -> date:
        day = moment.astimezone(IST).date()
        if bucket == "week":
            return day - timedelta(days=day.weekday())
        if bucket == "month":
            return day.replace(day=1)
        return day

    counts: dict[date, list[int]] = {}
    cursor = start_of(first)
    end = start_of(last - timedelta(microseconds=1)) if submitted_to else start_of(last)
    while cursor <= end:
        counts[cursor] = [0, 0]
        cursor = _next(cursor, bucket)

    for at, is_counted in submissions:
        entry = counts.setdefault(start_of(at), [0, 0])
        entry[0] += 1
        entry[1] += int(is_counted)

    return bucket, [
        AnalyticsBucket(start=day, submissions=values[0], counted=values[1])
        for day, values in sorted(counts.items())
    ]


def _next(day: date, bucket: str) -> date:
    if bucket == "day":
        return day + timedelta(days=1)
    if bucket == "week":
        return day + timedelta(days=7)
    return (day.replace(day=28) + timedelta(days=4)).replace(day=1)


def _histogram(values: list[float], *, integral: bool) -> list[AnalyticsScoreBin]:
    """At most SCORE_BINS equal-width bins from the lowest score to the highest.

    For counts (reps) each bin is a whole range of counts, both ends included,
    so "10-14" means what it says. For measurements the upper bound is
    exclusive, except in the last bin.
    """
    if not values:
        return []
    low, high = min(values), max(values)

    if integral:
        width = max(1, math.ceil((high - low + 1) / SCORE_BINS))
        edges = [
            low + index * width for index in range(math.ceil((high - low + 1) / width))
        ]
        bins = [[edge, edge + width - 1, 0] for edge in edges]
        for value in values:
            bins[int((value - low) // width)][2] += 1
    elif high == low:
        bins = [[low, high, len(values)]]
    else:
        width = (high - low) / SCORE_BINS
        bins = [[low + i * width, low + (i + 1) * width, 0] for i in range(SCORE_BINS)]
        for value in values:
            bins[min(SCORE_BINS - 1, int((value - low) / width))][2] += 1

    return [
        AnalyticsScoreBin(low=round(lo, 2), high=round(hi, 2), count=count)
        for lo, hi, count in bins
    ]


def _utc(value: datetime) -> datetime:
    """Stored times are UTC (SQLite returns them naive); filters may carry any offset."""
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def _value(value) -> str:
    return value.value if hasattr(value, "value") else str(value)

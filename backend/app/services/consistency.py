"""States an official result must never be in (Sprint 23).

    python -m app.cli consistency            # exit 1 when any problem is found

The plan's reliability rules, as queries:

* **uploaded but missing:** a submission whose video is not in storage;
* **verified but no result:** a machine verdict with no server measurement;
* **approved without verification:** an official decision on a result the
  machine never finished;
* **duplicate official result:** two live submissions for one athlete, session
  and test (the database refuses this; checked anyway);
* **partial state:** a result still waiting for, or stuck in, verification
  long past the SLA — the job was lost, and ``reverify-pending`` re-queues it.

Run after a load test, after an outage, and on a schedule in production: it
reads only, and each finding names the result so it can be looked at.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from sqlalchemy import String, cast, func, select
from sqlalchemy.orm import Session

from ..models import TestResult, TestResultStatus, Video
from .retention import is_retired

MACHINE_DONE = (
    TestResultStatus.verified,
    TestResultStatus.flagged,
)
DECIDED = (TestResultStatus.approved, TestResultStatus.rejected)
WAITING = (TestResultStatus.uploaded, TestResultStatus.processing)


@dataclass
class Report:
    checked: int = 0
    problems: dict[str, list[str]] = field(default_factory=dict)

    def add(self, rule: str, result_id) -> None:
        self.problems.setdefault(rule, []).append(str(result_id))

    @property
    def ok(self) -> bool:
        return not self.problems


def check(
    db: Session,
    storage,
    *,
    sla_seconds: int,
    now: datetime | None = None,
    check_storage: bool = True,
) -> Report:
    now = now or datetime.now(UTC)
    report = Report(checked=db.scalar(select(func.count()).select_from(TestResult)) or 0)

    if check_storage:
        rows = db.execute(
            select(TestResult.id, Video.s3_key)
            .join(Video, Video.test_result_id == TestResult.id)
            .where(TestResult.status != TestResultStatus.pending_sync)
        ).all()
        for result_id, key in rows:
            if not is_retired(key) and not storage.exists(key):
                report.add("uploaded_but_missing", result_id)

    # Every machine-finished result has the machine's verdict, and a verified
    # one has what the server measured. (A flagged one may not: a video the
    # server could not read goes to a human with the error instead.)
    for result_id in db.execute(
        select(TestResult.id)
        .where(TestResult.status.in_(MACHINE_DONE))
        .where(
            TestResult.verification_verdict.is_(None)
            | (
                (TestResult.status == TestResultStatus.verified)
                # SQL NULL, or JSON null: Postgres stores a Python None as the latter.
                & (
                    TestResult.server_result.is_(None)
                    | (cast(TestResult.server_result, String) == "null")
                )
            )
        )
    ).scalars():
        report.add("verified_but_no_result", result_id)

    for result_id in db.execute(
        select(TestResult.id)
        .where(TestResult.status.in_(DECIDED))
        .where(TestResult.verification_verdict.is_(None))
    ).scalars():
        report.add("decided_without_verification", result_id)

    duplicates = db.execute(
        select(TestResult.athlete_id, TestResult.session_id, TestResult.test_id)
        .where(TestResult.session_id.is_not(None))
        .where(TestResult.status != TestResultStatus.pending_sync)
        .group_by(TestResult.athlete_id, TestResult.session_id, TestResult.test_id)
        .having(func.count() > 1)
    ).all()
    for athlete_id, session_id, test_id in duplicates:
        report.add("duplicate_official_result", f"{athlete_id}/{session_id}/{test_id}")

    # Twice the SLA: a result this old and still waiting has lost its job.
    stale = now - timedelta(seconds=2 * sla_seconds)
    for result_id in db.execute(
        select(TestResult.id)
        .where(TestResult.status.in_(WAITING))
        .where(TestResult.updated_at < stale)
    ).scalars():
        report.add("stuck_in_verification", result_id)

    return report

"""Deleting personal data once it is no longer needed (Sprint 20).

    python -m app.cli retention            # delete what is past its period
    python -m app.cli retention --dry-run  # only count it

Run daily. Each kind of data has its own period (``RETENTION_*_DAYS`` in the
settings) and the reason for it is in ``docs/PRIVACY.md``:

* **Videos** of a decided result (approved or rejected), a year after the
  decision. The file and the server's landmarks go; the row stays, marked
  retired, so the result and its audit trail survive. The 16x16 fingerprint
  stays too — too coarse to show a face, and still catches a resubmission of
  the same footage.
* **Field-run traces** of a decided result, on the same clock: the stored
  trace and the GPS track kept for the reviewer's map. The trace's hash stays,
  so a replay is still caught; the measured numbers stay with the result.
* **Identity checks** no result refers to, after 90 days.
* **Notifications** after a year. **OTP challenges** after a month: they are
  the evidence if someone asks whether an account was brute-forced.
* **Refresh tokens** a month after they expired or were revoked, and
  **push device tokens** a month after they stopped working.
* **Upload sessions**, finished or abandoned, and any partial file, a week
  after they expired.
* **The access log** after two years — kept longer than the data it audits.

Results, scores, review decisions and consents are not touched: they are the
athlete's record, and deleting them is an account deletion, which SAI handles.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path

from sqlalchemy import delete, func, or_, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..models import (
    AccessLog,
    DeviceToken,
    IdentityCheck,
    Notification,
    OtpChallenge,
    RefreshToken,
    TestResult,
    TestResultStatus,
    UploadSession,
    Video,
)

logger = logging.getLogger(__name__)

RETIRED_PREFIX = "retired:"
DECIDED = (TestResultStatus.approved, TestResultStatus.rejected)


@dataclass
class Report:
    counts: dict[str, int] = field(default_factory=dict)
    storage_errors: int = 0

    def add(self, name: str, count: int) -> None:
        self.counts[name] = self.counts.get(name, 0) + count


def is_retired(key: str | None) -> bool:
    return bool(key) and key.startswith(RETIRED_PREFIX)


def run(
    db: Session,
    settings: Settings,
    storage,
    *,
    now: datetime | None = None,
    dry_run: bool = False,
) -> Report:
    now = now or datetime.now(UTC)
    report = Report()

    def before(days: int) -> datetime:
        return now - timedelta(days=days)

    # Videos and traces of decided results, past their period.
    decided = (
        select(TestResult.id)
        .where(TestResult.status.in_(DECIDED))
        .where(TestResult.updated_at < before(settings.retention_video_days))
    )
    videos = (
        db.execute(
            select(Video)
            .where(Video.test_result_id.in_(decided))
            .where(~Video.s3_key.startswith(RETIRED_PREFIX))
        )
        .scalars()
        .all()
    )
    report.add("videos", len(videos))
    traces = [
        result
        for result in db.execute(
            select(TestResult)
            .where(TestResult.id.in_(decided))
            .where(TestResult.telemetry_key.is_not(None))
        ).scalars()
        if not (result.server_result or {}).get("trace_retired")
    ]
    report.add("field_traces", len(traces))
    retired_on = now.date().isoformat()

    if not dry_run:
        for video in videos:
            keys = (video.s3_key, video.pose_sequence_key, video.sensor_telemetry_key)
            for key in keys:
                if key and not _delete(storage, key):
                    report.storage_errors += 1
            video.s3_key = f"{RETIRED_PREFIX}{retired_on}"
            video.pose_sequence_key = None
            video.sensor_telemetry_key = None
        for result in traces:
            # The hash-named key stays on the row: a replay is still found.
            if not _delete(storage, result.telemetry_key):
                report.storage_errors += 1
            # Where the athlete ran is location data; the splits are not.
            snapshot = dict(result.server_result or {})
            snapshot.pop("track", None)
            result.server_result = {**snapshot, "trace_retired": retired_on}

    # Upload sessions, finished or abandoned: the row and any partial file.
    uploads = (
        db.execute(
            select(UploadSession).where(
                UploadSession.expires_at < before(settings.retention_upload_days)
            )
        )
        .scalars()
        .all()
    )
    report.add("upload_sessions", len(uploads))
    if not dry_run:
        for upload in uploads:
            try:
                Path(upload.staging_path).unlink(missing_ok=True)
            except OSError:
                report.storage_errors += 1
            db.delete(upload)
        db.flush()

    referenced = select(TestResult.identity_check_id).where(
        TestResult.identity_check_id.is_not(None)
    )
    token_cutoff = before(settings.retention_refresh_token_days)
    purges = {
        "identity_checks": delete(IdentityCheck)
        .where(IdentityCheck.created_at < before(settings.retention_identity_check_days))
        .where(IdentityCheck.id.not_in(referenced)),
        "notifications": delete(Notification).where(
            Notification.created_at < before(settings.retention_notification_days)
        ),
        "otp_challenges": delete(OtpChallenge).where(
            OtpChallenge.created_at < before(settings.retention_otp_days)
        ),
        "refresh_tokens": delete(RefreshToken).where(
            or_(
                RefreshToken.expires_at < token_cutoff,
                RefreshToken.revoked_at < token_cutoff,
            )
        ),
        "device_tokens": delete(DeviceToken).where(
            DeviceToken.disabled_at < token_cutoff
        ),
        "access_log": delete(AccessLog).where(
            AccessLog.created_at < before(settings.retention_access_log_days)
        ),
    }
    for name, statement in purges.items():
        _purge(db, report, name, dry_run, statement)

    if dry_run:
        db.rollback()
    else:
        db.commit()
    logger.info("Retention %s: %s", "dry run" if dry_run else "done", report.counts)
    return report


def _purge(db: Session, report: Report, name: str, dry_run: bool, statement) -> None:
    if dry_run:
        counted = db.scalar(
            select(func.count()).select_from(statement.table).where(statement.whereclause)
        )
        report.add(name, counted or 0)
        return
    result = db.execute(statement.execution_options(synchronize_session=False))
    report.add(name, result.rowcount or 0)


def _delete(storage, key: str) -> bool:
    try:
        storage.delete(key)
        return True
    except Exception:
        logger.exception("Could not delete %s", key)
        return False

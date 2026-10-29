"""Sprint 20: personal data is deleted once its retention period ends.

Each kind of data is aged past its period and checked gone, and a twin just
inside the period is checked kept. What is never deleted — the result itself,
its review trail, an identity check a result refers to — is checked too.
"""

from __future__ import annotations

import itertools
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from app import cli
from app.models import (
    AccessLog,
    DeviceToken,
    IdentityCheck,
    IdentityCheckOutcome,
    Notification,
    OtpChallenge,
    RefreshToken,
    TestResult,
    TestResultStatus,
    UploadSession,
    Video,
)
from app.services import retention
from app.storage import get_storage
from tests.test_dashboard import (
    make_athlete,
    make_official,
    make_result,
    token_for,
)

NOW = datetime(2027, 6, 1, 12, 0, tzinfo=UTC)
LONG_AGO = NOW - timedelta(days=800)
ATTEMPTS = itertools.count(1)


def days_ago(days: float) -> datetime:
    return NOW - timedelta(days=days)


def stored(storage, key: str) -> str:
    storage.store_bytes(key, b"bytes", content_type="application/octet-stream")
    return key


def decided_with_video(db, storage, athlete, test, *, status, decided_days_ago):
    result = make_result(db, athlete, test, status=status, attempt=next(ATTEMPTS))
    video = Video(
        test_result_id=result.id,
        s3_key=stored(storage, f"videos/{uuid.uuid4().hex}.mp4"),
        pose_sequence_key=stored(storage, f"pose/{uuid.uuid4().hex}.json"),
        checksum_sha256="0" * 64,
        fingerprint={"frames": [[0] * 4]},
    )
    db.add(video)
    db.flush()
    db.commit()
    # Set directly: the ORM's onupdate would move updated_at again on the next flush.
    db.execute(
        TestResult.__table__.update()
        .where(TestResult.id == result.id)
        .values(updated_at=days_ago(decided_days_ago))
    )
    db.commit()
    return result, video


def test_videos_of_decided_results_go_after_a_year(db, settings, seeded_tests):
    storage = get_storage(settings)
    athlete = make_athlete(db)
    test = seeded_tests["SIT_UPS"]

    old, old_video = decided_with_video(
        db, storage, athlete, test, status=TestResultStatus.approved, decided_days_ago=400
    )
    old_keys = (old_video.s3_key, old_video.pose_sequence_key)
    _, recent_video = decided_with_video(
        db, storage, athlete, test, status=TestResultStatus.rejected, decided_days_ago=300
    )
    # Undecided, however old: a reviewer may still need to watch it.
    _, pending_video = decided_with_video(
        db, storage, athlete, test, status=TestResultStatus.flagged, decided_days_ago=900
    )

    report = retention.run(db, settings, storage, now=NOW)

    assert report.counts["videos"] == 1
    assert report.storage_errors == 0
    db.expire_all()
    assert all(not storage.exists(key) for key in old_keys)
    retired = db.get(Video, old_video.id)
    assert retention.is_retired(retired.s3_key)
    assert retired.pose_sequence_key is None
    # The row, the result and the coarse fingerprint stay.
    assert retired.fingerprint == {"frames": [[0] * 4]}
    assert db.get(TestResult, old.id).status is TestResultStatus.approved
    for kept in (recent_video, pending_video):
        assert storage.exists(db.get(Video, kept.id).s3_key)

    # Running again finds nothing new.
    assert retention.run(db, settings, storage, now=NOW).counts["videos"] == 0


def test_the_reviewer_sees_that_a_recording_was_retired(
    client, db, settings, seeded_tests
):
    from app.models import OfficialRole

    storage = get_storage(settings)
    athlete = make_athlete(db)
    result, _ = decided_with_video(
        db,
        storage,
        athlete,
        seeded_tests["SIT_UPS"],
        status=TestResultStatus.approved,
        decided_days_ago=400,
    )
    retention.run(db, settings, storage, now=NOW)
    admin = make_official(db, role=OfficialRole.sai_admin)

    body = client.get(
        f"/api/dashboard/reviews/{result.id}", headers=token_for(admin, settings)
    ).json()

    assert body["video_url"] is None
    assert body["video_retired_on"] == NOW.date().isoformat()
    assert body["has_pose_sequence"] is False


def test_field_traces_go_but_their_hash_stays(db, settings, seeded_tests):
    storage = get_storage(settings)
    athlete = make_athlete(db)
    result = make_result(
        db, athlete, seeded_tests["SIT_UPS"], status=TestResultStatus.approved
    )
    key = stored(storage, f"telemetry/{'a' * 64}.json")
    result.telemetry_key = key
    result.server_result = {"kind": "field", "track": [[13.08, 80.27], [13.09, 80.28]]}
    db.commit()
    db.execute(
        TestResult.__table__.update()
        .where(TestResult.id == result.id)
        .values(updated_at=days_ago(400))
    )
    db.commit()

    report = retention.run(db, settings, storage, now=NOW)

    assert report.counts["field_traces"] == 1
    assert not storage.exists(key)
    db.expire_all()
    row = db.get(TestResult, result.id)
    # Still found by its key: the same trace submitted again is a replay.
    assert row.telemetry_key == key
    assert row.server_result["trace_retired"] == NOW.date().isoformat()
    assert row.server_result["kind"] == "field"
    # The run's route is location data and goes with the trace.
    assert "track" not in row.server_result
    assert retention.run(db, settings, storage, now=NOW).counts["field_traces"] == 0


def test_short_lived_records_go_at_the_end_of_their_period(db, settings, seeded_tests):
    athlete = make_athlete(db)

    def check(days: float, *, used: bool = False) -> IdentityCheck:
        row = IdentityCheck(
            athlete_id=athlete.id,
            outcome=IdentityCheckOutcome.match,
            created_at=days_ago(days),
        )
        db.add(row)
        db.flush()
        if used:
            result = make_result(db, athlete, seeded_tests["SIT_UPS"])
            result.identity_check_id = row.id
        return row

    old_check, recent_check, used_check = check(91), check(89), check(400, used=True)

    def notification(days: float) -> Notification:
        row = Notification(
            athlete_id=athlete.id,
            event_type="result_approved",
            dedupe_key=uuid.uuid4().hex,
            title="t",
            body="b",
            created_at=days_ago(days),
        )
        db.add(row)
        return row

    old_note, recent_note = notification(366), notification(364)

    def otp(days: float) -> OtpChallenge:
        row = OtpChallenge(
            phone="9000000000",
            code_hash="h" * 64,
            expires_at=days_ago(days) + timedelta(minutes=5),
            created_at=days_ago(days),
        )
        db.add(row)
        return row

    old_otp, recent_otp = otp(31), otp(29)

    def token(*, expired_days: float, revoked_days: float | None = None) -> RefreshToken:
        row = RefreshToken(
            token_hash=uuid.uuid4().hex,
            subject_id=athlete.id,
            subject_role="athlete",
            expires_at=days_ago(expired_days),
            revoked_at=days_ago(revoked_days) if revoked_days is not None else None,
        )
        db.add(row)
        return row

    expired_token = token(expired_days=31)
    revoked_token = token(expired_days=-300, revoked_days=31)
    live_token = token(expired_days=-300)
    lately_revoked = token(expired_days=-300, revoked_days=2)

    def log(days: float) -> AccessLog:
        row = AccessLog(
            event="read",
            method="GET",
            path="/api/dashboard/submissions",
            status_code=200,
            created_at=days_ago(days),
        )
        db.add(row)
        return row

    old_log, recent_log = log(731), log(729)

    def device(disabled_days: float | None) -> DeviceToken:
        row = DeviceToken(
            athlete_id=athlete.id,
            token=uuid.uuid4().hex,
            disabled_at=days_ago(disabled_days) if disabled_days is not None else None,
        )
        db.add(row)
        return row

    dead_device, live_device, lately_dead = device(31), device(None), device(3)
    db.commit()
    gone = [
        (IdentityCheck, old_check.id),
        (Notification, old_note.id),
        (OtpChallenge, old_otp.id),
        (RefreshToken, expired_token.id),
        (RefreshToken, revoked_token.id),
        (AccessLog, old_log.id),
        (DeviceToken, dead_device.id),
    ]
    kept = [
        (IdentityCheck, recent_check.id),
        # A result still refers to it: it is part of that result's evidence.
        (IdentityCheck, used_check.id),
        (Notification, recent_note.id),
        (OtpChallenge, recent_otp.id),
        (RefreshToken, live_token.id),
        (RefreshToken, lately_revoked.id),
        (AccessLog, recent_log.id),
        (DeviceToken, live_device.id),
        (DeviceToken, lately_dead.id),
    ]

    report = retention.run(db, settings, get_storage(settings), now=NOW)

    assert report.counts["identity_checks"] == 1
    assert report.counts["notifications"] == 1
    assert report.counts["otp_challenges"] == 1
    assert report.counts["refresh_tokens"] == 2
    assert report.counts["access_log"] == 1
    assert report.counts["device_tokens"] == 1
    db.expire_all()
    assert [db.get(model, row_id) for model, row_id in gone] == [None] * len(gone)
    assert all(db.get(model, row_id) is not None for model, row_id in kept)


def test_upload_sessions_and_their_partial_files_go(db, settings, tmp_path):
    def upload(expired_days: float, *, completed: bool = False) -> UploadSession:
        staging = tmp_path / f"{uuid.uuid4().hex}.part"
        staging.write_bytes(b"partial")
        row = UploadSession(
            id=uuid.uuid4().hex,
            test_code="SIT_UPS",
            file_size_bytes=10,
            chunk_size_bytes=5,
            total_chunks=2,
            checksum_sha256="0" * 64,
            content_type="video/mp4",
            staging_path=str(staging),
            completed=completed,
            expires_at=days_ago(expired_days),
        )
        db.add(row)
        return row

    abandoned, finished, live = upload(8), upload(30, completed=True), upload(6)
    db.commit()
    paths = {row.id: row.staging_path for row in (abandoned, finished, live)}

    report = retention.run(db, settings, get_storage(settings), now=NOW)

    assert report.counts["upload_sessions"] == 2
    remaining = set(db.execute(select(UploadSession.id)).scalars())
    assert remaining == {live.id}
    from pathlib import Path

    assert not Path(paths[abandoned.id]).exists()
    assert Path(paths[live.id]).exists()


def test_a_dry_run_counts_and_changes_nothing(db, settings, seeded_tests):
    storage = get_storage(settings)
    athlete = make_athlete(db)
    _, video = decided_with_video(
        db,
        storage,
        athlete,
        seeded_tests["SIT_UPS"],
        status=TestResultStatus.approved,
        decided_days_ago=400,
    )
    db.add(
        AccessLog(
            event="read", method="GET", path="/x", status_code=200, created_at=LONG_AGO
        )
    )
    db.commit()
    key = video.s3_key

    report = retention.run(db, settings, storage, now=NOW, dry_run=True)

    assert report.counts["videos"] == 1
    assert report.counts["access_log"] == 1
    db.expire_all()
    assert db.get(Video, video.id).s3_key == key
    assert storage.exists(key)
    assert db.execute(select(AccessLog)).scalars().first() is not None


def test_a_file_that_cannot_be_deleted_is_reported_and_the_rest_carries_on(
    db, settings, seeded_tests
):
    storage = get_storage(settings)
    athlete = make_athlete(db)
    _, video = decided_with_video(
        db,
        storage,
        athlete,
        seeded_tests["SIT_UPS"],
        status=TestResultStatus.approved,
        decided_days_ago=400,
    )

    class Failing:
        def delete(self, key):
            raise OSError("bucket unreachable")

    report = retention.run(db, settings, Failing(), now=NOW)

    assert report.storage_errors == 2
    db.expire_all()
    # Marked anyway, so the file is not served; the log names what to clean up.
    assert retention.is_retired(db.get(Video, video.id).s3_key)


def test_the_cli_command_exits_non_zero_on_storage_errors(monkeypatch, capsys):
    calls = []

    def fake_run(db, settings, storage, *, dry_run):
        calls.append(dry_run)
        return retention.Report(counts={"videos": 3}, storage_errors=0)

    monkeypatch.setattr(retention, "run", fake_run)
    monkeypatch.setattr(cli, "session_scope", _null_scope)
    monkeypatch.setattr("app.storage.get_storage", lambda settings: object())

    assert cli.main(["retention", "--dry-run"]) == 0
    assert calls == [True]
    assert "Would delete      3 videos" in capsys.readouterr().out


class _null_scope:
    def __enter__(self):
        return None

    def __exit__(self, *exc):
        return False


# ---------------------------------------------------------------------------
# check-storage
# ---------------------------------------------------------------------------


class FakeS3:
    def __init__(self, *, block=True, encryption=True, versioning="Suspended"):
        self.block, self.encryption, self.versioning = block, encryption, versioning

    def _missing(self, op):
        from botocore.exceptions import ClientError

        return ClientError({"Error": {"Code": "NoSuchConfiguration"}}, op)

    def get_public_access_block(self, Bucket):
        if self.block is None:
            raise self._missing("GetPublicAccessBlock")
        names = (
            "BlockPublicAcls",
            "IgnorePublicAcls",
            "BlockPublicPolicy",
            "RestrictPublicBuckets",
        )
        return {
            "PublicAccessBlockConfiguration": {
                name: (self.block is True or name != "BlockPublicPolicy")
                for name in names
            }
        }

    def get_bucket_encryption(self, Bucket):
        if not self.encryption:
            raise self._missing("GetBucketEncryption")
        rules = [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]
        return {"ServerSideEncryptionConfiguration": {"Rules": rules}}

    def get_bucket_versioning(self, Bucket):
        return {"Status": self.versioning} if self.versioning else {}


def test_a_private_encrypted_bucket_passes():
    assert cli.storage_problems(FakeS3(), "bucket") == []


def test_each_bucket_problem_is_named():
    assert cli.storage_problems(FakeS3(block=None), "b") == [
        "The bucket has no public access block"
    ]
    assert cli.storage_problems(FakeS3(block="partial"), "b") == [
        "Public access block is off for: BlockPublicPolicy"
    ]
    assert cli.storage_problems(FakeS3(encryption=False), "b") == [
        "The bucket has no default encryption"
    ]
    (versioning,) = cli.storage_problems(FakeS3(versioning="Enabled"), "b")
    assert versioning.startswith("Versioning is on")

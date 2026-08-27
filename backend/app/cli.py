"""Operational commands.

    python -m app.cli seed                 create the test battery rows
    python -m app.cli reverify-pending     re-queue submissions stuck in processing
    python -m app.cli reverify <result_id> re-run one submission
    python -m app.cli sla                  report the verification backlog
"""

from __future__ import annotations

import argparse
import logging
import sys
from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from .config import get_settings
from .database import session_scope
from .logging_config import configure_logging
from .models import Test, TestResult, TestResultStatus

logger = logging.getLogger(__name__)

TEST_BATTERY = [
    {
        "code": "SIT_UPS",
        "name": "Sit-ups",
        "unit": "reps",
        "description": "Maximum sit-ups completed in the allotted time.",
    },
    {
        "code": "VERTICAL_JUMP",
        "name": "Vertical Jump",
        "unit": "cm",
        "description": "Standing vertical jump height above a standing reference.",
    },
]


def seed() -> int:
    """Idempotent. Safe to run on every deploy."""
    created = 0
    with session_scope() as db:
        for entry in TEST_BATTERY:
            existing = db.execute(
                select(Test).where(Test.code == entry["code"])
            ).scalar_one_or_none()

            if existing:
                # Description and name may be edited; the code never changes,
                # because the verification pipeline keys off it.
                existing.name = entry["name"]
                existing.unit = entry["unit"]
                existing.description = entry["description"]
                db.add(existing)
                continue

            db.add(Test(**entry))
            created += 1

    print(f"Seeded test battery ({created} created, {len(TEST_BATTERY)} total)")
    return 0


def reverify_pending(older_than_minutes: int = 10) -> int:
    """Re-queue submissions left in `processing`.

    Submissions land here when the broker was down at submit time, or a worker
    died before acknowledging. Without this they wait forever — and to the
    athlete that looks identical to a test that was never received.
    """
    cutoff = datetime.now(UTC) - timedelta(minutes=older_than_minutes)

    with session_scope() as db:
        stale = (
            db.execute(
                select(TestResult).where(
                    TestResult.status == TestResultStatus.processing,
                    TestResult.created_at < cutoff,
                )
            )
            .scalars()
            .all()
        )

        ids = [str(result.id) for result in stale]

    if not ids:
        print("Nothing pending re-verification")
        return 0

    from .tasks import verify_test_result

    for result_id in ids:
        verify_test_result.delay(result_id, None)

    print(f"Re-queued {len(ids)} submission(s)")
    return 0


def reverify(result_id: str) -> int:
    from .tasks import verify_test_result

    verify_test_result.delay(result_id, None)
    print(f"Queued {result_id}")
    return 0


def sla_report() -> int:
    settings = get_settings()
    cutoff = datetime.now(UTC) - timedelta(
        seconds=settings.verification_sla_seconds
    )

    with session_scope() as db:
        pending = (
            db.execute(
                select(TestResult).where(
                    TestResult.status == TestResultStatus.processing
                )
            )
            .scalars()
            .all()
        )
        breaching = [result for result in pending if result.created_at < cutoff]

    print(f"SLA target      : {settings.verification_sla_seconds}s")
    print(f"Pending         : {len(pending)}")
    print(f"Breaching SLA   : {len(breaching)}")

    for result in breaching[:20]:
        age = (datetime.now(UTC) - result.created_at).total_seconds()
        print(f"  {result.id} pending {age:.0f}s")

    return 1 if breaching else 0


def main(argv: list[str] | None = None) -> int:
    configure_logging(debug=False, json_output=False)

    parser = argparse.ArgumentParser(prog="app.cli")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("seed", help="Create or update the test battery")

    pending_parser = subparsers.add_parser(
        "reverify-pending", help="Re-queue stuck submissions"
    )
    pending_parser.add_argument("--older-than-minutes", type=int, default=10)

    one_parser = subparsers.add_parser("reverify", help="Re-queue one submission")
    one_parser.add_argument("result_id")

    subparsers.add_parser("sla", help="Report the verification backlog")

    args = parser.parse_args(argv)

    if args.command == "seed":
        return seed()
    if args.command == "reverify-pending":
        return reverify_pending(args.older_than_minutes)
    if args.command == "reverify":
        return reverify(args.result_id)
    if args.command == "sla":
        return sla_report()

    return 1


if __name__ == "__main__":
    sys.exit(main())

"""Operational commands.

    python -m app.cli seed                 create the test battery rows
    python -m app.cli reverify-pending     re-queue submissions stuck in processing
    python -m app.cli reverify <result_id> re-run one submission
    python -m app.cli sla                  report the verification backlog
    python -m app.cli seed-benchmarks      load age/gender norms
    python -m app.cli create-official      provision a dashboard account
    python -m app.cli identity-key         print a new photo-encryption key
    python -m app.cli encrypt-photos       encrypt photos stored before Sprint 8
    python -m app.cli export-openapi       regenerate docs/openapi.yaml from the code
"""

from __future__ import annotations

import argparse
import logging
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

from sqlalchemy import select

from .config import get_settings
from .database import session_scope
from .logging_config import configure_logging
from .models import Test, TestResult, TestResultStatus

logger = logging.getLogger(__name__)

TEST_BATTERY = [
    {
        "code": "SQUATS",
        "name": "Squats",
        "unit": "reps",
        "description": "Full-depth squats completed, filmed side-on.",
    },
    {
        "code": "PUSH_UPS",
        "name": "Push-ups",
        "unit": "reps",
        "description": "Full-range push-ups with a straight body, filmed side-on.",
    },
    {
        "code": "BICEP_CURLS",
        "name": "Bicep Curls",
        "unit": "reps",
        "description": "Full-range curls on one working arm, filmed facing the camera.",
    },
    {
        "code": "LUNGES",
        "name": "Lunges",
        "unit": "reps",
        "description": "Lunges to depth with a split stance, filmed side-on.",
    },
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


def seed_benchmarks_command(file: str | None, replace: bool) -> int:
    """Load benchmark norms.

    Defaults to the bundled provisional set. Those numbers are placeholders,
    not SAI's published standards — see the header of the CSV — and every row
    carries a `source` that keeps them labelled all the way to the athlete.
    """
    from pathlib import Path

    from .services.benchmark_seed import BenchmarkSeedError, seed_benchmarks

    path = Path(file) if file else None

    with session_scope() as db:
        try:
            report = seed_benchmarks(db, path=path, replace=replace)
        except BenchmarkSeedError as exc:
            print(f"Benchmark seeding failed: {exc}", file=sys.stderr)
            return 1

    print(f"Benchmarks: {report.summary()}")

    if path is None:
        print(
            "NOTE: these are PROVISIONAL placeholders, not official SAI norms. "
            "Load the official table with --file when it is available."
        )

    return 0


def create_official_command(
    email: str, name: str, role: str, region: str | None, password: str | None
) -> int:
    """Create or reset a dashboard account.

    The password is read from the terminal (or F4ALL_OFFICIAL_PASSWORD for
    scripted provisioning), never taken as a command-line argument, where it
    would land in shell history and the process list.
    """
    import getpass
    import os

    from .models import Official, OfficialRole
    from .regions import canonical_region
    from .services.passwords import WeakPassword, hash_password, validate_strength

    try:
        role_value = OfficialRole(role)
    except ValueError:
        print(f"Role must be one of: {[r.value for r in OfficialRole]}", file=sys.stderr)
        return 1

    canonical = None
    if role_value is OfficialRole.regional_reviewer:
        canonical = canonical_region(region or "")
        if canonical is None:
            # A regional reviewer without a valid region would see nothing; one
            # with a misspelt region would see nothing and not know why.
            print("A regional reviewer needs a valid --region", file=sys.stderr)
            return 1
    elif region:
        print("sai_admin sees every region; --region is ignored", file=sys.stderr)

    secret = password or os.environ.get("F4ALL_OFFICIAL_PASSWORD")
    if not secret:
        secret = getpass.getpass("Password: ")
        if secret != getpass.getpass("Repeat password: "):
            print("Passwords did not match", file=sys.stderr)
            return 1

    try:
        validate_strength(secret)
    except WeakPassword as exc:
        print(str(exc), file=sys.stderr)
        return 1

    normalised = email.strip().lower()

    with session_scope() as db:
        official = db.execute(
            select(Official).where(Official.email == normalised)
        ).scalar_one_or_none()

        created = official is None
        if created:
            official = Official(email=normalised, name=name, role=role_value)

        official.name = name
        official.role = role_value
        official.region = canonical
        official.password_hash = hash_password(secret)
        official.failed_login_attempts = 0
        official.locked_until = None
        official.is_active = True
        db.add(official)

    print(f"{'Created' if created else 'Updated'} {role_value.value} {normalised}")
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


def identity_key() -> int:
    import base64
    import os

    print(base64.urlsafe_b64encode(os.urandom(32)).decode())
    return 0


def encrypt_photos() -> int:
    """Encrypt registration photos stored in the clear before Sprint 8.

    Each is re-stored under a new ``.enc`` key and the plaintext deleted.
    Safe to run again: encrypted photos are skipped.
    """
    from .models import Athlete
    from .services import identity_crypto
    from .storage import get_storage

    settings = get_settings()
    storage = get_storage(settings)
    converted = failed = 0

    with session_scope() as db:
        athletes = db.execute(
            select(Athlete).where(Athlete.reference_face_key.is_not(None))
        ).scalars()
        for athlete in athletes:
            old_key = athlete.reference_face_key
            if identity_crypto.is_encrypted_key(old_key):
                continue
            try:
                photo = identity_crypto.load_photo(storage, settings, old_key)
                athlete.reference_face_key = identity_crypto.store_photo(
                    storage, settings, athlete.id, photo
                )
                db.add(athlete)
                db.flush()
                storage.delete(old_key)
                converted += 1
            except Exception:
                logger.exception("Could not encrypt photo for %s", athlete.id)
                failed += 1

    print(f"Encrypted {converted} photo(s); {failed} failed")
    return 1 if failed else 0


OPENAPI_PATH = Path(__file__).resolve().parent.parent.parent / "docs" / "openapi.yaml"

OPENAPI_HEADER = (
    "# Generated from the backend by `python -m app.cli export-openapi`.\n"
    "# Do not edit by hand: tests/test_api_contract.py fails when this file and\n"
    "# the code disagree.\n"
)


def openapi_document() -> dict:
    from .main import create_app

    return create_app().openapi()


def export_openapi(path: Path = OPENAPI_PATH) -> int:
    import yaml

    text = yaml.safe_dump(openapi_document(), sort_keys=False, allow_unicode=True)
    path.write_text(OPENAPI_HEADER + text, encoding="utf-8", newline="\n")
    print(f"Wrote {path}")
    return 0


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

    benchmark_parser = subparsers.add_parser(
        "seed-benchmarks", help="Load age/gender benchmark norms from a CSV"
    )
    benchmark_parser.add_argument(
        "--file", default=None, help="CSV to load (default: bundled provisional set)"
    )
    benchmark_parser.add_argument(
        "--replace",
        action="store_true",
        help="Clear existing benchmarks first, for swapping in an official table",
    )

    official_parser = subparsers.add_parser(
        "create-official", help="Create or reset an SAI dashboard account"
    )
    official_parser.add_argument("--email", required=True)
    official_parser.add_argument("--name", required=True)
    official_parser.add_argument(
        "--role", required=True, choices=["sai_admin", "regional_reviewer"]
    )
    official_parser.add_argument("--region", default=None)

    subparsers.add_parser("identity-key", help="Print a new photo-encryption key")
    subparsers.add_parser("export-openapi", help="Regenerate docs/openapi.yaml")
    subparsers.add_parser(
        "encrypt-photos", help="Encrypt registration photos stored in the clear"
    )

    args = parser.parse_args(argv)

    if args.command == "seed":
        return seed()
    if args.command == "reverify-pending":
        return reverify_pending(args.older_than_minutes)
    if args.command == "reverify":
        return reverify(args.result_id)
    if args.command == "sla":
        return sla_report()
    if args.command == "seed-benchmarks":
        return seed_benchmarks_command(args.file, args.replace)
    if args.command == "export-openapi":
        return export_openapi()
    if args.command == "identity-key":
        return identity_key()
    if args.command == "encrypt-photos":
        return encrypt_photos()
    if args.command == "create-official":
        return create_official_command(
            args.email, args.name, args.role, args.region, password=None
        )

    return 1


if __name__ == "__main__":
    sys.exit(main())

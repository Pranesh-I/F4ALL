"""sprint 11 server verification

Makes the machine verdict reproducible and the lifecycle explicit:

* ``uploaded`` joins the status constraint: a stored video waiting for a
  worker, distinct from ``processing`` (a worker has claimed it).
* The verdict's inputs and timing are persisted on the result: when processing
  started and how long it took, the deciding reason, the pipeline version, the
  mobile and server result snapshots and every check the verdict was built on.
* ``verification_attempts`` and ``verification_run_id`` let only the latest
  claim on a result write its verdict, so a redelivered job cannot duplicate
  flags.

Existing rows are left as they are. A result already in ``processing`` is
picked up by the worker exactly as before.

Revision ID: f3b8d2a61c47
Revises: e7a3c1d95b24
Create Date: 2026-09-29
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "f3b8d2a61c47"
down_revision: str | None = "e7a3c1d95b24"
branch_labels: str | None = None
depends_on: str | None = None

OLD_STATUSES = (
    "status IN ('pending_sync','processing','verified','flagged',"
    "'approved','rejected')"
)
NEW_STATUSES = (
    "status IN ('pending_sync','uploaded','processing','verified','flagged',"
    "'approved','rejected')"
)


def upgrade() -> None:
    with op.batch_alter_table("test_results") as batch:
        batch.drop_constraint("ck_test_results_status", type_="check")
        batch.create_check_constraint("ck_test_results_status", NEW_STATUSES)

        batch.add_column(
            sa.Column("processing_started_at", sa.DateTime(timezone=True), nullable=True)
        )
        batch.add_column(sa.Column("processing_duration_ms", sa.Integer(), nullable=True))
        batch.add_column(sa.Column("verification_reason", sa.String(length=64), nullable=True))
        batch.add_column(sa.Column("pipeline_version", sa.String(length=40), nullable=True))
        batch.add_column(sa.Column("mobile_result", sa.JSON(), nullable=True))
        batch.add_column(sa.Column("server_result", sa.JSON(), nullable=True))
        batch.add_column(sa.Column("verification_checks", sa.JSON(), nullable=True))
        batch.add_column(
            sa.Column(
                "verification_attempts", sa.Integer(), nullable=False, server_default="0"
            )
        )
        batch.add_column(sa.Column("verification_run_id", sa.String(length=64), nullable=True))


def downgrade() -> None:
    # The old constraint has no `uploaded`. Those rows are still waiting for a
    # worker, which is what `processing` meant before this revision.
    op.execute(
        "UPDATE test_results SET status = 'processing' WHERE status = 'uploaded'"
    )

    with op.batch_alter_table("test_results") as batch:
        batch.drop_column("verification_run_id")
        batch.drop_column("verification_attempts")
        batch.drop_column("verification_checks")
        batch.drop_column("server_result")
        batch.drop_column("mobile_result")
        batch.drop_column("pipeline_version")
        batch.drop_column("verification_reason")
        batch.drop_column("processing_duration_ms")
        batch.drop_column("processing_started_at")

        batch.drop_constraint("ck_test_results_status", type_="check")
        batch.create_check_constraint("ck_test_results_status", OLD_STATUSES)

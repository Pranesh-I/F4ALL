"""sprint 14 review audit and machine verdict

* ``test_results.verification_verdict`` — what the automated check concluded
  (verified / flagged / rejected), written once by the worker. ``status`` moves
  on when an official decides; this keeps the machine's answer beside it.
* ``review_actions.reason`` — a structured reason code (``ReviewReason``)
  beside the free-text notes.
* ``review_actions.previous_status`` / ``new_status`` — the result's status on
  either side of each action, so the audit trail reads as transitions.
* ``ix_review_actions_test_result`` — history and "has anyone reviewed this"
  lookups per result.

``review_actions.action`` gains the value ``flagged``. The column is a plain
VARCHAR(30) without a CHECK constraint (the initial schema created it that
way), so no constraint change is needed.

Backfill: only the unambiguous cases. A result still ``verified`` or
``flagged`` is its own machine verdict, and a machine rejection nobody has
reviewed yet is ``rejected``. Older decided results keep a null verdict and
null audit transitions rather than guesses.

Revision ID: c5d81e3f9a27
Revises: a4c9e1f07d52
Create Date: 2026-09-30
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "c5d81e3f9a27"
down_revision: str | None = "a4c9e1f07d52"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    with op.batch_alter_table("test_results") as batch:
        batch.add_column(sa.Column("verification_verdict", sa.String(20), nullable=True))
    with op.batch_alter_table("review_actions") as batch:
        batch.add_column(sa.Column("reason", sa.String(40), nullable=True))
        batch.add_column(sa.Column("previous_status", sa.String(30), nullable=True))
        batch.add_column(sa.Column("new_status", sa.String(30), nullable=True))
    op.create_index(
        "ix_review_actions_test_result", "review_actions", ["test_result_id"]
    )

    op.execute(
        "UPDATE test_results SET verification_verdict = status "
        "WHERE status IN ('verified', 'flagged')"
    )
    op.execute(
        "UPDATE test_results SET verification_verdict = 'rejected' "
        "WHERE status = 'rejected' AND verification_reason IS NOT NULL "
        "AND id NOT IN (SELECT test_result_id FROM review_actions)"
    )


def downgrade() -> None:
    # Review rows with action `flagged` are kept: the audit trail is
    # append-only. Code from before this revision cannot load that value, so
    # downgrading a database where reviewers have flagged needs those rows
    # dealt with by hand first.
    op.drop_index("ix_review_actions_test_result", table_name="review_actions")
    with op.batch_alter_table("review_actions") as batch:
        batch.drop_column("new_status")
        batch.drop_column("previous_status")
        batch.drop_column("reason")
    with op.batch_alter_table("test_results") as batch:
        batch.drop_column("verification_verdict")

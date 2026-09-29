"""assessment sessions

Official assessment windows created by SAI, and the link from each official
result to the session it was made for. The partial unique index is what
enforces one live submission per athlete, session and test.

Revision ID: 9d41c7e2b8f5
Revises: 5b2e9d7f1a36
Create Date: 2026-09-29
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "9d41c7e2b8f5"
down_revision: str | None = "5b2e9d7f1a36"
branch_labels: str | None = None
depends_on: str | None = None

LIVE_SUBMISSION = "session_id IS NOT NULL AND status <> 'pending_sync'"


def upgrade() -> None:
    op.create_table(
        "assessment_sessions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=150), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("rules", sa.Text(), nullable=True),
        sa.Column("starts_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ends_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("allowed_tests", sa.JSON(), nullable=False),
        sa.Column("region", sa.String(length=100), nullable=True),
        sa.Column("created_by", sa.Uuid(), nullable=True),
        sa.Column("updated_by", sa.Uuid(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("ends_at > starts_at", name="ck_assessment_sessions_window"),
        sa.ForeignKeyConstraint(["created_by"], ["officials.id"]),
        sa.ForeignKeyConstraint(["updated_by"], ["officials.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_assessment_sessions_window",
        "assessment_sessions",
        ["enabled", "starts_at", "ends_at"],
    )

    # Existing results have no session; they stay exactly as they were.
    with op.batch_alter_table("test_results") as batch:
        batch.add_column(sa.Column("session_id", sa.Uuid(), nullable=True))
        batch.create_foreign_key(
            "fk_test_results_session_id",
            "assessment_sessions",
            ["session_id"],
            ["id"],
        )

    op.create_index(
        "uq_session_submission",
        "test_results",
        ["athlete_id", "session_id", "test_id"],
        unique=True,
        postgresql_where=sa.text(LIVE_SUBMISSION),
        sqlite_where=sa.text(LIVE_SUBMISSION),
    )


def downgrade() -> None:
    op.drop_index("uq_session_submission", table_name="test_results")
    with op.batch_alter_table("test_results") as batch:
        batch.drop_constraint("fk_test_results_session_id", type_="foreignkey")
        batch.drop_column("session_id")
    op.drop_index("ix_assessment_sessions_window", table_name="assessment_sessions")
    op.drop_table("assessment_sessions")

"""practice attempts

An athlete's practice history, stored against their account so it follows
them to any phone they sign in on. Separate from test_results by design:
nothing official reads it, and only its owner can.

Revision ID: 5b2e9d7f1a36
Revises: 8c43f54b1ac2
Create Date: 2026-09-29
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "5b2e9d7f1a36"
down_revision: str | None = "8c43f54b1ac2"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_table(
        "practice_attempts",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("athlete_id", sa.Uuid(), nullable=False),
        sa.Column("client_attempt_id", sa.String(length=64), nullable=False),
        sa.Column("test_code", sa.String(length=50), nullable=False),
        sa.Column("score", sa.Numeric(precision=10, scale=2), nullable=False),
        sa.Column("unit", sa.String(length=20), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False),
        sa.Column("confidence", sa.Numeric(precision=4, scale=3), nullable=False),
        sa.Column("invalid_reason", sa.String(length=300), nullable=True),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("events", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "status IN ('COMPLETE','INVALID')", name="ck_practice_attempts_status"
        ),
        sa.ForeignKeyConstraint(["athlete_id"], ["athletes.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "athlete_id", "client_attempt_id", name="uq_practice_attempts_client_id"
        ),
    )
    op.create_index(
        "ix_practice_attempts_athlete_recorded",
        "practice_attempts",
        ["athlete_id", "recorded_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_practice_attempts_athlete_recorded", table_name="practice_attempts")
    op.drop_table("practice_attempts")

"""sprint 17 notifications

* ``device_tokens`` — push tokens, each addressed to the athlete signed in on
  the phone that registered it.
* ``notifications`` — the outbox and the athlete's inbox in one table, with a
  unique ``dedupe_key`` so one event is never stored twice.
* ``notifier_state`` — how far the notifier has read.

Revision ID: e4b19c7d2f63
Revises: d2f6a8b41c90
Create Date: 2026-10-13
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "e4b19c7d2f63"
down_revision: str | None = "d2f6a8b41c90"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_table(
        "device_tokens",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("athlete_id", sa.Uuid(), nullable=False),
        sa.Column("token", sa.String(512), nullable=False),
        sa.Column("platform", sa.String(20), nullable=False),
        sa.Column("provider", sa.String(20), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("disabled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("disabled_reason", sa.String(100), nullable=True),
        sa.ForeignKeyConstraint(["athlete_id"], ["athletes.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("token"),
    )
    op.create_index("ix_device_tokens_athlete", "device_tokens", ["athlete_id"])

    op.create_table(
        "notifications",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("athlete_id", sa.Uuid(), nullable=False),
        sa.Column("event_type", sa.String(40), nullable=False),
        sa.Column("dedupe_key", sa.String(200), nullable=False),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("body", sa.Text(), nullable=False),
        sa.Column("data", sa.JSON(), nullable=False),
        sa.Column("language", sa.String(5), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("delivery_status", sa.String(20), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("next_attempt_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("sent_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_error", sa.String(300), nullable=True),
        sa.Column("read_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "delivery_status IN ('pending','retrying','sent','failed','inbox')",
            name="ck_notifications_delivery_status",
        ),
        sa.ForeignKeyConstraint(["athlete_id"], ["athletes.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("dedupe_key"),
    )
    op.create_index(
        "ix_notifications_athlete_created", "notifications", ["athlete_id", "created_at"]
    )
    op.create_index(
        "ix_notifications_delivery",
        "notifications",
        ["delivery_status", "next_attempt_at"],
    )

    op.create_table(
        "notifier_state",
        sa.Column("name", sa.String(50), nullable=False),
        sa.Column("value", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("name"),
    )

    # The notifier reads results by when they last changed.
    op.create_index("ix_test_results_updated_at", "test_results", ["updated_at"])


def downgrade() -> None:
    op.drop_index("ix_test_results_updated_at", table_name="test_results")
    op.drop_table("notifier_state")
    op.drop_index("ix_notifications_delivery", table_name="notifications")
    op.drop_index("ix_notifications_athlete_created", table_name="notifications")
    op.drop_table("notifications")
    op.drop_index("ix_device_tokens_athlete", table_name="device_tokens")
    op.drop_table("device_tokens")

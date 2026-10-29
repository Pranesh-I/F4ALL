"""sprint 20 access log

* ``access_log`` — who read or changed what on the officials' side, and every
  official sign-in attempt. Append-only; trimmed only by the retention job.

Revision ID: a8d4e2c71f90
Revises: f7c2a9e15b48
Create Date: 2026-10-13
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "a8d4e2c71f90"
down_revision: str | None = "f7c2a9e15b48"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_table(
        "access_log",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actor_id", sa.Uuid(), nullable=True),
        sa.Column("actor_role", sa.String(30), nullable=True),
        sa.Column("event", sa.String(30), nullable=False),
        sa.Column("method", sa.String(10), nullable=False),
        sa.Column("path", sa.String(500), nullable=False),
        sa.Column("status_code", sa.Integer(), nullable=False),
        sa.Column("client_ip", sa.String(64), nullable=True),
        sa.Column("request_id", sa.String(64), nullable=True),
        sa.Column("detail", sa.String(200), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_access_log_created", "access_log", ["created_at"])
    op.create_index("ix_access_log_actor_created", "access_log", ["actor_id", "created_at"])


def downgrade() -> None:
    op.drop_index("ix_access_log_actor_created", table_name="access_log")
    op.drop_index("ix_access_log_created", table_name="access_log")
    op.drop_table("access_log")

"""sprint 8 official auth and pose sequences

Official sign-in (password hash, lockout, active flag) and the server's pose
sequence key for the dashboard's skeleton overlay.

Revision ID: c6c4584411d4
Revises: 560a7e6c34f6
Create Date: 2026-09-17
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = 'c6c4584411d4'
down_revision: str | None = '560a7e6c34f6'
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    # server_defaults so existing official rows migrate: NOT NULL columns
    # without a default fail outright on a table that already has data.
    with op.batch_alter_table('officials') as batch:
        batch.add_column(sa.Column('password_hash', sa.String(length=255), nullable=True))
        batch.add_column(
            sa.Column(
                'failed_login_attempts', sa.Integer(), nullable=False, server_default='0'
            )
        )
        batch.add_column(
            sa.Column('locked_until', sa.DateTime(timezone=True), nullable=True)
        )
        batch.add_column(
            sa.Column('is_active', sa.Boolean(), nullable=False, server_default=sa.true())
        )

    with op.batch_alter_table('videos') as batch:
        batch.add_column(
            sa.Column('pose_sequence_key', sa.String(length=500), nullable=True)
        )


def downgrade() -> None:
    with op.batch_alter_table('videos') as batch:
        batch.drop_column('pose_sequence_key')

    with op.batch_alter_table('officials') as batch:
        batch.drop_column('is_active')
        batch.drop_column('locked_until')
        batch.drop_column('failed_login_attempts')
        batch.drop_column('password_hash')

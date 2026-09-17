"""sprint 9 leaderboard opt in and language

Leaderboard visibility (off by default — most athletes are minors) and the
athlete's chosen app language.

Revision ID: 8c43f54b1ac2
Revises: c6c4584411d4
Create Date: 2026-09-18
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = '8c43f54b1ac2'
down_revision: str | None = 'c6c4584411d4'
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    # Existing athletes default to hidden: nobody is made public by a migration.
    with op.batch_alter_table('athletes') as batch:
        batch.add_column(
            sa.Column(
                'leaderboard_opt_in', sa.Boolean(), nullable=False, server_default=sa.false()
            )
        )
        batch.add_column(
            sa.Column(
                'preferred_language', sa.String(length=8), nullable=False, server_default='en'
            )
        )


def downgrade() -> None:
    with op.batch_alter_table('athletes') as batch:
        batch.drop_column('preferred_language')
        batch.drop_column('leaderboard_opt_in')

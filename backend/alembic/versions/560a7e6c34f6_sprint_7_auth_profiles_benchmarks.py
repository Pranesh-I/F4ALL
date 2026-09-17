"""sprint 7 auth profiles benchmarks

Adds the OTP challenge and refresh token tables, and gives benchmarks a
`source` column plus a uniqueness guarantee on the cohort key.

Revision ID: 560a7e6c34f6
Revises: 3cc2fab51ed5
Create Date: 2026-09-10 09:27:22.755377
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = '560a7e6c34f6'
down_revision: str | None = '3cc2fab51ed5'
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_table(
        'otp_challenges',
        sa.Column('id', sa.Uuid(), nullable=False),
        sa.Column('phone', sa.String(length=20), nullable=False),
        sa.Column('code_hash', sa.String(length=64), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('attempts', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('consumed_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        'ix_otp_challenges_phone_created',
        'otp_challenges',
        ['phone', 'created_at'],
        unique=False,
    )

    op.create_table(
        'refresh_tokens',
        sa.Column('id', sa.Uuid(), nullable=False),
        sa.Column('token_hash', sa.String(length=64), nullable=False),
        sa.Column('subject_id', sa.Uuid(), nullable=False),
        sa.Column('subject_role', sa.String(length=30), nullable=False),
        sa.Column('subject_phone', sa.String(length=20), nullable=True),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('revoked_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('replaced_by', sa.Uuid(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('last_used_at', sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_index(
        'ix_refresh_tokens_subject', 'refresh_tokens', ['subject_id'], unique=False
    )
    op.create_index(
        op.f('ix_refresh_tokens_token_hash'),
        'refresh_tokens',
        ['token_hash'],
        unique=True,
    )

    # batch_alter_table because SQLite cannot ALTER a table to add a constraint
    # and rebuilds it instead. The test suite runs on SQLite, so a migration
    # that only applies on Postgres is a migration nobody exercises until
    # deployment.
    # False for timed tests, where the lower score is the better performance.
    with op.batch_alter_table('tests') as batch:
        batch.add_column(
            sa.Column(
                'higher_is_better',
                sa.Boolean(),
                nullable=False,
                server_default=sa.true(),
            )
        )

    with op.batch_alter_table('benchmarks') as batch:
        # server_default rather than a Python-side default: an existing row
        # cannot be given a value by the ORM, and NOT NULL without a default
        # fails outright on a table that already has data.
        batch.add_column(
            sa.Column(
                'source', sa.String(length=255), nullable=False, server_default=''
            )
        )
        batch.create_index('ix_benchmarks_lookup', ['test_id', 'gender'], unique=False)
        batch.create_unique_constraint(
            'uq_benchmark_cohort', ['test_id', 'gender', 'age_min', 'age_max']
        )


def downgrade() -> None:
    with op.batch_alter_table('benchmarks') as batch:
        batch.drop_constraint('uq_benchmark_cohort', type_='unique')
        batch.drop_index('ix_benchmarks_lookup')
        batch.drop_column('source')

    with op.batch_alter_table('tests') as batch:
        batch.drop_column('higher_is_better')

    op.drop_index(op.f('ix_refresh_tokens_token_hash'), table_name='refresh_tokens')
    op.drop_index('ix_refresh_tokens_subject', table_name='refresh_tokens')
    op.drop_table('refresh_tokens')
    op.drop_index('ix_otp_challenges_phone_created', table_name='otp_challenges')
    op.drop_table('otp_challenges')

"""identity and consent

Profile fields below the state (city, place), achievements, recorded consents,
and the photo checks athletes take before official tests.

Existing athletes gain no consent rows: consent has to be given, not assumed,
so the app asks them for it the next time they open it.

Revision ID: e7a3c1d95b24
Revises: 9d41c7e2b8f5
Create Date: 2026-09-29
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "e7a3c1d95b24"
down_revision: str | None = "9d41c7e2b8f5"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    with op.batch_alter_table("athletes") as batch:
        batch.add_column(sa.Column("city", sa.String(length=100), nullable=True))
        batch.add_column(sa.Column("place", sa.String(length=100), nullable=True))
        batch.add_column(sa.Column("achievements", sa.Text(), nullable=True))

    op.create_table(
        "athlete_consents",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("athlete_id", sa.Uuid(), nullable=False),
        sa.Column("purpose", sa.String(length=40), nullable=False),
        sa.Column("version", sa.String(length=20), nullable=False),
        sa.Column("given_by", sa.String(length=20), nullable=False),
        sa.Column("guardian_name", sa.String(length=150), nullable=True),
        sa.Column("given_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("withdrawn_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["athlete_id"], ["athletes.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.CheckConstraint(
            "given_by <> 'guardian' OR guardian_name IS NOT NULL",
            name="ck_athlete_consents_guardian_named",
        ),
    )
    op.create_index(
        "ix_athlete_consents_athlete_purpose",
        "athlete_consents",
        ["athlete_id", "purpose"],
    )

    op.create_table(
        "identity_checks",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("athlete_id", sa.Uuid(), nullable=False),
        sa.Column("session_id", sa.Uuid(), nullable=True),
        sa.Column("outcome", sa.String(length=20), nullable=False),
        sa.Column("similarity", sa.Numeric(5, 4), nullable=True),
        sa.Column("reason", sa.Text(), nullable=True),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["athlete_id"], ["athletes.id"]),
        sa.ForeignKeyConstraint(["session_id"], ["assessment_sessions.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_identity_checks_athlete_created",
        "identity_checks",
        ["athlete_id", "created_at"],
    )

    with op.batch_alter_table("test_results") as batch:
        batch.add_column(sa.Column("identity_check_id", sa.Uuid(), nullable=True))
        batch.create_foreign_key(
            "fk_test_results_identity_check_id",
            "identity_checks",
            ["identity_check_id"],
            ["id"],
        )


def downgrade() -> None:
    with op.batch_alter_table("test_results") as batch:
        batch.drop_constraint("fk_test_results_identity_check_id", type_="foreignkey")
        batch.drop_column("identity_check_id")

    op.drop_index("ix_identity_checks_athlete_created", table_name="identity_checks")
    op.drop_table("identity_checks")
    op.drop_index(
        "ix_athlete_consents_athlete_purpose", table_name="athlete_consents"
    )
    op.drop_table("athlete_consents")

    with op.batch_alter_table("athletes") as batch:
        batch.drop_column("achievements")
        batch.drop_column("place")
        batch.drop_column("city")

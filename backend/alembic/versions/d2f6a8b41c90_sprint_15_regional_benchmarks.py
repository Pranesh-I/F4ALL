"""sprint 15 regional benchmarks

* ``benchmarks.region`` — a state or union territory whose own norms a row
  holds, or null for an all-India row. Every existing row is all-India.
* ``uq_benchmark_cohort`` is replaced by two partial unique indexes, one for
  all-India rows and one for regional rows. A plain unique constraint over a
  nullable ``region`` would treat each null as distinct and so stop guarding
  the all-India rows it guarded before.

Revision ID: d2f6a8b41c90
Revises: c5d81e3f9a27
Create Date: 2026-10-13
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "d2f6a8b41c90"
down_revision: str | None = "c5d81e3f9a27"
branch_labels: str | None = None
depends_on: str | None = None

COHORT = ["test_id", "gender", "age_min", "age_max"]


def upgrade() -> None:
    with op.batch_alter_table("benchmarks") as batch:
        batch.add_column(sa.Column("region", sa.String(100), nullable=True))
        batch.drop_constraint("uq_benchmark_cohort", type_="unique")

    op.create_index(
        "uq_benchmark_cohort_national",
        "benchmarks",
        COHORT,
        unique=True,
        postgresql_where=sa.text("region IS NULL"),
        sqlite_where=sa.text("region IS NULL"),
    )
    op.create_index(
        "uq_benchmark_cohort_regional",
        "benchmarks",
        [*COHORT, "region"],
        unique=True,
        postgresql_where=sa.text("region IS NOT NULL"),
        sqlite_where=sa.text("region IS NOT NULL"),
    )


def downgrade() -> None:
    # Regional rows cannot survive the old one-row-per-cohort constraint.
    op.execute("DELETE FROM benchmarks WHERE region IS NOT NULL")
    op.drop_index("uq_benchmark_cohort_regional", table_name="benchmarks")
    op.drop_index("uq_benchmark_cohort_national", table_name="benchmarks")
    with op.batch_alter_table("benchmarks") as batch:
        batch.create_unique_constraint("uq_benchmark_cohort", COHORT)
        batch.drop_column("region")

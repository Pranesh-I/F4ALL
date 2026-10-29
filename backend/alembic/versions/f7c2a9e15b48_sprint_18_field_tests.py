"""sprint 18 field tests

* ``tests`` gains SHUTTLE_RUN and ENDURANCE_RUN, timed (lowest is best), so a
  database migrated without running ``python -m app.cli seed`` still knows them.
* ``test_results.client_attempt_id`` — the phone's id for a field-test attempt,
  unique per athlete: a retried submission finds the result it created.
* ``test_results.telemetry_key`` — where the sensor/GPS trace is stored, named
  by its hash; indexed, so a trace submitted twice is found.

Revision ID: f7c2a9e15b48
Revises: e4b19c7d2f63
Create Date: 2026-10-13
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import sqlalchemy as sa
from alembic import op

revision: str = "f7c2a9e15b48"
down_revision: str | None = "e4b19c7d2f63"
branch_labels: str | None = None
depends_on: str | None = None

FIELD_TESTS = (
    (
        "SHUTTLE_RUN",
        "Shuttle Run",
        "4 x 10 m shuttle run, timed by the phone's motion sensors.",
    ),
    (
        "ENDURANCE_RUN",
        "Endurance Run",
        "600 m run (walking allowed), timed by GPS distance.",
    ),
)


def upgrade() -> None:
    with op.batch_alter_table("test_results") as batch:
        batch.add_column(sa.Column("client_attempt_id", sa.String(64), nullable=True))
        batch.add_column(sa.Column("telemetry_key", sa.String(200), nullable=True))
        batch.create_unique_constraint(
            "uq_result_client_attempt", ["athlete_id", "client_attempt_id"]
        )
    op.create_index("ix_test_results_telemetry_key", "test_results", ["telemetry_key"])

    tests = sa.table(
        "tests",
        sa.column("id", sa.Uuid()),
        sa.column("code", sa.String()),
        sa.column("name", sa.String()),
        sa.column("unit", sa.String()),
        sa.column("description", sa.Text()),
        sa.column("higher_is_better", sa.Boolean()),
        sa.column("created_at", sa.DateTime(timezone=True)),
    )
    connection = op.get_bind()
    existing = {row[0] for row in connection.execute(sa.select(tests.c.code))}
    rows = [
        {
            "id": uuid.uuid4(),
            "code": code,
            "name": name,
            "unit": "seconds",
            "description": description,
            "higher_is_better": False,
            "created_at": datetime.now(UTC),
        }
        for code, name, description in FIELD_TESTS
        if code not in existing
    ]
    if rows:
        op.bulk_insert(tests, rows)


def downgrade() -> None:
    # Field-test rows stay if results refer to them; only unused ones go.
    op.execute(
        "DELETE FROM tests WHERE code IN ('SHUTTLE_RUN', 'ENDURANCE_RUN') "
        "AND id NOT IN (SELECT test_id FROM test_results) "
        "AND id NOT IN (SELECT test_id FROM benchmarks)"
    )
    op.drop_index("ix_test_results_telemetry_key", table_name="test_results")
    with op.batch_alter_table("test_results") as batch:
        batch.drop_constraint("uq_result_client_attempt", type_="unique")
        batch.drop_column("telemetry_key")
        batch.drop_column("client_attempt_id")

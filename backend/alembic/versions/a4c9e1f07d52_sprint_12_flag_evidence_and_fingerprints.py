"""sprint 12 flag evidence and video fingerprints

* ``flags.evidence`` — the measurements behind each automatic flag and the
  limit they crossed, so a flag is auditable rather than a bare reason code.
* ``videos.fingerprint`` — sampled low-resolution frame signatures, so the same
  footage submitted again can be recognised.
* ``ix_videos_checksum`` — duplicate lookups by the uploaded bytes' hash.

Existing rows keep null evidence and fingerprints; nothing is backfilled.

Revision ID: a4c9e1f07d52
Revises: f3b8d2a61c47
Create Date: 2026-09-30
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "a4c9e1f07d52"
down_revision: str | None = "f3b8d2a61c47"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    with op.batch_alter_table("flags") as batch:
        batch.add_column(sa.Column("evidence", sa.JSON(), nullable=True))
    with op.batch_alter_table("videos") as batch:
        batch.add_column(sa.Column("fingerprint", sa.JSON(), nullable=True))
    op.create_index("ix_videos_checksum", "videos", ["checksum_sha256"])


def downgrade() -> None:
    op.drop_index("ix_videos_checksum", table_name="videos")
    with op.batch_alter_table("videos") as batch:
        batch.drop_column("fingerprint")
    with op.batch_alter_table("flags") as batch:
        batch.drop_column("evidence")

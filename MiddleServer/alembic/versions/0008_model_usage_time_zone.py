"""Add model usage batch time-zone metadata.

Revision ID: 0008_model_usage_time_zone
Revises: 0007_model_usage_refresh
Create Date: 2026-07-19
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0008_model_usage_time_zone"
down_revision: str | None = "0007_model_usage_refresh"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "model_usage_batches",
        sa.Column(
            "time_zone",
            sa.String(100),
            nullable=False,
            server_default=sa.text("'UTC'"),
        ),
    )
    op.add_column(
        "model_usage_batches",
        sa.Column(
            "time_zone_source",
            sa.String(20),
            nullable=False,
            server_default=sa.text("'SYSTEM_FALLBACK'"),
        ),
    )


def downgrade() -> None:
    op.drop_column("model_usage_batches", "time_zone_source")
    op.drop_column("model_usage_batches", "time_zone")

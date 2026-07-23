"""Add the safety monitoring enabled switch.

Revision ID: 0010_safety_monitoring_enabled
Revises: 0009_safety_monitoring_events
Create Date: 2026-07-22
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0010_safety_monitoring_enabled"
down_revision: str | None = "0009_safety_monitoring_events"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "safety_monitoring_configurations",
        sa.Column(
            "enabled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("1"),
        ),
    )


def downgrade() -> None:
    op.drop_column("safety_monitoring_configurations", "enabled")

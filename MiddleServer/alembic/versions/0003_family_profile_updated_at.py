"""Add profile sync timestamps and device credential expiry.

Revision ID: 0003_family_profile_updated_at
Revises: 0002_family_commands
Create Date: 2026-07-17
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0003_family_profile_updated_at"
down_revision: str | None = "0002_family_commands"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "family_accounts",
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.execute(
        sa.text("UPDATE family_accounts SET updated_at = created_at WHERE updated_at IS NULL")
    )
    op.add_column(
        "device_credentials",
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("device_credentials", "expires_at")
    op.drop_column("family_accounts", "updated_at")

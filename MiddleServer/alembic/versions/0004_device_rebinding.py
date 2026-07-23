"""Prevent duplicate active family and elder bindings.

Revision ID: 0004_device_rebinding
Revises: 0003_family_profile_updated_at
Create Date: 2026-07-17
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0004_device_rebinding"
down_revision: str | None = "0003_family_profile_updated_at"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_index(
        "uq_bindings_active_family_elder",
        "bindings",
        ["elder_id", "family_account_id"],
        unique=True,
        sqlite_where=sa.text("revoked_at IS NULL"),
    )


def downgrade() -> None:
    op.drop_index("uq_bindings_active_family_elder", table_name="bindings")

"""Add family-scoped reminder archives.

Revision ID: 0015_reminder_archives
Revises: 0014_reminder_completions
Create Date: 2026-08-12
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0015_reminder_archives"
down_revision: str | None = "0014_reminder_completions"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "reminder_archives",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("family_account_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("command_id", sa.String(36), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("archived_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["command_id"], ["commands.id"]),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "family_account_id",
            "command_id",
            name="uq_reminder_archives_family_command",
        ),
        sa.UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_reminder_archives_family_request",
        ),
    )
    op.create_index(
        "ix_reminder_archives_family_account_id",
        "reminder_archives",
        ["family_account_id"],
    )
    op.create_index("ix_reminder_archives_elder_id", "reminder_archives", ["elder_id"])
    op.create_index("ix_reminder_archives_command_id", "reminder_archives", ["command_id"])


def downgrade() -> None:
    op.drop_index("ix_reminder_archives_command_id", table_name="reminder_archives")
    op.drop_index("ix_reminder_archives_elder_id", table_name="reminder_archives")
    op.drop_index("ix_reminder_archives_family_account_id", table_name="reminder_archives")
    op.drop_table("reminder_archives")

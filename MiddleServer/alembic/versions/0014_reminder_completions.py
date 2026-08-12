"""Add reliable reminder completion records.

Revision ID: 0014_reminder_completions
Revises: 0013_voice_model_configuration
Create Date: 2026-08-11
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0014_reminder_completions"
down_revision: str | None = "0013_voice_model_configuration"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "command_completions",
        sa.Column("command_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("device_id", sa.String(36), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("reported_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("status = 'COMPLETED'", name="ck_command_completion_status"),
        sa.ForeignKeyConstraint(["command_id"], ["commands.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["device_id"], ["device_credentials.id"]),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.PrimaryKeyConstraint("command_id"),
    )
    op.create_index(
        "ix_command_completions_client_request_id",
        "command_completions",
        ["client_request_id"],
        unique=True,
    )
    op.create_index("ix_command_completions_device_id", "command_completions", ["device_id"])
    op.create_index("ix_command_completions_elder_id", "command_completions", ["elder_id"])


def downgrade() -> None:
    op.drop_index("ix_command_completions_elder_id", table_name="command_completions")
    op.drop_index("ix_command_completions_device_id", table_name="command_completions")
    op.drop_index(
        "ix_command_completions_client_request_id", table_name="command_completions"
    )
    op.drop_table("command_completions")

"""Create family command and command receipt tables.

Revision ID: 0002_family_commands
Revises: 0001_family_binding
Create Date: 2026-07-17
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0002_family_commands"
down_revision: str | None = "0001_family_binding"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "commands",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("server_sequence", sa.Integer(), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("binding_id", sa.String(36), nullable=False),
        sa.Column("actor_family_id", sa.String(36), nullable=False),
        sa.Column("command_type", sa.String(30), nullable=False),
        sa.Column("title", sa.String(40), nullable=True),
        sa.Column("content", sa.String(200), nullable=False),
        sa.Column("scheduled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("timezone", sa.String(100), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.Column("client_created_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["actor_family_id"], ["family_accounts.id"]),
        sa.ForeignKeyConstraint(["binding_id"], ["bindings.id"]),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "actor_family_id", "client_request_id", name="uq_commands_family_request"
        ),
    )
    op.create_index("ix_commands_actor_family_id", "commands", ["actor_family_id"])
    op.create_index("ix_commands_binding_id", "commands", ["binding_id"])
    op.create_index("ix_commands_elder_id", "commands", ["elder_id"])
    op.create_index(
        "ix_commands_family_elder_created",
        "commands",
        ["actor_family_id", "elder_id", "created_at"],
    )
    op.create_index("ix_commands_server_sequence", "commands", ["server_sequence"], unique=True)

    op.create_table(
        "command_receipts",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("command_id", sa.String(36), nullable=False),
        sa.Column("device_id", sa.String(36), nullable=False),
        sa.Column("ack_type", sa.String(20), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("stored_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("acked_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["command_id"], ["commands.id"]),
        sa.ForeignKeyConstraint(["device_id"], ["device_credentials.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("device_id", "command_id", "ack_type", name="uq_command_receipt_ack"),
        sa.UniqueConstraint(
            "device_id", "client_request_id", name="uq_command_receipt_device_request"
        ),
    )
    op.create_index("ix_command_receipts_command_id", "command_receipts", ["command_id"])
    op.create_index("ix_command_receipts_device_id", "command_receipts", ["device_id"])


def downgrade() -> None:
    op.drop_index("ix_command_receipts_device_id", table_name="command_receipts")
    op.drop_index("ix_command_receipts_command_id", table_name="command_receipts")
    op.drop_table("command_receipts")
    op.drop_index("ix_commands_server_sequence", table_name="commands")
    op.drop_index("ix_commands_family_elder_created", table_name="commands")
    op.drop_index("ix_commands_elder_id", table_name="commands")
    op.drop_index("ix_commands_binding_id", table_name="commands")
    op.drop_index("ix_commands_actor_family_id", table_name="commands")
    op.drop_table("commands")

"""Add idempotent family-triggered model usage refresh requests.

Revision ID: 0007_model_usage_refresh
Revises: 0006_context_and_model_usage
Create Date: 2026-07-19
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0007_model_usage_refresh"
down_revision: str | None = "0006_context_and_model_usage"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "model_usage_refresh_requests",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("family_account_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.Column("requested_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("device_online", sa.Boolean(), nullable=False),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_model_usage_refresh_family_client",
        ),
    )
    op.create_index(
        "ix_model_usage_refresh_requests_elder_id",
        "model_usage_refresh_requests",
        ["elder_id"],
    )
    op.create_index(
        "ix_model_usage_refresh_requests_family_account_id",
        "model_usage_refresh_requests",
        ["family_account_id"],
    )
    op.create_index(
        "ix_model_usage_refresh_family_elder_requested",
        "model_usage_refresh_requests",
        ["family_account_id", "elder_id", "requested_at"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_model_usage_refresh_family_elder_requested",
        table_name="model_usage_refresh_requests",
    )
    op.drop_index(
        "ix_model_usage_refresh_requests_family_account_id",
        table_name="model_usage_refresh_requests",
    )
    op.drop_index(
        "ix_model_usage_refresh_requests_elder_id",
        table_name="model_usage_refresh_requests",
    )
    op.drop_table("model_usage_refresh_requests")

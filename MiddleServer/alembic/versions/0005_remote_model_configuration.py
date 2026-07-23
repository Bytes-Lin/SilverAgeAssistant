"""Add non-sensitive remote model configuration.

Revision ID: 0005_remote_model_configuration
Revises: 0004_device_rebinding
Create Date: 2026-07-18
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0005_remote_model_configuration"
down_revision: str | None = "0004_device_rebinding"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "elder_model_configurations",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("schema_version", sa.Integer(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("base_url", sa.String(500), nullable=False),
        sa.Column("model", sa.String(120), nullable=False),
        sa.Column("dialect", sa.String(20), nullable=False),
        sa.Column("max_output_tokens", sa.Integer(), nullable=False),
        sa.Column("temperature", sa.Numeric(6, 4), nullable=False),
        sa.Column("top_p", sa.Numeric(6, 4), nullable=False),
        sa.Column("top_k", sa.Integer(), nullable=False),
        sa.Column("reasoning_enabled", sa.Boolean(), nullable=False),
        sa.Column("updated_by_family_id", sa.String(36), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_client_request_id", sa.String(36), nullable=False),
        sa.CheckConstraint("schema_version = 1", name="ck_model_config_schema_version"),
        sa.CheckConstraint("revision >= 1", name="ck_model_config_revision"),
        sa.CheckConstraint(
            "max_output_tokens >= 64 AND max_output_tokens <= 8192",
            name="ck_model_config_max_output_tokens",
        ),
        sa.CheckConstraint(
            "temperature >= 0 AND temperature <= 2",
            name="ck_model_config_temperature",
        ),
        sa.CheckConstraint("top_p >= 0 AND top_p <= 1", name="ck_model_config_top_p"),
        sa.CheckConstraint(
            "top_k >= 0 AND top_k <= 1000",
            name="ck_model_config_top_k",
        ),
        sa.CheckConstraint(
            "reasoning_enabled = 0",
            name="ck_model_config_reasoning_disabled",
        ),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["updated_by_family_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("elder_id", name="uq_elder_model_config_elder"),
    )
    op.create_table(
        "model_configuration_requests",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("family_account_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.Column("response_payload", sa.JSON(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_model_config_requests_family_client",
        ),
    )
    op.create_index(
        "ix_model_configuration_requests_elder_id",
        "model_configuration_requests",
        ["elder_id"],
    )
    op.create_index(
        "ix_model_configuration_requests_family_account_id",
        "model_configuration_requests",
        ["family_account_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_model_configuration_requests_family_account_id",
        table_name="model_configuration_requests",
    )
    op.drop_index(
        "ix_model_configuration_requests_elder_id",
        table_name="model_configuration_requests",
    )
    op.drop_table("model_configuration_requests")
    op.drop_table("elder_model_configurations")

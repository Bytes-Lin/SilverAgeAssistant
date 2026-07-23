"""Add context window configuration and aggregated model usage.

Revision ID: 0006_context_and_model_usage
Revises: 0005_remote_model_configuration
Create Date: 2026-07-19
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0006_context_and_model_usage"
down_revision: str | None = "0005_remote_model_configuration"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "elder_model_configurations",
        sa.Column(
            "context_window_tokens",
            sa.Integer(),
            nullable=False,
            server_default=sa.text("32768"),
        ),
    )
    op.create_table(
        "model_usage_batches",
        sa.Column("batch_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("device_id", sa.String(36), nullable=False),
        sa.Column("period_started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("period_ended_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["device_id"], ["device_credentials.id"]),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.PrimaryKeyConstraint("batch_id"),
    )
    op.create_index(
        "ix_model_usage_batches_device_id",
        "model_usage_batches",
        ["device_id"],
    )
    op.create_index(
        "ix_model_usage_batches_elder_id",
        "model_usage_batches",
        ["elder_id"],
    )
    op.create_index(
        "ix_model_usage_batches_elder_period",
        "model_usage_batches",
        ["elder_id", "period_started_at"],
    )
    op.create_table(
        "model_usage_items",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("batch_id", sa.String(36), nullable=False),
        sa.Column("modality", sa.String(10), nullable=False),
        sa.Column("provider", sa.String(80), nullable=False),
        sa.Column("model", sa.String(120), nullable=True),
        sa.Column("feature", sa.String(80), nullable=False),
        sa.Column("request_count", sa.BigInteger(), nullable=False),
        sa.Column("success_count", sa.BigInteger(), nullable=False),
        sa.Column("input_tokens", sa.BigInteger(), nullable=False),
        sa.Column("output_tokens", sa.BigInteger(), nullable=False),
        sa.Column("asr_audio_duration_ms", sa.BigInteger(), nullable=False),
        sa.Column("tts_character_count", sa.BigInteger(), nullable=False),
        sa.Column("tts_audio_duration_ms", sa.BigInteger(), nullable=False),
        sa.Column("contains_estimated_values", sa.Boolean(), nullable=False),
        sa.ForeignKeyConstraint(["batch_id"], ["model_usage_batches.batch_id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "batch_id",
            "modality",
            "provider",
            "model",
            "feature",
            name="uq_model_usage_items_batch_dimension",
        ),
    )
    op.create_index(
        "ix_model_usage_items_batch_id",
        "model_usage_items",
        ["batch_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_model_usage_items_batch_id", table_name="model_usage_items")
    op.drop_table("model_usage_items")
    op.drop_index(
        "ix_model_usage_batches_elder_period",
        table_name="model_usage_batches",
    )
    op.drop_index("ix_model_usage_batches_elder_id", table_name="model_usage_batches")
    op.drop_index("ix_model_usage_batches_device_id", table_name="model_usage_batches")
    op.drop_table("model_usage_batches")
    op.drop_column("elder_model_configurations", "context_window_tokens")

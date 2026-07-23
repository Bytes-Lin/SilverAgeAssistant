"""Add safety monitoring configuration and safety events.

Revision ID: 0009_safety_monitoring_events
Revises: 0008_model_usage_time_zone
Create Date: 2026-07-22
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0009_safety_monitoring_events"
down_revision: str | None = "0008_model_usage_time_zone"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "safety_monitoring_configurations",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("interval_minutes", sa.Integer(), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("updated_by_family_account_id", sa.String(36), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.CheckConstraint(
            "interval_minutes >= 1 AND interval_minutes <= 60",
            name="ck_safety_monitoring_interval",
        ),
        sa.CheckConstraint("revision >= 1", name="ck_safety_monitoring_revision"),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["updated_by_family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("elder_id", name="uq_safety_monitoring_config_elder"),
    )
    op.create_table(
        "safety_monitoring_configuration_requests",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("family_account_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.Column("response_payload", sa.JSON(), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_safety_config_requests_family_client",
        ),
    )
    op.create_index(
        op.f("ix_safety_monitoring_configuration_requests_elder_id"),
        "safety_monitoring_configuration_requests",
        ["elder_id"],
    )
    op.create_index(
        op.f("ix_safety_monitoring_configuration_requests_family_account_id"),
        "safety_monitoring_configuration_requests",
        ["family_account_id"],
    )
    op.create_table(
        "safety_events",
        sa.Column("event_id", sa.String(36), nullable=False),
        sa.Column("client_event_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("source_device_id", sa.String(36), nullable=False),
        sa.Column("server_sequence", sa.BigInteger(), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("event_type", sa.String(40), nullable=False),
        sa.Column("event_summary", sa.String(200), nullable=False),
        sa.Column("severity", sa.String(20), nullable=False),
        sa.Column("request_fingerprint", sa.String(64), nullable=False),
        sa.Column("acknowledged_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("acknowledged_by_family_account_id", sa.String(36), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["acknowledged_by_family_account_id"], ["family_accounts.id"]),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["source_device_id"], ["device_credentials.id"]),
        sa.PrimaryKeyConstraint("event_id"),
    )
    op.create_index(
        op.f("ix_safety_events_client_event_id"), "safety_events", ["client_event_id"], unique=True
    )
    op.create_index(op.f("ix_safety_events_elder_id"), "safety_events", ["elder_id"])
    op.create_index(
        op.f("ix_safety_events_server_sequence"), "safety_events", ["server_sequence"], unique=True
    )
    op.create_index(
        op.f("ix_safety_events_source_device_id"), "safety_events", ["source_device_id"]
    )
    op.create_index(
        "ix_safety_events_elder_occurred_sequence",
        "safety_events",
        ["elder_id", "occurred_at", "server_sequence"],
    )
    op.create_index(
        "ix_safety_events_device_created",
        "safety_events",
        ["source_device_id", "created_at"],
    )
    op.create_table(
        "safety_event_acknowledgement_requests",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("family_account_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("event_id", sa.String(36), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["event_id"], ["safety_events.event_id"]),
        sa.ForeignKeyConstraint(["family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_safety_event_ack_family_client",
        ),
    )
    op.create_index(
        op.f("ix_safety_event_acknowledgement_requests_elder_id"),
        "safety_event_acknowledgement_requests",
        ["elder_id"],
    )
    op.create_index(
        op.f("ix_safety_event_acknowledgement_requests_event_id"),
        "safety_event_acknowledgement_requests",
        ["event_id"],
    )
    op.create_index(
        op.f("ix_safety_event_acknowledgement_requests_family_account_id"),
        "safety_event_acknowledgement_requests",
        ["family_account_id"],
    )


def downgrade() -> None:
    op.drop_table("safety_event_acknowledgement_requests")
    op.drop_table("safety_events")
    op.drop_table("safety_monitoring_configuration_requests")
    op.drop_table("safety_monitoring_configurations")

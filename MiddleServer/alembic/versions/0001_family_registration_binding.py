"""Create family registration and binding tables.

Revision ID: 0001_family_binding
Revises:
Create Date: 2026-07-16
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0001_family_binding"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "family_accounts",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("display_name", sa.String(20), nullable=False),
        sa.Column("mobile_normalized", sa.String(20), nullable=False),
        sa.Column("mobile_masked", sa.String(20), nullable=False),
        sa.Column("mobile_verified_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_family_accounts_mobile_normalized",
        "family_accounts",
        ["mobile_normalized"],
        unique=True,
    )

    op.create_table(
        "elder_profiles",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("display_name", sa.String(20), nullable=False),
        sa.Column("mobile_normalized", sa.String(20), nullable=False),
        sa.Column("mobile_masked", sa.String(20), nullable=False),
        sa.Column("created_by_family_id", sa.String(36), nullable=False),
        sa.Column("relationship", sa.String(20), nullable=False),
        sa.Column("emergency_contact_requested", sa.Boolean(), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["created_by_family_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_elder_profiles_mobile_normalized", "elder_profiles", ["mobile_normalized"], unique=True
    )

    op.create_table(
        "binding_codes",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("family_account_id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("code_salt", sa.String(64), nullable=False),
        sa.Column("code_digest", sa.String(64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_binding_codes_elder_id", "binding_codes", ["elder_id"])
    op.create_index("ix_binding_codes_family_account_id", "binding_codes", ["family_account_id"])

    op.create_table(
        "bindings",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("family_account_id", sa.String(36), nullable=False),
        sa.Column("relationship", sa.String(20), nullable=False),
        sa.Column("permissions", sa.JSON(), nullable=False),
        sa.Column("audit_source", sa.String(40), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.ForeignKeyConstraint(["family_account_id"], ["family_accounts.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_bindings_elder_id", "bindings", ["elder_id"])
    op.create_index("ix_bindings_family_account_id", "bindings", ["family_account_id"])

    op.create_table(
        "device_credentials",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("external_device_id", sa.String(128), nullable=False),
        sa.Column("device_name", sa.String(80), nullable=True),
        sa.Column("elder_id", sa.String(36), nullable=False),
        sa.Column("binding_id", sa.String(36), nullable=False),
        sa.Column("credential_digest", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["binding_id"], ["bindings.id"]),
        sa.ForeignKeyConstraint(["elder_id"], ["elder_profiles.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_device_credentials_binding_id", "device_credentials", ["binding_id"])
    op.create_index(
        "ix_device_credentials_credential_digest",
        "device_credentials",
        ["credential_digest"],
        unique=True,
    )
    op.create_index("ix_device_credentials_elder_id", "device_credentials", ["elder_id"])
    op.create_index(
        "ix_device_credentials_external_device_id",
        "device_credentials",
        ["external_device_id"],
        unique=True,
    )

    op.create_table(
        "idempotency_records",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("actor_scope", sa.String(100), nullable=False),
        sa.Column("operation", sa.String(40), nullable=False),
        sa.Column("client_request_id", sa.String(36), nullable=False),
        sa.Column("resource_id", sa.String(36), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "actor_scope", "operation", "client_request_id", name="uq_idempotency_scope"
        ),
    )

    op.create_table(
        "binding_attempts",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("attempt_key", sa.String(64), nullable=False),
        sa.Column("attempted_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_binding_attempt_key_time", "binding_attempts", ["attempt_key", "attempted_at"]
    )

    op.create_table(
        "audit_logs",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("action", sa.String(50), nullable=False),
        sa.Column("actor_type", sa.String(20), nullable=False),
        sa.Column("actor_id", sa.String(36), nullable=True),
        sa.Column("resource_type", sa.String(30), nullable=False),
        sa.Column("resource_id", sa.String(36), nullable=True),
        sa.Column("details", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_audit_logs_action", "audit_logs", ["action"])


def downgrade() -> None:
    op.drop_index("ix_audit_logs_action", table_name="audit_logs")
    op.drop_table("audit_logs")
    op.drop_index("ix_binding_attempt_key_time", table_name="binding_attempts")
    op.drop_table("binding_attempts")
    op.drop_table("idempotency_records")
    op.drop_index("ix_device_credentials_external_device_id", table_name="device_credentials")
    op.drop_index("ix_device_credentials_elder_id", table_name="device_credentials")
    op.drop_index("ix_device_credentials_credential_digest", table_name="device_credentials")
    op.drop_index("ix_device_credentials_binding_id", table_name="device_credentials")
    op.drop_table("device_credentials")
    op.drop_index("ix_bindings_family_account_id", table_name="bindings")
    op.drop_index("ix_bindings_elder_id", table_name="bindings")
    op.drop_table("bindings")
    op.drop_index("ix_binding_codes_family_account_id", table_name="binding_codes")
    op.drop_index("ix_binding_codes_elder_id", table_name="binding_codes")
    op.drop_table("binding_codes")
    op.drop_index("ix_elder_profiles_mobile_normalized", table_name="elder_profiles")
    op.drop_table("elder_profiles")
    op.drop_index("ix_family_accounts_mobile_normalized", table_name="family_accounts")
    op.drop_table("family_accounts")

"""Add safety event resolution state and idempotency records.

Revision ID: 0012_safety_event_resolution
Revises: 0011_safety_event_images
Create Date: 2026-07-29
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0012_safety_event_resolution"
down_revision: str | None = "0011_safety_event_images"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table("safety_events") as batch_op:
        batch_op.add_column(sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True))
        batch_op.add_column(
            sa.Column("resolved_by_family_account_id", sa.String(36), nullable=True)
        )
        batch_op.create_foreign_key(
            "fk_safety_events_resolved_by_family_account_id",
            "family_accounts",
            ["resolved_by_family_account_id"],
            ["id"],
        )

    op.create_table(
        "safety_event_resolution_requests",
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
            name="uq_safety_event_resolution_family_client",
        ),
    )
    op.create_index(
        op.f("ix_safety_event_resolution_requests_elder_id"),
        "safety_event_resolution_requests",
        ["elder_id"],
    )
    op.create_index(
        op.f("ix_safety_event_resolution_requests_event_id"),
        "safety_event_resolution_requests",
        ["event_id"],
    )
    op.create_index(
        op.f("ix_safety_event_resolution_requests_family_account_id"),
        "safety_event_resolution_requests",
        ["family_account_id"],
    )


def downgrade() -> None:
    op.drop_table("safety_event_resolution_requests")
    with op.batch_alter_table("safety_events") as batch_op:
        batch_op.drop_constraint(
            "fk_safety_events_resolved_by_family_account_id",
            type_="foreignkey",
        )
        batch_op.drop_column("resolved_by_family_account_id")
        batch_op.drop_column("resolved_at")

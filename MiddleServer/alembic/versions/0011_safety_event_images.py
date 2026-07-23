"""Add private safety event image metadata.

Revision ID: 0011_safety_event_images
Revises: 0010_safety_monitoring_enabled
Create Date: 2026-07-22
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0011_safety_event_images"
down_revision: str | None = "0010_safety_monitoring_enabled"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "safety_event_images",
        sa.Column("id", sa.String(36), nullable=False),
        sa.Column("event_id", sa.String(36), nullable=False),
        sa.Column("content_type", sa.String(20), nullable=False),
        sa.Column("byte_size", sa.BigInteger(), nullable=False),
        sa.Column("content_sha256", sa.String(64), nullable=False),
        sa.Column("original_storage_name", sa.String(100), nullable=False),
        sa.Column("thumbnail_storage_name", sa.String(100), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["event_id"],
            ["safety_events.event_id"],
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("event_id"),
        sa.UniqueConstraint("original_storage_name"),
        sa.UniqueConstraint("thumbnail_storage_name"),
    )
    op.create_index(
        op.f("ix_safety_event_images_event_id"),
        "safety_event_images",
        ["event_id"],
        unique=True,
    )
    op.create_index(
        op.f("ix_safety_event_images_expires_at"),
        "safety_event_images",
        ["expires_at"],
    )


def downgrade() -> None:
    op.drop_table("safety_event_images")

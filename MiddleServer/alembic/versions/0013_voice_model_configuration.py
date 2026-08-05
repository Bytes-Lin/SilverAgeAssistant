"""Add non-sensitive ASR and TTS model configuration.

Revision ID: 0013_voice_model_configuration
Revises: 0012_safety_event_resolution
Create Date: 2026-08-04
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0013_voice_model_configuration"
down_revision: str | None = "0012_safety_event_resolution"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table("elder_model_configurations") as batch_op:
        batch_op.add_column(sa.Column("voice_websocket_url", sa.String(500), nullable=True))
        batch_op.add_column(sa.Column("voice_asr_model", sa.String(120), nullable=True))
        batch_op.add_column(sa.Column("voice_tts_model", sa.String(120), nullable=True))
        batch_op.add_column(sa.Column("voice_tts_voice", sa.String(120), nullable=True))
        batch_op.add_column(sa.Column("voice_tts_response_format", sa.String(10), nullable=True))
        batch_op.add_column(sa.Column("voice_tts_sample_rate", sa.Integer(), nullable=True))
        batch_op.add_column(sa.Column("voice_tts_volume", sa.Integer(), nullable=True))
        batch_op.add_column(sa.Column("voice_tts_rate", sa.Numeric(6, 4), nullable=True))
        batch_op.add_column(sa.Column("voice_tts_pitch", sa.Numeric(6, 4), nullable=True))
        batch_op.add_column(sa.Column("voice_language", sa.String(10), nullable=True))
        batch_op.create_check_constraint(
            "ck_model_config_voice_all_or_none",
            "(voice_websocket_url IS NULL AND voice_asr_model IS NULL "
            "AND voice_tts_model IS NULL AND voice_tts_voice IS NULL "
            "AND voice_tts_response_format IS NULL AND voice_tts_sample_rate IS NULL "
            "AND voice_tts_volume IS NULL AND voice_tts_rate IS NULL "
            "AND voice_tts_pitch IS NULL AND voice_language IS NULL) OR "
            "(voice_websocket_url IS NOT NULL AND voice_asr_model IS NOT NULL "
            "AND voice_tts_model IS NOT NULL AND voice_tts_voice IS NOT NULL "
            "AND voice_tts_response_format IS NOT NULL AND voice_tts_sample_rate IS NOT NULL "
            "AND voice_tts_volume IS NOT NULL AND voice_tts_rate IS NOT NULL "
            "AND voice_tts_pitch IS NOT NULL AND voice_language IS NOT NULL)",
        )
        batch_op.create_check_constraint(
            "ck_model_config_voice_format",
            "voice_tts_response_format IS NULL OR "
            "voice_tts_response_format IN ('pcm', 'wav', 'mp3', 'opus')",
        )
        batch_op.create_check_constraint(
            "ck_model_config_voice_sample_rate",
            "voice_tts_sample_rate IS NULL OR "
            "voice_tts_sample_rate IN (8000, 16000, 22050, 24000, 44100, 48000)",
        )
        batch_op.create_check_constraint(
            "ck_model_config_voice_volume",
            "voice_tts_volume IS NULL OR (voice_tts_volume >= 0 AND voice_tts_volume <= 100)",
        )
        batch_op.create_check_constraint(
            "ck_model_config_voice_rate",
            "voice_tts_rate IS NULL OR (voice_tts_rate >= 0.5 AND voice_tts_rate <= 2)",
        )
        batch_op.create_check_constraint(
            "ck_model_config_voice_pitch",
            "voice_tts_pitch IS NULL OR (voice_tts_pitch >= 0.5 AND voice_tts_pitch <= 2)",
        )
        batch_op.create_check_constraint(
            "ck_model_config_voice_language",
            "voice_language IS NULL OR voice_language = 'zh'",
        )


def downgrade() -> None:
    with op.batch_alter_table("elder_model_configurations") as batch_op:
        for constraint_name in (
            "ck_model_config_voice_language",
            "ck_model_config_voice_pitch",
            "ck_model_config_voice_rate",
            "ck_model_config_voice_volume",
            "ck_model_config_voice_sample_rate",
            "ck_model_config_voice_format",
            "ck_model_config_voice_all_or_none",
        ):
            batch_op.drop_constraint(constraint_name, type_="check")
        for column_name in (
            "voice_language",
            "voice_tts_pitch",
            "voice_tts_rate",
            "voice_tts_volume",
            "voice_tts_sample_rate",
            "voice_tts_response_format",
            "voice_tts_voice",
            "voice_tts_model",
            "voice_asr_model",
            "voice_websocket_url",
        ):
            batch_op.drop_column(column_name)

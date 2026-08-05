import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.core.security import utc_now
from app.models.base import Base


def new_uuid() -> str:
    return str(uuid.uuid4())


class FamilyAccount(Base):
    __tablename__ = "family_accounts"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    display_name: Mapped[str] = mapped_column(String(20))
    mobile_normalized: Mapped[str] = mapped_column(String(20), unique=True, index=True)
    mobile_masked: Mapped[str] = mapped_column(String(20))
    mobile_verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class ElderProfile(Base):
    __tablename__ = "elder_profiles"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    display_name: Mapped[str] = mapped_column(String(20))
    mobile_normalized: Mapped[str] = mapped_column(String(20), unique=True, index=True)
    mobile_masked: Mapped[str] = mapped_column(String(20))
    created_by_family_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"))
    relationship: Mapped[str] = mapped_column(String(20))
    emergency_contact_requested: Mapped[bool] = mapped_column(Boolean)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class BindingCode(Base):
    __tablename__ = "binding_codes"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    code_salt: Mapped[str] = mapped_column(String(64))
    code_digest: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Binding(Base):
    __tablename__ = "bindings"
    __table_args__ = (
        Index(
            "uq_bindings_active_family_elder",
            "elder_id",
            "family_account_id",
            unique=True,
            sqlite_where=text("revoked_at IS NULL"),
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    relationship: Mapped[str] = mapped_column(String(20))
    permissions: Mapped[list[str]] = mapped_column(JSON)
    audit_source: Mapped[str] = mapped_column(String(40))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class DeviceCredential(Base):
    __tablename__ = "device_credentials"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    external_device_id: Mapped[str] = mapped_column(String(128), unique=True, index=True)
    device_name: Mapped[str | None] = mapped_column(String(80))
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    binding_id: Mapped[str] = mapped_column(ForeignKey("bindings.id"), index=True)
    credential_digest: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class ElderModelConfiguration(Base):
    __tablename__ = "elder_model_configurations"
    __table_args__ = (
        CheckConstraint("schema_version = 1", name="ck_model_config_schema_version"),
        CheckConstraint("revision >= 1", name="ck_model_config_revision"),
        CheckConstraint(
            "max_output_tokens >= 64 AND max_output_tokens <= 8192",
            name="ck_model_config_max_output_tokens",
        ),
        CheckConstraint(
            "temperature >= 0 AND temperature <= 2",
            name="ck_model_config_temperature",
        ),
        CheckConstraint("top_p >= 0 AND top_p <= 1", name="ck_model_config_top_p"),
        CheckConstraint("top_k >= 0 AND top_k <= 1000", name="ck_model_config_top_k"),
        CheckConstraint(
            "reasoning_enabled = 0",
            name="ck_model_config_reasoning_disabled",
        ),
        CheckConstraint(
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
            name="ck_model_config_voice_all_or_none",
        ),
        CheckConstraint(
            "voice_tts_response_format IS NULL OR "
            "voice_tts_response_format IN ('pcm', 'wav', 'mp3', 'opus')",
            name="ck_model_config_voice_format",
        ),
        CheckConstraint(
            "voice_tts_sample_rate IS NULL OR "
            "voice_tts_sample_rate IN (8000, 16000, 22050, 24000, 44100, 48000)",
            name="ck_model_config_voice_sample_rate",
        ),
        CheckConstraint(
            "voice_tts_volume IS NULL OR (voice_tts_volume >= 0 AND voice_tts_volume <= 100)",
            name="ck_model_config_voice_volume",
        ),
        CheckConstraint(
            "voice_tts_rate IS NULL OR (voice_tts_rate >= 0.5 AND voice_tts_rate <= 2)",
            name="ck_model_config_voice_rate",
        ),
        CheckConstraint(
            "voice_tts_pitch IS NULL OR (voice_tts_pitch >= 0.5 AND voice_tts_pitch <= 2)",
            name="ck_model_config_voice_pitch",
        ),
        CheckConstraint(
            "voice_language IS NULL OR voice_language = 'zh'",
            name="ck_model_config_voice_language",
        ),
        UniqueConstraint("elder_id", name="uq_elder_model_config_elder"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"))
    schema_version: Mapped[int] = mapped_column(Integer)
    revision: Mapped[int] = mapped_column(Integer)
    base_url: Mapped[str] = mapped_column(String(500))
    model: Mapped[str] = mapped_column(String(120))
    dialect: Mapped[str] = mapped_column(String(20))
    context_window_tokens: Mapped[int] = mapped_column(Integer, server_default=text("32768"))
    max_output_tokens: Mapped[int] = mapped_column(Integer)
    temperature: Mapped[Decimal] = mapped_column(Numeric(6, 4))
    top_p: Mapped[Decimal] = mapped_column(Numeric(6, 4))
    top_k: Mapped[int] = mapped_column(Integer)
    reasoning_enabled: Mapped[bool] = mapped_column(Boolean)
    voice_websocket_url: Mapped[str | None] = mapped_column(String(500))
    voice_asr_model: Mapped[str | None] = mapped_column(String(120))
    voice_tts_model: Mapped[str | None] = mapped_column(String(120))
    voice_tts_voice: Mapped[str | None] = mapped_column(String(120))
    voice_tts_response_format: Mapped[str | None] = mapped_column(String(10))
    voice_tts_sample_rate: Mapped[int | None] = mapped_column(Integer)
    voice_tts_volume: Mapped[int | None] = mapped_column(Integer)
    voice_tts_rate: Mapped[Decimal | None] = mapped_column(Numeric(6, 4))
    voice_tts_pitch: Mapped[Decimal | None] = mapped_column(Numeric(6, 4))
    voice_language: Mapped[str | None] = mapped_column(String(10))
    updated_by_family_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    last_client_request_id: Mapped[str] = mapped_column(String(36))


class ModelConfigurationRequest(Base):
    __tablename__ = "model_configuration_requests"
    __table_args__ = (
        UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_model_config_requests_family_client",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    client_request_id: Mapped[str] = mapped_column(String(36))
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    response_payload: Mapped[dict[str, object]] = mapped_column(JSON)
    revision: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class ModelUsageBatch(Base):
    __tablename__ = "model_usage_batches"
    __table_args__ = (
        Index(
            "ix_model_usage_batches_elder_period",
            "elder_id",
            "period_started_at",
        ),
    )

    batch_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("device_credentials.id"), index=True)
    period_started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    period_ended_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    time_zone: Mapped[str] = mapped_column(String(100), server_default=text("'UTC'"))
    time_zone_source: Mapped[str] = mapped_column(
        String(20),
        server_default=text("'SYSTEM_FALLBACK'"),
    )
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class ModelUsageItem(Base):
    __tablename__ = "model_usage_items"
    __table_args__ = (
        UniqueConstraint(
            "batch_id",
            "modality",
            "provider",
            "model",
            "feature",
            name="uq_model_usage_items_batch_dimension",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    batch_id: Mapped[str] = mapped_column(ForeignKey("model_usage_batches.batch_id"), index=True)
    modality: Mapped[str] = mapped_column(String(10))
    provider: Mapped[str] = mapped_column(String(80))
    model: Mapped[str | None] = mapped_column(String(120))
    feature: Mapped[str] = mapped_column(String(80))
    request_count: Mapped[int] = mapped_column(BigInteger)
    success_count: Mapped[int] = mapped_column(BigInteger)
    input_tokens: Mapped[int] = mapped_column(BigInteger)
    output_tokens: Mapped[int] = mapped_column(BigInteger)
    asr_audio_duration_ms: Mapped[int] = mapped_column(BigInteger)
    tts_character_count: Mapped[int] = mapped_column(BigInteger)
    tts_audio_duration_ms: Mapped[int] = mapped_column(BigInteger)
    contains_estimated_values: Mapped[bool] = mapped_column(Boolean)


class ModelUsageRefreshRequest(Base):
    __tablename__ = "model_usage_refresh_requests"
    __table_args__ = (
        UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_model_usage_refresh_family_client",
        ),
        Index(
            "ix_model_usage_refresh_family_elder_requested",
            "family_account_id",
            "elder_id",
            "requested_at",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    client_request_id: Mapped[str] = mapped_column(String(36))
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    device_online: Mapped[bool] = mapped_column(Boolean)


class SafetyMonitoringConfiguration(Base):
    __tablename__ = "safety_monitoring_configurations"
    __table_args__ = (
        CheckConstraint(
            "interval_minutes >= 1 AND interval_minutes <= 60",
            name="ck_safety_monitoring_interval",
        ),
        CheckConstraint("revision >= 1", name="ck_safety_monitoring_revision"),
        UniqueConstraint("elder_id", name="uq_safety_monitoring_config_elder"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"))
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, server_default=text("1"))
    interval_minutes: Mapped[int] = mapped_column(Integer)
    revision: Mapped[int] = mapped_column(BigInteger)
    updated_by_family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    request_fingerprint: Mapped[str] = mapped_column(String(64))


class SafetyMonitoringConfigurationRequest(Base):
    __tablename__ = "safety_monitoring_configuration_requests"
    __table_args__ = (
        UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_safety_config_requests_family_client",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    client_request_id: Mapped[str] = mapped_column(String(36))
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    response_payload: Mapped[dict[str, object]] = mapped_column(JSON)
    revision: Mapped[int] = mapped_column(BigInteger)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class SafetyEvent(Base):
    __tablename__ = "safety_events"
    __table_args__ = (
        Index(
            "ix_safety_events_elder_occurred_sequence",
            "elder_id",
            "occurred_at",
            "server_sequence",
        ),
        Index("ix_safety_events_device_created", "source_device_id", "created_at"),
    )

    event_id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    client_event_id: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    source_device_id: Mapped[str] = mapped_column(ForeignKey("device_credentials.id"), index=True)
    server_sequence: Mapped[int] = mapped_column(BigInteger, unique=True, index=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    event_type: Mapped[str] = mapped_column(String(40))
    event_summary: Mapped[str] = mapped_column(String(200))
    severity: Mapped[str] = mapped_column(String(20))
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    acknowledged_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    acknowledged_by_family_account_id: Mapped[str | None] = mapped_column(
        ForeignKey("family_accounts.id")
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    resolved_by_family_account_id: Mapped[str | None] = mapped_column(
        ForeignKey("family_accounts.id")
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class SafetyEventImage(Base):
    __tablename__ = "safety_event_images"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    event_id: Mapped[str] = mapped_column(
        ForeignKey("safety_events.event_id", ondelete="CASCADE"), unique=True, index=True
    )
    content_type: Mapped[str] = mapped_column(String(20))
    byte_size: Mapped[int] = mapped_column(BigInteger)
    content_sha256: Mapped[str] = mapped_column(String(64))
    original_storage_name: Mapped[str] = mapped_column(String(100), unique=True)
    thumbnail_storage_name: Mapped[str] = mapped_column(String(100), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)


class SafetyEventAcknowledgementRequest(Base):
    __tablename__ = "safety_event_acknowledgement_requests"
    __table_args__ = (
        UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_safety_event_ack_family_client",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    event_id: Mapped[str] = mapped_column(ForeignKey("safety_events.event_id"), index=True)
    client_request_id: Mapped[str] = mapped_column(String(36))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class SafetyEventResolutionRequest(Base):
    __tablename__ = "safety_event_resolution_requests"
    __table_args__ = (
        UniqueConstraint(
            "family_account_id",
            "client_request_id",
            name="uq_safety_event_resolution_family_client",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    family_account_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    event_id: Mapped[str] = mapped_column(ForeignKey("safety_events.event_id"), index=True)
    client_request_id: Mapped[str] = mapped_column(String(36))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class Command(Base):
    __tablename__ = "commands"
    __table_args__ = (
        UniqueConstraint("actor_family_id", "client_request_id", name="uq_commands_family_request"),
        Index(
            "ix_commands_family_elder_created",
            "actor_family_id",
            "elder_id",
            "created_at",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    server_sequence: Mapped[int] = mapped_column(Integer, unique=True, index=True)
    elder_id: Mapped[str] = mapped_column(ForeignKey("elder_profiles.id"), index=True)
    binding_id: Mapped[str] = mapped_column(ForeignKey("bindings.id"), index=True)
    actor_family_id: Mapped[str] = mapped_column(ForeignKey("family_accounts.id"), index=True)
    command_type: Mapped[str] = mapped_column(String(30))
    title: Mapped[str | None] = mapped_column(String(40))
    content: Mapped[str] = mapped_column(String(200))
    scheduled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    timezone: Mapped[str] = mapped_column(String(100))
    client_request_id: Mapped[str] = mapped_column(String(36))
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    client_created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class CommandReceipt(Base):
    __tablename__ = "command_receipts"
    __table_args__ = (
        UniqueConstraint("device_id", "command_id", "ack_type", name="uq_command_receipt_ack"),
        UniqueConstraint(
            "device_id", "client_request_id", name="uq_command_receipt_device_request"
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    command_id: Mapped[str] = mapped_column(ForeignKey("commands.id"), index=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("device_credentials.id"), index=True)
    ack_type: Mapped[str] = mapped_column(String(20))
    client_request_id: Mapped[str] = mapped_column(String(36))
    stored_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    acked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_records"
    __table_args__ = (
        UniqueConstraint(
            "actor_scope", "operation", "client_request_id", name="uq_idempotency_scope"
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    actor_scope: Mapped[str] = mapped_column(String(100))
    operation: Mapped[str] = mapped_column(String(40))
    client_request_id: Mapped[str] = mapped_column(String(36))
    resource_id: Mapped[str] = mapped_column(String(36))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class BindingAttempt(Base):
    __tablename__ = "binding_attempts"
    __table_args__ = (Index("ix_binding_attempt_key_time", "attempt_key", "attempted_at"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    attempt_key: Mapped[str] = mapped_column(String(64))
    attempted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class AuditLog(Base):
    __tablename__ = "audit_logs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    action: Mapped[str] = mapped_column(String(50), index=True)
    actor_type: Mapped[str] = mapped_column(String(20))
    actor_id: Mapped[str | None] = mapped_column(String(36))
    resource_type: Mapped[str] = mapped_column(String(30))
    resource_id: Mapped[str | None] = mapped_column(String(36))
    details: Mapped[dict[str, str]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)

from datetime import date, datetime
from enum import StrEnum
from typing import Self
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, field_validator, model_validator

from app.schemas.common import StrictSchema

MAX_USAGE_COUNTER = 9_000_000_000_000_000


class ModelUsageModality(StrEnum):
    MLLM = "MLLM"
    ASR = "ASR"
    TTS = "TTS"


class ModelUsageTimeZoneSource(StrEnum):
    LOCATION = "LOCATION"
    SYSTEM_FALLBACK = "SYSTEM_FALLBACK"


class ModelUsageItemRequest(StrictSchema):
    modality: ModelUsageModality
    provider: str = Field(min_length=1, max_length=80)
    model: str | None = Field(default=None, max_length=120)
    feature: str = Field(min_length=1, max_length=80)
    request_count: int = Field(ge=0, le=MAX_USAGE_COUNTER)
    success_count: int = Field(ge=0, le=MAX_USAGE_COUNTER)
    input_tokens: int = Field(ge=0, le=MAX_USAGE_COUNTER)
    output_tokens: int = Field(ge=0, le=MAX_USAGE_COUNTER)
    asr_audio_duration_ms: int = Field(ge=0, le=MAX_USAGE_COUNTER)
    tts_character_count: int = Field(ge=0, le=MAX_USAGE_COUNTER)
    tts_audio_duration_ms: int = Field(ge=0, le=MAX_USAGE_COUNTER)
    contains_estimated_values: bool

    @field_validator("provider", "feature")
    @classmethod
    def normalize_required_dimension(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("usage dimension is empty")
        return normalized

    @field_validator("model")
    @classmethod
    def normalize_optional_model(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None

    @model_validator(mode="after")
    def validate_success_count(self) -> Self:
        if self.success_count > self.request_count:
            raise ValueError("success count must not exceed request count")
        return self


class ModelUsageBatchRequest(StrictSchema):
    batch_id: UUID
    period_started_at: datetime
    period_ended_at: datetime
    time_zone: str = Field(min_length=1, max_length=100)
    time_zone_source: ModelUsageTimeZoneSource
    items: list[ModelUsageItemRequest] = Field(min_length=1, max_length=100)

    @field_validator("time_zone")
    @classmethod
    def validate_time_zone(cls, value: str) -> str:
        normalized = value.strip()
        try:
            return ZoneInfo(normalized).key
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("time zone must be a valid IANA identifier") from exc


class ModelUsageBatchResponse(StrictSchema):
    batch_id: UUID
    accepted: bool
    received_at: datetime


class ModelUsageTotals(StrictSchema):
    input_tokens: int
    output_tokens: int
    mllm_request_count: int
    asr_request_count: int
    tts_request_count: int
    asr_audio_duration_ms: int
    tts_character_count: int
    tts_audio_duration_ms: int
    contains_estimated_values: bool


class ModelUsageSummaryResponse(StrictSchema):
    elder_id: UUID
    period_started_at: datetime
    period_ended_at: datetime
    totals: ModelUsageTotals
    last_reported_at: datetime | None


class DailyModelUsage(StrictSchema):
    date: date
    totals: ModelUsageTotals


class DailyModelUsageResponse(StrictSchema):
    elder_id: UUID
    period_started_on: date
    period_ended_on: date
    current_date: date
    timezone: str
    timezone_source: ModelUsageTimeZoneSource
    days: list[DailyModelUsage]
    last_reported_at: datetime | None


class ModelUsageRefreshRequest(StrictSchema):
    client_request_id: UUID


class ModelUsageRefreshResponse(StrictSchema):
    client_request_id: UUID
    requested_at: datetime
    device_online: bool

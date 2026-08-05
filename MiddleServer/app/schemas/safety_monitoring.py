from datetime import date, datetime
from enum import StrEnum
from typing import Self
from uuid import UUID

from pydantic import Field, field_validator, model_validator

from app.schemas.common import StrictSchema


class SafetyEventType(StrEnum):
    HEALTH_DISCOMFORT_REPORTED = "HEALTH_DISCOMFORT_REPORTED"
    FAMILY_REQUEST = "FAMILY_REQUEST"
    FALL_SUSPECTED = "FALL_SUSPECTED"
    UNCONSCIOUSNESS_SUSPECTED = "UNCONSCIOUSNESS_SUSPECTED"
    OTHER_ABNORMALITY = "OTHER_ABNORMALITY"
    GUI_ORDER_ASSISTANCE_REQUIRED = "GUI_ORDER_ASSISTANCE_REQUIRED"


class SafetyEventSeverity(StrEnum):
    GENERAL = "GENERAL"
    EMERGENCY = "EMERGENCY"


class SafetyMonitoringConfigurationUpdateRequest(StrictSchema):
    enabled: bool = Field(strict=True)
    interval_minutes: int = Field(ge=1, le=60)
    expected_revision: int | None = Field(default=None, ge=1)
    client_request_id: UUID


class SafetyMonitoringConfigurationResponse(StrictSchema):
    enabled: bool
    interval_minutes: int
    revision: int
    updated_at: datetime


class SafetyEventCreateRequest(StrictSchema):
    client_event_id: UUID
    occurred_at: datetime
    event_type: SafetyEventType
    event_summary: str = Field(min_length=1, max_length=200)
    severity: SafetyEventSeverity

    @field_validator("event_summary")
    @classmethod
    def normalize_summary(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("event summary is empty")
        if any(ord(character) < 32 and character not in "\t\n\r" for character in normalized):
            raise ValueError("event summary contains control characters")
        return normalized

    @model_validator(mode="after")
    def normalize_unverified_abnormality_wording(self) -> Self:
        unverified_types = {
            SafetyEventType.FALL_SUSPECTED,
            SafetyEventType.UNCONSCIOUSNESS_SUSPECTED,
            SafetyEventType.OTHER_ABNORMALITY,
        }
        if (
            self.event_type in unverified_types
            and "疑似" not in self.event_summary
            and "需要核实" not in self.event_summary
        ):
            prefix = "需要核实："
            maximum_detail_length = 200 - len(prefix)
            self.event_summary = prefix + self.event_summary[:maximum_detail_length].rstrip()
        return self


class SafetyEventResponse(StrictSchema):
    event_id: UUID
    server_sequence: int
    occurred_at: datetime
    event_type: SafetyEventType
    event_summary: str
    severity: SafetyEventSeverity
    acknowledged_at: datetime | None
    resolved_at: datetime | None
    created_at: datetime
    image_available: bool
    image_content_type: str | None
    image_byte_size: int | None


class SafetyEventsResponse(StrictSchema):
    current_date: date
    timezone: str
    events: list[SafetyEventResponse]
    synced_at: datetime


class SafetyEventAcknowledgementRequest(StrictSchema):
    client_request_id: UUID


class SafetyEventResolutionRequest(StrictSchema):
    client_request_id: UUID

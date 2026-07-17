from datetime import datetime
from enum import StrEnum
from uuid import UUID

from pydantic import Field

from app.schemas.common import StrictSchema


class CommandType(StrEnum):
    FAMILY_NOTIFICATION = "FAMILY_NOTIFICATION"
    REMOTE_REMINDER = "REMOTE_REMINDER"


class CommandStatus(StrEnum):
    PENDING = "PENDING"
    STORED = "STORED"


class AckType(StrEnum):
    STORED = "STORED"


class NotificationCreateRequest(StrictSchema):
    client_request_id: UUID
    content: str = Field(min_length=1, max_length=200)
    created_at: datetime


class ReminderCreateRequest(StrictSchema):
    client_request_id: UUID
    title: str = Field(min_length=1, max_length=40)
    content: str = Field(min_length=1, max_length=200)
    scheduled_at: datetime
    timezone: str = Field(min_length=1, max_length=100)


class CommandCreateResponse(StrictSchema):
    command_id: UUID
    elder_id: UUID
    command_type: CommandType
    server_sequence: int
    status: CommandStatus
    created_at: datetime


class CommandSender(StrictSchema):
    display_name: str


class PendingCommand(StrictSchema):
    command_id: UUID
    server_sequence: int
    elder_id: UUID
    command_type: CommandType
    title: str | None
    content: str
    scheduled_at: datetime | None
    timezone: str
    sender: CommandSender
    created_at: datetime


class PendingCommandsResponse(StrictSchema):
    commands: list[PendingCommand]
    next_after_sequence: int
    has_more: bool


class CommandAckRequest(StrictSchema):
    client_request_id: UUID
    ack_type: AckType
    stored_at: datetime


class CommandAckResponse(StrictSchema):
    command_id: UUID
    status: CommandStatus
    acked_at: datetime

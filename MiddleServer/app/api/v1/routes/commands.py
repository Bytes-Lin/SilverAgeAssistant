from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import (
    get_connection_manager,
    get_current_device,
    get_current_family,
    get_database,
    get_request_settings,
    get_session,
)
from app.core.config import Settings
from app.core.database import Database
from app.models import DeviceCredential, FamilyAccount
from app.schemas.command import (
    CommandAckRequest,
    CommandAckResponse,
    CommandCreateResponse,
    NotificationCreateRequest,
    PendingCommandsResponse,
    ReminderCreateRequest,
)
from app.services.commands import CommandNotifier, CommandService

router = APIRouter(tags=["family-commands"])


def service(
    session: AsyncSession,
    settings: Settings,
    database: Database,
    notifier: CommandNotifier,
) -> CommandService:
    return CommandService(session, settings, database.command_lock, notifier)


@router.post(
    "/elders/{elder_id}/commands/notifications",
    response_model=CommandCreateResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_notification(
    elder_id: UUID,
    payload: NotificationCreateRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    notifier: CommandNotifier = Depends(get_connection_manager),
) -> CommandCreateResponse:
    return await service(session, settings, database, notifier).create_notification(
        family, str(elder_id), payload, idempotency_key
    )


@router.post(
    "/elders/{elder_id}/commands/reminders",
    response_model=CommandCreateResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_reminder(
    elder_id: UUID,
    payload: ReminderCreateRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    notifier: CommandNotifier = Depends(get_connection_manager),
) -> CommandCreateResponse:
    return await service(session, settings, database, notifier).create_reminder(
        family, str(elder_id), payload, idempotency_key
    )


@router.get("/commands/pending", response_model=PendingCommandsResponse)
async def list_pending_commands(
    after_sequence: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=100),
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    notifier: CommandNotifier = Depends(get_connection_manager),
) -> PendingCommandsResponse:
    return await service(session, settings, database, notifier).list_pending(
        device, after_sequence, limit
    )


@router.post("/commands/{command_id}/ack", response_model=CommandAckResponse)
async def acknowledge_command(
    command_id: UUID,
    payload: CommandAckRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    notifier: CommandNotifier = Depends(get_connection_manager),
) -> CommandAckResponse:
    return await service(session, settings, database, notifier).acknowledge(
        device, str(command_id), payload, idempotency_key
    )

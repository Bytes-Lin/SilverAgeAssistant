from typing import Annotated, Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Query, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import (
    get_connection_manager,
    get_current_device,
    get_current_family,
    get_database,
    get_request_settings,
    get_safety_image_storage,
    get_session,
)
from app.core.config import Settings
from app.core.database import Database
from app.core.errors import ApiError
from app.models import DeviceCredential, FamilyAccount
from app.schemas.common import ErrorResponse
from app.schemas.safety_monitoring import (
    SafetyEventAcknowledgementRequest,
    SafetyEventCreateRequest,
    SafetyEventResponse,
    SafetyEventsTodayResponse,
    SafetyMonitoringConfigurationResponse,
    SafetyMonitoringConfigurationUpdateRequest,
)
from app.services.safety_image_storage import SafetyImageStorage
from app.services.safety_monitoring import SafetyMonitoringService
from app.websocket.manager import ConnectionManager

router = APIRouter(tags=["safety-monitoring"])

error_responses: dict[int | str, dict[str, Any]] = {
    400: {"model": ErrorResponse, "description": "Safety monitoring request is invalid"},
    401: {"model": ErrorResponse, "description": "Authentication is required"},
    403: {"model": ErrorResponse, "description": "Safety monitoring access is forbidden"},
    404: {"model": ErrorResponse, "description": "Configuration or safety event not found"},
    409: {"model": ErrorResponse, "description": "Revision or idempotency conflict"},
    410: {"model": ErrorResponse, "description": "The family binding is revoked"},
    413: {"model": ErrorResponse, "description": "Safety event image is too large"},
    429: {"model": ErrorResponse, "description": "Safety event rate limit exceeded"},
}


def service(
    session: AsyncSession,
    database: Database,
    settings: Settings,
    manager: ConnectionManager,
    image_storage: SafetyImageStorage,
) -> SafetyMonitoringService:
    return SafetyMonitoringService(
        session,
        database.safety_monitoring_lock,
        settings,
        manager,
        image_storage,
    )


@router.get(
    "/elders/{elder_id}/safety-monitoring/config",
    response_model=SafetyMonitoringConfigurationResponse,
    responses=error_responses,
    summary="Get an elder's safety monitoring interval as family",
)
async def get_family_safety_configuration(
    elder_id: UUID,
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> SafetyMonitoringConfigurationResponse:
    result = await service(
        session, database, settings, manager, image_storage
    ).get_configuration_for_family(family, str(elder_id))
    response.headers["Cache-Control"] = "no-store"
    return result


@router.put(
    "/elders/{elder_id}/safety-monitoring/config",
    response_model=SafetyMonitoringConfigurationResponse,
    responses=error_responses,
    summary="Create or update an elder's safety monitoring interval",
)
async def update_family_safety_configuration(
    elder_id: UUID,
    payload: SafetyMonitoringConfigurationUpdateRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> SafetyMonitoringConfigurationResponse:
    result = await service(
        session, database, settings, manager, image_storage
    ).update_configuration(family, str(elder_id), payload, idempotency_key)
    response.headers["Cache-Control"] = "no-store"
    return result


@router.get(
    "/devices/me/safety-monitoring/config",
    response_model=SafetyMonitoringConfigurationResponse,
    responses=error_responses,
    summary="Get the bound elder device's safety monitoring interval",
)
async def get_device_safety_configuration(
    response: Response,
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> SafetyMonitoringConfigurationResponse:
    result = await service(
        session, database, settings, manager, image_storage
    ).get_configuration_for_device(device)
    response.headers["Cache-Control"] = "no-store"
    return result


@router.post(
    "/devices/me/safety-events",
    response_model=SafetyEventResponse,
    status_code=status.HTTP_201_CREATED,
    responses=error_responses,
    summary="Persist a structured safety event from an elder device",
)
async def create_safety_event(
    payload: SafetyEventCreateRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    response: Response,
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> SafetyEventResponse:
    result = await service(session, database, settings, manager, image_storage).create_event(
        device, payload, idempotency_key
    )
    response.headers["Cache-Control"] = "no-store"
    return result


@router.get(
    "/elders/{elder_id}/safety-events",
    response_model=SafetyEventsTodayResponse,
    responses=error_responses,
    summary="Get today's safety events for an elder",
)
async def get_today_safety_events(
    elder_id: UUID,
    response: Response,
    scope: Annotated[Literal["today"], Query()] = "today",
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> SafetyEventsTodayResponse:
    del scope
    result = await service(session, database, settings, manager, image_storage).get_today_events(
        family, str(elder_id)
    )
    response.headers["Cache-Control"] = "no-store"
    return result


@router.post(
    "/elders/{elder_id}/safety-events/{event_id}/acknowledge",
    response_model=SafetyEventResponse,
    responses=error_responses,
    summary="Acknowledge a safety event without changing the first acknowledgement",
)
async def acknowledge_safety_event(
    elder_id: UUID,
    event_id: UUID,
    payload: SafetyEventAcknowledgementRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> SafetyEventResponse:
    result = await service(session, database, settings, manager, image_storage).acknowledge_event(
        family,
        str(elder_id),
        str(event_id),
        payload,
        idempotency_key,
    )
    response.headers["Cache-Control"] = "no-store"
    return result


@router.put(
    "/devices/me/safety-events/{event_id}/image",
    response_model=SafetyEventResponse,
    responses=error_responses,
    summary="Attach a private JPEG or PNG to a monitoring safety event",
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "image/jpeg": {"schema": {"type": "string", "format": "binary"}},
                "image/png": {"schema": {"type": "string", "format": "binary"}},
            },
        }
    },
)
async def upload_safety_event_image(
    event_id: UUID,
    request: Request,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> SafetyEventResponse:
    data = await _read_limited_image_body(request, settings.safety_image_max_bytes)
    content_type = request.headers.get("Content-Type", "").partition(";")[0].strip().lower()
    return await service(session, database, settings, manager, image_storage).upload_event_image(
        device,
        str(event_id),
        content_type,
        data,
        idempotency_key,
    )


@router.get(
    "/elders/{elder_id}/safety-events/{event_id}/image",
    responses={
        **error_responses,
        200: {
            "content": {
                "image/jpeg": {"schema": {"type": "string", "format": "binary"}},
                "image/png": {"schema": {"type": "string", "format": "binary"}},
            },
            "description": "Private safety event image bytes",
        },
    },
    summary="Read a private safety event thumbnail or original image",
)
async def get_safety_event_image(
    elder_id: UUID,
    event_id: UUID,
    variant: Annotated[Literal["thumbnail", "original"], Query()],
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
    settings: Settings = Depends(get_request_settings),
    manager: ConnectionManager = Depends(get_connection_manager),
    image_storage: SafetyImageStorage = Depends(get_safety_image_storage),
) -> Response:
    image = await service(session, database, settings, manager, image_storage).download_event_image(
        family,
        str(elder_id),
        str(event_id),
        thumbnail=variant == "thumbnail",
    )
    return Response(
        content=image.content,
        media_type=image.content_type,
        headers={
            "Cache-Control": "private, no-store",
            "X-Content-Type-Options": "nosniff",
            "Content-Disposition": "inline",
        },
    )


async def _read_limited_image_body(request: Request, maximum_bytes: int) -> bytes:
    raw_length = request.headers.get("Content-Length")
    if raw_length is not None:
        try:
            if int(raw_length) > maximum_bytes:
                raise ApiError(
                    413,
                    "SAFETY_EVENT_IMAGE_TOO_LARGE",
                    "事件图像超过 8 MiB 限制",
                )
        except ValueError:
            raise ApiError(
                400,
                "INVALID_SAFETY_EVENT_IMAGE",
                "Content-Length 不正确",
            ) from None
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > maximum_bytes:
            raise ApiError(
                413,
                "SAFETY_EVENT_IMAGE_TOO_LARGE",
                "事件图像超过 8 MiB 限制",
            )
        chunks.append(chunk)
    return b"".join(chunks)

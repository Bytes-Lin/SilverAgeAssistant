from fastapi import APIRouter, Depends, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import (
    get_auth_context,
    get_current_family,
    get_database,
    get_request_settings,
    get_session,
)
from app.core.config import Settings
from app.core.database import Database
from app.models import FamilyAccount
from app.schemas.binding import (
    BindingCodeCreateRequest,
    BindingCodeResponse,
    BindingListResponse,
    DeviceBindRequest,
    DeviceBindResponse,
    ElderCreateRequest,
    ElderResponse,
)
from app.services.family_binding import AuthContext, FamilyBindingService

router = APIRouter(tags=["family-binding"])


def service(
    session: AsyncSession,
    settings: Settings,
    database: Database,
) -> FamilyBindingService:
    return FamilyBindingService(session, settings, database.binding_lock)


@router.post("/elders", response_model=ElderResponse, status_code=status.HTTP_201_CREATED)
async def create_elder(
    payload: ElderCreateRequest,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
) -> ElderResponse:
    return await service(session, settings, database).create_elder(family, payload)


@router.post(
    "/bindings/codes",
    response_model=BindingCodeResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_binding_code(
    payload: BindingCodeCreateRequest,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
) -> BindingCodeResponse:
    return await service(session, settings, database).create_binding_code(family, payload)


@router.delete("/bindings/codes/{elder_id}", status_code=status.HTTP_204_NO_CONTENT)
async def revoke_binding_codes(
    elder_id: str,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
) -> Response:
    await service(session, settings, database).revoke_binding_codes(family, elder_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/devices/bind",
    response_model=DeviceBindResponse,
    status_code=status.HTTP_201_CREATED,
)
async def bind_device(
    payload: DeviceBindRequest,
    request: Request,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
) -> DeviceBindResponse:
    network_source = request.client.host if request.client else "unknown"
    return await service(session, settings, database).bind_device(payload, network_source)


@router.get("/bindings", response_model=BindingListResponse)
async def list_bindings(
    auth: AuthContext = Depends(get_auth_context),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
) -> BindingListResponse:
    return await service(session, settings, database).list_bindings(auth)


@router.delete("/bindings/{binding_id}", status_code=status.HTTP_204_NO_CONTENT)
async def revoke_binding(
    binding_id: str,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
) -> Response:
    await service(session, settings, database).revoke_binding(family, binding_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)

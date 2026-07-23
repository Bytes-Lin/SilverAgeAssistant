from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import (
    get_current_device,
    get_current_family,
    get_database,
    get_session,
)
from app.core.database import Database
from app.models import DeviceCredential, FamilyAccount
from app.schemas.common import ErrorResponse
from app.schemas.model_configuration import (
    ModelConfigurationResponse,
    ModelConfigurationUpdateRequest,
)
from app.services.model_configuration import ModelConfigurationService

router = APIRouter(tags=["model-configuration"])

error_responses: dict[int | str, dict[str, Any]] = {
    400: {"model": ErrorResponse, "description": "Model configuration is invalid"},
    401: {"model": ErrorResponse, "description": "Authentication is required"},
    403: {"model": ErrorResponse, "description": "Model configuration access is forbidden"},
    404: {"model": ErrorResponse, "description": "Model configuration is not set"},
    409: {"model": ErrorResponse, "description": "Revision or idempotency conflict"},
    410: {"model": ErrorResponse, "description": "The family binding is revoked"},
}


def service(session: AsyncSession, database: Database) -> ModelConfigurationService:
    return ModelConfigurationService(session, database.model_configuration_lock)


@router.get(
    "/elders/{elder_id}/model-config",
    response_model=ModelConfigurationResponse,
    responses=error_responses,
    summary="Get an elder's non-sensitive model configuration as family",
)
async def get_family_model_configuration(
    elder_id: UUID,
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
) -> ModelConfigurationResponse:
    result = await service(session, database).get_for_family(family, str(elder_id))
    response.headers["Cache-Control"] = "no-store"
    return result


@router.put(
    "/elders/{elder_id}/model-config",
    response_model=ModelConfigurationResponse,
    responses=error_responses,
    summary="Create or update an elder's non-sensitive model configuration",
)
async def update_family_model_configuration(
    elder_id: UUID,
    payload: ModelConfigurationUpdateRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
) -> ModelConfigurationResponse:
    result = await service(session, database).update_for_family(
        family,
        str(elder_id),
        payload,
        idempotency_key,
    )
    response.headers["Cache-Control"] = "no-store"
    return result


@router.get(
    "/devices/me/model-config",
    response_model=ModelConfigurationResponse,
    responses=error_responses,
    summary="Get the bound elder device's non-sensitive model configuration",
)
async def get_device_model_configuration(
    response: Response,
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
    database: Database = Depends(get_database),
) -> ModelConfigurationResponse:
    result = await service(session, database).get_for_device(device)
    response.headers["Cache-Control"] = "no-store"
    return result

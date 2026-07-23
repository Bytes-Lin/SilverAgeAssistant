from datetime import datetime
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Query, Response, status
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
from app.schemas.common import ErrorResponse
from app.schemas.model_usage import (
    DailyModelUsageResponse,
    ModelUsageBatchRequest,
    ModelUsageBatchResponse,
    ModelUsageRefreshRequest,
    ModelUsageRefreshResponse,
    ModelUsageSummaryResponse,
)
from app.services.model_usage import ModelUsageService
from app.websocket.manager import ConnectionManager

router = APIRouter(tags=["model-usage"])

upload_error_responses: dict[int | str, dict[str, Any]] = {
    400: {"model": ErrorResponse, "description": "Usage batch is invalid"},
    401: {"model": ErrorResponse, "description": "Device authentication is required"},
    409: {"model": ErrorResponse, "description": "Idempotency conflict"},
    413: {"model": ErrorResponse, "description": "Usage batch is too large"},
}

query_error_responses: dict[int | str, dict[str, Any]] = {
    400: {"model": ErrorResponse, "description": "Usage query range is invalid"},
    401: {"model": ErrorResponse, "description": "Family authentication is required"},
    403: {"model": ErrorResponse, "description": "Active family binding is required"},
}

refresh_error_responses: dict[int | str, dict[str, Any]] = {
    400: {"model": ErrorResponse, "description": "Refresh request is invalid"},
    401: {"model": ErrorResponse, "description": "Family authentication is required"},
    403: {"model": ErrorResponse, "description": "Active family binding is required"},
    409: {"model": ErrorResponse, "description": "Idempotency conflict"},
    429: {"model": ErrorResponse, "description": "Usage refresh is rate limited"},
}


def service(
    session: AsyncSession,
    settings: Settings,
    database: Database,
    connection_manager: ConnectionManager,
) -> ModelUsageService:
    return ModelUsageService(
        session,
        settings,
        database.model_usage_lock,
        connection_manager,
    )


@router.post(
    "/model-usage/batches",
    response_model=ModelUsageBatchResponse,
    status_code=status.HTTP_201_CREATED,
    responses=upload_error_responses,
    summary="Accept an aggregated model usage batch from an elder device",
)
async def create_model_usage_batch(
    payload: ModelUsageBatchRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    response: Response,
    device: DeviceCredential = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    connection_manager: ConnectionManager = Depends(get_connection_manager),
) -> ModelUsageBatchResponse:
    result = await service(
        session,
        settings,
        database,
        connection_manager,
    ).accept_batch(
        device,
        payload,
        idempotency_key,
    )
    response.headers["Cache-Control"] = "no-store"
    return result


@router.get(
    "/elders/{elder_id}/model-usage",
    response_model=ModelUsageSummaryResponse,
    responses=query_error_responses,
    summary="Get aggregated model usage for an actively bound elder",
)
async def get_model_usage_summary(
    elder_id: UUID,
    period_started_at: Annotated[datetime, Query(alias="from")],
    period_ended_at: Annotated[datetime, Query(alias="to")],
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    connection_manager: ConnectionManager = Depends(get_connection_manager),
) -> ModelUsageSummaryResponse:
    result = await service(
        session,
        settings,
        database,
        connection_manager,
    ).summarize_for_family(
        family,
        str(elder_id),
        period_started_at,
        period_ended_at,
    )
    response.headers["Cache-Control"] = "no-store"
    return result


@router.get(
    "/elders/{elder_id}/model-usage/daily",
    response_model=DailyModelUsageResponse,
    responses=query_error_responses,
    summary="Get the elder's current local month as daily usage buckets",
)
async def get_daily_model_usage(
    elder_id: UUID,
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    connection_manager: ConnectionManager = Depends(get_connection_manager),
) -> DailyModelUsageResponse:
    result = await service(
        session,
        settings,
        database,
        connection_manager,
    ).daily_for_family(
        family,
        str(elder_id),
    )
    response.headers["Cache-Control"] = "no-store"
    return result


@router.post(
    "/elders/{elder_id}/model-usage/refresh",
    response_model=ModelUsageRefreshResponse,
    responses=refresh_error_responses,
    summary="Ask an online elder device to report pending model usage now",
)
async def refresh_model_usage(
    elder_id: UUID,
    payload: ModelUsageRefreshRequest,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
    response: Response,
    family: FamilyAccount = Depends(get_current_family),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
    database: Database = Depends(get_database),
    connection_manager: ConnectionManager = Depends(get_connection_manager),
) -> ModelUsageRefreshResponse:
    result = await service(
        session,
        settings,
        database,
        connection_manager,
    ).request_current_usage(
        family,
        str(elder_id),
        payload,
        idempotency_key,
    )
    response.headers["Cache-Control"] = "no-store"
    return result

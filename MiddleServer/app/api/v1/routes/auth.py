from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_request_settings, get_session
from app.core.config import Settings
from app.schemas.auth import (
    AccessTokenResponse,
    FamilyRegisterRequest,
    FamilyRegisterResponse,
    RefreshRequest,
)
from app.services.auth import AuthService

router = APIRouter(prefix="/auth", tags=["family-auth"])


@router.post(
    "/family/register",
    response_model=FamilyRegisterResponse,
    status_code=status.HTTP_201_CREATED,
)
async def register_family(
    payload: FamilyRegisterRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> FamilyRegisterResponse:
    return await AuthService(session, settings).register_family(payload)


@router.post("/refresh", response_model=AccessTokenResponse)
async def refresh_family_access_token(
    payload: RefreshRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> AccessTokenResponse:
    return await AuthService(session, settings).refresh_access_token(payload.refresh_token)

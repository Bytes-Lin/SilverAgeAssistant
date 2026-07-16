import hmac

from fastapi import APIRouter, Depends, Header, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_request_settings, get_session
from app.core.config import Settings
from app.core.errors import ApiError
from app.schemas.auth import (
    AccessTokenResponse,
    DevelopmentVerificationRequest,
    DevelopmentVerificationResponse,
    FamilyRegisterRequest,
    FamilyRegisterResponse,
    RefreshRequest,
)
from app.services.auth import AuthService

router = APIRouter(prefix="/auth", tags=["family-auth"])


@router.post(
    "/family/dev-verification-token",
    response_model=DevelopmentVerificationResponse,
    summary="开发环境签发手机号验证结果",
)
async def create_development_verification_token(
    payload: DevelopmentVerificationRequest,
    development_key: str | None = Header(default=None, alias="X-Development-Verification-Key"),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> DevelopmentVerificationResponse:
    if (
        not settings.dev_verification_enabled
        or development_key is None
        or not hmac.compare_digest(development_key, settings.dev_verification_key)
    ):
        raise ApiError(404, "NOT_FOUND", "接口不存在")
    return AuthService(session, settings).issue_development_verification(payload.mobile_number)


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

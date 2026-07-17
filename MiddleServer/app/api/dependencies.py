from collections.abc import AsyncIterator
from typing import cast

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.requests import HTTPConnection

from app.core.config import Settings
from app.core.database import Database
from app.core.errors import ApiError
from app.core.security import decode_jwt, keyed_digest
from app.models import DeviceCredential, FamilyAccount
from app.repositories.family_binding import FamilyBindingRepository
from app.services.family_binding import AuthContext
from app.websocket.manager import ConnectionManager

bearer_scheme = HTTPBearer(auto_error=False)


def get_database(connection: HTTPConnection) -> Database:
    return cast(Database, connection.app.state.database)


def get_request_settings(connection: HTTPConnection) -> Settings:
    return cast(Settings, connection.app.state.settings)


def get_connection_manager(connection: HTTPConnection) -> ConnectionManager:
    return cast(ConnectionManager, connection.app.state.connection_manager)


async def get_session(database: Database = Depends(get_database)) -> AsyncIterator[AsyncSession]:
    async for session in database.session():
        yield session


async def get_current_family(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> FamilyAccount:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "需要家属身份认证")
    claims = decode_jwt(settings, credentials.credentials, "family_access")
    family = await FamilyBindingRepository(session).get_family(claims["sub"])
    await session.commit()
    if family is None or not family.is_active:
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "认证信息无效或已过期")
    return family


async def get_auth_context(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> AuthContext:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "需要认证")
    token = credentials.credentials
    try:
        claims = decode_jwt(settings, token, "family_access")
    except ApiError as exc:
        digest = keyed_digest(settings.security_secret, "device-credential", token)
        device = await FamilyBindingRepository(session).get_device_by_digest(digest)
        await session.commit()
        if device is None:
            raise ApiError(401, "AUTHENTICATION_REQUIRED", "认证信息无效或已过期") from exc
        return AuthContext(kind="device", principal_id=device.id, device=device)

    family = await FamilyBindingRepository(session).get_family(claims["sub"])
    await session.commit()
    if family is None or not family.is_active:
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "认证信息无效或已过期")
    return AuthContext(kind="family", principal_id=family.id)


async def get_current_device(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_request_settings),
) -> DeviceCredential:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "需要老人设备认证")
    digest = keyed_digest(settings.security_secret, "device-credential", credentials.credentials)
    device = await FamilyBindingRepository(session).get_device_by_digest(digest)
    await session.commit()
    if device is None:
        raise ApiError(401, "AUTHENTICATION_REQUIRED", "认证信息无效或已过期")
    return device

from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import ApiError
from app.core.security import (
    create_jwt,
    decode_jwt,
    keyed_digest,
    mask_mobile,
    normalize_mainland_mobile,
)
from app.repositories.family_binding import FamilyBindingRepository
from app.schemas.auth import (
    AccessTokenResponse,
    FamilyRegisterRequest,
    FamilyRegisterResponse,
)


class AuthService:
    def __init__(self, session: AsyncSession, settings: Settings) -> None:
        self.session = session
        self.settings = settings
        self.repository = FamilyBindingRepository(session)

    async def register_family(self, request: FamilyRegisterRequest) -> FamilyRegisterResponse:
        normalized = normalize_mainland_mobile(request.mobile_number)
        request_id = str(request.client_request_id)
        actor_scope = "mobile:" + keyed_digest(
            self.settings.security_secret, "mobile-actor", normalized
        )
        async with self.session.begin():
            idempotency = await self.repository.get_idempotency(
                actor_scope, "FAMILY_REGISTER", request_id
            )
            if idempotency:
                family = await self.repository.get_family(idempotency.resource_id)
                if family is None:
                    raise ApiError(503, "SERVICE_TEMPORARILY_UNAVAILABLE", "服务暂时不可用")
            else:
                existing = await self.repository.get_family_by_mobile(normalized)
                if existing:
                    raise ApiError(409, "FAMILY_ALREADY_REGISTERED", "该手机号已注册")
                family = await self.repository.create_family(
                    display_name=request.display_name.strip(),
                    mobile_normalized=normalized,
                    mobile_masked=mask_mobile(normalized),
                    verified_at=None,
                )
                self.repository.add_idempotency(
                    actor_scope, "FAMILY_REGISTER", request_id, family.id
                )
                self.repository.add_audit(
                    "FAMILY_REGISTERED", "FAMILY", family.id, "FAMILY_ACCOUNT", family.id
                )
        return self._family_registration_response(
            family.id, family.display_name, family.mobile_masked
        )

    def _family_registration_response(
        self, family_id: str, display_name: str, mobile_masked: str
    ) -> FamilyRegisterResponse:
        access_token, access_expires_at = create_jwt(
            self.settings,
            family_id,
            "family_access",
            self.settings.access_token_ttl_seconds,
        )
        refresh_token, _ = create_jwt(
            self.settings,
            family_id,
            "family_refresh",
            self.settings.refresh_token_ttl_seconds,
        )
        return FamilyRegisterResponse(
            family_account_id=UUID(family_id),
            display_name=display_name,
            family_mobile_masked=mobile_masked,
            access_token=access_token,
            refresh_token=refresh_token,
            access_token_expires_at=access_expires_at,
        )

    async def refresh_access_token(self, refresh_token: str) -> AccessTokenResponse:
        claims = decode_jwt(self.settings, refresh_token, "family_refresh")
        family = await self.repository.get_family(claims["sub"])
        if family is None or not family.is_active:
            raise ApiError(401, "AUTHENTICATION_REQUIRED", "认证信息无效或已过期")
        access_token, expires_at = create_jwt(
            self.settings,
            family.id,
            "family_access",
            self.settings.access_token_ttl_seconds,
        )
        return AccessTokenResponse(
            access_token=access_token,
            access_token_expires_at=expires_at,
        )

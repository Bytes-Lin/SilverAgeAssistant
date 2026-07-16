import asyncio
import hmac
from dataclasses import dataclass
from datetime import datetime, timedelta
from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import ApiError
from app.core.security import (
    derive_binding_code,
    derive_device_credential,
    ensure_utc,
    hash_binding_code,
    keyed_digest,
    mask_mobile,
    normalize_mainland_mobile,
    random_salt,
    utc_now,
)
from app.models import Binding, BindingCode, DeviceCredential, ElderProfile, FamilyAccount
from app.repositories.family_binding import FamilyBindingRepository
from app.schemas.binding import (
    BindingCodeCreateRequest,
    BindingCodeResponse,
    BindingListResponse,
    BindingResponse,
    DeviceBindRequest,
    DeviceBindResponse,
    ElderCreateRequest,
    ElderResponse,
)


@dataclass(frozen=True, slots=True)
class AuthContext:
    kind: str
    principal_id: str
    device: DeviceCredential | None = None


class FamilyBindingService:
    def __init__(
        self,
        session: AsyncSession,
        settings: Settings,
        binding_lock: asyncio.Lock,
    ) -> None:
        self.session = session
        self.settings = settings
        self.binding_lock = binding_lock
        self.repository = FamilyBindingRepository(session)

    async def create_elder(
        self, family: FamilyAccount, request: ElderCreateRequest
    ) -> ElderResponse:
        normalized = normalize_mainland_mobile(request.mobile_number)
        request_id = str(request.client_request_id)
        async with self.session.begin():
            idempotency = await self.repository.get_idempotency(
                family.id, "ELDER_CREATE", request_id
            )
            if idempotency:
                elder = await self.repository.get_elder(idempotency.resource_id)
                if elder is None:
                    raise ApiError(503, "SERVICE_TEMPORARILY_UNAVAILABLE", "服务暂时不可用")
            else:
                if await self.repository.get_elder_by_mobile(normalized):
                    raise ApiError(409, "ELDER_MOBILE_CONFLICT", "该老人手机号已创建档案")
                elder = await self.repository.create_elder(
                    family_id=family.id,
                    display_name=request.display_name.strip(),
                    mobile_normalized=normalized,
                    mobile_masked=mask_mobile(normalized),
                    relationship=request.relationship.value,
                    emergency_contact=request.emergency_contact,
                )
                self.repository.add_idempotency(family.id, "ELDER_CREATE", request_id, elder.id)
                self.repository.add_audit(
                    "ELDER_PROFILE_CREATED",
                    "FAMILY",
                    family.id,
                    "ELDER_PROFILE",
                    elder.id,
                )
        return self._elder_response(elder)

    @staticmethod
    def _elder_response(elder: ElderProfile) -> ElderResponse:
        return ElderResponse(
            elder_id=UUID(elder.id),
            display_name=elder.display_name,
            elder_mobile_masked=elder.mobile_masked,
            relationship=elder.relationship,
            emergency_contact=elder.emergency_contact_requested,
            created_at=ensure_utc(elder.created_at),
        )

    async def create_binding_code(
        self, family: FamilyAccount, request: BindingCodeCreateRequest
    ) -> BindingCodeResponse:
        if family.mobile_verified_at is None:
            raise ApiError(401, "FAMILY_MOBILE_NOT_VERIFIED", "请先完成手机号验证")
        request_id = str(request.client_request_id)
        elder_id = str(request.elder_id)
        async with self.session.begin():
            idempotency = await self.repository.get_idempotency(
                family.id, "BINDING_CODE_CREATE", request_id
            )
            if idempotency:
                code_record = await self.repository.get_binding_code(idempotency.resource_id)
                if code_record is None:
                    raise ApiError(503, "SERVICE_TEMPORARILY_UNAVAILABLE", "服务暂时不可用")
                code = derive_binding_code(
                    self.settings.security_secret,
                    family.id,
                    code_record.elder_id,
                    request_id,
                    code_record.code_salt,
                )
            else:
                elder = await self.repository.get_elder(elder_id)
                if elder is None or elder.created_by_family_id != family.id or not elder.is_active:
                    raise ApiError(404, "ELDER_NOT_FOUND", "老人档案不存在")
                now = utc_now()
                await self.repository.revoke_active_codes(family.id, elder.id, now)
                salt = random_salt()
                code = derive_binding_code(
                    self.settings.security_secret,
                    family.id,
                    elder.id,
                    request_id,
                    salt,
                )
                code_record = await self.repository.create_binding_code(
                    family.id,
                    elder.id,
                    salt,
                    hash_binding_code(self.settings.security_secret, salt, code),
                    now + timedelta(seconds=self.settings.binding_code_ttl_seconds),
                )
                self.repository.add_idempotency(
                    family.id, "BINDING_CODE_CREATE", request_id, code_record.id
                )
                self.repository.add_audit(
                    "BINDING_CODE_CREATED",
                    "FAMILY",
                    family.id,
                    "BINDING_CODE",
                    code_record.id,
                    {"elder_id": elder.id},
                )
        return BindingCodeResponse(
            binding_code=code,
            expires_at=ensure_utc(code_record.expires_at),
            elder_id=UUID(code_record.elder_id),
            family_mobile_masked=family.mobile_masked,
        )

    async def revoke_binding_codes(self, family: FamilyAccount, elder_id: str) -> None:
        now = utc_now()
        async with self.session.begin():
            elder = await self.repository.get_elder(elder_id)
            if elder is None or elder.created_by_family_id != family.id:
                raise ApiError(404, "ELDER_NOT_FOUND", "老人档案不存在")
            count = await self.repository.revoke_active_codes(family.id, elder_id, now)
            if count:
                self.repository.add_audit(
                    "BINDING_CODE_REVOKED",
                    "FAMILY",
                    family.id,
                    "ELDER_PROFILE",
                    elder_id,
                )

    async def bind_device(
        self, request: DeviceBindRequest, network_source: str
    ) -> DeviceBindResponse:
        if not request.sharing_consent:
            raise ApiError(400, "SHARING_CONSENT_REQUIRED", "绑定前请确认共享范围")
        normalized = normalize_mainland_mobile(request.family_mobile_number)
        attempt_key = keyed_digest(
            self.settings.security_secret,
            "binding-attempt",
            f"{request.device_id}|{normalized}|{network_source}",
        )
        async with self.binding_lock:
            return await self._bind_device_locked(request, normalized, attempt_key)

    async def _bind_device_locked(
        self,
        request: DeviceBindRequest,
        normalized_mobile: str,
        attempt_key: str,
    ) -> DeviceBindResponse:
        now = utc_now()
        since = now - timedelta(seconds=self.settings.binding_failure_window_seconds)
        failure_count = await self.repository.count_attempts(attempt_key, since)
        await self.session.rollback()
        if failure_count >= self.settings.binding_failure_limit:
            raise ApiError(429, "BINDING_ATTEMPTS_EXCEEDED", "尝试次数较多，请稍后再试")

        actor_scope = "device:" + keyed_digest(
            self.settings.security_secret, "device-actor", request.device_id
        )
        request_id = str(request.client_request_id)
        failure: ApiError | None = None
        response: DeviceBindResponse | None = None

        async with self.session.begin():
            idempotency = await self.repository.get_idempotency(
                actor_scope, "DEVICE_BIND", request_id
            )
            if idempotency:
                response = await self._rebuild_bind_response(
                    idempotency.resource_id, request.device_id, request_id
                )
            else:
                family = await self.repository.get_family_by_mobile(normalized_mobile)
                code_record = await self._match_code(family, request.binding_code)
                failure = self._binding_code_failure(code_record, now)
                elder = (
                    await self.repository.get_elder(code_record.elder_id)
                    if code_record and failure is None
                    else None
                )
                if elder is None and failure is None:
                    failure = ApiError(400, "BINDING_CREDENTIALS_INVALID", "手机号或绑定码不正确")

                if failure is None and family and code_record and elder:
                    existing_device = await self.repository.get_device_by_external_id(
                        request.device_id
                    )
                    active_conflict = (
                        await self.repository.find_active_binding_for_device_or_family(
                            request.device_id, elder.id, family.id
                        )
                    )
                    if (existing_device and existing_device.revoked_at is None) or active_conflict:
                        failure = ApiError(409, "DEVICE_BINDING_CONFLICT", "这部手机已绑定其他档案")
                    elif not await self.repository.consume_code(code_record.id, now):
                        failure = ApiError(409, "BINDING_CODE_USED_OR_REVOKED", "绑定码已失效")
                    else:
                        permissions = ["VIEWER", "HELPER"]
                        if elder.emergency_contact_requested:
                            permissions.append("EMERGENCY_CONTACT")
                        binding = await self.repository.create_binding(
                            elder, family, permissions, "ELDER_DEVICE_BIND"
                        )
                        credential = derive_device_credential(
                            self.settings.security_secret,
                            binding.id,
                            request.device_id,
                            request_id,
                        )
                        await self.repository.create_or_reactivate_device(
                            existing_device,
                            request.device_id,
                            request.device_name,
                            elder.id,
                            binding.id,
                            keyed_digest(
                                self.settings.security_secret,
                                "device-credential",
                                credential,
                            ),
                            now,
                        )
                        self.repository.add_idempotency(
                            actor_scope, "DEVICE_BIND", request_id, binding.id
                        )
                        self.repository.add_audit(
                            "BINDING_CODE_USED",
                            "DEVICE",
                            None,
                            "BINDING_CODE",
                            code_record.id,
                            {"elder_id": elder.id},
                        )
                        self.repository.add_audit(
                            "DEVICE_BOUND",
                            "DEVICE",
                            None,
                            "BINDING",
                            binding.id,
                            {"elder_id": elder.id},
                        )
                        response = self._bind_response(
                            binding,
                            elder,
                            family,
                            credential,
                            ensure_utc(binding.created_at),
                        )

                if failure is not None:
                    self.repository.add_attempt(attempt_key, now)

        if failure is not None:
            raise failure
        if response is None:
            raise ApiError(503, "SERVICE_TEMPORARILY_UNAVAILABLE", "服务暂时不可用")
        return response

    async def _match_code(
        self, family: FamilyAccount | None, submitted_code: str
    ) -> BindingCode | None:
        if family is None or family.mobile_verified_at is None:
            # Perform a keyed comparison even for an unknown family to reduce timing differences.
            dummy = hash_binding_code(self.settings.security_secret, "0" * 32, submitted_code)
            hmac.compare_digest(dummy, "0" * 64)
            return None
        matched: BindingCode | None = None
        for candidate in await self.repository.list_family_codes(family.id):
            submitted_digest = hash_binding_code(
                self.settings.security_secret, candidate.code_salt, submitted_code
            )
            if hmac.compare_digest(candidate.code_digest, submitted_digest):
                matched = candidate
        return matched

    @staticmethod
    def _binding_code_failure(code: BindingCode | None, now: datetime) -> ApiError | None:
        if code is None:
            return ApiError(400, "BINDING_CREDENTIALS_INVALID", "手机号或绑定码不正确")
        if code.used_at is not None or code.revoked_at is not None:
            return ApiError(409, "BINDING_CODE_USED_OR_REVOKED", "绑定码已失效")
        if ensure_utc(code.expires_at) <= now:
            return ApiError(410, "BINDING_CODE_EXPIRED", "绑定码已过期")
        return None

    async def _rebuild_bind_response(
        self, binding_id: str, device_id: str, request_id: str
    ) -> DeviceBindResponse:
        binding = await self.repository.get_binding(binding_id)
        if binding is None:
            raise ApiError(503, "SERVICE_TEMPORARILY_UNAVAILABLE", "服务暂时不可用")
        family = await self.repository.get_family(binding.family_account_id)
        elder = await self.repository.get_elder(binding.elder_id)
        if family is None or elder is None:
            raise ApiError(503, "SERVICE_TEMPORARILY_UNAVAILABLE", "服务暂时不可用")
        credential = derive_device_credential(
            self.settings.security_secret, binding.id, device_id, request_id
        )
        return self._bind_response(
            binding, elder, family, credential, ensure_utc(binding.created_at)
        )

    @staticmethod
    def _bind_response(
        binding: Binding,
        elder: ElderProfile,
        family: FamilyAccount,
        credential: str,
        bound_at: datetime,
    ) -> DeviceBindResponse:
        return DeviceBindResponse(
            binding_id=UUID(binding.id),
            elder_id=UUID(elder.id),
            family_account_id=UUID(family.id),
            family_mobile_masked=family.mobile_masked,
            relationship=binding.relationship,
            permissions=binding.permissions,
            device_credential=credential,
            bound_at=bound_at,
        )

    async def list_bindings(self, auth: AuthContext) -> BindingListResponse:
        if auth.kind == "family":
            rows = await self.repository.list_bindings_for_family(auth.principal_id)
        elif auth.kind == "device" and auth.device is not None:
            rows = await self.repository.list_bindings_for_device(auth.device)
        else:
            raise ApiError(401, "AUTHENTICATION_REQUIRED", "需要认证")
        return BindingListResponse(
            bindings=[
                BindingResponse(
                    binding_id=UUID(binding.id),
                    elder_id=UUID(elder.id),
                    elder_display_name=elder.display_name,
                    family_account_id=UUID(family.id),
                    family_display_name=family.display_name,
                    family_mobile_masked=family.mobile_masked,
                    relationship=binding.relationship,
                    permissions=binding.permissions,
                    device_id=device.external_device_id if device else None,
                    device_name=device.device_name if device else None,
                    bound_at=ensure_utc(binding.created_at),
                    revoked_at=ensure_utc(binding.revoked_at) if binding.revoked_at else None,
                )
                for binding, family, elder, device in rows
            ]
        )

    async def revoke_binding(self, family: FamilyAccount, binding_id: str) -> None:
        async with self.session.begin():
            binding = await self.repository.get_binding(binding_id)
            if binding is None or binding.family_account_id != family.id:
                raise ApiError(404, "BINDING_NOT_FOUND", "绑定关系不存在")
            if binding.revoked_at is None:
                now = utc_now()
                await self.repository.revoke_binding(binding, now)
                await self.repository.revoke_active_codes(family.id, binding.elder_id, now)
                self.repository.add_audit(
                    "BINDING_REVOKED", "FAMILY", family.id, "BINDING", binding.id
                )

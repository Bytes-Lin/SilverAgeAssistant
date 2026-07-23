from datetime import datetime
from typing import cast

from sqlalchemy import Select, func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import utc_now
from app.models import (
    AuditLog,
    Binding,
    BindingAttempt,
    BindingCode,
    DeviceCredential,
    ElderProfile,
    FamilyAccount,
    IdempotencyRecord,
)


class FamilyBindingRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_family(self, family_id: str) -> FamilyAccount | None:
        return await self.session.get(FamilyAccount, family_id)

    async def get_family_by_mobile(self, mobile_normalized: str) -> FamilyAccount | None:
        query = select(FamilyAccount).where(FamilyAccount.mobile_normalized == mobile_normalized)
        return (await self.session.scalars(query)).one_or_none()

    async def create_family(
        self,
        display_name: str,
        mobile_normalized: str,
        mobile_masked: str,
        verified_at: datetime | None,
    ) -> FamilyAccount:
        family = FamilyAccount(
            display_name=display_name,
            mobile_normalized=mobile_normalized,
            mobile_masked=mobile_masked,
            mobile_verified_at=verified_at,
        )
        self.session.add(family)
        await self.session.flush()
        return family

    async def get_elder(self, elder_id: str) -> ElderProfile | None:
        return await self.session.get(ElderProfile, elder_id)

    async def get_elder_by_mobile(self, mobile_normalized: str) -> ElderProfile | None:
        query = select(ElderProfile).where(ElderProfile.mobile_normalized == mobile_normalized)
        return (await self.session.scalars(query)).one_or_none()

    async def create_elder(
        self,
        family_id: str,
        display_name: str,
        mobile_normalized: str,
        mobile_masked: str,
        relationship: str,
        emergency_contact: bool,
    ) -> ElderProfile:
        elder = ElderProfile(
            display_name=display_name,
            mobile_normalized=mobile_normalized,
            mobile_masked=mobile_masked,
            created_by_family_id=family_id,
            relationship=relationship,
            emergency_contact_requested=emergency_contact,
        )
        self.session.add(elder)
        await self.session.flush()
        return elder

    async def get_idempotency(
        self, actor_scope: str, operation: str, request_id: str
    ) -> IdempotencyRecord | None:
        query = select(IdempotencyRecord).where(
            IdempotencyRecord.actor_scope == actor_scope,
            IdempotencyRecord.operation == operation,
            IdempotencyRecord.client_request_id == request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    def add_idempotency(
        self,
        actor_scope: str,
        operation: str,
        request_id: str,
        resource_id: str,
        created_at: datetime | None = None,
    ) -> None:
        values: dict[str, object] = {
            "actor_scope": actor_scope,
            "operation": operation,
            "client_request_id": request_id,
            "resource_id": resource_id,
        }
        if created_at is not None:
            values["created_at"] = created_at
        self.session.add(IdempotencyRecord(**values))

    async def get_active_binding_for_family_elder(
        self, family_id: str, elder_id: str
    ) -> Binding | None:
        query = select(Binding).where(
            Binding.family_account_id == family_id,
            Binding.elder_id == elder_id,
            Binding.revoked_at.is_(None),
        )
        return (await self.session.scalars(query)).one_or_none()

    async def revoke_active_codes(self, family_id: str, elder_id: str, revoked_at: datetime) -> int:
        statement = (
            update(BindingCode)
            .where(
                BindingCode.family_account_id == family_id,
                BindingCode.elder_id == elder_id,
                BindingCode.used_at.is_(None),
                BindingCode.revoked_at.is_(None),
            )
            .values(revoked_at=revoked_at)
            .execution_options(synchronize_session=False)
        )
        result = await self.session.execute(statement)
        return int(result.rowcount)  # type: ignore[attr-defined]

    async def create_binding_code(
        self,
        family_id: str,
        elder_id: str,
        salt: str,
        digest: str,
        expires_at: datetime,
    ) -> BindingCode:
        code = BindingCode(
            family_account_id=family_id,
            elder_id=elder_id,
            code_salt=salt,
            code_digest=digest,
            expires_at=expires_at,
        )
        self.session.add(code)
        await self.session.flush()
        return code

    async def get_binding_code(self, code_id: str) -> BindingCode | None:
        return await self.session.get(BindingCode, code_id)

    async def list_family_codes(self, family_id: str) -> list[BindingCode]:
        query = (
            select(BindingCode)
            .where(BindingCode.family_account_id == family_id)
            .order_by(BindingCode.created_at.desc())
            .limit(100)
        )
        return list((await self.session.scalars(query)).all())

    async def consume_code(self, code_id: str, now: datetime) -> bool:
        statement = (
            update(BindingCode)
            .where(
                BindingCode.id == code_id,
                BindingCode.used_at.is_(None),
                BindingCode.revoked_at.is_(None),
                BindingCode.expires_at > now,
            )
            .values(used_at=now)
            .execution_options(synchronize_session=False)
        )
        result = await self.session.execute(statement)
        return bool(result.rowcount == 1)  # type: ignore[attr-defined]

    async def get_device_by_external_id(self, device_id: str) -> DeviceCredential | None:
        query = select(DeviceCredential).where(DeviceCredential.external_device_id == device_id)
        return (await self.session.scalars(query)).one_or_none()

    async def get_device_by_digest(self, digest: str) -> DeviceCredential | None:
        query = select(DeviceCredential).where(
            DeviceCredential.credential_digest == digest,
            DeviceCredential.revoked_at.is_(None),
            or_(
                DeviceCredential.expires_at.is_(None),
                DeviceCredential.expires_at > utc_now(),
            ),
        )
        return (await self.session.scalars(query)).one_or_none()

    async def create_binding(
        self,
        elder: ElderProfile,
        family: FamilyAccount,
        permissions: list[str],
        audit_source: str,
        created_at: datetime,
    ) -> Binding:
        binding = Binding(
            elder_id=elder.id,
            family_account_id=family.id,
            relationship=elder.relationship,
            permissions=permissions,
            audit_source=audit_source,
            created_at=created_at,
        )
        self.session.add(binding)
        await self.session.flush()
        return binding

    async def create_or_reactivate_device(
        self,
        existing: DeviceCredential | None,
        external_device_id: str,
        device_name: str | None,
        elder_id: str,
        binding_id: str,
        credential_digest: str,
        now: datetime,
        expires_at: datetime,
    ) -> DeviceCredential:
        if existing is None:
            device = DeviceCredential(
                external_device_id=external_device_id,
                device_name=device_name,
                elder_id=elder_id,
                binding_id=binding_id,
                credential_digest=credential_digest,
                created_at=now,
                expires_at=expires_at,
            )
            self.session.add(device)
        else:
            existing.device_name = device_name
            existing.elder_id = elder_id
            existing.binding_id = binding_id
            existing.credential_digest = credential_digest
            existing.created_at = now
            existing.expires_at = expires_at
            existing.revoked_at = None
            device = existing
        await self.session.flush()
        return device

    async def list_unrevoked_devices_for_elder(self, elder_id: str) -> list[DeviceCredential]:
        query = select(DeviceCredential).where(
            DeviceCredential.elder_id == elder_id,
            DeviceCredential.revoked_at.is_(None),
        )
        return list((await self.session.scalars(query)).all())

    async def revoke_unrevoked_devices_for_elder(self, elder_id: str, revoked_at: datetime) -> int:
        statement = (
            update(DeviceCredential)
            .where(
                DeviceCredential.elder_id == elder_id,
                DeviceCredential.revoked_at.is_(None),
            )
            .values(revoked_at=revoked_at)
            .execution_options(synchronize_session="fetch")
        )
        result = await self.session.execute(statement)
        return int(result.rowcount)  # type: ignore[attr-defined]

    async def get_binding(self, binding_id: str) -> Binding | None:
        return await self.session.get(Binding, binding_id)

    async def count_attempts(self, attempt_key: str, since: datetime) -> int:
        query = select(func.count(BindingAttempt.id)).where(
            BindingAttempt.attempt_key == attempt_key,
            BindingAttempt.attempted_at >= since,
        )
        return int((await self.session.scalar(query)) or 0)

    def add_attempt(self, attempt_key: str, now: datetime) -> None:
        self.session.add(BindingAttempt(attempt_key=attempt_key, attempted_at=now))

    def add_audit(
        self,
        action: str,
        actor_type: str,
        actor_id: str | None,
        resource_type: str,
        resource_id: str | None,
        details: dict[str, str] | None = None,
    ) -> None:
        self.session.add(
            AuditLog(
                action=action,
                actor_type=actor_type,
                actor_id=actor_id,
                resource_type=resource_type,
                resource_id=resource_id,
                details=details or {},
            )
        )

    def _binding_view_query(
        self,
    ) -> Select[tuple[Binding, FamilyAccount, ElderProfile, DeviceCredential]]:
        return (
            select(Binding, FamilyAccount, ElderProfile, DeviceCredential)
            .join(FamilyAccount, FamilyAccount.id == Binding.family_account_id)
            .join(ElderProfile, ElderProfile.id == Binding.elder_id)
            .outerjoin(DeviceCredential, DeviceCredential.binding_id == Binding.id)
        )

    async def list_bindings_for_family(
        self, family_id: str
    ) -> list[tuple[Binding, FamilyAccount, ElderProfile, DeviceCredential | None]]:
        query = self._binding_view_query().where(Binding.family_account_id == family_id)
        rows = (await self.session.execute(query)).tuples().all()
        return cast(
            list[tuple[Binding, FamilyAccount, ElderProfile, DeviceCredential | None]],
            list(rows),
        )

    async def list_bindings_for_device(
        self, device: DeviceCredential
    ) -> list[tuple[Binding, FamilyAccount, ElderProfile, DeviceCredential | None]]:
        query = self._binding_view_query().where(
            Binding.elder_id == device.elder_id,
            Binding.revoked_at.is_(None),
        )
        rows = (await self.session.execute(query)).tuples().all()
        return cast(
            list[tuple[Binding, FamilyAccount, ElderProfile, DeviceCredential | None]],
            list(rows),
        )

    async def revoke_binding(self, binding: Binding, now: datetime) -> None:
        binding.revoked_at = now
        statement = (
            update(DeviceCredential)
            .where(
                DeviceCredential.binding_id == binding.id,
                DeviceCredential.revoked_at.is_(None),
            )
            .values(revoked_at=now)
            .execution_options(synchronize_session=False)
        )
        await self.session.execute(statement)

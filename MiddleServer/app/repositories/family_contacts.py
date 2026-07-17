from typing import cast

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AuditLog, Binding, DeviceCredential, ElderProfile, FamilyAccount


class FamilyContactsRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_active_device_context(
        self, device: DeviceCredential
    ) -> tuple[Binding, ElderProfile] | None:
        query = (
            select(Binding, ElderProfile)
            .join(ElderProfile, ElderProfile.id == Binding.elder_id)
            .where(
                Binding.id == device.binding_id,
                Binding.elder_id == device.elder_id,
                Binding.revoked_at.is_(None),
                ElderProfile.is_active.is_(True),
            )
        )
        row = (await self.session.execute(query)).tuples().one_or_none()
        return row

    async def list_active_contacts(self, elder_id: str) -> list[tuple[Binding, FamilyAccount]]:
        query = (
            select(Binding, FamilyAccount)
            .join(FamilyAccount, FamilyAccount.id == Binding.family_account_id)
            .where(
                Binding.elder_id == elder_id,
                Binding.revoked_at.is_(None),
                FamilyAccount.is_active.is_(True),
            )
            .order_by(Binding.created_at.asc(), Binding.id.asc())
        )
        rows = (await self.session.execute(query)).tuples().all()
        return cast(list[tuple[Binding, FamilyAccount]], list(rows))

    def add_snapshot_audit(
        self,
        *,
        device_id: str,
        elder_id: str,
        contact_count: int,
        snapshot_version: str,
    ) -> None:
        self.session.add(
            AuditLog(
                action="FAMILY_CONTACTS_READ",
                actor_type="DEVICE",
                actor_id=device_id,
                resource_type="ELDER_PROFILE",
                resource_id=elder_id,
                details={
                    "contact_count": str(contact_count),
                    "snapshot_version": snapshot_version,
                    "result": "SUCCESS",
                },
            )
        )

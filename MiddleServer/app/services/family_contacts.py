import hashlib
import json
from datetime import datetime
from uuid import UUID

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
from app.core.security import ensure_utc, utc_now
from app.models import Binding, DeviceCredential, FamilyAccount
from app.repositories.family_contacts import FamilyContactsRepository
from app.schemas.binding import Relationship
from app.schemas.family_contacts import FamilyContact, FamilyContactsResponse


class FamilyContactsService:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session
        self.repository = FamilyContactsRepository(session)

    async def get_snapshot(self, device: DeviceCredential) -> FamilyContactsResponse:
        try:
            async with self.session.begin():
                context = await self.repository.get_active_device_context(device)
                if context is None:
                    raise ApiError(
                        403,
                        "FAMILY_CONTACTS_FORBIDDEN",
                        "当前设备无权读取家属联系人",
                    )
                _, elder = context
                rows = await self.repository.list_active_contacts(elder.id)
                contacts = [self._contact(binding, family) for binding, family in rows]
                contacts.sort(
                    key=lambda contact: (
                        not contact.emergency_contact,
                        contact.bound_at,
                        str(contact.binding_id),
                    )
                )
                snapshot_version = self._snapshot_version(contacts)
                self.repository.add_snapshot_audit(
                    device_id=device.id,
                    elder_id=elder.id,
                    contact_count=len(contacts),
                    snapshot_version=snapshot_version,
                )
        except ApiError:
            raise
        except (SQLAlchemyError, TypeError, ValueError) as exc:
            raise ApiError(
                503,
                "FAMILY_CONTACTS_UNAVAILABLE",
                "暂时无法生成联系人快照",
            ) from exc
        return FamilyContactsResponse(
            snapshot_version=snapshot_version,
            synced_at=utc_now(),
            contacts=contacts,
        )

    @staticmethod
    def _contact(binding: Binding, family: FamilyAccount) -> FamilyContact:
        permissions = sorted(set(binding.permissions))
        profile_updated_at: datetime = family.updated_at or family.created_at
        return FamilyContact(
            binding_id=UUID(binding.id),
            family_account_id=UUID(family.id),
            display_name=family.display_name,
            mobile_number=family.mobile_normalized,
            relationship=Relationship(binding.relationship),
            permissions=permissions,
            emergency_contact="EMERGENCY_CONTACT" in permissions,
            bound_at=ensure_utc(binding.created_at),
            profile_updated_at=ensure_utc(profile_updated_at),
        )

    @staticmethod
    def _snapshot_version(contacts: list[FamilyContact]) -> str:
        if not contacts:
            return "empty-v1"
        visible_fields = [contact.model_dump(mode="json") for contact in contacts]
        encoded = json.dumps(
            visible_fields,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
        return "sha256-" + hashlib.sha256(encoded).hexdigest()

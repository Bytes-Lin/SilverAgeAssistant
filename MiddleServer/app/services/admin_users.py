import asyncio

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
from app.core.security import normalize_mainland_mobile
from app.repositories.admin_users import (
    AdminFamilyBindingSummary,
    AdminUserRepository,
    DeletedUserData,
)
from app.schemas.binding import Relationship


class AdminUserService:
    def __init__(self, session: AsyncSession, binding_lock: asyncio.Lock) -> None:
        self.session = session
        self.binding_lock = binding_lock
        self.repository = AdminUserRepository(session)

    async def list_bindings(self, query: str = "") -> list[AdminFamilyBindingSummary]:
        async with self.session.begin():
            return await self.repository.list_bindings(query)

    async def update_binding(
        self,
        binding_id: str,
        family_display_name: str,
        elder_display_name: str,
        relationship: str,
        admin_username: str,
    ) -> bool:
        family_name = family_display_name.strip()
        elder_name = elder_display_name.strip()
        if not (1 <= len(family_name) <= 20 and 1 <= len(elder_name) <= 20):
            return False
        try:
            normalized_relationship = Relationship(relationship)
        except ValueError:
            return False
        async with self.binding_lock:
            async with self.session.begin():
                binding = await self.repository.get_active_binding(binding_id)
                if binding is None:
                    return False
                family = await self.repository.get_family(binding.family_account_id)
                elder = await self.repository.get_elder(binding.elder_id)
                if family is None or elder is None:
                    return False
                family.display_name = family_name
                elder.display_name = elder_name
                binding.relationship = normalized_relationship.value
                self.repository.add_audit(
                    "ADMIN_BINDING_UPDATED",
                    admin_username,
                    binding.id,
                    {"relationship": normalized_relationship.value},
                )
        return True

    async def delete_user_data(
        self,
        binding_id: str,
        family_mobile: str,
        admin_username: str,
    ) -> DeletedUserData | None:
        try:
            normalized_family_mobile = normalize_mainland_mobile(family_mobile)
        except ApiError:
            return None
        async with self.binding_lock:
            async with self.session.begin():
                binding = await self.repository.get_binding(binding_id)
                if binding is None:
                    return None
                family = await self.repository.get_family(binding.family_account_id)
                if family is None or family.mobile_normalized != normalized_family_mobile:
                    return None
                return await self.repository.delete_family_user_data(family, admin_username)

from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import delete, func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import aliased

from app.models import (
    AuditLog,
    Binding,
    BindingCode,
    Command,
    CommandCompletion,
    CommandReceipt,
    DeviceCredential,
    ElderModelConfiguration,
    ElderProfile,
    FamilyAccount,
    IdempotencyRecord,
    ModelConfigurationRequest,
    ModelUsageBatch,
    ModelUsageItem,
    ModelUsageRefreshRequest,
    ReminderArchive,
    SafetyEvent,
    SafetyEventAcknowledgementRequest,
    SafetyEventImage,
    SafetyEventResolutionRequest,
    SafetyMonitoringConfiguration,
    SafetyMonitoringConfigurationRequest,
)


@dataclass(frozen=True, slots=True)
class AdminFamilyBindingSummary:
    binding_id: str
    binding_is_active: bool
    family_display_name: str
    family_mobile_masked: str
    family_is_active: bool
    elder_display_name: str
    elder_mobile_masked: str
    relationship: str
    elder_is_active: bool
    active_device_count: int
    bound_at: datetime


@dataclass(frozen=True, slots=True)
class DeletedUserData:
    family_id: str
    elder_ids: frozenset[str]
    device_ids: frozenset[str]
    image_files: tuple[tuple[str, str], ...]


class AdminUserRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def list_bindings(self, query: str = "") -> list[AdminFamilyBindingSummary]:
        conditions = []
        if query:
            conditions.append(self._search_condition(query))
        other_binding = aliased(Binding)
        latest_binding_id = (
            select(other_binding.id)
            .where(
                other_binding.family_account_id == Binding.family_account_id,
                other_binding.elder_id == Binding.elder_id,
            )
            .order_by(other_binding.created_at.desc(), other_binding.id.desc())
            .limit(1)
            .correlate(Binding)
            .scalar_subquery()
        )
        device_counts = (
            select(
                DeviceCredential.binding_id.label("binding_id"),
                func.count(DeviceCredential.id).label("device_count"),
            )
            .where(DeviceCredential.revoked_at.is_(None))
            .group_by(DeviceCredential.binding_id)
            .subquery()
        )
        statement = (
            select(
                Binding.id,
                Binding.revoked_at,
                FamilyAccount.display_name,
                FamilyAccount.mobile_masked,
                FamilyAccount.is_active,
                ElderProfile.display_name,
                ElderProfile.mobile_masked,
                Binding.relationship,
                ElderProfile.is_active,
                func.coalesce(device_counts.c.device_count, 0),
                Binding.created_at,
            )
            .join(ElderProfile, Binding.elder_id == ElderProfile.id)
            .join(FamilyAccount, FamilyAccount.id == Binding.family_account_id)
            .outerjoin(device_counts, device_counts.c.binding_id == Binding.id)
            .where(Binding.id == latest_binding_id, *conditions)
            .order_by(Binding.created_at.desc(), Binding.id)
        )
        return [
            AdminFamilyBindingSummary(
                binding_id=str(row[0]),
                binding_is_active=row[1] is None,
                family_display_name=str(row[2]),
                family_mobile_masked=str(row[3]),
                family_is_active=bool(row[4]),
                elder_display_name=str(row[5]),
                elder_mobile_masked=str(row[6]),
                relationship=str(row[7]),
                elder_is_active=bool(row[8]),
                active_device_count=int(row[9]),
                bound_at=row[10],
            )
            for row in (await self.session.execute(statement)).all()
        ]

    @staticmethod
    def _search_condition(query: str):  # type: ignore[no-untyped-def]
        normalized_mobile = "".join(character for character in query if character.isdigit())
        conditions = [
            func.lower(FamilyAccount.display_name).contains(query.lower(), autoescape=True),
            func.lower(ElderProfile.display_name).contains(query.lower(), autoescape=True),
        ]
        if len(normalized_mobile) == 11:
            conditions.append(FamilyAccount.mobile_normalized == normalized_mobile)
            conditions.append(ElderProfile.mobile_normalized == normalized_mobile)
        return or_(*conditions)

    async def get_binding(self, binding_id: str) -> Binding | None:
        return await self.session.get(Binding, binding_id)

    async def get_active_binding(self, binding_id: str) -> Binding | None:
        return (
            await self.session.scalars(
                select(Binding).where(Binding.id == binding_id, Binding.revoked_at.is_(None))
            )
        ).one_or_none()

    async def get_family(self, family_id: str) -> FamilyAccount | None:
        return await self.session.get(FamilyAccount, family_id)

    async def get_elder(self, elder_id: str) -> ElderProfile | None:
        return await self.session.get(ElderProfile, elder_id)

    def add_audit(
        self,
        action: str,
        username: str,
        binding_id: str | None,
        details: dict[str, str],
    ) -> None:
        self.session.add(
            AuditLog(
                action=action,
                actor_type="ADMIN",
                actor_id=None,
                resource_type="BINDING" if binding_id else "USER_DATA",
                resource_id=binding_id,
                details={"admin_username": username, **details},
            )
        )

    async def delete_family_user_data(
        self, family: FamilyAccount, admin_username: str
    ) -> DeletedUserData:
        elder_ids = set(
            await self.session.scalars(
                select(ElderProfile.id).where(ElderProfile.created_by_family_id == family.id)
            )
        )
        binding_ids = set(
            await self.session.scalars(
                select(Binding.id).where(
                    or_(Binding.family_account_id == family.id, Binding.elder_id.in_(elder_ids))
                )
            )
        )
        device_ids = set(
            await self.session.scalars(
                select(DeviceCredential.id).where(
                    or_(
                        DeviceCredential.binding_id.in_(binding_ids),
                        DeviceCredential.elder_id.in_(elder_ids),
                    )
                )
            )
        )
        event_ids = set(
            await self.session.scalars(
                select(SafetyEvent.event_id).where(
                    or_(
                        SafetyEvent.elder_id.in_(elder_ids),
                        SafetyEvent.source_device_id.in_(device_ids),
                    )
                )
            )
        )
        command_ids = set(
            await self.session.scalars(
                select(Command.id).where(
                    or_(
                        Command.actor_family_id == family.id,
                        Command.binding_id.in_(binding_ids),
                        Command.elder_id.in_(elder_ids),
                    )
                )
            )
        )
        batch_ids = set(
            await self.session.scalars(
                select(ModelUsageBatch.batch_id).where(
                    or_(
                        ModelUsageBatch.elder_id.in_(elder_ids),
                        ModelUsageBatch.device_id.in_(device_ids),
                    )
                )
            )
        )
        model_configuration_ids = set(
            await self.session.scalars(
                select(ElderModelConfiguration.id).where(
                    or_(
                        ElderModelConfiguration.updated_by_family_id == family.id,
                        ElderModelConfiguration.elder_id.in_(elder_ids),
                    )
                )
            )
        )
        usage_refresh_ids = set(
            await self.session.scalars(
                select(ModelUsageRefreshRequest.id).where(
                    or_(
                        ModelUsageRefreshRequest.family_account_id == family.id,
                        ModelUsageRefreshRequest.elder_id.in_(elder_ids),
                    )
                )
            )
        )
        safety_configuration_ids = set(
            await self.session.scalars(
                select(SafetyMonitoringConfiguration.id).where(
                    or_(
                        SafetyMonitoringConfiguration.updated_by_family_account_id == family.id,
                        SafetyMonitoringConfiguration.elder_id.in_(elder_ids),
                    )
                )
            )
        )
        image_files = tuple(
            (str(row[0]), str(row[1]))
            for row in (
                await self.session.execute(
                    select(
                        SafetyEventImage.original_storage_name,
                        SafetyEventImage.thumbnail_storage_name,
                    ).where(SafetyEventImage.event_id.in_(event_ids))
                )
            ).all()
        )
        resource_ids = {
            family.id,
            *elder_ids,
            *binding_ids,
            *device_ids,
            *event_ids,
            *command_ids,
            *batch_ids,
            *model_configuration_ids,
            *usage_refresh_ids,
            *safety_configuration_ids,
        }

        await self._delete_where(
            CommandCompletion,
            or_(
                CommandCompletion.command_id.in_(command_ids),
                CommandCompletion.elder_id.in_(elder_ids),
                CommandCompletion.device_id.in_(device_ids),
            ),
        )
        await self._delete_where(
            CommandReceipt,
            or_(
                CommandReceipt.command_id.in_(command_ids),
                CommandReceipt.device_id.in_(device_ids),
            ),
        )
        await self._delete_where(
            SafetyEventAcknowledgementRequest,
            or_(
                SafetyEventAcknowledgementRequest.family_account_id == family.id,
                SafetyEventAcknowledgementRequest.elder_id.in_(elder_ids),
                SafetyEventAcknowledgementRequest.event_id.in_(event_ids),
            ),
        )
        await self._delete_where(
            SafetyEventResolutionRequest,
            or_(
                SafetyEventResolutionRequest.family_account_id == family.id,
                SafetyEventResolutionRequest.elder_id.in_(elder_ids),
                SafetyEventResolutionRequest.event_id.in_(event_ids),
            ),
        )
        await self._delete_where(SafetyEventImage, SafetyEventImage.event_id.in_(event_ids))
        await self._delete_where(ModelUsageItem, ModelUsageItem.batch_id.in_(batch_ids))
        await self._delete_where(SafetyEvent, SafetyEvent.event_id.in_(event_ids))
        await self.session.execute(
            update(SafetyEvent)
            .where(SafetyEvent.acknowledged_by_family_account_id == family.id)
            .values(acknowledged_by_family_account_id=None, acknowledged_at=None)
        )
        await self.session.execute(
            update(SafetyEvent)
            .where(SafetyEvent.resolved_by_family_account_id == family.id)
            .values(resolved_by_family_account_id=None, resolved_at=None)
        )
        await self._delete_where(
            ReminderArchive,
            or_(
                ReminderArchive.family_account_id == family.id,
                ReminderArchive.elder_id.in_(elder_ids),
                ReminderArchive.command_id.in_(command_ids),
            ),
        )
        await self._delete_where(Command, Command.id.in_(command_ids))
        await self._delete_where(ModelUsageBatch, ModelUsageBatch.batch_id.in_(batch_ids))
        await self._delete_where(
            ModelConfigurationRequest,
            or_(
                ModelConfigurationRequest.family_account_id == family.id,
                ModelConfigurationRequest.elder_id.in_(elder_ids),
            ),
        )
        await self._delete_where(
            ModelUsageRefreshRequest,
            or_(
                ModelUsageRefreshRequest.family_account_id == family.id,
                ModelUsageRefreshRequest.elder_id.in_(elder_ids),
            ),
        )
        await self._delete_where(
            SafetyMonitoringConfigurationRequest,
            or_(
                SafetyMonitoringConfigurationRequest.family_account_id == family.id,
                SafetyMonitoringConfigurationRequest.elder_id.in_(elder_ids),
            ),
        )
        await self._delete_where(
            ElderModelConfiguration,
            or_(
                ElderModelConfiguration.updated_by_family_id == family.id,
                ElderModelConfiguration.elder_id.in_(elder_ids),
            ),
        )
        await self._delete_where(
            SafetyMonitoringConfiguration,
            or_(
                SafetyMonitoringConfiguration.updated_by_family_account_id == family.id,
                SafetyMonitoringConfiguration.elder_id.in_(elder_ids),
            ),
        )
        await self._delete_where(DeviceCredential, DeviceCredential.id.in_(device_ids))
        await self._delete_where(
            BindingCode,
            or_(
                BindingCode.family_account_id == family.id,
                BindingCode.elder_id.in_(elder_ids),
            ),
        )
        await self._delete_where(Binding, Binding.id.in_(binding_ids))
        await self._delete_where(IdempotencyRecord, IdempotencyRecord.resource_id.in_(resource_ids))
        await self._delete_where(
            AuditLog,
            or_(AuditLog.actor_id.in_(resource_ids), AuditLog.resource_id.in_(resource_ids)),
        )
        await self._delete_where(ElderProfile, ElderProfile.id.in_(elder_ids))
        await self.session.delete(family)
        self.add_audit(
            "ADMIN_USER_DATA_DELETED",
            admin_username,
            None,
            {
                "elder_count": str(len(elder_ids)),
                "binding_count": str(len(binding_ids)),
                "device_count": str(len(device_ids)),
            },
        )
        return DeletedUserData(
            family_id=family.id,
            elder_ids=frozenset(elder_ids),
            device_ids=frozenset(device_ids),
            image_files=image_files,
        )

    async def _delete_where(self, model: type[object], condition: object) -> None:
        await self.session.execute(delete(model).where(condition))  # type: ignore[arg-type]

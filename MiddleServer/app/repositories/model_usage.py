from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    AuditLog,
    Binding,
    DeviceCredential,
    ElderProfile,
    ModelUsageBatch,
    ModelUsageItem,
    ModelUsageRefreshRequest,
)


class ModelUsageRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_active_binding_for_device(self, device: DeviceCredential) -> Binding | None:
        query = select(Binding).where(
            Binding.id == device.binding_id,
            Binding.elder_id == device.elder_id,
            Binding.revoked_at.is_(None),
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_elder(self, elder_id: str) -> ElderProfile | None:
        return await self.session.get(ElderProfile, elder_id)

    async def get_active_family_binding(self, family_id: str, elder_id: str) -> Binding | None:
        query = (
            select(Binding)
            .where(
                Binding.family_account_id == family_id,
                Binding.elder_id == elder_id,
                Binding.revoked_at.is_(None),
            )
            .order_by(Binding.created_at.desc())
            .limit(1)
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_batch(self, batch_id: str) -> ModelUsageBatch | None:
        return await self.session.get(ModelUsageBatch, batch_id)

    async def get_latest_time_zone_batch(
        self,
        elder_id: str,
        time_zone_source: str | None = None,
    ) -> ModelUsageBatch | None:
        query = select(ModelUsageBatch).where(ModelUsageBatch.elder_id == elder_id)
        if time_zone_source is not None:
            query = query.where(ModelUsageBatch.time_zone_source == time_zone_source)
        query = query.order_by(ModelUsageBatch.received_at.desc()).limit(1)
        return (await self.session.scalars(query)).one_or_none()

    async def get_refresh_request(
        self, family_id: str, client_request_id: str
    ) -> ModelUsageRefreshRequest | None:
        query = select(ModelUsageRefreshRequest).where(
            ModelUsageRefreshRequest.family_account_id == family_id,
            ModelUsageRefreshRequest.client_request_id == client_request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_latest_refresh_request(
        self,
        family_id: str,
        elder_id: str,
    ) -> ModelUsageRefreshRequest | None:
        query = (
            select(ModelUsageRefreshRequest)
            .where(
                ModelUsageRefreshRequest.family_account_id == family_id,
                ModelUsageRefreshRequest.elder_id == elder_id,
            )
            .order_by(ModelUsageRefreshRequest.requested_at.desc())
            .limit(1)
        )
        return (await self.session.scalars(query)).one_or_none()

    async def list_active_device_ids(self, elder_id: str) -> set[str]:
        query = (
            select(DeviceCredential.id)
            .join(Binding, Binding.id == DeviceCredential.binding_id)
            .where(
                DeviceCredential.elder_id == elder_id,
                DeviceCredential.revoked_at.is_(None),
                Binding.elder_id == elder_id,
                Binding.revoked_at.is_(None),
            )
        )
        return set((await self.session.scalars(query)).all())

    async def create_batch(
        self,
        *,
        batch_id: str,
        elder_id: str,
        device_id: str,
        period_started_at: datetime,
        period_ended_at: datetime,
        time_zone: str,
        time_zone_source: str,
        request_fingerprint: str,
        received_at: datetime,
    ) -> ModelUsageBatch:
        batch = ModelUsageBatch(
            batch_id=batch_id,
            elder_id=elder_id,
            device_id=device_id,
            period_started_at=period_started_at,
            period_ended_at=period_ended_at,
            time_zone=time_zone,
            time_zone_source=time_zone_source,
            request_fingerprint=request_fingerprint,
            received_at=received_at,
        )
        self.session.add(batch)
        await self.session.flush()
        return batch

    async def create_refresh_request(
        self,
        *,
        family_id: str,
        elder_id: str,
        client_request_id: str,
        request_fingerprint: str,
        requested_at: datetime,
    ) -> ModelUsageRefreshRequest:
        request = ModelUsageRefreshRequest(
            family_account_id=family_id,
            elder_id=elder_id,
            client_request_id=client_request_id,
            request_fingerprint=request_fingerprint,
            requested_at=requested_at,
            device_online=False,
        )
        self.session.add(request)
        await self.session.flush()
        return request

    def add_item(
        self,
        *,
        batch_id: str,
        modality: str,
        provider: str,
        model: str | None,
        feature: str,
        request_count: int,
        success_count: int,
        input_tokens: int,
        output_tokens: int,
        asr_audio_duration_ms: int,
        tts_character_count: int,
        tts_audio_duration_ms: int,
        contains_estimated_values: bool,
    ) -> None:
        self.session.add(
            ModelUsageItem(
                batch_id=batch_id,
                modality=modality,
                provider=provider,
                model=model,
                feature=feature,
                request_count=request_count,
                success_count=success_count,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                asr_audio_duration_ms=asr_audio_duration_ms,
                tts_character_count=tts_character_count,
                tts_audio_duration_ms=tts_audio_duration_ms,
                contains_estimated_values=contains_estimated_values,
            )
        )

    async def list_items_for_period(
        self,
        elder_id: str,
        period_started_at: datetime,
        period_ended_at: datetime,
    ) -> list[tuple[ModelUsageItem, datetime]]:
        query = (
            select(ModelUsageItem, ModelUsageBatch.received_at)
            .join(ModelUsageBatch, ModelUsageBatch.batch_id == ModelUsageItem.batch_id)
            .where(
                ModelUsageBatch.elder_id == elder_id,
                ModelUsageBatch.period_started_at >= period_started_at,
                ModelUsageBatch.period_started_at < period_ended_at,
            )
        )
        rows = await self.session.execute(query)
        return [(item, received_at) for item, received_at in rows.all()]

    async def list_items_with_batch_for_period(
        self,
        elder_id: str,
        period_started_at: datetime,
        period_ended_at: datetime,
    ) -> list[tuple[ModelUsageItem, datetime, datetime]]:
        query = (
            select(
                ModelUsageItem,
                ModelUsageBatch.period_started_at,
                ModelUsageBatch.received_at,
            )
            .join(ModelUsageBatch, ModelUsageBatch.batch_id == ModelUsageItem.batch_id)
            .where(
                ModelUsageBatch.elder_id == elder_id,
                ModelUsageBatch.period_started_at >= period_started_at,
                ModelUsageBatch.period_started_at < period_ended_at,
            )
        )
        rows = await self.session.execute(query)
        return [
            (item, batch_started_at, received_at)
            for item, batch_started_at, received_at in rows.all()
        ]

    def add_acceptance_audit(
        self,
        *,
        device_id: str,
        elder_id: str,
        batch_id: str,
        item_count: int,
    ) -> None:
        self.session.add(
            AuditLog(
                action="MODEL_USAGE_BATCH_ACCEPTED",
                actor_type="DEVICE",
                actor_id=device_id,
                resource_type="MODEL_USAGE_BATCH",
                resource_id=batch_id,
                details={
                    "elder_id": elder_id,
                    "item_count": str(item_count),
                },
            )
        )

    def add_refresh_audit(
        self,
        *,
        family_id: str,
        elder_id: str,
        refresh_request_id: str,
        device_online: bool,
    ) -> None:
        self.session.add(
            AuditLog(
                action="MODEL_USAGE_REFRESH_REQUESTED",
                actor_type="FAMILY",
                actor_id=family_id,
                resource_type="MODEL_USAGE_REFRESH",
                resource_id=refresh_request_id,
                details={
                    "elder_id": elder_id,
                    "device_online": str(device_online).lower(),
                },
            )
        )

from datetime import datetime

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    AuditLog,
    Binding,
    DeviceCredential,
    ElderProfile,
    ModelUsageBatch,
    SafetyEvent,
    SafetyEventAcknowledgementRequest,
    SafetyEventImage,
    SafetyMonitoringConfiguration,
    SafetyMonitoringConfigurationRequest,
)


class SafetyMonitoringRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_elder(self, elder_id: str) -> ElderProfile | None:
        return await self.session.get(ElderProfile, elder_id)

    async def get_latest_family_binding(self, family_id: str, elder_id: str) -> Binding | None:
        query = (
            select(Binding)
            .where(Binding.family_account_id == family_id, Binding.elder_id == elder_id)
            .order_by(Binding.created_at.desc())
            .limit(1)
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_active_device_binding(self, device: DeviceCredential) -> Binding | None:
        query = select(Binding).where(
            Binding.id == device.binding_id,
            Binding.elder_id == device.elder_id,
            Binding.revoked_at.is_(None),
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_configuration(self, elder_id: str) -> SafetyMonitoringConfiguration | None:
        query = select(SafetyMonitoringConfiguration).where(
            SafetyMonitoringConfiguration.elder_id == elder_id
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_configuration_request(
        self, family_id: str, client_request_id: str
    ) -> SafetyMonitoringConfigurationRequest | None:
        query = select(SafetyMonitoringConfigurationRequest).where(
            SafetyMonitoringConfigurationRequest.family_account_id == family_id,
            SafetyMonitoringConfigurationRequest.client_request_id == client_request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def save_configuration(
        self,
        current: SafetyMonitoringConfiguration | None,
        *,
        elder_id: str,
        enabled: bool,
        interval_minutes: int,
        revision: int,
        family_id: str,
        request_fingerprint: str,
        now: datetime,
    ) -> SafetyMonitoringConfiguration:
        if current is None:
            current = SafetyMonitoringConfiguration(
                elder_id=elder_id,
                enabled=enabled,
                interval_minutes=interval_minutes,
                revision=revision,
                updated_by_family_account_id=family_id,
                updated_at=now,
                request_fingerprint=request_fingerprint,
            )
            self.session.add(current)
        else:
            current.enabled = enabled
            current.interval_minutes = interval_minutes
            current.revision = revision
            current.updated_by_family_account_id = family_id
            current.updated_at = now
            current.request_fingerprint = request_fingerprint
        await self.session.flush()
        return current

    def add_configuration_request(
        self,
        *,
        family_id: str,
        elder_id: str,
        client_request_id: str,
        request_fingerprint: str,
        response_payload: dict[str, object],
        revision: int,
        created_at: datetime,
    ) -> None:
        self.session.add(
            SafetyMonitoringConfigurationRequest(
                family_account_id=family_id,
                elder_id=elder_id,
                client_request_id=client_request_id,
                request_fingerprint=request_fingerprint,
                response_payload=response_payload,
                revision=revision,
                created_at=created_at,
            )
        )

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

    async def get_event_by_client_id(self, client_event_id: str) -> SafetyEvent | None:
        query = select(SafetyEvent).where(SafetyEvent.client_event_id == client_event_id)
        return (await self.session.scalars(query)).one_or_none()

    async def get_event(self, event_id: str) -> SafetyEvent | None:
        return await self.session.get(SafetyEvent, event_id)

    async def get_event_image(self, event_id: str) -> SafetyEventImage | None:
        query = select(SafetyEventImage).where(SafetyEventImage.event_id == event_id)
        return (await self.session.scalars(query)).one_or_none()

    async def next_event_sequence(self) -> int:
        return (
            int((await self.session.scalar(select(func.max(SafetyEvent.server_sequence)))) or 0) + 1
        )

    async def count_device_events_since(self, device_id: str, since: datetime) -> int:
        query = select(func.count(SafetyEvent.event_id)).where(
            SafetyEvent.source_device_id == device_id,
            SafetyEvent.created_at >= since,
        )
        return int((await self.session.scalar(query)) or 0)

    async def create_event(
        self,
        *,
        client_event_id: str,
        elder_id: str,
        device_id: str,
        server_sequence: int,
        occurred_at: datetime,
        event_type: str,
        event_summary: str,
        severity: str,
        request_fingerprint: str,
        created_at: datetime,
    ) -> SafetyEvent:
        event = SafetyEvent(
            client_event_id=client_event_id,
            elder_id=elder_id,
            source_device_id=device_id,
            server_sequence=server_sequence,
            occurred_at=occurred_at,
            event_type=event_type,
            event_summary=event_summary,
            severity=severity,
            request_fingerprint=request_fingerprint,
            created_at=created_at,
        )
        self.session.add(event)
        await self.session.flush()
        return event

    async def list_authorized_family_ids(self, elder_id: str) -> set[str]:
        allowed = {"VIEWER", "HELPER", "EMERGENCY_CONTACT", "OWNER"}
        query = select(Binding).where(
            Binding.elder_id == elder_id,
            Binding.revoked_at.is_(None),
        )
        bindings = (await self.session.scalars(query)).all()
        return {
            binding.family_account_id
            for binding in bindings
            if allowed.intersection(binding.permissions or [])
        }

    async def list_events(
        self, elder_id: str, started_at: datetime, ended_at: datetime
    ) -> list[tuple[SafetyEvent, SafetyEventImage | None]]:
        query = (
            select(SafetyEvent, SafetyEventImage)
            .outerjoin(SafetyEventImage, SafetyEventImage.event_id == SafetyEvent.event_id)
            .where(
                SafetyEvent.elder_id == elder_id,
                SafetyEvent.occurred_at >= started_at,
                SafetyEvent.occurred_at < ended_at,
            )
            .order_by(SafetyEvent.occurred_at.desc(), SafetyEvent.server_sequence.desc())
        )
        return list((await self.session.execute(query)).tuples().all())

    async def create_event_image(
        self,
        *,
        event_id: str,
        content_type: str,
        byte_size: int,
        content_sha256: str,
        original_storage_name: str,
        thumbnail_storage_name: str,
        created_at: datetime,
        expires_at: datetime,
    ) -> SafetyEventImage:
        image = SafetyEventImage(
            event_id=event_id,
            content_type=content_type,
            byte_size=byte_size,
            content_sha256=content_sha256,
            original_storage_name=original_storage_name,
            thumbnail_storage_name=thumbnail_storage_name,
            created_at=created_at,
            expires_at=expires_at,
        )
        self.session.add(image)
        await self.session.flush()
        return image

    async def list_expired_images(self, now: datetime) -> list[SafetyEventImage]:
        query = select(SafetyEventImage).where(SafetyEventImage.expires_at <= now)
        return list((await self.session.scalars(query)).all())

    async def delete_event_images(self, image_ids: list[str]) -> None:
        if not image_ids:
            return
        await self.session.execute(
            delete(SafetyEventImage).where(SafetyEventImage.id.in_(image_ids))
        )

    async def count_image_views_since(
        self,
        family_id: str,
        event_id: str,
        since: datetime,
    ) -> int:
        query = select(func.count(AuditLog.id)).where(
            AuditLog.action == "SAFETY_EVENT_IMAGE_VIEWED",
            AuditLog.actor_id == family_id,
            AuditLog.resource_id == event_id,
            AuditLog.created_at >= since,
        )
        return int((await self.session.scalar(query)) or 0)

    async def get_latest_time_zone_batch(
        self, elder_id: str, source: str | None = None
    ) -> ModelUsageBatch | None:
        query = select(ModelUsageBatch).where(ModelUsageBatch.elder_id == elder_id)
        if source is not None:
            query = query.where(ModelUsageBatch.time_zone_source == source)
        query = query.order_by(ModelUsageBatch.received_at.desc()).limit(1)
        return (await self.session.scalars(query)).one_or_none()

    async def get_acknowledgement_request(
        self, family_id: str, client_request_id: str
    ) -> SafetyEventAcknowledgementRequest | None:
        query = select(SafetyEventAcknowledgementRequest).where(
            SafetyEventAcknowledgementRequest.family_account_id == family_id,
            SafetyEventAcknowledgementRequest.client_request_id == client_request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    def add_acknowledgement_request(
        self,
        *,
        family_id: str,
        elder_id: str,
        event_id: str,
        client_request_id: str,
        created_at: datetime,
    ) -> None:
        self.session.add(
            SafetyEventAcknowledgementRequest(
                family_account_id=family_id,
                elder_id=elder_id,
                event_id=event_id,
                client_request_id=client_request_id,
                created_at=created_at,
            )
        )

    def add_audit(
        self,
        *,
        action: str,
        actor_type: str,
        actor_id: str,
        resource_type: str,
        resource_id: str,
        elder_id: str,
        details: dict[str, str] | None = None,
    ) -> None:
        self.session.add(
            AuditLog(
                action=action,
                actor_type=actor_type,
                actor_id=actor_id,
                resource_type=resource_type,
                resource_id=resource_id,
                details={"elder_id": elder_id, **(details or {})},
            )
        )

import asyncio
import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime, time, timedelta
from typing import Protocol
from uuid import UUID
from zoneinfo import ZoneInfo

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import ApiError
from app.core.security import ensure_utc, utc_now
from app.models import (
    Binding,
    DeviceCredential,
    FamilyAccount,
    SafetyEvent,
    SafetyEventImage,
    SafetyMonitoringConfiguration,
)
from app.repositories.safety_monitoring import SafetyMonitoringRepository
from app.schemas.safety_monitoring import (
    SafetyEventAcknowledgementRequest,
    SafetyEventCreateRequest,
    SafetyEventResolutionRequest,
    SafetyEventResponse,
    SafetyEventSeverity,
    SafetyEventsResponse,
    SafetyEventType,
    SafetyMonitoringConfigurationResponse,
    SafetyMonitoringConfigurationUpdateRequest,
)
from app.services.safety_image_storage import InvalidSafetyImage, SafetyImageStorage


@dataclass(frozen=True, slots=True)
class SafetyEventImageDownload:
    content: bytes
    content_type: str


class SafetyMonitoringNotifier(Protocol):
    async def notify_safety_monitoring_config_available(
        self, elder_id: str, active_device_ids: set[str], revision: int
    ) -> bool: ...

    async def notify_safety_event_available(
        self,
        family_ids: set[str],
        elder_id: str,
        event_id: str,
        server_sequence: int,
        severity: str,
    ) -> bool: ...

    async def notify_safety_event_image_available(
        self,
        family_ids: set[str],
        elder_id: str,
        event_id: str,
    ) -> bool: ...


class SafetyMonitoringService:
    def __init__(
        self,
        session: AsyncSession,
        lock: asyncio.Lock,
        settings: Settings,
        notifier: SafetyMonitoringNotifier,
        image_storage: SafetyImageStorage,
    ) -> None:
        self.session = session
        self.lock = lock
        self.settings = settings
        self.notifier = notifier
        self.image_storage = image_storage
        self.repository = SafetyMonitoringRepository(session)

    async def get_configuration_for_family(
        self, family: FamilyAccount, elder_id: str
    ) -> SafetyMonitoringConfigurationResponse:
        async with self.session.begin():
            await self._require_family_binding(family.id, elder_id, for_event=False)
            configuration = await self.repository.get_configuration(elder_id)
            if configuration is None:
                raise ApiError(404, "SAFETY_CONFIG_NOT_FOUND", "尚未设置状态检测间隔")
            return self._configuration_response(configuration)

    async def get_configuration_for_device(
        self, device: DeviceCredential
    ) -> SafetyMonitoringConfigurationResponse:
        async with self.session.begin():
            await self._require_device_binding(device)
            configuration = await self.repository.get_configuration(device.elder_id)
            if configuration is None:
                raise ApiError(404, "SAFETY_CONFIG_NOT_FOUND", "尚未设置状态检测间隔")
            return self._configuration_response(configuration)

    async def update_configuration(
        self,
        family: FamilyAccount,
        elder_id: str,
        request: SafetyMonitoringConfigurationUpdateRequest,
        idempotency_key: str | None,
    ) -> SafetyMonitoringConfigurationResponse:
        self._validate_idempotency_key(idempotency_key, request.client_request_id)
        request_id = str(request.client_request_id)
        fingerprint = self._fingerprint(
            {"elder_id": elder_id, **request.model_dump(mode="json", exclude={"client_request_id"})}
        )
        should_notify = False
        active_device_ids: set[str] = set()

        async with self.lock:
            async with self.session.begin():
                await self._require_family_binding(family.id, elder_id, for_event=False)
                previous = await self.repository.get_configuration_request(family.id, request_id)
                if previous is not None:
                    if previous.request_fingerprint != fingerprint:
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一请求标识对应了不同内容",
                        )
                    return SafetyMonitoringConfigurationResponse.model_validate(
                        previous.response_payload
                    )

                current = await self.repository.get_configuration(elder_id)
                current_revision = current.revision if current is not None else None
                if request.expected_revision != current_revision:
                    raise ApiError(
                        409,
                        "SAFETY_CONFIG_REVISION_CONFLICT",
                        "状态检测配置已更新，请刷新后重试",
                        {"current_revision": current_revision},
                    )

                now = utc_now()
                revision = (current_revision or 0) + 1
                saved = await self.repository.save_configuration(
                    current,
                    elder_id=elder_id,
                    enabled=request.enabled,
                    interval_minutes=request.interval_minutes,
                    revision=revision,
                    family_id=family.id,
                    request_fingerprint=fingerprint,
                    now=now,
                )
                response = self._configuration_response(saved)
                self.repository.add_configuration_request(
                    family_id=family.id,
                    elder_id=elder_id,
                    client_request_id=request_id,
                    request_fingerprint=fingerprint,
                    response_payload=response.model_dump(mode="json"),
                    revision=revision,
                    created_at=now,
                )
                self.repository.add_audit(
                    action="SAFETY_MONITORING_CONFIG_UPDATED",
                    actor_type="FAMILY",
                    actor_id=family.id,
                    resource_type="SAFETY_MONITORING_CONFIG",
                    resource_id=saved.id,
                    elder_id=elder_id,
                    details={"revision": str(revision)},
                )
                active_device_ids = await self.repository.list_active_device_ids(elder_id)
                should_notify = True

        if should_notify:
            try:
                await self.notifier.notify_safety_monitoring_config_available(
                    elder_id, active_device_ids, response.revision
                )
            except Exception:
                pass
        return response

    async def create_event(
        self,
        device: DeviceCredential,
        request: SafetyEventCreateRequest,
        idempotency_key: str | None,
    ) -> SafetyEventResponse:
        self._validate_idempotency_key(idempotency_key, request.client_event_id)
        if request.occurred_at.tzinfo is None or request.occurred_at.utcoffset() != timedelta(0):
            raise ApiError(400, "INVALID_SAFETY_EVENT", "事件时间必须使用 UTC")
        occurred_at = request.occurred_at.astimezone(UTC)
        fingerprint = self._fingerprint(
            {
                "elder_id": device.elder_id,
                **request.model_dump(mode="json", exclude={"client_event_id"}),
            }
        )
        family_ids: set[str] = set()
        created = False

        async with self.lock:
            async with self.session.begin():
                await self._require_device_binding(device)
                existing = await self.repository.get_event_by_client_id(
                    str(request.client_event_id)
                )
                if existing is not None:
                    if existing.request_fingerprint != fingerprint:
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一事件标识对应了不同内容",
                        )
                    image = await self.repository.get_event_image(existing.event_id)
                    return self._event_response(existing, image)

                now = utc_now()
                if occurred_at > now + timedelta(
                    seconds=self.settings.safety_event_future_tolerance_seconds
                ) or occurred_at < now - timedelta(days=self.settings.safety_event_max_age_days):
                    raise ApiError(400, "INVALID_SAFETY_EVENT", "事件时间超出允许范围")
                recent_count = await self.repository.count_device_events_since(
                    device.id, now - timedelta(minutes=1)
                )
                if recent_count >= self.settings.safety_event_per_minute_limit:
                    raise ApiError(429, "SAFETY_EVENT_RATE_LIMITED", "安全事件提交过于频繁")

                event = await self.repository.create_event(
                    client_event_id=str(request.client_event_id),
                    elder_id=device.elder_id,
                    device_id=device.id,
                    server_sequence=await self.repository.next_event_sequence(),
                    occurred_at=occurred_at,
                    event_type=request.event_type.value,
                    event_summary=request.event_summary,
                    severity=self._required_severity(request.event_type).value,
                    request_fingerprint=fingerprint,
                    created_at=now,
                )
                self.repository.add_audit(
                    action="SAFETY_EVENT_CREATED",
                    actor_type="DEVICE",
                    actor_id=device.id,
                    resource_type="SAFETY_EVENT",
                    resource_id=event.event_id,
                    elder_id=device.elder_id,
                    details={
                        "event_type": event.event_type,
                        "severity": event.severity,
                    },
                )
                family_ids = await self.repository.list_authorized_family_ids(device.elder_id)
                response = self._event_response(event, None)
                created = True

        if created:
            try:
                await self.notifier.notify_safety_event_available(
                    family_ids,
                    device.elder_id,
                    str(response.event_id),
                    response.server_sequence,
                    response.severity.value,
                )
            except Exception:
                pass
        return response

    async def get_events(
        self, family: FamilyAccount, elder_id: str, scope: str
    ) -> SafetyEventsResponse:
        if scope not in {"today", "active_emergencies"}:
            raise ApiError(400, "INVALID_SAFETY_EVENT_SCOPE", "不支持该安全事件查询范围")
        async with self.session.begin():
            await self._require_family_binding(family.id, elder_id, for_event=True)
            time_zone = await self._resolve_time_zone(elder_id)
            zone = ZoneInfo(time_zone)
            synced_at = utc_now()
            current_date = synced_at.astimezone(zone).date()
            if scope == "today":
                started_at = datetime.combine(current_date, time.min, tzinfo=zone).astimezone(UTC)
                ended_at = started_at + timedelta(days=1)
                events = await self.repository.list_events(
                    elder_id,
                    started_at=started_at,
                    ended_at=ended_at,
                )
            else:
                events = await self.repository.list_events(elder_id, emergency_only=True)
            return SafetyEventsResponse(
                current_date=current_date,
                timezone=time_zone,
                events=[self._event_response(event, image) for event, image in events],
                synced_at=synced_at,
            )

    async def acknowledge_event(
        self,
        family: FamilyAccount,
        elder_id: str,
        event_id: str,
        request: SafetyEventAcknowledgementRequest,
        idempotency_key: str | None,
    ) -> SafetyEventResponse:
        self._validate_idempotency_key(idempotency_key, request.client_request_id)
        request_id = str(request.client_request_id)
        async with self.lock:
            async with self.session.begin():
                await self._require_family_binding(family.id, elder_id, for_event=True)
                previous = await self.repository.get_acknowledgement_request(family.id, request_id)
                if previous is not None:
                    if previous.elder_id != elder_id or previous.event_id != event_id:
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一请求标识对应了不同事件",
                        )
                    previous_event = await self.repository.get_event(previous.event_id)
                    if previous_event is None:
                        raise ApiError(404, "SAFETY_EVENT_NOT_FOUND", "安全事件不存在")
                    image = await self.repository.get_event_image(previous.event_id)
                    return self._event_response(previous_event, image)

                event = await self.repository.get_event(event_id)
                if event is None or event.elder_id != elder_id:
                    raise ApiError(404, "SAFETY_EVENT_NOT_FOUND", "安全事件不存在")
                now = utc_now()
                if event.acknowledged_at is None:
                    event.acknowledged_at = now
                    event.acknowledged_by_family_account_id = family.id
                self.repository.add_acknowledgement_request(
                    family_id=family.id,
                    elder_id=elder_id,
                    event_id=event_id,
                    client_request_id=request_id,
                    created_at=now,
                )
                self.repository.add_audit(
                    action="SAFETY_EVENT_ACKNOWLEDGED",
                    actor_type="FAMILY",
                    actor_id=family.id,
                    resource_type="SAFETY_EVENT",
                    resource_id=event.event_id,
                    elder_id=elder_id,
                    details={"severity": event.severity},
                )
                await self.session.flush()
                image = await self.repository.get_event_image(event.event_id)
                return self._event_response(event, image)

    async def resolve_event(
        self,
        family: FamilyAccount,
        elder_id: str,
        event_id: str,
        request: SafetyEventResolutionRequest,
        idempotency_key: str | None,
    ) -> SafetyEventResponse:
        self._validate_idempotency_key(idempotency_key, request.client_request_id)
        request_id = str(request.client_request_id)
        family_ids: set[str] = set()
        resolved_now = False
        async with self.lock:
            async with self.session.begin():
                await self._require_family_binding(family.id, elder_id, for_event=True)
                previous = await self.repository.get_resolution_request(family.id, request_id)
                if previous is not None:
                    if previous.elder_id != elder_id or previous.event_id != event_id:
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一请求标识对应了不同事件",
                        )
                    previous_event = await self.repository.get_event(previous.event_id)
                    if previous_event is None:
                        raise ApiError(404, "SAFETY_EVENT_NOT_FOUND", "安全事件不存在")
                    image = await self.repository.get_event_image(previous.event_id)
                    return self._event_response(previous_event, image)

                event = await self.repository.get_event(event_id)
                if event is None or event.elder_id != elder_id:
                    raise ApiError(404, "SAFETY_EVENT_NOT_FOUND", "安全事件不存在")
                now = utc_now()
                if event.resolved_at is None:
                    event.resolved_at = now
                    event.resolved_by_family_account_id = family.id
                    resolved_now = True
                    family_ids = await self.repository.list_authorized_family_ids(elder_id)
                    self.repository.add_audit(
                        action="SAFETY_EVENT_RESOLVED",
                        actor_type="FAMILY",
                        actor_id=family.id,
                        resource_type="SAFETY_EVENT",
                        resource_id=event.event_id,
                        elder_id=elder_id,
                        details={"severity": event.severity},
                    )
                self.repository.add_resolution_request(
                    family_id=family.id,
                    elder_id=elder_id,
                    event_id=event_id,
                    client_request_id=request_id,
                    created_at=now,
                )
                await self.session.flush()
                image = await self.repository.get_event_image(event.event_id)
                response = self._event_response(event, image)

        if resolved_now:
            try:
                await self.notifier.notify_safety_event_available(
                    family_ids,
                    elder_id,
                    event_id,
                    response.server_sequence,
                    response.severity.value,
                )
            except Exception:
                pass
        return response

    async def upload_event_image(
        self,
        device: DeviceCredential,
        event_id: str,
        content_type: str,
        data: bytes,
        idempotency_key: str | None,
    ) -> SafetyEventResponse:
        self._validate_idempotency_key(idempotency_key, UUID(event_id))
        if content_type not in {"image/jpeg", "image/png"} or not data:
            raise ApiError(
                400,
                "INVALID_SAFETY_EVENT_IMAGE",
                "只接受有效的 JPEG 或 PNG 图像",
            )
        if len(data) > self.settings.safety_image_max_bytes:
            raise ApiError(
                413,
                "SAFETY_EVENT_IMAGE_TOO_LARGE",
                "事件图像超过 8 MiB 限制",
            )
        await self.cleanup_expired_images()
        content_sha256 = hashlib.sha256(data).hexdigest()
        existing_response = await self._validate_image_upload_target(
            device, event_id, content_sha256
        )
        if existing_response is not None:
            return existing_response
        try:
            prepared = await self.image_storage.prepare(data, content_type)
        except InvalidSafetyImage as exc:
            raise ApiError(
                400,
                "INVALID_SAFETY_EVENT_IMAGE",
                "图像文件签名或内容无效",
            ) from exc

        stored = None
        family_ids: set[str] = set()
        try:
            async with self.lock:
                async with self.session.begin():
                    await self._require_device_binding(device)
                    event = self._require_image_upload_target(
                        await self.repository.get_event(event_id), device
                    )
                    existing = await self.repository.get_event_image(event_id)
                    if existing is not None:
                        if existing.content_sha256 != prepared.content_sha256:
                            raise ApiError(
                                409,
                                "IDEMPOTENCY_CONFLICT",
                                "该事件已经对应另一张图像",
                            )
                        return self._event_response(event, existing)
                    stored = await self.image_storage.save(prepared)
                    now = utc_now()
                    image = await self.repository.create_event_image(
                        event_id=event_id,
                        content_type=prepared.content_type,
                        byte_size=prepared.uploaded_byte_size,
                        content_sha256=prepared.content_sha256,
                        original_storage_name=stored.original_storage_name,
                        thumbnail_storage_name=stored.thumbnail_storage_name,
                        created_at=now,
                        expires_at=now + timedelta(days=self.settings.safety_image_retention_days),
                    )
                    self.repository.add_audit(
                        action="SAFETY_EVENT_IMAGE_STORED",
                        actor_type="DEVICE",
                        actor_id=device.id,
                        resource_type="SAFETY_EVENT_IMAGE",
                        resource_id=event_id,
                        elder_id=device.elder_id,
                        details={
                            "content_type": prepared.content_type,
                            "byte_size": str(prepared.uploaded_byte_size),
                        },
                    )
                    family_ids = await self.repository.list_authorized_family_ids(device.elder_id)
                    response = self._event_response(event, image)
        except Exception:
            if stored is not None:
                await self.image_storage.delete(
                    stored.original_storage_name,
                    stored.thumbnail_storage_name,
                )
            raise

        try:
            await self.notifier.notify_safety_event_image_available(
                family_ids,
                device.elder_id,
                event_id,
            )
        except Exception:
            pass
        return response

    async def download_event_image(
        self,
        family: FamilyAccount,
        elder_id: str,
        event_id: str,
        *,
        thumbnail: bool,
    ) -> SafetyEventImageDownload:
        await self.cleanup_expired_images()
        async with self.lock:
            async with self.session.begin():
                await self._require_family_binding(family.id, elder_id, for_event=True)
                event = await self.repository.get_event(event_id)
                if event is None or event.elder_id != elder_id:
                    raise ApiError(404, "SAFETY_EVENT_NOT_FOUND", "安全事件不存在")
                image = await self.repository.get_event_image(event_id)
                if image is None or ensure_utc(image.expires_at) <= utc_now():
                    raise ApiError(
                        404,
                        "SAFETY_EVENT_IMAGE_NOT_FOUND",
                        "事件图像不存在或已过期",
                    )
                view_count = await self.repository.count_image_views_since(
                    family.id,
                    event_id,
                    utc_now() - timedelta(minutes=1),
                )
                if view_count >= self.settings.safety_image_download_per_minute_limit:
                    raise ApiError(
                        429,
                        "SAFETY_EVENT_IMAGE_RATE_LIMITED",
                        "事件图像读取过于频繁",
                    )
                storage_name = (
                    image.thumbnail_storage_name if thumbnail else image.original_storage_name
                )
                content_type = image.content_type
                try:
                    content = await self.image_storage.read(storage_name, thumbnail=thumbnail)
                except (FileNotFoundError, ValueError) as exc:
                    raise ApiError(
                        404,
                        "SAFETY_EVENT_IMAGE_NOT_FOUND",
                        "事件图像不存在或已过期",
                    ) from exc
                self.repository.add_audit(
                    action="SAFETY_EVENT_IMAGE_VIEWED",
                    actor_type="FAMILY",
                    actor_id=family.id,
                    resource_type="SAFETY_EVENT_IMAGE",
                    resource_id=event_id,
                    elder_id=elder_id,
                    details={"variant": "thumbnail" if thumbnail else "original"},
                )
        return SafetyEventImageDownload(content=content, content_type=content_type)

    async def cleanup_expired_images(self) -> int:
        async with self.lock:
            async with self.session.begin():
                expired = await self.repository.list_expired_images(utc_now())
        deleted_ids: list[str] = []
        for image in expired:
            try:
                await self.image_storage.delete(
                    image.original_storage_name,
                    image.thumbnail_storage_name,
                )
            except Exception:
                continue
            deleted_ids.append(image.id)
        if deleted_ids:
            async with self.lock:
                async with self.session.begin():
                    await self.repository.delete_event_images(deleted_ids)
        return len(deleted_ids)

    async def _validate_image_upload_target(
        self,
        device: DeviceCredential,
        event_id: str,
        content_sha256: str,
    ) -> SafetyEventResponse | None:
        async with self.lock:
            async with self.session.begin():
                await self._require_device_binding(device)
                event = self._require_image_upload_target(
                    await self.repository.get_event(event_id), device
                )
                image = await self.repository.get_event_image(event_id)
                if image is None:
                    return None
                if image.content_sha256 != content_sha256:
                    raise ApiError(
                        409,
                        "IDEMPOTENCY_CONFLICT",
                        "该事件已经对应另一张图像",
                    )
                return self._event_response(event, image)

    @staticmethod
    def _require_image_upload_target(
        event: SafetyEvent | None,
        device: DeviceCredential,
    ) -> SafetyEvent:
        if event is None:
            raise ApiError(404, "SAFETY_EVENT_NOT_FOUND", "安全事件不存在")
        if event.elder_id != device.elder_id or event.source_device_id != device.id:
            raise ApiError(403, "SAFETY_EVENT_FORBIDDEN", "不能上传该事件的图像")
        if event.event_type not in {
            SafetyEventType.FALL_SUSPECTED.value,
            SafetyEventType.UNCONSCIOUSNESS_SUSPECTED.value,
            SafetyEventType.OTHER_ABNORMALITY.value,
        }:
            raise ApiError(
                400,
                "INVALID_SAFETY_EVENT_IMAGE",
                "该事件类型不允许上传图像",
            )
        return event

    async def _require_family_binding(
        self, family_id: str, elder_id: str, *, for_event: bool
    ) -> Binding:
        elder = await self.repository.get_elder(elder_id)
        binding = await self.repository.get_latest_family_binding(family_id, elder_id)
        error_code = "SAFETY_EVENT_FORBIDDEN" if for_event else "SAFETY_CONFIG_FORBIDDEN"
        if elder is None or not elder.is_active or binding is None:
            raise ApiError(403, error_code, "没有安全监测访问权限")
        if binding.revoked_at is not None:
            raise ApiError(403, error_code, "没有安全监测访问权限")
        if for_event and not {
            "VIEWER",
            "HELPER",
            "EMERGENCY_CONTACT",
            "OWNER",
        }.intersection(binding.permissions or []):
            raise ApiError(403, "SAFETY_EVENT_FORBIDDEN", "没有安全事件查看权限")
        return binding

    async def _require_device_binding(self, device: DeviceCredential) -> Binding:
        elder = await self.repository.get_elder(device.elder_id)
        binding = await self.repository.get_active_device_binding(device)
        if elder is None or not elder.is_active or binding is None:
            raise ApiError(410, "BINDING_REVOKED", "绑定关系已撤销")
        return binding

    async def _resolve_time_zone(self, elder_id: str) -> str:
        located = await self.repository.get_latest_time_zone_batch(elder_id, "LOCATION")
        if located is not None:
            return located.time_zone
        latest = await self.repository.get_latest_time_zone_batch(elder_id)
        return latest.time_zone if latest is not None else "UTC"

    @staticmethod
    def _validate_idempotency_key(value: str | None, request_id: UUID) -> None:
        try:
            header_id = UUID(value) if value else None
        except ValueError as exc:
            raise ApiError(400, "REQUEST_VALIDATION_ERROR", "幂等请求标识不正确") from exc
        if header_id != request_id:
            raise ApiError(400, "REQUEST_VALIDATION_ERROR", "幂等请求标识不一致")

    @staticmethod
    def _fingerprint(data: dict[str, object]) -> str:
        encoded = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(encoded.encode()).hexdigest()

    @staticmethod
    def _required_severity(event_type: SafetyEventType) -> SafetyEventSeverity:
        if event_type is SafetyEventType.FAMILY_REQUEST:
            return SafetyEventSeverity.GENERAL
        return SafetyEventSeverity.EMERGENCY

    @staticmethod
    def _configuration_response(
        configuration: SafetyMonitoringConfiguration,
    ) -> SafetyMonitoringConfigurationResponse:
        return SafetyMonitoringConfigurationResponse(
            enabled=configuration.enabled,
            interval_minutes=configuration.interval_minutes,
            revision=configuration.revision,
            updated_at=ensure_utc(configuration.updated_at),
        )

    @staticmethod
    def _event_response(
        event: SafetyEvent,
        image: SafetyEventImage | None,
    ) -> SafetyEventResponse:
        image_available = image is not None and ensure_utc(image.expires_at) > utc_now()
        return SafetyEventResponse(
            event_id=UUID(event.event_id),
            server_sequence=event.server_sequence,
            occurred_at=ensure_utc(event.occurred_at),
            event_type=SafetyEventType(event.event_type),
            event_summary=event.event_summary,
            severity=SafetyEventSeverity(event.severity),
            acknowledged_at=(
                ensure_utc(event.acknowledged_at) if event.acknowledged_at is not None else None
            ),
            resolved_at=ensure_utc(event.resolved_at) if event.resolved_at is not None else None,
            created_at=ensure_utc(event.created_at),
            image_available=image_available,
            image_content_type=image.content_type if image_available and image else None,
            image_byte_size=image.byte_size if image_available and image else None,
        )

import asyncio
import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Protocol
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import ApiError
from app.core.security import ensure_utc, utc_now
from app.models import Binding, Command, DeviceCredential, FamilyAccount
from app.repositories.commands import CommandRepository
from app.schemas.command import (
    AckType,
    CommandAckRequest,
    CommandAckResponse,
    CommandCreateResponse,
    CommandSender,
    CommandStatus,
    CommandType,
    NotificationCreateRequest,
    PendingCommand,
    PendingCommandsResponse,
    ReminderCreateRequest,
)


class CommandNotifier(Protocol):
    async def notify_command(self, command: Command) -> None: ...


class CommandService:
    def __init__(
        self,
        session: AsyncSession,
        settings: Settings,
        command_lock: asyncio.Lock,
        notifier: CommandNotifier,
    ) -> None:
        self.session = session
        self.settings = settings
        self.command_lock = command_lock
        self.notifier = notifier
        self.repository = CommandRepository(session)

    async def create_notification(
        self,
        family: FamilyAccount,
        elder_id: str,
        request: NotificationCreateRequest,
        idempotency_key: str | None,
    ) -> CommandCreateResponse:
        self._validate_idempotency_key(idempotency_key, request.client_request_id)
        now = utc_now()
        content = self._validated_text(request.content, 200)
        client_created_at = self._validated_client_time(request.created_at, now)
        return await self._create_command(
            family=family,
            elder_id=elder_id,
            command_type=CommandType.FAMILY_NOTIFICATION,
            title=None,
            content=content,
            scheduled_at=None,
            timezone=self.settings.command_default_timezone,
            client_created_at=client_created_at,
            client_request_id=request.client_request_id,
        )

    async def create_reminder(
        self,
        family: FamilyAccount,
        elder_id: str,
        request: ReminderCreateRequest,
        idempotency_key: str | None,
    ) -> CommandCreateResponse:
        self._validate_idempotency_key(idempotency_key, request.client_request_id)
        now = utc_now()
        title = self._validated_text(request.title, 40)
        content = self._validated_text(request.content, 200)
        scheduled_at = self._require_utc(request.scheduled_at)
        if scheduled_at <= now or scheduled_at > now + timedelta(days=365):
            raise ApiError(400, "INVALID_COMMAND_CONTENT", "提醒时间不正确")
        timezone = request.timezone.strip()
        try:
            ZoneInfo(timezone)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ApiError(400, "INVALID_COMMAND_CONTENT", "提醒时区不正确") from exc
        return await self._create_command(
            family=family,
            elder_id=elder_id,
            command_type=CommandType.REMOTE_REMINDER,
            title=title,
            content=content,
            scheduled_at=scheduled_at,
            timezone=timezone,
            client_created_at=None,
            client_request_id=request.client_request_id,
        )

    async def _create_command(
        self,
        *,
        family: FamilyAccount,
        elder_id: str,
        command_type: CommandType,
        title: str | None,
        content: str,
        scheduled_at: datetime | None,
        timezone: str,
        client_created_at: datetime | None,
        client_request_id: UUID,
    ) -> CommandCreateResponse:
        request_id = str(client_request_id)
        fingerprint = self._fingerprint(
            elder_id=elder_id,
            command_type=command_type,
            title=title,
            content=content,
            scheduled_at=scheduled_at,
            timezone=timezone,
            client_created_at=client_created_at,
        )
        async with self.command_lock:
            async with self.session.begin():
                binding = await self._require_family_binding(family.id, elder_id)
                existing = await self.repository.get_command_by_idempotency(family.id, request_id)
                if existing is not None:
                    if existing.request_fingerprint != fingerprint:
                        raise ApiError(
                            409,
                            "IDEMPOTENCY_CONFLICT",
                            "同一请求标识对应了不同内容",
                        )
                    command = existing
                else:
                    await self._enforce_rate_limit(family.id, elder_id)
                    command = await self.repository.create_command(
                        server_sequence=await self.repository.next_server_sequence(),
                        elder_id=elder_id,
                        binding_id=binding.id,
                        actor_family_id=family.id,
                        command_type=command_type.value,
                        title=title,
                        content=content,
                        scheduled_at=scheduled_at,
                        timezone=timezone,
                        client_request_id=request_id,
                        request_fingerprint=fingerprint,
                        client_created_at=client_created_at,
                    )
                    self.repository.add_audit(
                        "COMMAND_CREATED",
                        "FAMILY",
                        family.id,
                        "COMMAND",
                        command.id,
                        {"elder_id": elder_id, "command_type": command_type.value},
                    )
        if existing is None:
            try:
                await self.notifier.notify_command(command)
            except Exception:
                # REST/SQLite remain the reliable delivery path.
                pass
        return self._create_response(command)

    async def list_pending(
        self,
        device: DeviceCredential,
        after_sequence: int,
        limit: int,
    ) -> PendingCommandsResponse:
        if await self.repository.get_active_binding_for_device(device) is None:
            raise ApiError(410, "BINDING_REVOKED", "绑定关系已撤销")
        rows = await self.repository.list_commands_for_device(device, after_sequence, limit + 1)
        has_more = len(rows) > limit
        page = rows[:limit]
        commands = [
            PendingCommand(
                command_id=UUID(command.id),
                server_sequence=command.server_sequence,
                elder_id=UUID(command.elder_id),
                command_type=CommandType(command.command_type),
                title=command.title,
                content=command.content,
                scheduled_at=(ensure_utc(command.scheduled_at) if command.scheduled_at else None),
                timezone=command.timezone,
                sender=CommandSender(display_name=family.display_name),
                created_at=ensure_utc(command.created_at),
            )
            for command, family in page
        ]
        next_after = commands[-1].server_sequence if commands else after_sequence
        return PendingCommandsResponse(
            commands=commands,
            next_after_sequence=next_after,
            has_more=has_more,
        )

    async def acknowledge(
        self,
        device: DeviceCredential,
        command_id: str,
        request: CommandAckRequest,
        idempotency_key: str | None,
    ) -> CommandAckResponse:
        self._validate_idempotency_key(idempotency_key, request.client_request_id)
        stored_at = self._require_utc(request.stored_at)
        now = utc_now()
        if stored_at > now + timedelta(seconds=self.settings.command_client_clock_skew_seconds):
            raise ApiError(400, "INVALID_COMMAND_CONTENT", "存储时间不正确")
        async with self.session.begin():
            if await self.repository.get_active_binding_for_device(device) is None:
                raise ApiError(410, "BINDING_REVOKED", "绑定关系已撤销")
            command = await self.repository.get_command(command_id)
            if command is None or command.elder_id != device.elder_id:
                raise ApiError(404, "COMMAND_NOT_FOUND", "命令不存在")
            existing = await self.repository.get_receipt(
                device.id, command.id, request.ack_type.value
            )
            if existing is None:
                request_receipt = await self.repository.get_receipt_by_request(
                    device.id, str(request.client_request_id)
                )
                if request_receipt is not None:
                    raise ApiError(
                        409,
                        "IDEMPOTENCY_CONFLICT",
                        "同一请求标识对应了不同内容",
                    )
                existing = await self.repository.create_receipt(
                    command_id=command.id,
                    device_id=device.id,
                    ack_type=AckType.STORED.value,
                    client_request_id=str(request.client_request_id),
                    stored_at=stored_at,
                    acked_at=now,
                )
                self.repository.add_audit(
                    "COMMAND_STORED",
                    "DEVICE",
                    device.id,
                    "COMMAND",
                    command.id,
                    {"elder_id": device.elder_id, "ack_type": AckType.STORED.value},
                )
        return CommandAckResponse(
            command_id=UUID(command_id),
            status=CommandStatus.STORED,
            acked_at=ensure_utc(existing.acked_at),
        )

    async def _require_family_binding(self, family_id: str, elder_id: str) -> Binding:
        elder = await self.repository.get_elder(elder_id)
        binding = await self.repository.get_latest_binding_for_family(family_id, elder_id)
        if elder is None or not elder.is_active or binding is None:
            raise ApiError(404, "ELDER_NOT_FOUND", "老人档案不存在")
        if binding.revoked_at is not None:
            raise ApiError(410, "BINDING_REVOKED", "绑定关系已撤销")
        if not ({"HELPER", "OWNER"} & set(binding.permissions)):
            raise ApiError(403, "COMMAND_FORBIDDEN", "没有发送通知或提醒的权限")
        return binding

    async def _enforce_rate_limit(self, family_id: str, elder_id: str) -> None:
        now = utc_now()
        minute_count = await self.repository.count_commands_since(
            family_id, elder_id, now - timedelta(minutes=1)
        )
        day_count = await self.repository.count_commands_since(
            family_id, elder_id, now - timedelta(days=1)
        )
        if (
            minute_count >= self.settings.command_per_minute_limit
            or day_count >= self.settings.command_per_day_limit
        ):
            raise ApiError(429, "COMMAND_RATE_LIMITED", "发送频率较高，请稍后再试")

    def _validated_client_time(self, value: datetime, now: datetime) -> datetime:
        normalized = self._require_utc(value)
        skew = timedelta(seconds=self.settings.command_client_clock_skew_seconds)
        if abs(normalized - now) > skew:
            raise ApiError(400, "INVALID_COMMAND_CONTENT", "客户端时间偏差过大")
        return normalized

    @staticmethod
    def _validated_text(value: str, maximum: int) -> str:
        normalized = value.strip()
        if not normalized or len(normalized) > maximum:
            raise ApiError(400, "INVALID_COMMAND_CONTENT", "通知或提醒内容不正确")
        return normalized

    @staticmethod
    def _require_utc(value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() != timedelta(0):
            raise ApiError(400, "INVALID_COMMAND_CONTENT", "时间必须使用 UTC")
        return value.astimezone(UTC)

    @staticmethod
    def _validate_idempotency_key(value: str | None, request_id: UUID) -> None:
        try:
            header_id = UUID(value) if value else None
        except ValueError as exc:
            raise ApiError(400, "REQUEST_VALIDATION_ERROR", "幂等请求标识不正确") from exc
        if header_id != request_id:
            raise ApiError(400, "REQUEST_VALIDATION_ERROR", "幂等请求标识不一致")

    @staticmethod
    def _fingerprint(
        *,
        elder_id: str,
        command_type: CommandType,
        title: str | None,
        content: str,
        scheduled_at: datetime | None,
        timezone: str,
        client_created_at: datetime | None,
    ) -> str:
        data = {
            "elder_id": elder_id,
            "command_type": command_type.value,
            "title": title,
            "content": content,
            "scheduled_at": scheduled_at.isoformat() if scheduled_at else None,
            "timezone": timezone,
            "client_created_at": client_created_at.isoformat() if client_created_at else None,
        }
        encoded = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(encoded.encode()).hexdigest()

    @staticmethod
    def _create_response(command: Command) -> CommandCreateResponse:
        return CommandCreateResponse(
            command_id=UUID(command.id),
            elder_id=UUID(command.elder_id),
            command_type=CommandType(command.command_type),
            server_sequence=command.server_sequence,
            status=CommandStatus.PENDING,
            created_at=ensure_utc(command.created_at),
        )

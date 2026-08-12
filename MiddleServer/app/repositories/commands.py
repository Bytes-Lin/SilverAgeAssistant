from datetime import datetime
from typing import cast

from sqlalchemy import and_, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    AuditLog,
    Binding,
    Command,
    CommandCompletion,
    CommandReceipt,
    DeviceCredential,
    ElderProfile,
    FamilyAccount,
    IdempotencyRecord,
    ReminderArchive,
)


class CommandRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_elder(self, elder_id: str) -> ElderProfile | None:
        return await self.session.get(ElderProfile, elder_id)

    async def get_latest_binding_for_family(self, family_id: str, elder_id: str) -> Binding | None:
        query = (
            select(Binding)
            .where(
                Binding.family_account_id == family_id,
                Binding.elder_id == elder_id,
            )
            .order_by(Binding.created_at.desc())
            .limit(1)
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_active_binding_for_device(self, device: DeviceCredential) -> Binding | None:
        query = select(Binding).where(
            Binding.id == device.binding_id,
            Binding.elder_id == device.elder_id,
            Binding.revoked_at.is_(None),
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_command(self, command_id: str) -> Command | None:
        return await self.session.get(Command, command_id)

    async def get_command_by_idempotency(
        self, family_id: str, client_request_id: str
    ) -> Command | None:
        query = select(Command).where(
            Command.actor_family_id == family_id,
            Command.client_request_id == client_request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def count_commands_since(self, family_id: str, elder_id: str, since: datetime) -> int:
        query = select(func.count(Command.id)).where(
            Command.actor_family_id == family_id,
            Command.elder_id == elder_id,
            Command.created_at >= since,
        )
        return int((await self.session.scalar(query)) or 0)

    async def next_server_sequence(self) -> int:
        current = await self.session.scalar(select(func.max(Command.server_sequence)))
        return int(current or 0) + 1

    async def create_command(
        self,
        *,
        server_sequence: int,
        elder_id: str,
        binding_id: str,
        actor_family_id: str,
        command_type: str,
        title: str | None,
        content: str,
        scheduled_at: datetime | None,
        timezone: str,
        client_request_id: str,
        request_fingerprint: str,
        client_created_at: datetime | None,
    ) -> Command:
        command = Command(
            server_sequence=server_sequence,
            elder_id=elder_id,
            binding_id=binding_id,
            actor_family_id=actor_family_id,
            command_type=command_type,
            title=title,
            content=content,
            scheduled_at=scheduled_at,
            timezone=timezone,
            client_request_id=client_request_id,
            request_fingerprint=request_fingerprint,
            client_created_at=client_created_at,
        )
        self.session.add(command)
        await self.session.flush()
        return command

    async def list_commands_for_device(
        self,
        device: DeviceCredential,
        after_sequence: int,
        limit: int,
    ) -> list[tuple[Command, FamilyAccount]]:
        query = (
            select(Command, FamilyAccount)
            .join(FamilyAccount, FamilyAccount.id == Command.actor_family_id)
            .where(
                Command.elder_id == device.elder_id,
                Command.server_sequence > after_sequence,
            )
            .order_by(Command.server_sequence.asc())
            .limit(limit)
        )
        rows = (await self.session.execute(query)).tuples().all()
        return cast(list[tuple[Command, FamilyAccount]], list(rows))

    async def get_receipt(
        self, device_id: str, command_id: str, ack_type: str
    ) -> CommandReceipt | None:
        query = select(CommandReceipt).where(
            CommandReceipt.device_id == device_id,
            CommandReceipt.command_id == command_id,
            CommandReceipt.ack_type == ack_type,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_receipt_by_request(
        self, device_id: str, client_request_id: str
    ) -> CommandReceipt | None:
        query = select(CommandReceipt).where(
            CommandReceipt.device_id == device_id,
            CommandReceipt.client_request_id == client_request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def create_receipt(
        self,
        *,
        command_id: str,
        device_id: str,
        ack_type: str,
        client_request_id: str,
        stored_at: datetime,
        acked_at: datetime,
    ) -> CommandReceipt:
        receipt = CommandReceipt(
            command_id=command_id,
            device_id=device_id,
            ack_type=ack_type,
            client_request_id=client_request_id,
            stored_at=stored_at,
            acked_at=acked_at,
        )
        self.session.add(receipt)
        await self.session.flush()
        return receipt

    async def get_completion(self, command_id: str) -> CommandCompletion | None:
        return await self.session.get(CommandCompletion, command_id)

    async def get_completion_by_request(
        self, client_request_id: str
    ) -> CommandCompletion | None:
        query = select(CommandCompletion).where(
            CommandCompletion.client_request_id == client_request_id
        )
        return (await self.session.scalars(query)).one_or_none()

    async def create_completion(
        self,
        *,
        command_id: str,
        elder_id: str,
        device_id: str,
        client_request_id: str,
        completed_at: datetime,
        reported_at: datetime,
    ) -> CommandCompletion:
        completion = CommandCompletion(
            command_id=command_id,
            elder_id=elder_id,
            device_id=device_id,
            status="COMPLETED",
            client_request_id=client_request_id,
            completed_at=completed_at,
            reported_at=reported_at,
        )
        self.session.add(completion)
        await self.session.flush()
        return completion

    async def list_reminders_for_family(
        self,
        family_id: str,
        elder_id: str,
        limit: int,
        cursor_scheduled_at: datetime | None,
        cursor_command_id: str | None,
    ) -> list[Command]:
        filters = [
            Command.elder_id == elder_id,
            Command.command_type == "REMOTE_REMINDER",
            Command.scheduled_at.is_not(None),
            ~select(ReminderArchive.id)
            .where(
                ReminderArchive.family_account_id == family_id,
                ReminderArchive.command_id == Command.id,
            )
            .exists(),
        ]
        if cursor_scheduled_at is not None and cursor_command_id is not None:
            filters.append(
                or_(
                    Command.scheduled_at < cursor_scheduled_at,
                    and_(
                        Command.scheduled_at == cursor_scheduled_at,
                        Command.id < cursor_command_id,
                    ),
                )
            )
        query = (
            select(Command)
            .where(*filters)
            .order_by(Command.scheduled_at.desc(), Command.id.desc())
            .limit(limit)
        )
        return list((await self.session.scalars(query)).all())

    async def list_first_stored_at(self, command_ids: list[str]) -> dict[str, datetime]:
        if not command_ids:
            return {}
        query = (
            select(CommandReceipt.command_id, func.min(CommandReceipt.stored_at))
            .where(
                CommandReceipt.command_id.in_(command_ids),
                CommandReceipt.ack_type == "STORED",
            )
            .group_by(CommandReceipt.command_id)
        )
        rows = (await self.session.execute(query)).all()
        return {command_id: stored_at for command_id, stored_at in rows}

    async def list_completions(self, command_ids: list[str]) -> dict[str, CommandCompletion]:
        if not command_ids:
            return {}
        query = select(CommandCompletion).where(CommandCompletion.command_id.in_(command_ids))
        return {
            completion.command_id: completion
            for completion in (await self.session.scalars(query)).all()
        }

    async def get_archive_by_request(
        self, family_id: str, client_request_id: str
    ) -> ReminderArchive | None:
        actor_scope = f"family:{family_id}"
        command_id = await self.session.scalar(
            select(IdempotencyRecord.resource_id).where(
                IdempotencyRecord.actor_scope == actor_scope,
                IdempotencyRecord.operation == "REMINDER_ARCHIVE",
                IdempotencyRecord.client_request_id == client_request_id,
            )
        )
        if command_id is not None:
            return await self.get_archive_by_command(family_id, command_id)
        query = select(ReminderArchive).where(
            ReminderArchive.family_account_id == family_id,
            ReminderArchive.client_request_id == client_request_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def get_archive_by_command(
        self, family_id: str, command_id: str
    ) -> ReminderArchive | None:
        query = select(ReminderArchive).where(
            ReminderArchive.family_account_id == family_id,
            ReminderArchive.command_id == command_id,
        )
        return (await self.session.scalars(query)).one_or_none()

    async def create_archive(
        self,
        *,
        family_id: str,
        elder_id: str,
        command_id: str,
        client_request_id: str,
        archived_at: datetime,
    ) -> ReminderArchive:
        archive = ReminderArchive(
            family_account_id=family_id,
            elder_id=elder_id,
            command_id=command_id,
            client_request_id=client_request_id,
            archived_at=archived_at,
        )
        self.session.add(archive)
        await self.session.flush()
        return archive

    async def record_archive_request(
        self, family_id: str, client_request_id: str, command_id: str
    ) -> None:
        self.session.add(
            IdempotencyRecord(
                actor_scope=f"family:{family_id}",
                operation="REMINDER_ARCHIVE",
                client_request_id=client_request_id,
                resource_id=command_id,
            )
        )
        await self.session.flush()

    def add_audit(
        self,
        action: str,
        actor_type: str,
        actor_id: str,
        resource_type: str,
        resource_id: str,
        details: dict[str, str],
    ) -> None:
        self.session.add(
            AuditLog(
                action=action,
                actor_type=actor_type,
                actor_id=actor_id,
                resource_type=resource_type,
                resource_id=resource_id,
                details=details,
            )
        )

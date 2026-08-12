import json
from datetime import timedelta
from typing import Any
from unittest.mock import AsyncMock
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

from fastapi import FastAPI
from httpx import AsyncClient, Response
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import utc_now
from app.models import AuditLog, Binding, CommandCompletion, ReminderArchive
from app.websocket.manager import ConnectionManager
from tests.conftest import bind_payload, create_code, create_elder, register_family

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


async def prepared_binding(
    client: AsyncClient,
    *,
    family_mobile: str = "13800138000",
    elder_mobile: str = "13900139000",
    device_id: str = "device-reminder-completion-001",
    request_prefix: str = "8",
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    family = await register_family(
        client,
        mobile=family_mobile,
        request_id=f"{request_prefix}1111111-1111-4111-8111-111111111111",
    )
    elder = await create_elder(
        client,
        family["access_token"],
        mobile=elder_mobile,
        request_id=f"{request_prefix}2222222-2222-4222-8222-222222222222",
    )
    code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id=f"{request_prefix}3333333-3333-4333-8333-333333333333",
    )
    bound = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            code["binding_code"],
            mobile=family_mobile,
            device_id=device_id,
            request_id=f"{request_prefix}4444444-4444-4444-8444-444444444444",
        ),
    )
    assert bound.status_code == 201, bound.text
    return family, elder, bound.json()


async def create_reminder(
    client: AsyncClient,
    family_token: str,
    elder_id: str,
    *,
    scheduled_at: str,
    title: str = "量血压",
    content: str = "测量后把结果记下来。",
) -> Response:
    request_id = str(uuid4())
    return await client.post(
        f"/api/v1/elders/{elder_id}/commands/reminders",
        headers={
            "Authorization": f"Bearer {family_token}",
            "Idempotency-Key": request_id,
        },
        json={
            "client_request_id": request_id,
            "title": title,
            "content": content,
            "scheduled_at": scheduled_at,
            "timezone": "Asia/Shanghai",
        },
    )


async def acknowledge_reminder(
    client: AsyncClient,
    credential: str,
    command_id: str,
) -> Response:
    request_id = str(uuid4())
    return await client.post(
        f"/api/v1/commands/{command_id}/ack",
        headers={
            "Authorization": f"Bearer {credential}",
            "Idempotency-Key": request_id,
        },
        json={
            "client_request_id": request_id,
            "ack_type": "STORED",
            "stored_at": utc_now().isoformat(),
        },
    )


async def complete_reminder(
    client: AsyncClient,
    credential: str,
    command_id: str,
    *,
    request_id: str,
    completed_at: str,
) -> Response:
    return await client.post(
        f"/api/v1/commands/{command_id}/completion",
        headers={
            "Authorization": f"Bearer {credential}",
            "Idempotency-Key": request_id,
        },
        json={
            "client_request_id": request_id,
            "status": "COMPLETED",
            "completed_at": completed_at,
        },
    )


async def archive_reminder(
    client: AsyncClient,
    family_token: str,
    elder_id: str,
    command_id: str,
    *,
    request_id: str,
) -> Response:
    return await client.post(
        f"/api/v1/elders/{elder_id}/reminders/{command_id}/archive",
        headers={
            "Authorization": f"Bearer {family_token}",
            "Idempotency-Key": request_id,
        },
        json={"client_request_id": request_id},
    )


async def test_completion_is_idempotent_and_history_aggregates_states(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    app.state.connection_manager.notify_reminder_status_changed = AsyncMock(return_value=True)
    scheduled_at = (utc_now() + timedelta(hours=1)).isoformat()
    created = await create_reminder(
        client,
        family["access_token"],
        elder["elder_id"],
        scheduled_at=scheduled_at,
    )
    assert created.status_code == 201, created.text
    command_id = created.json()["command_id"]

    pending_history = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert pending_history.status_code == 200
    assert pending_history.headers["Cache-Control"] == "no-store"
    assert pending_history.json()["reminders"][0]["delivery_status"] == "PENDING"
    assert pending_history.json()["reminders"][0]["completion_status"] == "PENDING"

    ack = await acknowledge_reminder(client, bound["device_credential"], command_id)
    assert ack.status_code == 200, ack.text
    request_id = str(uuid4())
    completed_at = utc_now().isoformat()
    first = await complete_reminder(
        client,
        bound["device_credential"],
        command_id,
        request_id=request_id,
        completed_at=completed_at,
    )
    retry = await complete_reminder(
        client,
        bound["device_credential"],
        command_id,
        request_id=request_id,
        completed_at=completed_at,
    )
    second_request = await complete_reminder(
        client,
        bound["device_credential"],
        command_id,
        request_id=str(uuid4()),
        completed_at=(utc_now() - timedelta(seconds=1)).isoformat(),
    )
    assert first.status_code == retry.status_code == second_request.status_code == 200
    assert first.json() == retry.json() == second_request.json()
    assert first.json()["status"] == "COMPLETED"

    history = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    item = history.json()["reminders"][0]
    assert item == {
        "command_id": command_id,
        "title": "量血压",
        "content": "测量后把结果记下来。",
        "scheduled_at": scheduled_at.replace("+00:00", "Z"),
        "timezone": "Asia/Shanghai",
        "created_at": created.json()["created_at"],
        "delivery_status": "STORED",
        "completion_status": "COMPLETED",
        "stored_at": item["stored_at"],
        "completed_at": first.json()["completed_at"],
    }
    notifier = app.state.connection_manager.notify_reminder_status_changed
    notifier.assert_awaited_once()
    assert notifier.await_args.args[1:] == (elder["elder_id"], command_id)

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(CommandCompletion.command_id))) == 1
        audits = (await session.scalars(select(AuditLog))).all()
        serialized = json.dumps([audit.details for audit in audits], ensure_ascii=False)
        assert "测量后" not in serialized


async def test_completion_rejects_wrong_command_device_time_and_idempotency(
    api: ApiFixture,
) -> None:
    client, _, _ = api
    family, elder, bound = await prepared_binding(client)
    other_family, other_elder, other_bound = await prepared_binding(
        client,
        family_mobile="13700137000",
        elder_mobile="13600136000",
        device_id="device-reminder-completion-002",
        request_prefix="9",
    )
    scheduled_at = (utc_now() + timedelta(hours=1)).isoformat()
    reminder = await create_reminder(
        client, family["access_token"], elder["elder_id"], scheduled_at=scheduled_at
    )
    notification_id = str(uuid4())
    notification = await client.post(
        f"/api/v1/elders/{elder['elder_id']}/commands/notifications",
        headers={
            "Authorization": f"Bearer {family['access_token']}",
            "Idempotency-Key": notification_id,
        },
        json={
            "client_request_id": notification_id,
            "content": "即时通知",
            "created_at": utc_now().isoformat(),
        },
    )
    assert reminder.status_code == notification.status_code == 201

    completion_id = str(uuid4())
    completed_at = utc_now().isoformat()
    wrong_device = await complete_reminder(
        client,
        other_bound["device_credential"],
        reminder.json()["command_id"],
        request_id=completion_id,
        completed_at=completed_at,
    )
    notification_completion = await complete_reminder(
        client,
        bound["device_credential"],
        notification.json()["command_id"],
        request_id=str(uuid4()),
        completed_at=completed_at,
    )
    non_utc = await complete_reminder(
        client,
        bound["device_credential"],
        reminder.json()["command_id"],
        request_id=str(uuid4()),
        completed_at=utc_now().astimezone(ZoneInfo("Asia/Shanghai")).isoformat(),
    )
    future = await complete_reminder(
        client,
        bound["device_credential"],
        reminder.json()["command_id"],
        request_id=str(uuid4()),
        completed_at=(utc_now() + timedelta(minutes=6)).isoformat(),
    )
    mismatch_id = str(uuid4())
    mismatch = await client.post(
        f"/api/v1/commands/{reminder.json()['command_id']}/completion",
        headers={
            "Authorization": f"Bearer {bound['device_credential']}",
            "Idempotency-Key": mismatch_id,
        },
        json={
            "client_request_id": str(uuid4()),
            "status": "COMPLETED",
            "completed_at": completed_at,
        },
    )
    assert wrong_device.status_code == 404
    assert wrong_device.json()["error"]["code"] == "COMMAND_NOT_FOUND"
    assert notification_completion.status_code == 400
    assert notification_completion.json()["error"]["code"] == "COMMAND_NOT_COMPLETABLE"
    own_history = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert [item["command_id"] for item in own_history.json()["reminders"]] == [
        reminder.json()["command_id"]
    ]
    for response in (non_utc, future, mismatch):
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

    first = await complete_reminder(
        client,
        bound["device_credential"],
        reminder.json()["command_id"],
        request_id=completion_id,
        completed_at=completed_at,
    )
    conflict = await complete_reminder(
        client,
        bound["device_credential"],
        reminder.json()["command_id"],
        request_id=completion_id,
        completed_at=(utc_now() - timedelta(minutes=1)).isoformat(),
    )
    assert first.status_code == 200
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    foreign_history = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers={"Authorization": f"Bearer {other_family['access_token']}"},
    )
    assert foreign_history.status_code == 403
    assert foreign_history.json()["error"]["code"] == "COMMAND_FORBIDDEN"
    assert other_elder["elder_id"] != elder["elder_id"]


async def test_websocket_failure_does_not_rollback_completion(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    app.state.connection_manager.notify_reminder_status_changed = AsyncMock(
        side_effect=RuntimeError("family websocket unavailable")
    )
    reminder = await create_reminder(
        client,
        family["access_token"],
        elder["elder_id"],
        scheduled_at=(utc_now() + timedelta(hours=1)).isoformat(),
    )
    response = await complete_reminder(
        client,
        bound["device_credential"],
        reminder.json()["command_id"],
        request_id=str(uuid4()),
        completed_at=utc_now().isoformat(),
    )
    assert response.status_code == 200, response.text
    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(CommandCompletion.command_id))) == 1


async def test_reminder_history_cursor_is_stable_and_binding_is_required(
    api: ApiFixture,
) -> None:
    client, _, settings = api
    settings.command_per_minute_limit = 20
    family, elder, bound = await prepared_binding(client)
    created_ids: list[str] = []
    same_scheduled_at = (utc_now() + timedelta(hours=3)).isoformat()
    for index in range(3):
        response = await create_reminder(
            client,
            family["access_token"],
            elder["elder_id"],
            scheduled_at=same_scheduled_at,
            title=f"提醒 {index}",
        )
        assert response.status_code == 201, response.text
        created_ids.append(response.json()["command_id"])

    headers = {"Authorization": f"Bearer {family['access_token']}"}
    first = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders?limit=2", headers=headers
    )
    assert first.status_code == 200
    expected_order = sorted(created_ids, reverse=True)
    assert [item["command_id"] for item in first.json()["reminders"]] == expected_order[:2]
    cursor = first.json()["next_cursor"]
    assert cursor is not None
    second = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers=headers,
        params={"limit": 2, "cursor": cursor},
    )
    assert [item["command_id"] for item in second.json()["reminders"]] == expected_order[2:]
    assert second.json()["next_cursor"] is None
    assert not (
        {item["command_id"] for item in first.json()["reminders"]}
        & {item["command_id"] for item in second.json()["reminders"]}
    )

    invalid_cursor = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers=headers,
        params={"cursor": "not-a-valid-cursor"},
    )
    assert invalid_cursor.status_code == 400
    assert invalid_cursor.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

    revoked = await client.delete(
        f"/api/v1/bindings/{bound['binding_id']}",
        headers=headers,
    )
    assert revoked.status_code == 204
    after_revoke = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders", headers=headers
    )
    assert after_revoke.status_code == 410
    assert after_revoke.json()["error"]["code"] == "BINDING_REVOKED"


async def test_family_archive_is_idempotent_and_does_not_delete_reminder_state(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    reminder = await create_reminder(
        client,
        family["access_token"],
        elder["elder_id"],
        scheduled_at=(utc_now() + timedelta(hours=2)).isoformat(),
    )
    command_id = reminder.json()["command_id"]
    await acknowledge_reminder(client, bound["device_credential"], command_id)
    await complete_reminder(
        client,
        bound["device_credential"],
        command_id,
        request_id=str(uuid4()),
        completed_at=utc_now().isoformat(),
    )

    request_id = str(uuid4())
    first = await archive_reminder(
        client,
        family["access_token"],
        elder["elder_id"],
        command_id,
        request_id=request_id,
    )
    retry = await archive_reminder(
        client,
        family["access_token"],
        elder["elder_id"],
        command_id,
        request_id=request_id,
    )
    alias_request_id = str(uuid4())
    another_request = await archive_reminder(
        client,
        family["access_token"],
        elder["elder_id"],
        command_id,
        request_id=alias_request_id,
    )
    alias_conflict = await archive_reminder(
        client,
        family["access_token"],
        elder["elder_id"],
        str(uuid4()),
        request_id=alias_request_id,
    )
    assert first.status_code == retry.status_code == another_request.status_code == 200
    assert first.json() == retry.json() == another_request.json()
    assert first.json()["archived"] is True
    assert alias_conflict.status_code == 409
    assert alias_conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    history = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    pending = await client.get(
        "/api/v1/commands/pending?after_sequence=0&limit=100",
        headers={"Authorization": f"Bearer {bound['device_credential']}"},
    )
    assert history.json()["reminders"] == []
    assert command_id in {item["command_id"] for item in pending.json()["commands"]}
    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ReminderArchive.id))) == 1
        assert await session.scalar(select(func.count(CommandCompletion.command_id))) == 1


async def test_archive_is_family_scoped_and_rejects_conflicts_and_notifications(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    creator, elder, _ = await prepared_binding(client)
    reminder_one = await create_reminder(
        client,
        creator["access_token"],
        elder["elder_id"],
        scheduled_at=(utc_now() + timedelta(hours=2)).isoformat(),
    )
    reminder_two = await create_reminder(
        client,
        creator["access_token"],
        elder["elder_id"],
        scheduled_at=(utc_now() + timedelta(hours=3)).isoformat(),
    )
    other = await register_family(
        client,
        mobile="13700137000",
        request_id="91111111-1111-4111-8111-111111111111",
    )
    async with app.state.database.session_factory() as session:
        async with session.begin():
            session.add(
                Binding(
                    elder_id=elder["elder_id"],
                    family_account_id=other["family_account_id"],
                    relationship="RELATIVE",
                    permissions=["VIEWER"],
                    audit_source="TEST",
                )
            )

    request_id = str(uuid4())
    archived = await archive_reminder(
        client,
        creator["access_token"],
        elder["elder_id"],
        reminder_one.json()["command_id"],
        request_id=request_id,
    )
    conflict = await archive_reminder(
        client,
        creator["access_token"],
        elder["elder_id"],
        reminder_two.json()["command_id"],
        request_id=request_id,
    )
    other_history = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/reminders",
        headers={"Authorization": f"Bearer {other['access_token']}"},
    )
    notification_request_id = str(uuid4())
    notification = await client.post(
        f"/api/v1/elders/{elder['elder_id']}/commands/notifications",
        headers={
            "Authorization": f"Bearer {creator['access_token']}",
            "Idempotency-Key": notification_request_id,
        },
        json={
            "client_request_id": notification_request_id,
            "content": "这是一条即时通知",
            "created_at": utc_now().isoformat(),
        },
    )
    not_archivable = await archive_reminder(
        client,
        creator["access_token"],
        elder["elder_id"],
        notification.json()["command_id"],
        request_id=str(uuid4()),
    )

    assert archived.status_code == 200
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"
    assert other_history.status_code == 200
    assert reminder_one.json()["command_id"] in {
        item["command_id"] for item in other_history.json()["reminders"]
    }
    assert not_archivable.status_code == 400
    assert not_archivable.json()["error"]["code"] == "COMMAND_NOT_ARCHIVABLE"


async def test_reminder_status_websocket_hint_is_minimal() -> None:
    manager = ConnectionManager()
    family_socket = AsyncMock()
    other_socket = AsyncMock()
    await manager.connect_family("family-1", "connection-1", family_socket)
    await manager.connect_family("family-2", "connection-2", other_socket)

    delivered = await manager.notify_reminder_status_changed(
        {"family-1"}, "elder-1", "command-1"
    )

    assert delivered is True
    family_socket.send_json.assert_awaited_once()
    other_socket.send_json.assert_not_awaited()
    message = family_socket.send_json.await_args.args[0]
    assert set(message) == {
        "protocol_version",
        "message_type",
        "message_id",
        "sent_at",
        "payload",
    }
    assert message["message_type"] == "REMINDER_STATUS_CHANGED"
    assert message["payload"] == {"elder_id": "elder-1", "command_id": "command-1"}
    UUID(message["message_id"])

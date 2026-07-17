import json
from datetime import timedelta
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock
from uuid import uuid4
from zoneinfo import ZoneInfo

from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import AsyncClient
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import utc_now
from app.main import create_app
from app.models import AuditLog, Binding, Command, CommandReceipt
from tests.conftest import bind_payload, create_code, create_elder, register_family

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


async def prepared_binding(
    client: AsyncClient,
    *,
    family_mobile: str = "13800138000",
    elder_mobile: str = "13900139000",
    device_id: str = "device-command-001",
    request_prefix: str = "a",
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
    bind = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            code["binding_code"],
            mobile=family_mobile,
            device_id=device_id,
            request_id=f"{request_prefix}4444444-4444-4444-8444-444444444444",
        ),
    )
    assert bind.status_code == 201, bind.text
    return family, elder, bind.json()


async def send_notification(
    client: AsyncClient,
    family_token: str,
    elder_id: str,
    *,
    request_id: str,
    content: str = "下午有快递，请留意电话。",
    created_at: str | None = None,
) -> Any:
    return await client.post(
        f"/api/v1/elders/{elder_id}/commands/notifications",
        headers={
            "Authorization": f"Bearer {family_token}",
            "Idempotency-Key": request_id,
        },
        json={
            "client_request_id": request_id,
            "content": content,
            "created_at": created_at or utc_now().isoformat(),
        },
    )


async def test_notification_pull_and_ack_are_reliable_and_idempotent(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    request_id = "b0000000-0000-4000-8000-000000000001"
    client_created_at = utc_now().isoformat()
    first = await send_notification(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id=request_id,
        created_at=client_created_at,
    )
    second = await send_notification(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id=request_id,
        created_at=client_created_at,
    )
    assert first.status_code == second.status_code == 201
    assert first.json() == second.json()
    assert first.json()["status"] == "PENDING"

    pending = await client.get(
        "/api/v1/commands/pending?after_sequence=0&limit=100",
        headers={"Authorization": f"Bearer {bound['device_credential']}"},
    )
    assert pending.status_code == 200, pending.text
    body = pending.json()
    assert body["has_more"] is False
    assert body["next_after_sequence"] == first.json()["server_sequence"]
    assert body["commands"] == [
        {
            "command_id": first.json()["command_id"],
            "server_sequence": first.json()["server_sequence"],
            "elder_id": elder["elder_id"],
            "command_type": "FAMILY_NOTIFICATION",
            "title": None,
            "content": "下午有快递，请留意电话。",
            "scheduled_at": None,
            "timezone": "Asia/Shanghai",
            "sender": {"display_name": "小林"},
            "created_at": first.json()["created_at"],
        }
    ]

    ack_id = "b0000000-0000-4000-8000-000000000002"
    ack_payload = {
        "client_request_id": ack_id,
        "ack_type": "STORED",
        "stored_at": utc_now().isoformat(),
    }
    ack = await client.post(
        f"/api/v1/commands/{first.json()['command_id']}/ack",
        headers={
            "Authorization": f"Bearer {bound['device_credential']}",
            "Idempotency-Key": ack_id,
        },
        json=ack_payload,
    )
    repeated_ack_id = "b0000000-0000-4000-8000-000000000003"
    repeated = await client.post(
        f"/api/v1/commands/{first.json()['command_id']}/ack",
        headers={
            "Authorization": f"Bearer {bound['device_credential']}",
            "Idempotency-Key": repeated_ack_id,
        },
        json={**ack_payload, "client_request_id": repeated_ack_id},
    )
    assert ack.status_code == repeated.status_code == 200
    assert ack.json() == repeated.json()
    assert ack.json()["status"] == "STORED"

    already_seen = await client.get(
        f"/api/v1/commands/pending?after_sequence={first.json()['server_sequence']}&limit=100",
        headers={"Authorization": f"Bearer {bound['device_credential']}"},
    )
    assert already_seen.json() == {
        "commands": [],
        "next_after_sequence": first.json()["server_sequence"],
        "has_more": False,
    }

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(Command.id))) == 1
        assert await session.scalar(select(func.count(CommandReceipt.id))) == 1
        audits = (await session.scalars(select(AuditLog))).all()
        serialized = json.dumps([audit.details for audit in audits], ensure_ascii=False)
        assert "下午有快递" not in serialized


async def test_command_idempotency_header_conflict_and_delivery_failure(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    request_id = "c0000000-0000-4000-8000-000000000001"
    app.state.connection_manager.notify_command = AsyncMock(
        side_effect=RuntimeError("simulated websocket failure")
    )
    created = await send_notification(
        client, family["access_token"], elder["elder_id"], request_id=request_id
    )
    assert created.status_code == 201

    conflict = await send_notification(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id=request_id,
        content="同一个请求标识但正文不同",
    )
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    mismatch = await client.post(
        f"/api/v1/elders/{elder['elder_id']}/commands/notifications",
        headers={
            "Authorization": f"Bearer {family['access_token']}",
            "Idempotency-Key": "c0000000-0000-4000-8000-000000000099",
        },
        json={
            "client_request_id": "c0000000-0000-4000-8000-000000000002",
            "content": "测试",
            "created_at": utc_now().isoformat(),
        },
    )
    assert mismatch.status_code == 400
    assert mismatch.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

    missing_header = await client.post(
        f"/api/v1/elders/{elder['elder_id']}/commands/notifications",
        headers={"Authorization": f"Bearer {family['access_token']}"},
        json={
            "client_request_id": "c0000000-0000-4000-8000-000000000003",
            "content": "测试",
            "created_at": utc_now().isoformat(),
        },
    )
    assert missing_header.status_code == 400
    assert missing_header.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

    blank = await send_notification(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id="c0000000-0000-4000-8000-000000000004",
        content="   ",
    )
    too_long = await send_notification(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id="c0000000-0000-4000-8000-000000000005",
        content="长" * 201,
    )
    for response in (blank, too_long):
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_COMMAND_CONTENT"

    pending = await client.get(
        "/api/v1/commands/pending",
        headers={"Authorization": f"Bearer {bound['device_credential']}"},
    )
    assert pending.status_code == 200
    assert pending.json()["commands"][0]["command_id"] == created.json()["command_id"]


async def test_reminder_validation_permissions_revocation_and_rate_limit(
    api: ApiFixture,
) -> None:
    client, app, settings = api
    family, elder, bound = await prepared_binding(client)
    endpoint = f"/api/v1/elders/{elder['elder_id']}/commands/reminders"

    async def reminder(
        request_id: str,
        *,
        title: str = "量血压",
        scheduled_at: str | None = None,
        timezone: str = "Asia/Shanghai",
    ) -> Any:
        return await client.post(
            endpoint,
            headers={
                "Authorization": f"Bearer {family['access_token']}",
                "Idempotency-Key": request_id,
            },
            json={
                "client_request_id": request_id,
                "title": title,
                "content": "测量后把结果记下来。",
                "scheduled_at": scheduled_at or (utc_now() + timedelta(hours=1)).isoformat(),
                "timezone": timezone,
            },
        )

    valid = await reminder("d0000000-0000-4000-8000-000000000001")
    assert valid.status_code == 201, (valid.text, valid.request.content)
    invalid_timezone = await reminder(
        "d0000000-0000-4000-8000-000000000002", timezone="Mars/Olympus"
    )
    past = await reminder(
        "d0000000-0000-4000-8000-000000000003",
        scheduled_at=(utc_now() - timedelta(seconds=1)).isoformat(),
    )
    too_far = await reminder(
        "d0000000-0000-4000-8000-000000000004",
        scheduled_at=(utc_now() + timedelta(days=366)).isoformat(),
    )
    blank_title = await reminder("d0000000-0000-4000-8000-000000000005", title="   ")
    non_utc = await reminder(
        "d0000000-0000-4000-8000-000000000010",
        scheduled_at=(utc_now() + timedelta(hours=1))
        .astimezone(ZoneInfo("Asia/Shanghai"))
        .isoformat(),
    )
    for response in (invalid_timezone, past, too_far, blank_title, non_utc):
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_COMMAND_CONTENT"

    async with app.state.database.session_factory() as session, session.begin():
        binding = (await session.scalars(select(Binding))).one()
        binding.permissions = ["VIEWER"]
    forbidden = await reminder("d0000000-0000-4000-8000-000000000006")
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "COMMAND_FORBIDDEN"

    async with app.state.database.session_factory() as session, session.begin():
        binding = (await session.scalars(select(Binding))).one()
        binding.permissions = ["VIEWER", "HELPER"]
    settings.command_per_minute_limit = 2
    second = await reminder("d0000000-0000-4000-8000-000000000007")
    limited = await reminder("d0000000-0000-4000-8000-000000000008")
    assert second.status_code == 201
    assert limited.status_code == 429
    assert limited.json()["error"]["code"] == "COMMAND_RATE_LIMITED"

    revoked = await client.delete(
        f"/api/v1/bindings/{bound['binding_id']}",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert revoked.status_code == 204
    after_revoke = await reminder("d0000000-0000-4000-8000-000000000009")
    assert after_revoke.status_code == 410
    assert after_revoke.json()["error"]["code"] == "BINDING_REVOKED"
    device_pull = await client.get(
        "/api/v1/commands/pending",
        headers={"Authorization": f"Bearer {bound['device_credential']}"},
    )
    assert device_pull.status_code == 401


async def test_pending_commands_are_ordered_paginated_and_isolated(api: ApiFixture) -> None:
    client, _, settings = api
    settings.command_per_minute_limit = 20
    first_family, first_elder, first_bound = await prepared_binding(client)
    second_family, second_elder, second_bound = await prepared_binding(
        client,
        family_mobile="13700137000",
        elder_mobile="13600136000",
        device_id="device-command-002",
        request_prefix="e",
    )
    created_sequences: list[int] = []
    created_ids: list[str] = []
    for index in range(3):
        response = await send_notification(
            client,
            first_family["access_token"],
            first_elder["elder_id"],
            request_id=f"f0000000-0000-4000-8000-{index:012d}",
            content=f"通知 {index}",
        )
        assert response.status_code == 201
        created_sequences.append(response.json()["server_sequence"])
        created_ids.append(response.json()["command_id"])
    other = await send_notification(
        client,
        second_family["access_token"],
        second_elder["elder_id"],
        request_id="f0000000-0000-4000-8000-999999999999",
        content="另一个老人的通知",
    )
    assert other.status_code == 201

    first_page = await client.get(
        "/api/v1/commands/pending?after_sequence=0&limit=2",
        headers={"Authorization": f"Bearer {first_bound['device_credential']}"},
    )
    assert first_page.status_code == 200
    assert [item["server_sequence"] for item in first_page.json()["commands"]] == (
        created_sequences[:2]
    )
    assert first_page.json()["has_more"] is True
    second_page = await client.get(
        "/api/v1/commands/pending"
        f"?after_sequence={first_page.json()['next_after_sequence']}&limit=2",
        headers={"Authorization": f"Bearer {first_bound['device_credential']}"},
    )
    assert [item["server_sequence"] for item in second_page.json()["commands"]] == (
        created_sequences[2:]
    )
    assert second_page.json()["has_more"] is False

    isolated = await client.get(
        "/api/v1/commands/pending",
        headers={"Authorization": f"Bearer {second_bound['device_credential']}"},
    )
    assert [item["command_id"] for item in isolated.json()["commands"]] == [
        other.json()["command_id"]
    ]

    unauthorized_ack_id = "f0000000-0000-4000-8000-888888888888"
    unauthorized_ack = await client.post(
        f"/api/v1/commands/{created_ids[0]}/ack",
        headers={
            "Authorization": f"Bearer {second_bound['device_credential']}",
            "Idempotency-Key": unauthorized_ack_id,
        },
        json={
            "client_request_id": unauthorized_ack_id,
            "ack_type": "STORED",
            "stored_at": utc_now().isoformat(),
        },
    )
    assert unauthorized_ack.status_code == 404
    assert unauthorized_ack.json()["error"]["code"] == "COMMAND_NOT_FOUND"

    unauthorized_create = await send_notification(
        client,
        second_family["access_token"],
        first_elder["elder_id"],
        request_id="f0000000-0000-4000-8000-777777777777",
    )
    assert unauthorized_create.status_code == 404
    assert unauthorized_create.json()["error"]["code"] == "ELDER_NOT_FOUND"


def test_websocket_emits_only_command_availability(
    tmp_path: Path,
) -> None:
    settings = Settings(
        database_url=f"sqlite+aiosqlite:///{(tmp_path / 'websocket.db').as_posix()}",
        auto_create_schema=True,
        jwt_secret="websocket-test-jwt-secret-with-enough-entropy",
        security_secret="websocket-test-security-secret-with-enough-entropy",
    )
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        family_response = client.post(
            "/api/v1/auth/family/register",
            json={
                "display_name": "小林",
                "mobile_number": "13800138000",
                "client_request_id": str(uuid4()),
            },
        )
        family = family_response.json()
        elder = client.post(
            "/api/v1/elders",
            headers={"Authorization": f"Bearer {family['access_token']}"},
            json={
                "display_name": "王阿姨",
                "mobile_number": "13900139000",
                "relationship": "CHILD",
                "emergency_contact": False,
                "client_request_id": str(uuid4()),
            },
        ).json()
        code = client.post(
            "/api/v1/bindings/codes",
            headers={"Authorization": f"Bearer {family['access_token']}"},
            json={"elder_id": elder["elder_id"], "client_request_id": str(uuid4())},
        ).json()
        bound = client.post(
            "/api/v1/devices/bind",
            json=bind_payload(
                code["binding_code"],
                device_id="device-websocket-001",
                request_id=str(uuid4()),
            ),
        ).json()

        with client.websocket_connect(
            "/api/v1/ws",
            headers={"Authorization": f"Bearer {bound['device_credential']}"},
        ) as websocket:
            request_id = str(uuid4())
            created = client.post(
                f"/api/v1/elders/{elder['elder_id']}/commands/notifications",
                headers={
                    "Authorization": f"Bearer {family['access_token']}",
                    "Idempotency-Key": request_id,
                },
                json={
                    "client_request_id": request_id,
                    "content": "这段正文不能出现在 WebSocket 中",
                    "created_at": utc_now().isoformat(),
                },
            )
            assert created.status_code == 201, created.text
            message = websocket.receive_json()
            assert message["message_type"] == "COMMAND_AVAILABLE"
            assert message["server_sequence"] == created.json()["server_sequence"]
            assert message["payload"] == {
                "command_id": created.json()["command_id"],
                "command_type": "FAMILY_NOTIFICATION",
            }
            assert "正文" not in json.dumps(message, ensure_ascii=False)

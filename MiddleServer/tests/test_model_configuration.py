import asyncio
import copy
import json
from typing import Any
from unittest.mock import AsyncMock
from uuid import UUID

from fastapi import FastAPI
from httpx import AsyncClient, Response
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import utc_now
from app.models import (
    AuditLog,
    Binding,
    ElderModelConfiguration,
    ModelConfigurationRequest,
)
from app.websocket.manager import ConnectionManager
from tests.conftest import bind_payload, create_code, create_elder, register_family

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


def model_configuration_payload(
    request_id: str = "91000000-0000-4000-8000-000000000001",
    *,
    expected_revision: int | None = None,
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "base_url": "http://58.199.163.98:11435",
        "model": "qwen3_5",
        "dialect": "llama_cpp",
        "context_window_tokens": 32768,
        "max_output_tokens": 512,
        "sampling": {
            "temperature": 0.6,
            "top_p": 0.9,
            "top_k": 40,
        },
        "reasoning_enabled": False,
        "expected_revision": expected_revision,
        "client_request_id": request_id,
    }


def voice_configuration_payload() -> dict[str, Any]:
    return {
        "websocket_url": ("wss://workspace-id.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference"),
        "asr_model": "qwen-audio-3.0-asr-flash-streaming",
        "tts_model": "qwen-audio-3.0-tts-flash",
        "tts_voice": "longanfengyue",
        "tts_response_format": "pcm",
        "tts_sample_rate": 22050,
        "tts_volume": 50,
        "tts_rate": 0.9,
        "tts_pitch": 1.0,
        "language": "zh",
    }


async def prepared_binding(
    client: AsyncClient,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    family = await register_family(client)
    elder = await create_elder(client, family["access_token"])
    code = await create_code(client, family["access_token"], elder["elder_id"])
    bound = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(code["binding_code"]),
    )
    assert bound.status_code == 201, bound.text
    return family, elder, bound.json()


async def put_configuration(
    client: AsyncClient,
    access_token: str,
    elder_id: str,
    payload: dict[str, Any],
    *,
    idempotency_key: str | None = None,
) -> Response:
    request_id = idempotency_key or str(payload["client_request_id"])
    return await client.put(
        f"/api/v1/elders/{elder_id}/model-config",
        headers={
            "Authorization": f"Bearer {access_token}",
            "Idempotency-Key": request_id,
        },
        json=payload,
    )


async def test_family_creates_and_both_sides_read_same_configuration(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    family_headers = {"Authorization": f"Bearer {family['access_token']}"}
    device_headers = {"Authorization": f"Bearer {bound['device_credential']}"}
    app.state.connection_manager.notify_model_config_available = AsyncMock(return_value=True)

    missing = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-config",
        headers=family_headers,
    )
    assert missing.status_code == 404
    assert missing.json()["error"]["code"] == "MODEL_CONFIG_NOT_FOUND"
    assert missing.headers["Cache-Control"] == "no-store"

    payload = model_configuration_payload()
    created = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        payload,
    )
    assert created.status_code == 200, created.text
    assert created.headers["Cache-Control"] == "no-store"
    body = created.json()
    assert body == {
        "configuration": {
            "schema_version": 1,
            "base_url": "http://58.199.163.98:11435",
            "model": "qwen3_5",
            "dialect": "llama_cpp",
            "context_window_tokens": 32768,
            "max_output_tokens": 512,
            "sampling": {
                "temperature": 0.6,
                "top_p": 0.9,
                "top_k": 40,
            },
            "reasoning_enabled": False,
        },
        "revision": 1,
        "updated_at": body["updated_at"],
    }

    family_read = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-config",
        headers=family_headers,
    )
    device_read = await client.get("/api/v1/devices/me/model-config", headers=device_headers)
    assert family_read.status_code == device_read.status_code == 200
    assert family_read.json() == device_read.json() == body
    assert family_read.headers["Cache-Control"] == "no-store"
    assert device_read.headers["Cache-Control"] == "no-store"

    notifier = app.state.connection_manager.notify_model_config_available
    notifier.assert_awaited_once()
    assert notifier.await_args.args[0] == elder["elder_id"]
    assert len(notifier.await_args.args[1]) == 1
    assert notifier.await_args.args[2] == 1

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ElderModelConfiguration.id))) == 1
        stored = (await session.scalars(select(ElderModelConfiguration))).one()
        assert float(stored.temperature) == 0.6
        assert float(stored.top_p) == 0.9
        assert stored.reasoning_enabled is False


async def test_websocket_hint_failure_does_not_rollback_configuration(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, _ = await prepared_binding(client)
    app.state.connection_manager.notify_model_config_available = AsyncMock(
        side_effect=RuntimeError("websocket unavailable")
    )

    created = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        model_configuration_payload(),
    )
    assert created.status_code == 200, created.text
    assert created.json()["revision"] == 1

    async with app.state.database.session_factory() as session:
        stored = (await session.scalars(select(ElderModelConfiguration))).one()
        assert stored.revision == 1


async def test_model_config_hint_is_minimal_and_targets_only_active_devices() -> None:
    manager = ConnectionManager()
    active = AsyncMock()
    inactive = AsyncMock()
    await manager.connect("elder-1", "active-device", active)
    await manager.connect("elder-1", "inactive-device", inactive)

    delivered = await manager.notify_model_config_available(
        "elder-1",
        {"active-device"},
        3,
    )

    assert delivered is True
    active.send_json.assert_awaited_once()
    inactive.send_json.assert_not_awaited()
    message = active.send_json.await_args.args[0]
    assert set(message) == {
        "protocol_version",
        "message_type",
        "message_id",
        "sent_at",
        "payload",
    }
    assert message["protocol_version"] == 1
    assert message["message_type"] == "MODEL_CONFIG_AVAILABLE"
    assert message["payload"] == {"revision": 3}
    UUID(message["message_id"])
    assert message["sent_at"].endswith("Z")


async def test_idempotency_history_and_revision_conflicts_are_stable(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, _ = await prepared_binding(client)
    app.state.connection_manager.notify_model_config_available = AsyncMock(return_value=True)
    first_payload = model_configuration_payload()
    first = await put_configuration(
        client, family["access_token"], elder["elder_id"], first_payload
    )
    retry = await put_configuration(
        client, family["access_token"], elder["elder_id"], first_payload
    )
    assert first.status_code == retry.status_code == 200
    assert first.json() == retry.json()

    conflicting_payload = copy.deepcopy(first_payload)
    conflicting_payload["model"] = "different-model"
    idempotency_conflict = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        conflicting_payload,
    )
    assert idempotency_conflict.status_code == 409
    assert idempotency_conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    second_payload = model_configuration_payload(
        "91000000-0000-4000-8000-000000000002",
        expected_revision=1,
    )
    second_payload["model"] = "qwen3_5-updated"
    updated = await put_configuration(
        client, family["access_token"], elder["elder_id"], second_payload
    )
    assert updated.status_code == 200
    assert updated.json()["revision"] == 2

    old_retry_after_update = await put_configuration(
        client, family["access_token"], elder["elder_id"], first_payload
    )
    assert old_retry_after_update.status_code == 200
    assert old_retry_after_update.json() == first.json()

    stale_payload = model_configuration_payload(
        "91000000-0000-4000-8000-000000000003",
        expected_revision=1,
    )
    stale = await put_configuration(
        client, family["access_token"], elder["elder_id"], stale_payload
    )
    assert stale.status_code == 409
    assert stale.json()["error"]["code"] == "MODEL_CONFIG_REVISION_CONFLICT"

    async with app.state.database.session_factory() as session:
        configuration = (await session.scalars(select(ElderModelConfiguration))).one()
        assert configuration.revision == 2
        assert await session.scalar(select(func.count(ModelConfigurationRequest.id))) == 2
        assert (
            await session.scalar(
                select(func.count(AuditLog.id)).where(AuditLog.action == "MODEL_CONFIG_UPDATED")
            )
            == 2
        )
    notifier = app.state.connection_manager.notify_model_config_available
    assert notifier.await_count == 2
    assert [call.args[2] for call in notifier.await_args_list] == [1, 2]


async def test_voice_configuration_is_atomic_versioned_and_available_to_device(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    payload = model_configuration_payload()
    payload["voice"] = voice_configuration_payload()

    created = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        payload,
    )
    assert created.status_code == 200, created.text
    assert created.json()["configuration"]["voice"] == voice_configuration_payload()
    assert created.json()["revision"] == 1

    family_read = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-config",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    device_read = await client.get(
        "/api/v1/devices/me/model-config",
        headers={"Authorization": f"Bearer {bound['device_credential']}"},
    )
    assert family_read.json() == device_read.json() == created.json()

    updated_payload = model_configuration_payload(
        "91000000-0000-4000-8000-000000000010",
        expected_revision=1,
    )
    updated_voice = voice_configuration_payload()
    updated_voice["tts_volume"] = 60
    updated_payload["voice"] = updated_voice
    updated = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        updated_payload,
    )
    retry = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        updated_payload,
    )
    assert updated.status_code == retry.status_code == 200
    assert updated.json() == retry.json()
    assert updated.json()["revision"] == 2
    assert updated.json()["configuration"]["voice"]["tts_volume"] == 60

    conflicting_payload = copy.deepcopy(updated_payload)
    conflicting_payload["voice"]["tts_pitch"] = 1.1
    conflict = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        conflicting_payload,
    )
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    async with app.state.database.session_factory() as session:
        stored = (await session.scalars(select(ElderModelConfiguration))).one()
        assert stored.revision == 2
        assert stored.voice_websocket_url == updated_voice["websocket_url"]
        assert stored.voice_tts_volume == 60
        assert float(stored.voice_tts_rate or 0) == 0.9
        assert float(stored.voice_tts_pitch or 0) == 1.0
        assert await session.scalar(select(func.count(ModelConfigurationRequest.id))) == 2


async def test_concurrent_updates_use_optimistic_revision(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, _ = await prepared_binding(client)
    created = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        model_configuration_payload(),
    )
    assert created.status_code == 200

    first = model_configuration_payload(
        "92000000-0000-4000-8000-000000000001",
        expected_revision=1,
    )
    second = model_configuration_payload(
        "92000000-0000-4000-8000-000000000002",
        expected_revision=1,
    )
    first["model"] = "concurrent-a"
    second["model"] = "concurrent-b"
    responses = await asyncio.gather(
        put_configuration(client, family["access_token"], elder["elder_id"], first),
        put_configuration(client, family["access_token"], elder["elder_id"], second),
    )
    assert sorted(response.status_code for response in responses) == [200, 409]
    failed = next(response for response in responses if response.status_code == 409)
    assert failed.json()["error"]["code"] == "MODEL_CONFIG_REVISION_CONFLICT"

    async with app.state.database.session_factory() as session:
        configuration = (await session.scalars(select(ElderModelConfiguration))).one()
        assert configuration.revision == 2


async def test_unrelated_family_and_revoked_binding_are_denied(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    created = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        model_configuration_payload(),
    )
    assert created.status_code == 200

    unrelated = await register_family(
        client,
        mobile="13700137000",
        display_name="无关家属",
        request_id="93000000-0000-4000-8000-000000000001",
    )
    unrelated_headers = {"Authorization": f"Bearer {unrelated['access_token']}"}
    forbidden_read = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-config",
        headers=unrelated_headers,
    )
    forbidden_write_payload = model_configuration_payload(
        "93000000-0000-4000-8000-000000000002",
        expected_revision=1,
    )
    forbidden_write = await put_configuration(
        client,
        unrelated["access_token"],
        elder["elder_id"],
        forbidden_write_payload,
    )
    assert forbidden_read.status_code == forbidden_write.status_code == 403
    assert forbidden_read.json()["error"]["code"] == "MODEL_CONFIG_FORBIDDEN"
    assert forbidden_write.json()["error"]["code"] == "MODEL_CONFIG_FORBIDDEN"

    async with app.state.database.session_factory() as session, session.begin():
        binding = (
            await session.scalars(select(Binding).where(Binding.id == bound["binding_id"]))
        ).one()
        binding.revoked_at = utc_now()

    family_revoked = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-config",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    device_revoked = await client.get(
        "/api/v1/devices/me/model-config",
        headers={"Authorization": f"Bearer {bound['device_credential']}"},
    )
    assert family_revoked.status_code == device_revoked.status_code == 410
    assert family_revoked.json()["error"]["code"] == "BINDING_REVOKED"
    assert device_revoked.json()["error"]["code"] == "BINDING_REVOKED"


async def test_invalid_and_secret_bearing_payloads_are_rejected_without_storage(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, _ = await prepared_binding(client)
    cases: list[dict[str, Any]] = []

    def changed(path: tuple[str, ...], value: object) -> dict[str, Any]:
        payload = model_configuration_payload()
        target: dict[str, Any] = payload
        for segment in path[:-1]:
            target = target[segment]
        target[path[-1]] = value
        return payload

    cases.extend(
        [
            changed(("schema_version",), 2),
            changed(("base_url",), "ftp://model.example.com"),
            changed(("base_url",), "https://user:pass@model.example.com"),
            changed(("base_url",), "https://model.example.com/v1?api_key=secret"),
            changed(("base_url",), "https://model.example.com/v1#fragment"),
            changed(("model",), "   "),
            changed(("dialect",), "unknown"),
            changed(("context_window_tokens",), 1023),
            changed(("context_window_tokens",), 2_000_001),
            changed(("max_output_tokens",), 63),
            changed(("max_output_tokens",), 8193),
            changed(("sampling", "temperature"), -0.1),
            changed(("sampling", "temperature"), 2.1),
            changed(("sampling", "top_p"), 1.1),
            changed(("sampling", "top_k"), 1001),
            changed(("reasoning_enabled",), True),
        ]
    )
    context_smaller_than_output = changed(("context_window_tokens",), 1024)
    context_smaller_than_output["max_output_tokens"] = 2048
    cases.append(context_smaller_than_output)
    for field in ("api_key", "encrypted_api_key", "authorization", "credential"):
        payload = model_configuration_payload()
        payload[field] = "must-never-be-stored"
        cases.append(payload)

    def with_voice_change(field: str, value: object) -> dict[str, Any]:
        payload = model_configuration_payload()
        voice = voice_configuration_payload()
        voice[field] = value
        payload["voice"] = voice
        return payload

    cases.extend(
        [
            {**model_configuration_payload(), "voice": {"websocket_url": "wss://x"}},
            with_voice_change(
                "websocket_url",
                "ws://workspace-id.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
            ),
            with_voice_change(
                "websocket_url",
                "wss://voice.example.com/api-ws/v1/inference",
            ),
            with_voice_change(
                "websocket_url",
                "wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
            ),
            with_voice_change(
                "websocket_url",
                "wss://user@workspace-id.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
            ),
            with_voice_change(
                "websocket_url",
                "wss://workspace-id.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference?key=x",
            ),
            with_voice_change(
                "websocket_url",
                "wss://workspace-id.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference#x",
            ),
            with_voice_change(
                "websocket_url",
                "wss://workspace-id.cn-beijing.maas.aliyuncs.com/wrong",
            ),
            with_voice_change("asr_model", " "),
            with_voice_change("tts_model", " "),
            with_voice_change("tts_voice", " "),
            with_voice_change("tts_response_format", "aac"),
            with_voice_change("tts_sample_rate", 12345),
            with_voice_change("tts_volume", -1),
            with_voice_change("tts_volume", 101),
            with_voice_change("tts_rate", 0.49),
            with_voice_change("tts_rate", 2.01),
            with_voice_change("tts_pitch", 0.49),
            with_voice_change("tts_pitch", 2.01),
            with_voice_change("language", "en"),
        ]
    )
    secret_voice = model_configuration_payload()
    secret_voice["voice"] = {**voice_configuration_payload(), "api_key": "voice-secret"}
    cases.append(secret_voice)

    for index, payload in enumerate(cases):
        request_id = f"94000000-0000-4000-8000-{index:012d}"
        payload["client_request_id"] = request_id
        response = await put_configuration(
            client,
            family["access_token"],
            elder["elder_id"],
            payload,
        )
        assert response.status_code == 400, (index, response.text)
        assert response.json()["error"]["code"] == "INVALID_MODEL_CONFIG"
        assert response.headers["Cache-Control"] == "no-store"
        assert "must-never-be-stored" not in response.text
        assert "voice-secret" not in response.text
        assert "api_key=secret" not in response.text

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ElderModelConfiguration.id))) == 0
        assert await session.scalar(select(func.count(ModelConfigurationRequest.id))) == 0
        audits = (await session.scalars(select(AuditLog))).all()
        serialized = json.dumps([audit.details for audit in audits])
        assert "must-never-be-stored" not in serialized
        assert "voice-secret" not in serialized
        assert "api_key=secret" not in serialized


async def test_numeric_boundaries_are_accepted(api: ApiFixture) -> None:
    client, _, _ = api
    family, elder, _ = await prepared_binding(client)
    payload = model_configuration_payload()
    payload["max_output_tokens"] = 8192
    payload["context_window_tokens"] = 2_000_000
    payload["sampling"] = {
        "temperature": 2,
        "top_p": 0,
        "top_k": 1000,
    }
    voice = voice_configuration_payload()
    voice.update(
        {
            "websocket_url": "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference",
            "tts_response_format": "opus",
            "tts_sample_rate": 48000,
            "tts_volume": 100,
            "tts_rate": 2.0,
            "tts_pitch": 0.5,
        }
    )
    payload["voice"] = voice
    response = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        payload,
    )
    assert response.status_code == 200, response.text
    assert response.json()["configuration"]["sampling"] == {
        "temperature": 2.0,
        "top_p": 0.0,
        "top_k": 1000,
    }
    assert response.json()["configuration"]["voice"] == voice


async def test_idempotency_header_must_match_body(api: ApiFixture) -> None:
    client, _, _ = api
    family, elder, _ = await prepared_binding(client)
    response = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        model_configuration_payload(),
        idempotency_key="95000000-0000-4000-8000-000000000001",
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

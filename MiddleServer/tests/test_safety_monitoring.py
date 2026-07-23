import copy
import io
from datetime import timedelta
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import AsyncClient, Response
from PIL import Image
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import utc_now
from app.main import create_app
from app.models import AuditLog, SafetyEvent, SafetyMonitoringConfiguration
from tests.conftest import bind_payload, create_code, create_elder, register_family

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


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


def configuration_payload(
    request_id: str = "b1000000-0000-4000-8000-000000000001",
    *,
    enabled: bool = True,
    interval_minutes: int = 5,
    expected_revision: int | None = None,
) -> dict[str, Any]:
    return {
        "enabled": enabled,
        "interval_minutes": interval_minutes,
        "expected_revision": expected_revision,
        "client_request_id": request_id,
    }


async def put_configuration(
    client: AsyncClient,
    token: str,
    elder_id: str,
    payload: dict[str, Any],
    idempotency_key: str | None = None,
) -> Response:
    return await client.put(
        f"/api/v1/elders/{elder_id}/safety-monitoring/config",
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": idempotency_key or str(payload["client_request_id"]),
        },
        json=payload,
    )


def event_payload(
    event_id: str = "b2000000-0000-4000-8000-000000000001",
    *,
    occurred_at: str | None = None,
    event_type: str = "FALL_SUSPECTED",
    severity: str = "EMERGENCY",
    summary: str = "检测到老人疑似跌倒，请尽快联系核实。",
) -> dict[str, Any]:
    return {
        "client_event_id": event_id,
        "occurred_at": occurred_at or utc_now().isoformat().replace("+00:00", "Z"),
        "event_type": event_type,
        "event_summary": summary,
        "severity": severity,
    }


async def post_event(
    client: AsyncClient,
    credential: str,
    payload: dict[str, Any],
    idempotency_key: str | None = None,
) -> Response:
    return await client.post(
        "/api/v1/devices/me/safety-events",
        headers={
            "Authorization": f"Bearer {credential}",
            "Idempotency-Key": idempotency_key or str(payload["client_event_id"]),
        },
        json=payload,
    )


async def test_configuration_is_persisted_shared_and_idempotent(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    family_headers = {"Authorization": f"Bearer {family['access_token']}"}
    device_headers = {"Authorization": f"Bearer {bound['device_credential']}"}

    for path, headers in (
        (f"/api/v1/elders/{elder['elder_id']}/safety-monitoring/config", family_headers),
        ("/api/v1/devices/me/safety-monitoring/config", device_headers),
    ):
        missing = await client.get(path, headers=headers)
        assert missing.status_code == 404
        assert missing.json()["error"]["code"] == "SAFETY_CONFIG_NOT_FOUND"
        assert missing.headers["Cache-Control"] == "no-store"

    app.state.connection_manager.notify_safety_monitoring_config_available = AsyncMock(
        return_value=True
    )
    payload = configuration_payload()
    first = await put_configuration(client, family["access_token"], elder["elder_id"], payload)
    retry = await put_configuration(client, family["access_token"], elder["elder_id"], payload)
    assert first.status_code == retry.status_code == 200
    assert first.json() == retry.json()
    assert first.json()["enabled"] is True
    assert first.json()["interval_minutes"] == 5
    assert first.json()["revision"] == 1
    family_read = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/safety-monitoring/config",
        headers=family_headers,
    )
    device_read = await client.get(
        "/api/v1/devices/me/safety-monitoring/config", headers=device_headers
    )
    assert family_read.json() == device_read.json() == first.json()
    notifier = app.state.connection_manager.notify_safety_monitoring_config_available
    notifier.assert_awaited_once()
    assert notifier.await_args.args[0] == elder["elder_id"]
    assert notifier.await_args.args[2] == 1

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(SafetyMonitoringConfiguration.id))) == 1


async def test_configuration_revision_validation_and_stable_history(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, _ = await prepared_binding(client)
    app.state.connection_manager.notify_safety_monitoring_config_available = AsyncMock(
        return_value=True
    )
    first_payload = configuration_payload()
    first = await put_configuration(
        client, family["access_token"], elder["elder_id"], first_payload
    )
    assert first.status_code == 200

    changed_same_key = copy.deepcopy(first_payload)
    changed_same_key["enabled"] = False
    conflict = await put_configuration(
        client, family["access_token"], elder["elder_id"], changed_same_key
    )
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    stale = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        configuration_payload(
            "b1000000-0000-4000-8000-000000000002",
            interval_minutes=10,
            expected_revision=None,
        ),
    )
    assert stale.status_code == 409
    assert stale.json()["error"]["code"] == "SAFETY_CONFIG_REVISION_CONFLICT"
    assert stale.json()["error"]["details"] == {"current_revision": 1}

    second = await put_configuration(
        client,
        family["access_token"],
        elder["elder_id"],
        configuration_payload(
            "b1000000-0000-4000-8000-000000000003",
            enabled=False,
            interval_minutes=60,
            expected_revision=1,
        ),
    )
    assert second.status_code == 200
    assert second.json()["enabled"] is False
    assert second.json()["revision"] == 2
    old_retry = await put_configuration(
        client, family["access_token"], elder["elder_id"], first_payload
    )
    assert old_retry.json() == first.json()

    for invalid in (0, 61):
        payload = configuration_payload(str(uuid4()), interval_minutes=invalid)
        response = await put_configuration(
            client, family["access_token"], elder["elder_id"], payload
        )
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_SAFETY_INTERVAL"

    for invalid_enabled in (None, "true", 1):
        payload = configuration_payload(str(uuid4()), expected_revision=2)
        if invalid_enabled is None:
            payload.pop("enabled")
        else:
            payload["enabled"] = invalid_enabled
        response = await put_configuration(
            client, family["access_token"], elder["elder_id"], payload
        )
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_SAFETY_ENABLED"

    notifier = app.state.connection_manager.notify_safety_monitoring_config_available
    assert notifier.await_count == 2
    assert [call.args[2] for call in notifier.await_args_list] == [1, 2]


async def test_event_persists_before_hint_and_is_idempotent(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    app.state.connection_manager.notify_safety_event_available = AsyncMock(return_value=True)
    payload = event_payload()
    first = await post_event(client, bound["device_credential"], payload)
    retry = await post_event(client, bound["device_credential"], payload)
    assert first.status_code == retry.status_code == 201
    assert first.json() == retry.json()
    body = first.json()
    assert body["server_sequence"] == 1
    assert body["acknowledged_at"] is None
    assert set(body) == {
        "event_id",
        "server_sequence",
        "occurred_at",
        "event_type",
        "event_summary",
        "severity",
        "acknowledged_at",
        "created_at",
        "image_available",
        "image_content_type",
        "image_byte_size",
    }
    assert body["image_available"] is False
    assert body["image_content_type"] is None
    assert body["image_byte_size"] is None
    notifier = app.state.connection_manager.notify_safety_event_available
    notifier.assert_awaited_once()
    assert notifier.await_args.args[1] == elder["elder_id"]
    assert notifier.await_args.args[2] == body["event_id"]

    changed = copy.deepcopy(payload)
    changed["severity"] = "GENERAL"
    conflict = await post_event(client, bound["device_credential"], changed)
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(SafetyEvent.event_id))) == 1
        audit = (
            await session.scalars(select(AuditLog).where(AuditLog.action == "SAFETY_EVENT_CREATED"))
        ).one()
        assert payload["event_summary"] not in str(audit.details)


async def test_elder_reports_health_and_family_request_with_server_severity_policy(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    _, elder, bound = await prepared_binding(client)
    app.state.connection_manager.notify_safety_event_available = AsyncMock(return_value=True)

    health = await post_event(
        client,
        bound["device_credential"],
        event_payload(
            "b2100000-0000-4000-8000-000000000001",
            event_type="HEALTH_DISCOMFORT_REPORTED",
            severity="GENERAL",
            summary="老人说今天身体不舒服",
        ),
    )
    family_request = await post_event(
        client,
        bound["device_credential"],
        event_payload(
            "b2100000-0000-4000-8000-000000000002",
            event_type="FAMILY_REQUEST",
            severity="EMERGENCY",
            summary="老人想让儿子回家吃饭",
        ),
    )

    assert health.status_code == family_request.status_code == 201
    assert health.json()["event_type"] == "HEALTH_DISCOMFORT_REPORTED"
    assert health.json()["severity"] == "EMERGENCY"
    assert family_request.json()["event_type"] == "FAMILY_REQUEST"
    assert family_request.json()["severity"] == "GENERAL"

    notifier = app.state.connection_manager.notify_safety_event_available
    assert notifier.await_count == 2
    assert notifier.await_args_list[0].args[1] == elder["elder_id"]
    assert notifier.await_args_list[0].args[4] == "EMERGENCY"
    assert notifier.await_args_list[1].args[4] == "GENERAL"

    async with app.state.database.session_factory() as session:
        stored = list(
            (await session.scalars(select(SafetyEvent).order_by(SafetyEvent.server_sequence))).all()
        )
        assert [(event.event_type, event.severity) for event in stored] == [
            ("HEALTH_DISCOMFORT_REPORTED", "EMERGENCY"),
            ("FAMILY_REQUEST", "GENERAL"),
        ]


async def test_event_validation_rate_limit_and_today_query(api: ApiFixture) -> None:
    client, _, settings = api
    settings.safety_event_per_minute_limit = 1
    family, elder, bound = await prepared_binding(client)

    invalid_payloads = [
        event_payload(str(uuid4()), event_type="CERTAIN_FALL"),
        event_payload(str(uuid4()), summary="老人已经跌倒。"),
        event_payload(str(uuid4()), occurred_at=utc_now().replace(tzinfo=None).isoformat()),
        event_payload(
            str(uuid4()),
            occurred_at=(utc_now() + timedelta(hours=1)).isoformat().replace("+00:00", "Z"),
        ),
        {**event_payload(str(uuid4())), "image": "base64"},
    ]
    for payload in invalid_payloads:
        response = await post_event(client, bound["device_credential"], payload)
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_SAFETY_EVENT"

    accepted = await post_event(client, bound["device_credential"], event_payload())
    assert accepted.status_code == 201
    limited = await post_event(client, bound["device_credential"], event_payload(str(uuid4())))
    assert limited.status_code == 429
    assert limited.json()["error"]["code"] == "SAFETY_EVENT_RATE_LIMITED"

    now = utc_now()
    usage_batch_id = str(uuid4())
    usage = await client.post(
        "/api/v1/model-usage/batches",
        headers={
            "Authorization": f"Bearer {bound['device_credential']}",
            "Idempotency-Key": usage_batch_id,
        },
        json={
            "batch_id": usage_batch_id,
            "period_started_at": (now - timedelta(minutes=5)).isoformat().replace("+00:00", "Z"),
            "period_ended_at": now.isoformat().replace("+00:00", "Z"),
            "time_zone": "Asia/Shanghai",
            "time_zone_source": "LOCATION",
            "items": [
                {
                    "modality": "MLLM",
                    "provider": "openai_compatible",
                    "model": "local-test",
                    "feature": "safety-monitoring",
                    "request_count": 1,
                    "success_count": 1,
                    "input_tokens": 0,
                    "output_tokens": 0,
                    "asr_audio_duration_ms": 0,
                    "tts_character_count": 0,
                    "tts_audio_duration_ms": 0,
                    "contains_estimated_values": False,
                }
            ],
        },
    )
    assert usage.status_code == 201, usage.text

    today = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/safety-events",
        headers={"Authorization": f"Bearer {family['access_token']}"},
        params={"scope": "today"},
    )
    assert today.status_code == 200
    assert today.headers["Cache-Control"] == "no-store"
    assert today.json()["timezone"] == "Asia/Shanghai"
    assert [item["event_id"] for item in today.json()["events"]] == [accepted.json()["event_id"]]


async def test_first_acknowledgement_is_immutable_and_access_is_enforced(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    created = await post_event(client, bound["device_credential"], event_payload())
    event_id = created.json()["event_id"]
    request_id = "b3000000-0000-4000-8000-000000000001"
    path = f"/api/v1/elders/{elder['elder_id']}/safety-events/{event_id}/acknowledge"
    headers = {
        "Authorization": f"Bearer {family['access_token']}",
        "Idempotency-Key": request_id,
    }
    first = await client.post(path, headers=headers, json={"client_request_id": request_id})
    retry = await client.post(path, headers=headers, json={"client_request_id": request_id})
    assert first.status_code == retry.status_code == 200
    assert first.json() == retry.json()
    assert first.json()["acknowledged_at"] is not None

    unrelated = await register_family(
        client,
        mobile="13700137000",
        request_id="b3000000-0000-4000-8000-000000000002",
    )
    forbidden = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/safety-events?scope=today",
        headers={"Authorization": f"Bearer {unrelated['access_token']}"},
    )
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "SAFETY_EVENT_FORBIDDEN"

    async with app.state.database.session_factory() as session:
        stored = await session.get(SafetyEvent, event_id)
        assert stored is not None
        assert stored.acknowledged_by_family_account_id == family["family_account_id"]


def test_websocket_delivers_minimal_config_and_event_hints(tmp_path: Path) -> None:
    settings = Settings(
        database_url=f"sqlite+aiosqlite:///{(tmp_path / 'safety-ws.db').as_posix()}",
        auto_create_schema=True,
        jwt_secret="safety-ws-jwt-secret-with-enough-entropy",
        security_secret="safety-ws-security-secret-with-enough-entropy",
        safety_image_storage_path=str(tmp_path / "safety-images"),
    )
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        family = client.post(
            "/api/v1/auth/family/register",
            json={
                "display_name": "小林",
                "mobile_number": "13800138000",
                "client_request_id": str(uuid4()),
            },
        ).json()
        elder = client.post(
            "/api/v1/elders",
            headers={"Authorization": f"Bearer {family['access_token']}"},
            json={
                "display_name": "王阿姨",
                "mobile_number": "13900139000",
                "relationship": "CHILD",
                "emergency_contact": True,
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
                device_id="device-safety-ws",
                request_id=str(uuid4()),
            ),
        ).json()

        with (
            client.websocket_connect(
                "/api/v1/ws",
                headers={"Authorization": f"Bearer {family['access_token']}"},
            ) as family_ws,
            client.websocket_connect(
                "/api/v1/ws",
                headers={"Authorization": f"Bearer {bound['device_credential']}"},
            ) as device_ws,
        ):
            config_request_id = str(uuid4())
            configured = client.put(
                f"/api/v1/elders/{elder['elder_id']}/safety-monitoring/config",
                headers={
                    "Authorization": f"Bearer {family['access_token']}",
                    "Idempotency-Key": config_request_id,
                },
                json={
                    "enabled": True,
                    "interval_minutes": 10,
                    "expected_revision": None,
                    "client_request_id": config_request_id,
                },
            )
            assert configured.status_code == 200, configured.text
            config_hint = device_ws.receive_json()
            assert config_hint["message_type"] == "SAFETY_MONITORING_CONFIG_AVAILABLE"
            assert config_hint["revision"] == 1

            event_request_id = str(uuid4())
            created = client.post(
                "/api/v1/devices/me/safety-events",
                headers={
                    "Authorization": f"Bearer {bound['device_credential']}",
                    "Idempotency-Key": event_request_id,
                },
                json=event_payload(event_request_id),
            )
            assert created.status_code == 201, created.text
            event_hint = family_ws.receive_json()
            assert event_hint["message_type"] == "SAFETY_EVENT_AVAILABLE"
            assert event_hint["elder_id"] == elder["elder_id"]
            assert event_hint["event_id"] == created.json()["event_id"]
            assert event_hint["severity"] == "EMERGENCY"
            assert "event_summary" not in event_hint
            assert str(UUID(event_hint["message_id"])) == event_hint["message_id"]

            image_output = io.BytesIO()
            Image.new("RGB", (32, 32), (180, 30, 30)).save(image_output, format="JPEG")
            image_uploaded = client.put(
                f"/api/v1/devices/me/safety-events/{created.json()['event_id']}/image",
                headers={
                    "Authorization": f"Bearer {bound['device_credential']}",
                    "Idempotency-Key": created.json()["event_id"],
                    "Content-Type": "image/jpeg",
                },
                content=image_output.getvalue(),
            )
            assert image_uploaded.status_code == 200, image_uploaded.text
            image_hint = family_ws.receive_json()
            assert image_hint["message_type"] == "SAFETY_EVENT_IMAGE_AVAILABLE"
            assert image_hint["elder_id"] == elder["elder_id"]
            assert image_hint["event_id"] == created.json()["event_id"]
            assert not {"image", "url", "event_summary"} & set(image_hint)

import asyncio
import copy
import json
from datetime import UTC, date, datetime, time, timedelta
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

from fastapi import FastAPI
from fastapi.testclient import TestClient
from httpx import AsyncClient, Response
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import utc_now
from app.main import create_app
from app.models import (
    AuditLog,
    ModelUsageBatch,
    ModelUsageItem,
    ModelUsageRefreshRequest,
)
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


def usage_payload(
    batch_id: str = "a1000000-0000-4000-8000-000000000001",
) -> dict[str, Any]:
    now = utc_now()
    return {
        "batch_id": batch_id,
        "period_started_at": (now - timedelta(hours=1)).isoformat().replace("+00:00", "Z"),
        "period_ended_at": now.isoformat().replace("+00:00", "Z"),
        "time_zone": "Asia/Shanghai",
        "time_zone_source": "LOCATION",
        "items": [
            {
                "modality": "MLLM",
                "provider": "openai_compatible",
                "model": "qwen3_5",
                "feature": "conversation",
                "request_count": 4,
                "success_count": 4,
                "input_tokens": 6120,
                "output_tokens": 980,
                "asr_audio_duration_ms": 0,
                "tts_character_count": 0,
                "tts_audio_duration_ms": 0,
                "contains_estimated_values": False,
            }
        ],
    }


async def post_usage(
    client: AsyncClient,
    credential: str,
    payload: dict[str, Any],
    *,
    idempotency_key: str | None = None,
) -> Response:
    return await client.post(
        "/api/v1/model-usage/batches",
        headers={
            "Authorization": f"Bearer {credential}",
            "Idempotency-Key": idempotency_key or str(payload["batch_id"]),
        },
        json=payload,
    )


async def get_summary(
    client: AsyncClient,
    access_token: str,
    elder_id: str,
    started_at: str,
    ended_at: str,
) -> Response:
    return await client.get(
        f"/api/v1/elders/{elder_id}/model-usage",
        headers={"Authorization": f"Bearer {access_token}"},
        params={"from": started_at, "to": ended_at},
    )


async def request_refresh(
    client: AsyncClient,
    access_token: str,
    elder_id: str,
    request_id: str,
    *,
    idempotency_key: str | None = None,
) -> Response:
    return await client.post(
        f"/api/v1/elders/{elder_id}/model-usage/refresh",
        headers={
            "Authorization": f"Bearer {access_token}",
            "Idempotency-Key": idempotency_key or request_id,
        },
        json={"client_request_id": request_id},
    )


async def test_usage_batch_is_idempotent_and_counted_once(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    payload = usage_payload()

    first, retry = await asyncio.gather(
        post_usage(client, bound["device_credential"], payload),
        post_usage(client, bound["device_credential"], payload),
    )
    assert first.status_code == retry.status_code == 201
    assert first.json() == retry.json()
    assert first.headers["Cache-Control"] == "no-store"

    summary = await get_summary(
        client,
        family["access_token"],
        elder["elder_id"],
        payload["period_started_at"],
        payload["period_ended_at"],
    )
    assert summary.status_code == 200, summary.text
    body = summary.json()
    assert body["totals"] == {
        "input_tokens": 6120,
        "output_tokens": 980,
        "mllm_request_count": 4,
        "asr_request_count": 0,
        "tts_request_count": 0,
        "asr_audio_duration_ms": 0,
        "tts_character_count": 0,
        "tts_audio_duration_ms": 0,
        "contains_estimated_values": False,
    }
    assert body["last_reported_at"] == first.json()["received_at"]
    assert summary.headers["Cache-Control"] == "no-store"

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ModelUsageBatch.batch_id))) == 1
        assert await session.scalar(select(func.count(ModelUsageItem.id))) == 1
        assert (
            await session.scalar(
                select(func.count(AuditLog.id)).where(
                    AuditLog.action == "MODEL_USAGE_BATCH_ACCEPTED"
                )
            )
            == 1
        )


async def test_usage_modalities_and_duplicate_dimensions_are_aggregated(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    payload = usage_payload("a2000000-0000-4000-8000-000000000001")
    base_item = payload["items"][0]
    duplicate = copy.deepcopy(base_item)
    duplicate.update(
        {
            "request_count": 1,
            "success_count": 1,
            "input_tokens": 100,
            "output_tokens": 25,
            "contains_estimated_values": True,
        }
    )
    asr = copy.deepcopy(base_item)
    asr.update(
        {
            "modality": "ASR",
            "model": None,
            "feature": "speech_input",
            "request_count": 3,
            "success_count": 2,
            "input_tokens": 999,
            "output_tokens": 999,
            "asr_audio_duration_ms": 14000,
        }
    )
    tts = copy.deepcopy(base_item)
    tts.update(
        {
            "modality": "TTS",
            "model": None,
            "feature": "reply_speech",
            "request_count": 2,
            "success_count": 2,
            "input_tokens": 777,
            "output_tokens": 777,
            "tts_character_count": 320,
            "tts_audio_duration_ms": 18000,
        }
    )
    payload["items"] = [base_item, duplicate, asr, tts]
    accepted = await post_usage(client, bound["device_credential"], payload)
    assert accepted.status_code == 201, accepted.text

    summary = await get_summary(
        client,
        family["access_token"],
        elder["elder_id"],
        payload["period_started_at"],
        payload["period_ended_at"],
    )
    assert summary.status_code == 200
    assert summary.json()["totals"] == {
        "input_tokens": 6220,
        "output_tokens": 1005,
        "mllm_request_count": 5,
        "asr_request_count": 3,
        "tts_request_count": 2,
        "asr_audio_duration_ms": 14000,
        "tts_character_count": 320,
        "tts_audio_duration_ms": 18000,
        "contains_estimated_values": True,
    }
    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ModelUsageItem.id))) == 3


async def test_empty_period_returns_zero_totals(api: ApiFixture) -> None:
    client, _, _ = api
    family, elder, _ = await prepared_binding(client)
    now = utc_now()
    response = await get_summary(
        client,
        family["access_token"],
        elder["elder_id"],
        (now - timedelta(days=1)).isoformat().replace("+00:00", "Z"),
        now.isoformat().replace("+00:00", "Z"),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["last_reported_at"] is None
    assert all(
        value is False if key == "contains_estimated_values" else value == 0
        for key, value in body["totals"].items()
    )


async def test_usage_idempotency_conflict_and_header_validation(
    api: ApiFixture,
) -> None:
    client, _, _ = api
    _, _, bound = await prepared_binding(client)
    payload = usage_payload("a3000000-0000-4000-8000-000000000001")
    accepted = await post_usage(client, bound["device_credential"], payload)
    assert accepted.status_code == 201

    changed = copy.deepcopy(payload)
    changed["items"][0]["input_tokens"] += 1
    conflict = await post_usage(client, bound["device_credential"], changed)
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    mismatch = await post_usage(
        client,
        bound["device_credential"],
        usage_payload("a3000000-0000-4000-8000-000000000002"),
        idempotency_key="a3000000-0000-4000-8000-000000000003",
    )
    assert mismatch.status_code == 400
    assert mismatch.json()["error"]["code"] == "INVALID_USAGE_BATCH"


async def test_invalid_or_sensitive_usage_payloads_are_rejected(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    _, _, bound = await prepared_binding(client)
    cases: list[dict[str, Any]] = []
    for field, value in (
        ("request_count", -1),
        ("success_count", 5),
        ("input_tokens", 9_000_000_000_000_001),
        ("provider", " "),
        ("feature", "x" * 81),
    ):
        payload = usage_payload()
        payload["items"][0][field] = value
        cases.append(payload)

    reversed_period = usage_payload()
    reversed_period["period_started_at"] = reversed_period["period_ended_at"]
    cases.append(reversed_period)
    too_long = usage_payload()
    too_long["period_started_at"] = (
        (utc_now() - timedelta(days=8)).isoformat().replace("+00:00", "Z")
    )
    cases.append(too_long)
    future = usage_payload()
    future["period_ended_at"] = (
        (utc_now() + timedelta(minutes=11)).isoformat().replace("+00:00", "Z")
    )
    cases.append(future)
    non_utc = usage_payload()
    non_utc["period_started_at"] = "2026-07-19T10:00:00+08:00"
    non_utc["period_ended_at"] = "2026-07-19T11:00:00+08:00"
    cases.append(non_utc)
    sensitive = usage_payload()
    sensitive["api_key"] = "must-never-be-stored"
    cases.append(sensitive)
    identity_override = usage_payload()
    identity_override["elder_id"] = "a088a55f-f2c5-4a89-b4d7-d9b7f1759637"
    cases.append(identity_override)

    for index, payload in enumerate(cases):
        payload["batch_id"] = f"a4000000-0000-4000-8000-{index:012d}"
        response = await post_usage(client, bound["device_credential"], payload)
        assert response.status_code == 400, (index, response.text)
        assert response.json()["error"]["code"] == "INVALID_USAGE_BATCH"
        assert "must-never-be-stored" not in response.text

    oversized = usage_payload("a4000000-0000-4000-8000-999999999999")
    oversized["items"] = [copy.deepcopy(oversized["items"][0]) for _ in range(101)]
    too_large = await post_usage(client, bound["device_credential"], oversized)
    assert too_large.status_code == 413
    assert too_large.json()["error"]["code"] == "USAGE_BATCH_TOO_LARGE"

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ModelUsageBatch.batch_id))) == 0
        serialized = json.dumps(
            [audit.details for audit in (await session.scalars(select(AuditLog))).all()]
        )
        assert "must-never-be-stored" not in serialized


async def test_family_binding_and_query_range_are_enforced(api: ApiFixture) -> None:
    client, _, _ = api
    family, elder, _ = await prepared_binding(client)
    unrelated = await register_family(
        client,
        mobile="13700137000",
        request_id="a5000000-0000-4000-8000-000000000001",
    )
    now = utc_now()
    started = (now - timedelta(days=1)).isoformat().replace("+00:00", "Z")
    ended = now.isoformat().replace("+00:00", "Z")
    forbidden = await get_summary(
        client,
        unrelated["access_token"],
        elder["elder_id"],
        started,
        ended,
    )
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "BINDING_FORBIDDEN"
    daily_forbidden = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-usage/daily",
        headers={"Authorization": f"Bearer {unrelated['access_token']}"},
    )
    assert daily_forbidden.status_code == 403
    assert daily_forbidden.json()["error"]["code"] == "BINDING_FORBIDDEN"

    invalid_ranges = [
        (ended, started),
        (
            (now - timedelta(days=367)).isoformat().replace("+00:00", "Z"),
            ended,
        ),
        ("2026-07-19T10:00:00+08:00", "2026-07-19T11:00:00+08:00"),
    ]
    for invalid_start, invalid_end in invalid_ranges:
        response = await get_summary(
            client,
            family["access_token"],
            elder["elder_id"],
            invalid_start,
            invalid_end,
        )
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_USAGE_QUERY"


async def test_daily_usage_uses_latest_location_zone_and_fills_month(
    api: ApiFixture,
) -> None:
    client, _, _ = api
    family, elder, bound = await prepared_binding(client)
    shanghai = ZoneInfo("Asia/Shanghai")
    local_now = utc_now().astimezone(shanghai)
    period_started_on = local_now.date().replace(day=1)
    period_ended_on = (
        date(period_started_on.year + 1, 1, 1)
        if period_started_on.month == 12
        else date(period_started_on.year, period_started_on.month + 1, 1)
    )

    first_local = datetime.combine(
        period_started_on,
        time(hour=1),
        tzinfo=shanghai,
    )
    first_payload = usage_payload("a5500000-0000-4000-8000-000000000001")
    first_payload["period_started_at"] = first_local.astimezone(UTC).isoformat()
    first_payload["period_ended_at"] = (
        (first_local + timedelta(minutes=30)).astimezone(UTC).isoformat()
    )
    first = await post_usage(client, bound["device_credential"], first_payload)
    assert first.status_code == 201, first.text

    second_started = utc_now() - timedelta(minutes=30)
    second_payload = usage_payload("a5500000-0000-4000-8000-000000000002")
    second_payload["period_started_at"] = second_started.isoformat()
    second_payload["period_ended_at"] = (second_started + timedelta(minutes=10)).isoformat()
    second_payload["time_zone"] = "America/New_York"
    second_payload["time_zone_source"] = "SYSTEM_FALLBACK"
    base_item = second_payload["items"][0]
    asr = copy.deepcopy(base_item)
    asr.update(
        {
            "modality": "ASR",
            "model": None,
            "feature": "speech_input",
            "request_count": 2,
            "success_count": 2,
            "input_tokens": 999,
            "output_tokens": 999,
            "asr_audio_duration_ms": 12000,
            "contains_estimated_values": True,
        }
    )
    tts = copy.deepcopy(base_item)
    tts.update(
        {
            "modality": "TTS",
            "model": None,
            "feature": "reply_speech",
            "request_count": 3,
            "success_count": 3,
            "input_tokens": 888,
            "output_tokens": 888,
            "tts_character_count": 240,
            "tts_audio_duration_ms": 15000,
        }
    )
    second_payload["items"] = [asr, tts]
    second = await post_usage(client, bound["device_credential"], second_payload)
    assert second.status_code == 201, second.text

    daily = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-usage/daily",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert daily.status_code == 200, daily.text
    assert daily.headers["Cache-Control"] == "no-store"
    body = daily.json()
    assert body["period_started_on"] == period_started_on.isoformat()
    assert body["period_ended_on"] == period_ended_on.isoformat()
    assert body["current_date"] == local_now.date().isoformat()
    assert body["timezone"] == "Asia/Shanghai"
    assert body["timezone_source"] == "LOCATION"
    assert len(body["days"]) == (period_ended_on - period_started_on).days
    assert [item["date"] for item in body["days"]] == sorted(item["date"] for item in body["days"])

    first_day = body["days"][0]
    assert first_day["date"] == period_started_on.isoformat()
    assert first_day["totals"]["input_tokens"] == 6120
    assert first_day["totals"]["output_tokens"] == 980
    assert first_day["totals"]["mllm_request_count"] == 4

    second_local_date = second_started.astimezone(shanghai).date().isoformat()
    second_day = next(item for item in body["days"] if item["date"] == second_local_date)
    assert second_day["totals"] == {
        "input_tokens": 0,
        "output_tokens": 0,
        "mllm_request_count": 0,
        "asr_request_count": 2,
        "tts_request_count": 3,
        "asr_audio_duration_ms": 12000,
        "tts_character_count": 240,
        "tts_audio_duration_ms": 15000,
        "contains_estimated_values": True,
    }
    assert body["last_reported_at"] == second.json()["received_at"]


async def test_daily_usage_without_reports_uses_utc_and_zero_buckets(
    api: ApiFixture,
) -> None:
    client, _, _ = api
    family, elder, _ = await prepared_binding(client)
    response = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/model-usage/daily",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["timezone"] == "UTC"
    assert body["timezone_source"] == "SYSTEM_FALLBACK"
    assert body["current_date"] == utc_now().date().isoformat()
    assert body["last_reported_at"] is None
    assert all(
        all(
            value is False if key == "contains_estimated_values" else value == 0
            for key, value in day["totals"].items()
        )
        for day in body["days"]
    )


async def test_usage_time_zone_validation_and_idempotency(api: ApiFixture) -> None:
    client, app, _ = api
    _, _, bound = await prepared_binding(client)
    invalid_zone = usage_payload("a5600000-0000-4000-8000-000000000001")
    invalid_zone["time_zone"] = "Mars/Olympus"
    invalid_source = usage_payload("a5600000-0000-4000-8000-000000000002")
    invalid_source["time_zone_source"] = "FAMILY_DEVICE"
    for payload in (invalid_zone, invalid_source):
        response = await post_usage(client, bound["device_credential"], payload)
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_USAGE_BATCH"

    valid = usage_payload("a5600000-0000-4000-8000-000000000003")
    accepted = await post_usage(client, bound["device_credential"], valid)
    assert accepted.status_code == 201
    changed_zone = copy.deepcopy(valid)
    changed_zone["time_zone"] = "UTC"
    conflict = await post_usage(client, bound["device_credential"], changed_zone)
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ModelUsageBatch.batch_id))) == 1


async def test_offline_refresh_is_idempotent_rate_limited_and_audited(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, _ = await prepared_binding(client)
    request_id = "a6000000-0000-4000-8000-000000000001"

    first = await request_refresh(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id,
    )
    retry = await request_refresh(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id,
    )
    assert first.status_code == retry.status_code == 200
    assert first.json() == retry.json()
    assert first.json() == {
        "client_request_id": request_id,
        "requested_at": first.json()["requested_at"],
        "device_online": False,
    }
    assert first.headers["Cache-Control"] == "no-store"

    limited = await request_refresh(
        client,
        family["access_token"],
        elder["elder_id"],
        "a6000000-0000-4000-8000-000000000002",
    )
    assert limited.status_code == 429
    assert limited.json()["error"]["code"] == "USAGE_REFRESH_RATE_LIMITED"

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(ModelUsageRefreshRequest.id))) == 1
        assert (
            await session.scalar(
                select(func.count(AuditLog.id)).where(
                    AuditLog.action == "MODEL_USAGE_REFRESH_REQUESTED"
                )
            )
            == 1
        )


async def test_refresh_permissions_header_and_online_delivery_result(
    api: ApiFixture,
) -> None:
    client, app, settings = api
    settings.usage_refresh_min_interval_seconds = 0
    family, elder, _ = await prepared_binding(client)
    unrelated = await register_family(
        client,
        mobile="13700137000",
        request_id="a7000000-0000-4000-8000-000000000001",
    )
    request_id = "a7000000-0000-4000-8000-000000000002"
    forbidden = await request_refresh(
        client,
        unrelated["access_token"],
        elder["elder_id"],
        request_id,
    )
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "BINDING_FORBIDDEN"

    mismatch = await request_refresh(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id,
        idempotency_key="a7000000-0000-4000-8000-000000000003",
    )
    assert mismatch.status_code == 400
    assert mismatch.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

    app.state.connection_manager.notify_model_usage_report_requested = AsyncMock(return_value=True)
    online = await request_refresh(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id,
    )
    assert online.status_code == 200
    assert online.json()["device_online"] is True
    notification = app.state.connection_manager.notify_model_usage_report_requested
    notification.assert_awaited_once()
    assert notification.await_args.args[0] == elder["elder_id"]
    assert len(notification.await_args.args[1]) == 1
    assert notification.await_args.args[2] == request_id

    second_elder = await create_elder(
        client,
        family["access_token"],
        mobile="13600136000",
        request_id="a7000000-0000-4000-8000-000000000004",
    )
    second_code = await create_code(
        client,
        family["access_token"],
        second_elder["elder_id"],
        request_id="a7000000-0000-4000-8000-000000000005",
    )
    second_bound = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            second_code["binding_code"],
            device_id="device-elder-usage-second",
            request_id="a7000000-0000-4000-8000-000000000006",
        ),
    )
    assert second_bound.status_code == 201
    conflict = await request_refresh(
        client,
        family["access_token"],
        second_elder["elder_id"],
        request_id,
    )
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"


def test_refresh_emits_usage_report_request_to_online_device(tmp_path: Path) -> None:
    settings = Settings(
        database_url=f"sqlite+aiosqlite:///{(tmp_path / 'usage-refresh-ws.db').as_posix()}",
        auto_create_schema=True,
        jwt_secret="usage-refresh-jwt-secret-with-enough-entropy",
        security_secret="usage-refresh-security-secret-with-enough-entropy",
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
                "emergency_contact": False,
                "client_request_id": str(uuid4()),
            },
        ).json()
        code = client.post(
            "/api/v1/bindings/codes",
            headers={"Authorization": f"Bearer {family['access_token']}"},
            json={
                "elder_id": elder["elder_id"],
                "client_request_id": str(uuid4()),
            },
        ).json()
        bound = client.post(
            "/api/v1/devices/bind",
            json=bind_payload(
                code["binding_code"],
                device_id="device-usage-refresh-ws",
                request_id=str(uuid4()),
            ),
        ).json()

        with client.websocket_connect(
            "/api/v1/ws",
            headers={"Authorization": f"Bearer {bound['device_credential']}"},
        ) as websocket:
            request_id = str(uuid4())
            refreshed = client.post(
                f"/api/v1/elders/{elder['elder_id']}/model-usage/refresh",
                headers={
                    "Authorization": f"Bearer {family['access_token']}",
                    "Idempotency-Key": request_id,
                },
                json={"client_request_id": request_id},
            )
            assert refreshed.status_code == 200, refreshed.text
            assert refreshed.json()["device_online"] is True
            message = websocket.receive_json()
            assert message["protocol_version"] == 1
            assert message["message_type"] == "MODEL_USAGE_REPORT_REQUESTED"
            assert message["payload"] == {"client_request_id": request_id}
            assert message["sent_at"] == refreshed.json()["requested_at"]
            assert str(UUID(message["message_id"])) == message["message_id"]
            assert not {
                "device_credential",
                "api_key",
                "prompt",
                "content",
                "audio",
            } & set(message["payload"])

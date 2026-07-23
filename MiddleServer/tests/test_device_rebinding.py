import asyncio
import json
from datetime import timedelta
from typing import Any

import pytest
from fastapi import FastAPI
from httpx import AsyncClient
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import utc_now
from app.models import AuditLog, Binding, BindingCode, DeviceCredential
from tests.conftest import bind_payload, create_code, create_elder, register_family

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


async def create_initial_binding(
    client: AsyncClient,
    *,
    device_id: str = "device-rebind-old",
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    family = await register_family(client)
    elder = await create_elder(client, family["access_token"])
    code = await create_code(client, family["access_token"], elder["elder_id"])
    response = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(code["binding_code"], device_id=device_id),
    )
    assert response.status_code == 201, response.text
    return family, elder, response.json()


async def assert_credential_status(
    client: AsyncClient, credential: str, expected_status: int
) -> None:
    response = await client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {credential}"},
    )
    assert response.status_code == expected_status, response.text


async def test_regenerating_code_keeps_old_credential_and_revokes_only_older_code(
    api: ApiFixture,
) -> None:
    client, _, _ = api
    family, elder, initial = await create_initial_binding(client)
    first_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "81000000-0000-4000-8000-000000000001",
    )
    second_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "81000000-0000-4000-8000-000000000002",
    )

    assert first_code["binding_code"] != second_code["binding_code"]
    await assert_credential_status(client, initial["device_credential"], 200)

    revoked_code = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            first_code["binding_code"],
            device_id="device-rebind-revoked-code",
            request_id="81000000-0000-4000-8000-000000000003",
        ),
    )
    assert revoked_code.status_code == 409
    assert revoked_code.json()["error"]["code"] == "BINDING_CODE_USED_OR_REVOKED"
    await assert_credential_status(client, initial["device_credential"], 200)


async def test_rebinding_reuses_binding_and_atomically_rotates_device_credential(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, initial = await create_initial_binding(client)
    new_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "82000000-0000-4000-8000-000000000001",
    )
    payload = bind_payload(
        new_code["binding_code"],
        device_id="device-rebind-new",
        request_id="82000000-0000-4000-8000-000000000002",
    )
    payload["device_name"] = "王阿姨的新手机"

    rebound = await client.post("/api/v1/devices/bind", json=payload)
    assert rebound.status_code == 201, rebound.text
    result = rebound.json()
    assert result["binding_id"] == initial["binding_id"]
    assert result["relationship"] == initial["relationship"]
    assert result["permissions"] == initial["permissions"]
    assert result["device_credential"] != initial["device_credential"]

    await assert_credential_status(client, initial["device_credential"], 401)
    await assert_credential_status(client, result["device_credential"], 200)

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(Binding.id))) == 1
        active_devices = (
            await session.scalars(
                select(DeviceCredential).where(DeviceCredential.revoked_at.is_(None))
            )
        ).all()
        assert len(active_devices) == 1
        assert active_devices[0].external_device_id == "device-rebind-new"
        audit = (
            await session.scalars(select(AuditLog).where(AuditLog.action == "DEVICE_REBOUND"))
        ).one()

    assert audit.actor_id == active_devices[0].id
    assert audit.details["elder_id"] == elder["elder_id"]
    assert audit.details["binding_id"] == initial["binding_id"]
    assert audit.details["previous_device_record_ids"]
    assert audit.details["new_device_record_id"] == active_devices[0].id
    serialized_audit = json.dumps(audit.details)
    assert new_code["binding_code"] not in serialized_audit
    assert result["device_credential"] not in serialized_audit
    assert "13800138000" not in serialized_audit


async def test_same_device_id_can_rotate_credential_without_duplicate_binding(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, initial = await create_initial_binding(client, device_id="device-rebind-same")
    new_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "83000000-0000-4000-8000-000000000001",
    )
    payload = bind_payload(
        new_code["binding_code"],
        device_id="device-rebind-same",
        request_id="83000000-0000-4000-8000-000000000002",
    )

    first = await client.post("/api/v1/devices/bind", json=payload)
    retry = await client.post("/api/v1/devices/bind", json=payload)
    assert first.status_code == retry.status_code == 201
    assert first.json() == retry.json()
    assert first.json()["binding_id"] == initial["binding_id"]

    await assert_credential_status(client, initial["device_credential"], 401)
    await assert_credential_status(client, first.json()["device_credential"], 200)
    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(Binding.id))) == 1
        assert await session.scalar(select(func.count(DeviceCredential.id))) == 1
        assert (
            await session.scalar(
                select(func.count(DeviceCredential.id)).where(DeviceCredential.revoked_at.is_(None))
            )
            == 1
        )


async def test_failed_rebinding_does_not_revoke_old_credential(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, initial = await create_initial_binding(client)
    new_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "84000000-0000-4000-8000-000000000001",
    )

    wrong_mobile = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            new_code["binding_code"],
            mobile="13700137000",
            device_id="device-rebind-wrong-mobile",
            request_id="84000000-0000-4000-8000-000000000002",
        ),
    )
    assert wrong_mobile.status_code == 400

    no_consent_payload = bind_payload(
        new_code["binding_code"],
        device_id="device-rebind-no-consent",
        request_id="84000000-0000-4000-8000-000000000003",
    )
    no_consent_payload["sharing_consent"] = False
    no_consent = await client.post("/api/v1/devices/bind", json=no_consent_payload)
    assert no_consent.status_code == 400
    await assert_credential_status(client, initial["device_credential"], 200)

    async with app.state.database.session_factory() as session, session.begin():
        active_code = (
            await session.scalars(
                select(BindingCode).where(
                    BindingCode.used_at.is_(None),
                    BindingCode.revoked_at.is_(None),
                )
            )
        ).one()
        active_code.expires_at = utc_now() - timedelta(seconds=1)

    expired = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            new_code["binding_code"],
            device_id="device-rebind-expired",
            request_id="84000000-0000-4000-8000-000000000004",
        ),
    )
    assert expired.status_code == 410
    await assert_credential_status(client, initial["device_credential"], 200)


async def test_device_bound_to_other_elder_is_not_taken_over(api: ApiFixture) -> None:
    client, _, _ = api
    _, _, first_binding = await create_initial_binding(client, device_id="device-rebind-shared")

    other_family = await register_family(
        client,
        mobile="13700137000",
        display_name="另一位家属",
        request_id="85000000-0000-4000-8000-000000000001",
    )
    other_elder = await create_elder(
        client,
        other_family["access_token"],
        mobile="13600136000",
        request_id="85000000-0000-4000-8000-000000000002",
    )
    other_code = await create_code(
        client,
        other_family["access_token"],
        other_elder["elder_id"],
        "85000000-0000-4000-8000-000000000003",
    )
    conflict = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            other_code["binding_code"],
            mobile="13700137000",
            device_id="device-rebind-shared",
            request_id="85000000-0000-4000-8000-000000000004",
        ),
    )
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "DEVICE_BINDING_CONFLICT"
    await assert_credential_status(client, first_binding["device_credential"], 200)


async def test_concurrent_rebinding_consumes_new_code_once(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, initial = await create_initial_binding(client)
    new_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "86000000-0000-4000-8000-000000000001",
    )
    responses = await asyncio.gather(
        client.post(
            "/api/v1/devices/bind",
            json=bind_payload(
                new_code["binding_code"],
                device_id="device-rebind-concurrent-a",
                request_id="86000000-0000-4000-8000-000000000002",
            ),
        ),
        client.post(
            "/api/v1/devices/bind",
            json=bind_payload(
                new_code["binding_code"],
                device_id="device-rebind-concurrent-b",
                request_id="86000000-0000-4000-8000-000000000003",
            ),
        ),
    )
    assert sorted(response.status_code for response in responses) == [201, 409]
    await assert_credential_status(client, initial["device_credential"], 401)
    async with app.state.database.session_factory() as session:
        assert (
            await session.scalar(
                select(func.count(DeviceCredential.id)).where(DeviceCredential.revoked_at.is_(None))
            )
            == 1
        )


async def test_credential_derivation_failure_rolls_back_code_and_old_device(
    api: ApiFixture,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client, app, _ = api
    family, elder, initial = await create_initial_binding(client)
    new_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "87000000-0000-4000-8000-000000000001",
    )

    def fail_credential_derivation(*_args: object) -> str:
        raise RuntimeError("simulated credential derivation failure")

    monkeypatch.setattr(
        "app.services.family_binding.derive_device_credential",
        fail_credential_derivation,
    )
    with pytest.raises(RuntimeError, match="simulated credential derivation failure"):
        await client.post(
            "/api/v1/devices/bind",
            json=bind_payload(
                new_code["binding_code"],
                device_id="device-rebind-rollback",
                request_id="87000000-0000-4000-8000-000000000002",
            ),
        )

    await assert_credential_status(client, initial["device_credential"], 200)
    async with app.state.database.session_factory() as session:
        code = (
            await session.scalars(
                select(BindingCode).where(
                    BindingCode.used_at.is_(None),
                    BindingCode.revoked_at.is_(None),
                )
            )
        ).one()
        assert code.used_at is None
        assert (
            await session.scalar(
                select(func.count(DeviceCredential.id)).where(DeviceCredential.revoked_at.is_(None))
            )
            == 1
        )

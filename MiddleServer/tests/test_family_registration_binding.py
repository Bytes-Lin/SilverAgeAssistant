import asyncio
import json
from datetime import timedelta
from typing import Any

from fastapi import FastAPI
from httpx import AsyncClient
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import format_binding_code, utc_now
from app.models import AuditLog, Binding, BindingCode, DeviceCredential, ElderProfile, FamilyAccount
from tests.conftest import bind_payload, create_code, create_elder, register_family

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


async def prepared_family(
    client: AsyncClient,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    family = await register_family(client)
    elder = await create_elder(client, family["access_token"])
    code = await create_code(client, family["access_token"], elder["elder_id"])
    return family, elder, code


async def test_registration_and_elder_creation_are_idempotent(api: ApiFixture) -> None:
    client, app, _ = api
    first_family = await register_family(client)
    second_family = await register_family(client)
    assert first_family["family_account_id"] == second_family["family_account_id"]
    assert "13800138000" not in json.dumps(second_family, ensure_ascii=False)

    refreshed = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": first_family["refresh_token"]},
    )
    assert refreshed.status_code == 200
    assert refreshed.json()["access_token"]

    first_elder = await create_elder(client, first_family["access_token"])
    second_elder = await create_elder(client, first_family["access_token"])
    assert first_elder["elder_id"] == second_elder["elder_id"]

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(FamilyAccount.id))) == 1
        assert await session.scalar(select(func.count(ElderProfile.id))) == 1
        stored_family = (await session.scalars(select(FamilyAccount))).one()
        assert stored_family.mobile_verified_at is None


async def test_registration_rejects_removed_verification_token_field(api: ApiFixture) -> None:
    client, _, _ = api
    response = await client.post(
        "/api/v1/auth/family/register",
        json={
            "display_name": "小林",
            "mobile_number": "13800138000",
            "verification_token": "legacy-field-is-not-accepted",
            "client_request_id": "10000000-0000-4000-8000-000000000001",
        },
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"


async def test_code_is_six_digits_and_generation_is_idempotent(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, first = await prepared_family(client)
    second = await create_code(client, family["access_token"], elder["elder_id"])
    assert first == second
    assert len(first["binding_code"]) == 6
    assert first["binding_code"].isdigit()
    assert format_binding_code(12345) == "012345"

    async with app.state.database.session_factory() as session:
        record = (await session.scalars(select(BindingCode))).one()
        assert record.code_digest != first["binding_code"]
        assert first["binding_code"] not in record.code_digest


async def test_wrong_code_or_mobile_returns_same_generic_error(api: ApiFixture) -> None:
    client, _, _ = api
    _, _, code = await prepared_family(client)
    wrong_mobile = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(code["binding_code"], mobile="13700137000"),
    )
    wrong_code = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload("999999", device_id="device-elder-002"),
    )
    for response in (wrong_mobile, wrong_code):
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "BINDING_CREDENTIALS_INVALID"

    no_consent_payload = bind_payload(
        code["binding_code"],
        device_id="device-no-consent",
        request_id="40000000-0000-4000-8000-000000000001",
    )
    no_consent_payload["sharing_consent"] = False
    no_consent = await client.post("/api/v1/devices/bind", json=no_consent_payload)
    assert no_consent.status_code == 400
    assert no_consent.json()["error"]["code"] == "SHARING_CONSENT_REQUIRED"


async def test_successful_binding_is_atomic_idempotent_and_queryable(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, code = await prepared_family(client)
    payload = bind_payload(code["binding_code"])
    first = await client.post("/api/v1/devices/bind", json=payload)
    second = await client.post("/api/v1/devices/bind", json=payload)
    assert first.status_code == 201, first.text
    assert second.status_code == 201, second.text
    assert first.json() == second.json()
    result = first.json()
    assert result["elder_id"] == elder["elder_id"]
    assert result["permissions"] == ["VIEWER", "HELPER", "EMERGENCY_CONTACT"]

    family_view = await client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    device_view = await client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {result['device_credential']}"},
    )
    assert family_view.status_code == device_view.status_code == 200
    assert family_view.json()["bindings"][0]["binding_id"] == result["binding_id"]
    assert device_view.json()["bindings"][0]["elder_id"] == elder["elder_id"]

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(Binding.id))) == 1
        device = (await session.scalars(select(DeviceCredential))).one()
        assert device.credential_digest != result["device_credential"]

    revoked = await client.delete(
        f"/api/v1/bindings/{result['binding_id']}",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert revoked.status_code == 204
    revoked_device_view = await client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {result['device_credential']}"},
    )
    assert revoked_device_view.status_code == 401


async def test_expired_revoked_and_used_codes_are_rejected(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, code = await prepared_family(client)
    async with app.state.database.session_factory() as session, session.begin():
        record = (await session.scalars(select(BindingCode))).one()
        record.expires_at = utc_now() - timedelta(seconds=1)
    expired = await client.post("/api/v1/devices/bind", json=bind_payload(code["binding_code"]))
    assert expired.status_code == 410
    assert expired.json()["error"]["code"] == "BINDING_CODE_EXPIRED"

    new_code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "33333333-3333-4333-8333-333333333334",
    )
    revoked = await client.delete(
        f"/api/v1/bindings/codes/{elder['elder_id']}",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert revoked.status_code == 204
    revoked_bind = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            new_code["binding_code"],
            device_id="device-revoked",
            request_id="44444444-4444-4444-8444-444444444445",
        ),
    )
    assert revoked_bind.status_code == 409
    assert revoked_bind.json()["error"]["code"] == "BINDING_CODE_USED_OR_REVOKED"

    usable = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        "33333333-3333-4333-8333-333333333335",
    )
    first = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            usable["binding_code"],
            device_id="device-used-one",
            request_id="44444444-4444-4444-8444-444444444446",
        ),
    )
    assert first.status_code == 201
    used = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            usable["binding_code"],
            device_id="device-used-two",
            request_id="44444444-4444-4444-8444-444444444447",
        ),
    )
    assert used.status_code == 409
    assert used.json()["error"]["code"] == "BINDING_CODE_USED_OR_REVOKED"


async def test_concurrent_code_consumption_has_only_one_success(api: ApiFixture) -> None:
    client, _, _ = api
    _, _, code = await prepared_family(client)
    responses = await asyncio.gather(
        client.post(
            "/api/v1/devices/bind",
            json=bind_payload(
                code["binding_code"],
                device_id="device-concurrent-a",
                request_id="50000000-0000-4000-8000-000000000001",
            ),
        ),
        client.post(
            "/api/v1/devices/bind",
            json=bind_payload(
                code["binding_code"],
                device_id="device-concurrent-b",
                request_id="50000000-0000-4000-8000-000000000002",
            ),
        ),
    )
    assert sorted(response.status_code for response in responses) == [201, 409]


async def test_failed_attempts_are_rate_limited(api: ApiFixture) -> None:
    client, _, _ = api
    for index in range(5):
        response = await client.post(
            "/api/v1/devices/bind",
            json=bind_payload("999999", request_id=f"60000000-0000-4000-8000-{index:012d}"),
        )
        assert response.status_code == 400
    limited = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            "999999",
            request_id="60000000-0000-4000-8000-999999999999",
        ),
    )
    assert limited.status_code == 429
    assert limited.json()["error"]["code"] == "BINDING_ATTEMPTS_EXCEEDED"


async def test_other_family_cannot_see_binding_and_audit_has_no_secrets(api: ApiFixture) -> None:
    client, app, _ = api
    family, _, code = await prepared_family(client)
    bind = await client.post("/api/v1/devices/bind", json=bind_payload(code["binding_code"]))
    assert bind.status_code == 201
    credential = bind.json()["device_credential"]

    other = await register_family(
        client,
        mobile="13700137000",
        display_name="另一位家属",
        request_id="70000000-0000-4000-8000-000000000001",
    )
    other_view = await client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {other['access_token']}"},
    )
    assert other_view.status_code == 200
    assert other_view.json() == {"bindings": []}

    async with app.state.database.session_factory() as session:
        audits = (await session.scalars(select(AuditLog))).all()
        serialized = json.dumps(
            [
                {
                    "action": audit.action,
                    "actor_id": audit.actor_id,
                    "details": audit.details,
                }
                for audit in audits
            ]
        )
    assert code["binding_code"] not in serialized
    assert "13800138000" not in serialized
    assert credential not in serialized
    assert family["family_mobile_masked"] == "138****8000"

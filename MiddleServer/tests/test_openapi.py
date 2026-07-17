from fastapi import FastAPI
from httpx import AsyncClient

from app.core.config import Settings

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


async def test_openapi_contains_binding_contract(api: ApiFixture) -> None:
    client, _, _ = api
    response = await client.get("/openapi.json")
    assert response.status_code == 200
    document = response.json()
    paths = document["paths"]
    expected = {
        "/api/v1/auth/family/register",
        "/api/v1/auth/refresh",
        "/api/v1/elders",
        "/api/v1/bindings/codes",
        "/api/v1/devices/bind",
        "/api/v1/bindings",
        "/api/v1/elders/{elder_id}/commands/notifications",
        "/api/v1/elders/{elder_id}/commands/reminders",
        "/api/v1/commands/pending",
        "/api/v1/commands/{command_id}/ack",
        "/api/v1/devices/me/family-contacts",
    }
    assert expected.issubset(paths)
    assert "/api/v1/auth/family/dev-verification-token" not in paths
    register_properties = document["components"]["schemas"]["FamilyRegisterRequest"]["properties"]
    assert "verification_token" not in register_properties
    notification_operation = paths["/api/v1/elders/{elder_id}/commands/notifications"]["post"]
    assert notification_operation["security"] == [{"HTTPBearer": []}]
    idempotency_parameter = next(
        parameter
        for parameter in notification_operation["parameters"]
        if parameter["name"] == "Idempotency-Key"
    )
    assert idempotency_parameter["required"] is True
    notification_schema = document["components"]["schemas"]["NotificationCreateRequest"]
    assert notification_schema["properties"]["content"]["maxLength"] == 200
    contacts_operation = paths["/api/v1/devices/me/family-contacts"]["get"]
    assert contacts_operation["security"] == [{"HTTPBearer": []}]
    assert {"401", "403", "503"}.issubset(contacts_operation["responses"])
    contacts_schema = document["components"]["schemas"]["FamilyContact"]
    assert contacts_schema["properties"]["relationship"]["$ref"].endswith("/Relationship")
    assert (
        "中国大陆手机号"
        in document["components"]["schemas"]["FamilyRegisterRequest"]["properties"][
            "mobile_number"
        ]["description"]
    )


async def test_removed_development_verification_endpoint_returns_not_found(
    api: ApiFixture,
) -> None:
    client, _, _ = api
    response = await client.post(
        "/api/v1/auth/family/dev-verification-token",
        json={"mobile_number": "13800138000"},
    )
    assert response.status_code == 404


async def test_validation_errors_do_not_echo_sensitive_input(api: ApiFixture) -> None:
    client, _, _ = api
    response = await client.post(
        "/api/v1/devices/bind",
        json={
            "binding_code": "secret-code",
            "family_mobile_number": "13800138000",
            "elder_display_name": "王阿姨",
            "sharing_consent": True,
            "device_id": "device-001",
            "client_request_id": "not-a-uuid",
        },
    )
    assert response.status_code == 422
    serialized = response.text
    assert "secret-code" not in serialized
    assert "13800138000" not in serialized
    assert response.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

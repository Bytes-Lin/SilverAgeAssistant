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
    }
    assert expected.issubset(paths)
    assert (
        "中国大陆手机号"
        in document["components"]["schemas"]["FamilyRegisterRequest"]["properties"][
            "mobile_number"
        ]["description"]
    )


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

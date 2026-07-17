from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any, cast

import pytest
from asgi_lifespan import LifespanManager
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.core.config import Settings
from app.main import create_app


@pytest.fixture
async def api(tmp_path: Path) -> AsyncIterator[tuple[AsyncClient, FastAPI, Settings]]:
    database_path = (tmp_path / "test.db").as_posix()
    settings = Settings(
        database_url=f"sqlite+aiosqlite:///{database_path}",
        auto_create_schema=True,
        jwt_secret="test-jwt-secret-with-enough-entropy",
        security_secret="test-security-secret-with-enough-entropy",
        binding_failure_limit=5,
    )
    app = create_app(settings)
    async with LifespanManager(app):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="https://testserver") as client:
            yield client, app, settings


async def register_family(
    client: AsyncClient,
    mobile: str = "13800138000",
    display_name: str = "小林",
    request_id: str = "11111111-1111-4111-8111-111111111111",
) -> dict[str, Any]:
    response = await client.post(
        "/api/v1/auth/family/register",
        json={
            "display_name": display_name,
            "mobile_number": mobile,
            "client_request_id": request_id,
        },
    )
    assert response.status_code == 201, response.text
    return cast(dict[str, Any], response.json())


async def create_elder(
    client: AsyncClient,
    access_token: str,
    mobile: str = "13900139000",
    request_id: str = "22222222-2222-4222-8222-222222222222",
) -> dict[str, Any]:
    response = await client.post(
        "/api/v1/elders",
        headers={"Authorization": f"Bearer {access_token}"},
        json={
            "display_name": "王阿姨",
            "mobile_number": mobile,
            "relationship": "CHILD",
            "emergency_contact": True,
            "client_request_id": request_id,
        },
    )
    assert response.status_code == 201, response.text
    return cast(dict[str, Any], response.json())


async def create_code(
    client: AsyncClient,
    access_token: str,
    elder_id: str,
    request_id: str = "33333333-3333-4333-8333-333333333333",
) -> dict[str, Any]:
    response = await client.post(
        "/api/v1/bindings/codes",
        headers={"Authorization": f"Bearer {access_token}"},
        json={"elder_id": elder_id, "client_request_id": request_id},
    )
    assert response.status_code == 201, response.text
    return cast(dict[str, Any], response.json())


def bind_payload(
    code: str,
    mobile: str = "13800138000",
    device_id: str = "device-elder-001",
    request_id: str = "44444444-4444-4444-8444-444444444444",
) -> dict[str, Any]:
    return {
        "binding_code": code,
        "family_mobile_number": mobile,
        "elder_display_name": "王阿姨",
        "sharing_consent": True,
        "device_id": device_id,
        "device_name": "王阿姨的手机",
        "client_request_id": request_id,
    }

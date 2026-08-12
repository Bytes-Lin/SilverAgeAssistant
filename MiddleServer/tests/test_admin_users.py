from collections.abc import AsyncIterator
from pathlib import Path

import pytest
from asgi_lifespan import LifespanManager
from httpx import ASGITransport, AsyncClient

from app.core.config import Settings
from app.main import create_app
from tests.conftest import bind_payload, create_code, create_elder, register_family


@pytest.fixture
async def admin_client(tmp_path: Path) -> AsyncIterator[AsyncClient]:
    settings = Settings(
        database_url=f"sqlite+aiosqlite:///{(tmp_path / 'admin.db').as_posix()}",
        auto_create_schema=True,
        jwt_secret="test-jwt-secret-with-enough-entropy",
        security_secret="test-security-secret-with-enough-entropy",
        admin_enabled=True,
        safety_image_storage_path=str(tmp_path / "safety-images"),
    )
    app = create_app(settings)
    async with LifespanManager(app):
        transport = ASGITransport(app=app, client=("127.0.0.1", 12345))
        async with AsyncClient(
            transport=transport,
            base_url="http://localhost",
            follow_redirects=True,
        ) as client:
            yield client


async def login(client: AsyncClient, username: str = "admin", password: str = "Ace@0101"):
    return await client.post(
        "/admin/login",
        data={"username": username, "password": password},
    )


async def test_admin_is_disabled_by_default(tmp_path: Path) -> None:
    settings = Settings(
        database_url=f"sqlite+aiosqlite:///{(tmp_path / 'disabled.db').as_posix()}",
        auto_create_schema=True,
        safety_image_storage_path=str(tmp_path / "safety-images"),
    )
    app = create_app(settings)
    async with LifespanManager(app):
        async with AsyncClient(
            transport=ASGITransport(app=app, client=("127.0.0.1", 12345)),
            base_url="http://localhost",
        ) as client:
            response = await client.get("/admin")
    assert response.status_code == 404


@pytest.mark.parametrize("username", ["admin", "test"])
async def test_built_in_admins_can_log_in_with_equal_access(
    admin_client: AsyncClient, username: str
) -> None:
    response = await login(admin_client, username)
    assert response.status_code == 200
    assert response.url.path == "/admin/users"
    assert "家属绑定管理" in response.text
    cookie = admin_client.cookies.get("silverage_admin_session")
    assert cookie is not None


async def test_login_rejects_wrong_credentials(admin_client: AsyncClient) -> None:
    landing = await admin_client.get("/admin")
    wrong_password = await login(admin_client, password="wrong-password")
    unknown_user = await login(admin_client, username="root")
    assert landing.status_code == 200
    assert "登录密码" in landing.text
    assert wrong_password.status_code == unknown_user.status_code == 401
    assert "账号或密码不正确" in wrong_password.text


async def test_remote_clients_cannot_discover_admin(tmp_path: Path) -> None:
    settings = Settings(
        database_url=f"sqlite+aiosqlite:///{(tmp_path / 'remote.db').as_posix()}",
        auto_create_schema=True,
        admin_enabled=True,
        safety_image_storage_path=str(tmp_path / "safety-images"),
    )
    app = create_app(settings)
    async with LifespanManager(app):
        async with AsyncClient(
            transport=ASGITransport(app=app, client=("192.0.2.10", 12345)),
            base_url="http://server",
        ) as client:
            response = await client.get("/admin")
    assert response.status_code == 404


async def test_admin_only_returns_family_binding_information(admin_client: AsyncClient) -> None:
    family = await register_family(admin_client)
    elder = await create_elder(admin_client, family["access_token"])
    code = await create_code(admin_client, family["access_token"], elder["elder_id"])
    bound = await admin_client.post(
        "/api/v1/devices/bind",
        json=bind_payload(code["binding_code"]),
    )
    assert bound.status_code == 201
    assert (await login(admin_client)).status_code == 200

    empty = await admin_client.get("/admin/users")
    result = await admin_client.post("/admin/users", data={"q": "13800138000"})

    assert "全部绑定关系" in empty.text
    assert "小林" in empty.text and "王阿姨" in empty.text
    assert "小林" in result.text and "138****8000" in result.text
    assert "王阿姨" in result.text and "139****9000" in result.text
    assert "CHILD" in result.text
    assert "13800138000" not in result.text
    forbidden_values = (
        family["access_token"],
        family["refresh_token"],
        bound.json()["device_credential"],
        elder["elder_id"],
        "model-config",
        "safety-events",
        "model-usage",
    )
    assert all(value not in result.text for value in forbidden_values)
    assert result.headers["Cache-Control"] == "no-store"
    assert result.headers["X-Frame-Options"] == "DENY"
    assert "13800138000" not in result.request.url.query.decode()


async def test_admin_updates_and_deletes_user_data_after_mobile_confirmation(
    admin_client: AsyncClient,
) -> None:
    family = await register_family(admin_client)
    elder = await create_elder(admin_client, family["access_token"])
    code = await create_code(admin_client, family["access_token"], elder["elder_id"])
    bound = await admin_client.post(
        "/api/v1/devices/bind",
        json=bind_payload(code["binding_code"]),
    )
    credential = bound.json()["device_credential"]
    await login(admin_client)
    page = await admin_client.get("/admin/users")
    handle = page.text.split('name="binding_handle" value="', 1)[1].split('"', 1)[0]

    updated = await admin_client.post(
        "/admin/bindings/update",
        data={
            "binding_handle": handle,
            "family_display_name": "新家属称呼",
            "elder_display_name": "新老人称呼",
            "relationship": "CAREGIVER",
        },
    )
    assert "新家属称呼" in updated.text
    assert "新老人称呼" in updated.text
    assert '<option value="CAREGIVER" selected>' in updated.text

    refreshed_handle = updated.text.split('name="binding_handle" value="', 1)[1].split('"', 1)[0]
    rejected = await admin_client.post(
        "/admin/bindings/delete",
        data={
            "binding_handle": refreshed_handle,
            "family_mobile_confirmation": "13900139000",
        },
    )
    assert rejected.status_code == 400
    assert "输入的家属完整手机号不正确" in rejected.text

    refreshed_handle = rejected.text.split('name="binding_handle" value="', 1)[1].split('"', 1)[0]
    deleted = await admin_client.post(
        "/admin/bindings/delete",
        data={
            "binding_handle": refreshed_handle,
            "family_mobile_confirmation": "13800138000",
        },
    )
    assert deleted.status_code == 200
    assert "新家属称呼" not in deleted.text
    device_access = await admin_client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {credential}"},
    )
    assert device_access.status_code == 401


async def test_search_wildcards_do_not_enumerate_families(admin_client: AsyncClient) -> None:
    await register_family(admin_client)
    await login(admin_client, username="test")
    result = await admin_client.post("/admin/users", data={"q": "%%"})
    assert "没有找到匹配的绑定关系" in result.text
    assert "小林" not in result.text


async def test_logout_revokes_session(admin_client: AsyncClient) -> None:
    await login(admin_client)
    logged_out = await admin_client.post("/admin/logout")
    protected = await admin_client.get("/admin/users")
    assert logged_out.url.path == "/admin"
    assert "登录密码" in protected.text


async def test_admin_hard_delete_releases_accounts_for_full_re_registration(
    admin_client: AsyncClient,
) -> None:
    family = await register_family(admin_client)
    elder = await create_elder(admin_client, family["access_token"])
    code = await create_code(admin_client, family["access_token"], elder["elder_id"])
    bound = await admin_client.post(
        "/api/v1/devices/bind",
        json=bind_payload(code["binding_code"]),
    )
    credential = bound.json()["device_credential"]
    await login(admin_client)
    page = await admin_client.get("/admin/users")
    handle = page.text.split('name="binding_handle" value="', 1)[1].split('"', 1)[0]
    deleted = await admin_client.post(
        "/admin/bindings/delete",
        data={
            "binding_handle": handle,
            "family_mobile_confirmation": "13800138000",
        },
    )
    assert deleted.status_code == 200
    assert "重新联调账号" not in deleted.text
    assert "RESET" not in deleted.text

    new_family = await register_family(
        admin_client,
        request_id="51111111-1111-4111-8111-111111111111",
    )
    new_elder = await create_elder(
        admin_client,
        new_family["access_token"],
        request_id="52222222-2222-4222-8222-222222222222",
    )
    assert new_family["family_account_id"] != family["family_account_id"]
    assert new_elder["elder_id"] != elder["elder_id"]
    old_device = await admin_client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {credential}"},
    )
    assert old_device.status_code == 401

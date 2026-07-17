import json
from datetime import timedelta
from typing import Any

from fastapi import FastAPI
from httpx import AsyncClient
from sqlalchemy import select

from app.core.config import Settings
from app.core.security import utc_now
from app.models import AuditLog, Binding, DeviceCredential, ElderProfile, FamilyAccount
from tests.conftest import bind_payload, create_code, create_elder, register_family

ApiFixture = tuple[AsyncClient, FastAPI, Settings]


async def prepared_contact_binding(
    client: AsyncClient,
    *,
    family_mobile: str = "13800138000",
    elder_mobile: str = "13900139000",
    device_id: str = "device-contact-001",
    prefix: str = "1",
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    family = await register_family(
        client,
        mobile=family_mobile,
        request_id=f"{prefix}1111111-1111-4111-8111-111111111111",
    )
    elder = await create_elder(
        client,
        family["access_token"],
        mobile=elder_mobile,
        request_id=f"{prefix}2222222-2222-4222-8222-222222222222",
    )
    code = await create_code(
        client,
        family["access_token"],
        elder["elder_id"],
        request_id=f"{prefix}3333333-3333-4333-8333-333333333333",
    )
    bind = await client.post(
        "/api/v1/devices/bind",
        json=bind_payload(
            code["binding_code"],
            mobile=family_mobile,
            device_id=device_id,
            request_id=f"{prefix}4444444-4444-4444-8444-444444444444",
        ),
    )
    assert bind.status_code == 201, bind.text
    return family, elder, bind.json()


async def get_contacts(client: AsyncClient, credential: str) -> Any:
    return await client.get(
        "/api/v1/devices/me/family-contacts",
        headers={"Authorization": f"Bearer {credential}"},
    )


async def test_device_gets_stable_complete_contact_snapshot_without_leaking_to_audit(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_contact_binding(client)

    first = await get_contacts(client, bound["device_credential"])
    second = await get_contacts(client, bound["device_credential"])
    assert first.status_code == second.status_code == 200
    assert first.headers["Cache-Control"] == "no-store"
    assert first.json()["snapshot_version"] == second.json()["snapshot_version"]
    assert first.json()["snapshot_version"].startswith("sha256-")
    contact = first.json()["contacts"][0]
    assert contact == {
        "binding_id": bound["binding_id"],
        "family_account_id": bound["family_account_id"],
        "display_name": "小林",
        "mobile_number": "13800138000",
        "relationship": "CHILD",
        "permissions": ["EMERGENCY_CONTACT", "HELPER", "VIEWER"],
        "emergency_contact": True,
        "bound_at": bound["bound_at"],
        "profile_updated_at": contact["profile_updated_at"],
    }
    assert first.json()["synced_at"]

    family_bindings = await client.get(
        "/api/v1/bindings",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert family_bindings.status_code == 200
    assert "13800138000" not in family_bindings.text
    assert family_bindings.json()["bindings"][0]["family_mobile_masked"] == "138****8000"
    assert family_bindings.json()["bindings"][0]["elder_id"] == elder["elder_id"]

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
            ],
            ensure_ascii=False,
        )
    assert "13800138000" not in serialized
    assert bound["device_credential"] not in serialized
    contact_audits = [audit for audit in audits if audit.action == "FAMILY_CONTACTS_READ"]
    assert len(contact_audits) == 2
    assert contact_audits[0].details["contact_count"] == "1"


async def test_snapshot_changes_for_profile_permissions_and_active_contact_set(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    _, elder, bound = await prepared_contact_binding(client)
    initial = await get_contacts(client, bound["device_credential"])
    initial_version = initial.json()["snapshot_version"]

    async with app.state.database.session_factory() as session, session.begin():
        second_family = FamilyAccount(
            display_name="周先生",
            mobile_normalized="13700137000",
            mobile_masked="137****7000",
            mobile_verified_at=None,
            updated_at=utc_now(),
        )
        session.add(second_family)
        await session.flush()
        second_binding = Binding(
            elder_id=elder["elder_id"],
            family_account_id=second_family.id,
            relationship="CAREGIVER",
            permissions=["VIEWER"],
            audit_source="TEST_SECOND_FAMILY",
            created_at=utc_now() + timedelta(seconds=1),
        )
        session.add(second_binding)

    with_second = await get_contacts(client, bound["device_credential"])
    assert with_second.status_code == 200
    assert with_second.json()["snapshot_version"] != initial_version
    assert [contact["display_name"] for contact in with_second.json()["contacts"]] == [
        "小林",
        "周先生",
    ]

    async with app.state.database.session_factory() as session, session.begin():
        first_family = (
            await session.scalars(
                select(FamilyAccount).where(FamilyAccount.mobile_normalized == "13800138000")
            )
        ).one()
        first_family.display_name = "林女士"
        first_family.mobile_normalized = "13600136000"
        first_family.mobile_masked = "136****6000"
        first_family.updated_at = utc_now() + timedelta(seconds=2)
        second_binding = (
            await session.scalars(
                select(Binding).where(Binding.family_account_id != first_family.id)
            )
        ).one()
        second_binding.permissions = ["VIEWER", "EMERGENCY_CONTACT"]

    updated = await get_contacts(client, bound["device_credential"])
    assert updated.json()["snapshot_version"] != with_second.json()["snapshot_version"]
    assert [contact["display_name"] for contact in updated.json()["contacts"]] == [
        "林女士",
        "周先生",
    ]
    first_contact = updated.json()["contacts"][0]
    assert first_contact["mobile_number"] == "13600136000"
    assert "13800138000" not in updated.text

    async with app.state.database.session_factory() as session, session.begin():
        families = (await session.scalars(select(FamilyAccount))).all()
        for family in families:
            family.is_active = False

    empty = await get_contacts(client, bound["device_credential"])
    assert empty.status_code == 200
    assert empty.json()["contacts"] == []
    assert empty.json()["snapshot_version"] == "empty-v1"
    assert empty.json()["snapshot_version"] != updated.json()["snapshot_version"]


async def test_contact_snapshot_authentication_isolation_and_forbidden_state(
    api: ApiFixture,
) -> None:
    client, app, _ = api
    first_family, first_elder, first_bound = await prepared_contact_binding(client)
    _, second_elder, second_bound = await prepared_contact_binding(
        client,
        family_mobile="13700137000",
        elder_mobile="13600136000",
        device_id="device-contact-002",
        prefix="2",
    )

    first = await get_contacts(client, first_bound["device_credential"])
    second = await get_contacts(client, second_bound["device_credential"])
    assert first.json()["contacts"][0]["family_account_id"] == first_bound["family_account_id"]
    assert second.json()["contacts"][0]["family_account_id"] == second_bound["family_account_id"]
    assert first_elder["elder_id"] != second_elder["elder_id"]
    assert second_bound["family_account_id"] not in first.text

    family_token = await get_contacts(client, first_family["access_token"])
    invalid = await get_contacts(client, "invalid-device-credential")
    for response in (family_token, invalid):
        assert response.status_code == 401
        assert response.json()["error"]["code"] == "AUTHENTICATION_REQUIRED"
        assert response.headers["Cache-Control"] == "no-store"

    async with app.state.database.session_factory() as session, session.begin():
        second_device = (
            await session.scalars(
                select(DeviceCredential).where(
                    DeviceCredential.external_device_id == "device-contact-002"
                )
            )
        ).one()
        second_device.expires_at = utc_now() - timedelta(seconds=1)
    expired = await get_contacts(client, second_bound["device_credential"])
    assert expired.status_code == 401
    assert expired.json()["error"]["code"] == "AUTHENTICATION_REQUIRED"

    async with app.state.database.session_factory() as session, session.begin():
        elder = await session.get(ElderProfile, first_elder["elder_id"])
        assert elder is not None
        elder.is_active = False
    forbidden = await get_contacts(client, first_bound["device_credential"])
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "FAMILY_CONTACTS_FORBIDDEN"
    assert forbidden.headers["Cache-Control"] == "no-store"

    async with app.state.database.session_factory() as session, session.begin():
        elder = await session.get(ElderProfile, first_elder["elder_id"])
        assert elder is not None
        elder.is_active = True
    revoked = await client.delete(
        f"/api/v1/bindings/{first_bound['binding_id']}",
        headers={"Authorization": f"Bearer {first_family['access_token']}"},
    )
    assert revoked.status_code == 204
    revoked_credential = await get_contacts(client, first_bound["device_credential"])
    assert revoked_credential.status_code == 401
    assert revoked_credential.json()["error"]["code"] == "AUTHENTICATION_REQUIRED"

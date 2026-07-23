import io
from datetime import timedelta
from typing import Any
from unittest.mock import AsyncMock
from uuid import uuid4

from fastapi import FastAPI
from httpx import AsyncClient, Response
from PIL import Image
from sqlalchemy import func, select

from app.core.config import Settings
from app.core.security import utc_now
from app.models import AuditLog, SafetyEventImage
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


async def create_event(
    client: AsyncClient,
    credential: str,
    *,
    event_type: str = "FALL_SUSPECTED",
    summary: str = "检测到老人疑似跌倒，请尽快联系核实。",
) -> dict[str, Any]:
    request_id = str(uuid4())
    response = await client.post(
        "/api/v1/devices/me/safety-events",
        headers={
            "Authorization": f"Bearer {credential}",
            "Idempotency-Key": request_id,
        },
        json={
            "client_event_id": request_id,
            "occurred_at": utc_now().isoformat().replace("+00:00", "Z"),
            "event_type": event_type,
            "event_summary": summary,
            "severity": "EMERGENCY",
        },
    )
    assert response.status_code == 201, response.text
    return response.json()


def image_bytes(
    image_format: str = "JPEG",
    *,
    color: tuple[int, int, int] = (180, 30, 30),
    include_exif: bool = True,
) -> bytes:
    image = Image.new("RGB", (900, 600), color)
    output = io.BytesIO()
    if image_format == "JPEG" and include_exif:
        exif = Image.Exif()
        exif[0x010E] = "private diagnostic metadata"
        exif[0x0112] = 6
        image.save(output, format=image_format, quality=90, exif=exif)
    else:
        image.save(output, format=image_format)
    return output.getvalue()


async def upload_image(
    client: AsyncClient,
    credential: str,
    event_id: str,
    data: bytes,
    *,
    content_type: str = "image/jpeg",
    idempotency_key: str | None = None,
) -> Response:
    return await client.put(
        f"/api/v1/devices/me/safety-events/{event_id}/image",
        headers={
            "Authorization": f"Bearer {credential}",
            "Idempotency-Key": idempotency_key or event_id,
            "Content-Type": content_type,
        },
        content=data,
    )


async def get_image(
    client: AsyncClient,
    access_token: str,
    elder_id: str,
    event_id: str,
    variant: str,
) -> Response:
    return await client.get(
        f"/api/v1/elders/{elder_id}/safety-events/{event_id}/image",
        headers={"Authorization": f"Bearer {access_token}"},
        params={"variant": variant},
    )


async def test_private_image_upload_read_idempotency_and_metadata(api: ApiFixture) -> None:
    client, app, _ = api
    family, elder, bound = await prepared_binding(client)
    event = await create_event(client, bound["device_credential"])
    assert event["image_available"] is False
    app.state.connection_manager.notify_safety_event_image_available = AsyncMock(return_value=True)
    jpeg = image_bytes()

    uploaded = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        jpeg,
    )
    retry = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        jpeg,
    )
    assert uploaded.status_code == retry.status_code == 200
    assert uploaded.json() == retry.json()
    assert uploaded.json()["image_available"] is True
    assert uploaded.json()["image_content_type"] == "image/jpeg"
    assert uploaded.json()["image_byte_size"] == len(jpeg)
    notifier = app.state.connection_manager.notify_safety_event_image_available
    notifier.assert_awaited_once_with(
        {family["family_account_id"]}, elder["elder_id"], event["event_id"]
    )

    conflict = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        image_bytes(color=(30, 30, 180)),
    )
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    thumbnail = await get_image(
        client,
        family["access_token"],
        elder["elder_id"],
        event["event_id"],
        "thumbnail",
    )
    original = await get_image(
        client,
        family["access_token"],
        elder["elder_id"],
        event["event_id"],
        "original",
    )
    for response in (thumbnail, original):
        assert response.status_code == 200
        assert response.headers["Content-Type"] == "image/jpeg"
        assert response.headers["Cache-Control"] == "private, no-store"
        assert response.headers["X-Content-Type-Options"] == "nosniff"
        with Image.open(io.BytesIO(response.content)) as decoded:
            assert not decoded.getexif()
    with Image.open(io.BytesIO(thumbnail.content)) as decoded_thumbnail:
        assert max(decoded_thumbnail.size) <= 512
    with Image.open(io.BytesIO(original.content)) as decoded_original:
        assert decoded_original.size == (600, 900)

    snapshot = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/safety-events?scope=today",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert snapshot.status_code == 200
    assert snapshot.json()["events"][0]["image_available"] is True

    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(SafetyEventImage.id))) == 1
        image = (await session.scalars(select(SafetyEventImage))).one()
        assert family["family_account_id"] not in image.original_storage_name
        assert elder["elder_id"] not in image.original_storage_name
        audits = list(
            (
                await session.scalars(
                    select(AuditLog).where(
                        AuditLog.action.in_(
                            ["SAFETY_EVENT_IMAGE_STORED", "SAFETY_EVENT_IMAGE_VIEWED"]
                        )
                    )
                )
            ).all()
        )
        assert len(audits) == 3
        assert all("private diagnostic metadata" not in str(audit.details) for audit in audits)


async def test_image_validation_permissions_and_size_limit(api: ApiFixture) -> None:
    client, _, settings = api
    family, elder, bound = await prepared_binding(client)
    event = await create_event(client, bound["device_credential"])
    jpeg = image_bytes(include_exif=False)

    mismatch = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        jpeg,
        idempotency_key=str(uuid4()),
    )
    assert mismatch.status_code == 400
    assert mismatch.json()["error"]["code"] == "REQUEST_VALIDATION_ERROR"

    wrong_type = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        jpeg,
        content_type="image/png",
    )
    assert wrong_type.status_code == 400
    assert wrong_type.json()["error"]["code"] == "INVALID_SAFETY_EVENT_IMAGE"

    invalid_signature = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        b"not-an-image",
    )
    assert invalid_signature.status_code == 400
    assert invalid_signature.json()["error"]["code"] == "INVALID_SAFETY_EVENT_IMAGE"

    settings.safety_image_max_bytes = 8
    too_large = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        b"123456789",
    )
    assert too_large.status_code == 413
    assert too_large.json()["error"]["code"] == "SAFETY_EVENT_IMAGE_TOO_LARGE"
    settings.safety_image_max_bytes = 8 * 1024 * 1024

    family_event = await create_event(
        client,
        bound["device_credential"],
        event_type="FAMILY_REQUEST",
        summary="老人希望家人今晚回家吃饭",
    )
    disallowed = await upload_image(
        client,
        bound["device_credential"],
        family_event["event_id"],
        jpeg,
    )
    assert disallowed.status_code == 400
    assert disallowed.json()["error"]["code"] == "INVALID_SAFETY_EVENT_IMAGE"

    no_image = await get_image(
        client,
        family["access_token"],
        elder["elder_id"],
        event["event_id"],
        "thumbnail",
    )
    assert no_image.status_code == 404
    assert no_image.json()["error"]["code"] == "SAFETY_EVENT_IMAGE_NOT_FOUND"

    unrelated = await register_family(
        client,
        mobile="13700137000",
        request_id=str(uuid4()),
    )
    forbidden = await get_image(
        client,
        unrelated["access_token"],
        elder["elder_id"],
        event["event_id"],
        "thumbnail",
    )
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "SAFETY_EVENT_FORBIDDEN"


async def test_image_download_rate_limit_and_expiration_cleanup(api: ApiFixture) -> None:
    client, app, settings = api
    family, elder, bound = await prepared_binding(client)
    event = await create_event(client, bound["device_credential"])
    uploaded = await upload_image(
        client,
        bound["device_credential"],
        event["event_id"],
        image_bytes("PNG", include_exif=False),
        content_type="image/png",
    )
    assert uploaded.status_code == 200

    settings.safety_image_download_per_minute_limit = 1
    first = await get_image(
        client,
        family["access_token"],
        elder["elder_id"],
        event["event_id"],
        "thumbnail",
    )
    limited = await get_image(
        client,
        family["access_token"],
        elder["elder_id"],
        event["event_id"],
        "original",
    )
    assert first.status_code == 200
    assert limited.status_code == 429
    assert limited.json()["error"]["code"] == "SAFETY_EVENT_IMAGE_RATE_LIMITED"

    async with app.state.database.session_factory() as session:
        image = (await session.scalars(select(SafetyEventImage))).one()
        original_path = app.state.safety_image_storage.originals / image.original_storage_name
        thumbnail_path = app.state.safety_image_storage.thumbnails / image.thumbnail_storage_name
        image.expires_at = utc_now() - timedelta(seconds=1)
        await session.commit()
    settings.safety_image_download_per_minute_limit = 60

    expired = await get_image(
        client,
        family["access_token"],
        elder["elder_id"],
        event["event_id"],
        "thumbnail",
    )
    assert expired.status_code == 404
    assert expired.json()["error"]["code"] == "SAFETY_EVENT_IMAGE_NOT_FOUND"
    assert not original_path.exists()
    assert not thumbnail_path.exists()
    async with app.state.database.session_factory() as session:
        assert await session.scalar(select(func.count(SafetyEventImage.id))) == 0

    snapshot = await client.get(
        f"/api/v1/elders/{elder['elder_id']}/safety-events?scope=today",
        headers={"Authorization": f"Bearer {family['access_token']}"},
    )
    assert snapshot.json()["events"][0]["image_available"] is False

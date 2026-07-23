import uuid

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.core.database import Database
from app.core.errors import ApiError
from app.core.security import decode_jwt, keyed_digest
from app.repositories.commands import CommandRepository
from app.repositories.family_binding import FamilyBindingRepository
from app.websocket.manager import ConnectionManager

router = APIRouter(tags=["realtime"])


@router.websocket("/ws")
async def realtime_websocket(websocket: WebSocket) -> None:
    authorization = websocket.headers.get("Authorization", "")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        await websocket.close(code=4401)
        return

    database: Database = websocket.app.state.database
    settings = websocket.app.state.settings
    manager: ConnectionManager = websocket.app.state.connection_manager
    try:
        claims = decode_jwt(settings, token, "family_access")
    except ApiError:
        claims = None

    if claims is not None:
        async with database.session_factory() as session:
            family = await FamilyBindingRepository(session).get_family(claims["sub"])
            if family is None or not family.is_active:
                await websocket.close(code=4401)
                return
            family_id = family.id
        connection_id = str(uuid.uuid4())
        await manager.connect_family(family_id, connection_id, websocket)
        try:
            while True:
                message = await websocket.receive_json()
                if message.get("message_type") == "PING":
                    await websocket.send_json({"protocol_version": 1, "message_type": "PONG"})
        except (WebSocketDisconnect, ValueError):
            pass
        finally:
            await manager.disconnect_family(family_id, connection_id, websocket)
        return

    digest = keyed_digest(settings.security_secret, "device-credential", token)
    async with database.session_factory() as session:
        device = await FamilyBindingRepository(session).get_device_by_digest(digest)
        if (
            device is None
            or await CommandRepository(session).get_active_binding_for_device(device) is None
        ):
            await websocket.close(code=4401)
            return
        elder_id = device.elder_id
        device_id = device.id

    await manager.connect(elder_id, device_id, websocket)
    try:
        while True:
            message = await websocket.receive_json()
            if message.get("message_type") == "PING":
                await websocket.send_json({"protocol_version": 1, "message_type": "PONG"})
    except (WebSocketDisconnect, ValueError):
        pass
    finally:
        await manager.disconnect(elder_id, device_id, websocket)

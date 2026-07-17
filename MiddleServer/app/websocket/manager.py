import asyncio
import uuid
from collections import defaultdict

from fastapi import WebSocket

from app.core.security import utc_now
from app.models import Command


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: dict[str, dict[str, WebSocket]] = defaultdict(dict)
        self._lock = asyncio.Lock()

    async def connect(self, elder_id: str, device_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        async with self._lock:
            previous = self._connections[elder_id].get(device_id)
            self._connections[elder_id][device_id] = websocket
        if previous is not None and previous is not websocket:
            await previous.close(code=1000)

    async def disconnect(self, elder_id: str, device_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            current = self._connections.get(elder_id, {}).get(device_id)
            if current is websocket:
                self._connections[elder_id].pop(device_id, None)
                if not self._connections[elder_id]:
                    self._connections.pop(elder_id, None)

    async def notify_command(self, command: Command) -> None:
        async with self._lock:
            targets = list(self._connections.get(command.elder_id, {}).items())
        if not targets:
            return
        message = {
            "protocol_version": 1,
            "message_type": "COMMAND_AVAILABLE",
            "message_id": str(uuid.uuid4()),
            "server_sequence": command.server_sequence,
            "sent_at": utc_now().isoformat().replace("+00:00", "Z"),
            "payload": {
                "command_id": command.id,
                "command_type": command.command_type,
            },
        }
        disconnected: list[tuple[str, WebSocket]] = []
        for device_id, websocket in targets:
            try:
                await websocket.send_json(message)
            except Exception:
                disconnected.append((device_id, websocket))
        for device_id, websocket in disconnected:
            await self.disconnect(command.elder_id, device_id, websocket)

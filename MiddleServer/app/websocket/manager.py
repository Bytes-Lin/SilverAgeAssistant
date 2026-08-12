import asyncio
import uuid
from collections import defaultdict
from datetime import datetime

from fastapi import WebSocket

from app.core.security import utc_now
from app.models import Command


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: dict[str, dict[str, WebSocket]] = defaultdict(dict)
        self._family_connections: dict[str, dict[str, WebSocket]] = defaultdict(dict)
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

    async def connect_family(
        self, family_id: str, connection_id: str, websocket: WebSocket
    ) -> None:
        await websocket.accept()
        async with self._lock:
            self._family_connections[family_id][connection_id] = websocket

    async def disconnect_family(
        self, family_id: str, connection_id: str, websocket: WebSocket
    ) -> None:
        async with self._lock:
            current = self._family_connections.get(family_id, {}).get(connection_id)
            if current is websocket:
                self._family_connections[family_id].pop(connection_id, None)
                if not self._family_connections[family_id]:
                    self._family_connections.pop(family_id, None)

    async def disconnect_user_data(
        self,
        family_id: str,
        elder_ids: frozenset[str],
        device_ids: frozenset[str],
    ) -> None:
        async with self._lock:
            family_targets = list(self._family_connections.pop(family_id, {}).values())
            device_targets: list[WebSocket] = []
            for elder_id in elder_ids:
                connections = self._connections.get(elder_id, {})
                for device_id in device_ids:
                    websocket = connections.pop(device_id, None)
                    if websocket is not None:
                        device_targets.append(websocket)
                if not connections:
                    self._connections.pop(elder_id, None)
        for websocket in [*family_targets, *device_targets]:
            try:
                await websocket.close(code=1008)
            except Exception:
                pass

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

    async def notify_model_usage_report_requested(
        self,
        elder_id: str,
        active_device_ids: set[str],
        client_request_id: str,
        requested_at: datetime,
    ) -> bool:
        async with self._lock:
            targets = [
                (device_id, websocket)
                for device_id, websocket in self._connections.get(elder_id, {}).items()
                if device_id in active_device_ids
            ]
        if not targets:
            return False
        message = {
            "protocol_version": 1,
            "message_type": "MODEL_USAGE_REPORT_REQUESTED",
            "message_id": str(uuid.uuid4()),
            "sent_at": requested_at.isoformat().replace("+00:00", "Z"),
            "payload": {
                "client_request_id": client_request_id,
            },
        }
        delivered = False
        disconnected: list[tuple[str, WebSocket]] = []
        for device_id, websocket in targets:
            try:
                await websocket.send_json(message)
                delivered = True
            except Exception:
                disconnected.append((device_id, websocket))
        for device_id, websocket in disconnected:
            await self.disconnect(elder_id, device_id, websocket)
        return delivered

    async def notify_safety_monitoring_config_available(
        self,
        elder_id: str,
        active_device_ids: set[str],
        revision: int,
    ) -> bool:
        async with self._lock:
            targets = [
                (device_id, websocket)
                for device_id, websocket in self._connections.get(elder_id, {}).items()
                if device_id in active_device_ids
            ]
        message = {
            "protocol_version": 1,
            "message_type": "SAFETY_MONITORING_CONFIG_AVAILABLE",
            "message_id": str(uuid.uuid4()),
            "sent_at": utc_now().isoformat().replace("+00:00", "Z"),
            "revision": revision,
        }
        delivered = False
        disconnected: list[tuple[str, WebSocket]] = []
        for device_id, websocket in targets:
            try:
                await websocket.send_json(message)
                delivered = True
            except Exception:
                disconnected.append((device_id, websocket))
        for device_id, websocket in disconnected:
            await self.disconnect(elder_id, device_id, websocket)
        return delivered

    async def notify_model_config_available(
        self,
        elder_id: str,
        active_device_ids: set[str],
        revision: int,
    ) -> bool:
        async with self._lock:
            targets = [
                (device_id, websocket)
                for device_id, websocket in self._connections.get(elder_id, {}).items()
                if device_id in active_device_ids
            ]
        message = {
            "protocol_version": 1,
            "message_type": "MODEL_CONFIG_AVAILABLE",
            "message_id": str(uuid.uuid4()),
            "sent_at": utc_now().isoformat().replace("+00:00", "Z"),
            "payload": {"revision": revision},
        }
        delivered = False
        disconnected: list[tuple[str, WebSocket]] = []
        for device_id, websocket in targets:
            try:
                await websocket.send_json(message)
                delivered = True
            except Exception:
                disconnected.append((device_id, websocket))
        for device_id, websocket in disconnected:
            await self.disconnect(elder_id, device_id, websocket)
        return delivered

    async def notify_safety_event_available(
        self,
        family_ids: set[str],
        elder_id: str,
        event_id: str,
        server_sequence: int,
        severity: str,
    ) -> bool:
        async with self._lock:
            targets = [
                (family_id, connection_id, websocket)
                for family_id in family_ids
                for connection_id, websocket in self._family_connections.get(family_id, {}).items()
            ]
        message = {
            "protocol_version": 1,
            "message_type": "SAFETY_EVENT_AVAILABLE",
            "message_id": str(uuid.uuid4()),
            "sent_at": utc_now().isoformat().replace("+00:00", "Z"),
            "elder_id": elder_id,
            "event_id": event_id,
            "server_sequence": server_sequence,
            "severity": severity,
        }
        delivered = False
        disconnected: list[tuple[str, str, WebSocket]] = []
        for family_id, connection_id, websocket in targets:
            try:
                await websocket.send_json(message)
                delivered = True
            except Exception:
                disconnected.append((family_id, connection_id, websocket))
        for family_id, connection_id, websocket in disconnected:
            await self.disconnect_family(family_id, connection_id, websocket)
        return delivered

    async def notify_reminder_status_changed(
        self,
        family_ids: set[str],
        elder_id: str,
        command_id: str,
    ) -> bool:
        async with self._lock:
            targets = [
                (family_id, connection_id, websocket)
                for family_id in family_ids
                for connection_id, websocket in self._family_connections.get(family_id, {}).items()
            ]
        message = {
            "protocol_version": 1,
            "message_type": "REMINDER_STATUS_CHANGED",
            "message_id": str(uuid.uuid4()),
            "sent_at": utc_now().isoformat().replace("+00:00", "Z"),
            "payload": {"elder_id": elder_id, "command_id": command_id},
        }
        delivered = False
        disconnected: list[tuple[str, str, WebSocket]] = []
        for family_id, connection_id, websocket in targets:
            try:
                await websocket.send_json(message)
                delivered = True
            except Exception:
                disconnected.append((family_id, connection_id, websocket))
        for family_id, connection_id, websocket in disconnected:
            await self.disconnect_family(family_id, connection_id, websocket)
        return delivered

    async def notify_safety_event_image_available(
        self,
        family_ids: set[str],
        elder_id: str,
        event_id: str,
    ) -> bool:
        async with self._lock:
            targets = [
                (family_id, connection_id, websocket)
                for family_id in family_ids
                for connection_id, websocket in self._family_connections.get(family_id, {}).items()
            ]
        message = {
            "protocol_version": 1,
            "message_type": "SAFETY_EVENT_IMAGE_AVAILABLE",
            "message_id": str(uuid.uuid4()),
            "sent_at": utc_now().isoformat().replace("+00:00", "Z"),
            "elder_id": elder_id,
            "event_id": event_id,
        }
        delivered = False
        disconnected: list[tuple[str, str, WebSocket]] = []
        for family_id, connection_id, websocket in targets:
            try:
                await websocket.send_json(message)
                delivered = True
            except Exception:
                disconnected.append((family_id, connection_id, websocket))
        for family_id, connection_id, websocket in disconnected:
            await self.disconnect_family(family_id, connection_id, websocket)
        return delivered

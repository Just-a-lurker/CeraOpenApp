# SPDX-License-Identifier: GPL-3.0-or-later
"""Machine service: owns one CeraClient, serializes commands, publishes events.

No web code here, so another frontend (CLI, desktop, Home Assistant) can reuse it.
"""

from __future__ import annotations

import asyncio
import time
from collections import deque
from typing import Any, Callable, Deque, Dict, List, Optional

from cera import BrewMode, discover
from cera.client import CeraClient, CeraError, CommandRejected
from cera.messages import FLOW_HIGH, FLOW_NORMAL, State

Listener = Callable[[Dict[str, Any]], None]


class ControlLocked(PermissionError):
    """Raised when a write is attempted while control is not enabled."""


class MachineService:
    def __init__(self) -> None:
        self._client: Optional[CeraClient] = None
        self._lock = asyncio.Lock()
        self._listeners: List[Listener] = []
        self.frames: Deque[Dict[str, Any]] = deque(maxlen=400)
        self.address: Optional[str] = None
        self.connecting = False
        # Writes are refused until a user explicitly turns control on.
        self.control_enabled = False

    # -- events ----------------------------------------------------------
    def subscribe(self, fn: Listener) -> Callable[[], None]:
        self._listeners.append(fn)
        return lambda: self._listeners.remove(fn)

    def _emit(self, kind: str, **data: Any) -> None:
        event = {"type": kind, "t": time.time(), **data}
        for fn in list(self._listeners):
            try:
                fn(event)
            except Exception:
                pass

    def _frame(self, direction: str, raw: bytes) -> None:
        entry = {"dir": direction, "hex": raw.hex(" "), "t": time.time()}
        self.frames.append(entry)
        self._emit("frame", **entry)

    # -- state snapshot ----------------------------------------------------
    def snapshot(self) -> Dict[str, Any]:
        c = self._client
        connected = bool(c and c.is_connected)
        status = c.status if (c and connected) else None
        settings = c.settings if (c and connected) else None
        info = c.info if (c and connected) else None
        state = None
        if status is not None:
            try:
                state = State(status.state).name
            except ValueError:
                state = f"UNKNOWN_{status.state}"
        return {
            "connected": connected,
            "connecting": self.connecting,
            "address": self.address,
            "control_enabled": self.control_enabled,
            "info": None if info is None else {
                "model": info.model, "version_a": info.version_a, "version_b": info.version_b,
            },
            "status": None if status is None else {
                "water_temp_c": status.water_temp_c,
                "battery_raw": status.battery_raw,
                "state": state,
                "raw": status.raw.hex(" "),
            },
            "target_c": c.target_c if (c and connected) else None,
            "settings": None if settings is None else {
                "flow": settings.flow,
                "flow_name": {FLOW_NORMAL: "normal", FLOW_HIGH: "high"}.get(settings.flow, f"0x{settings.flow:02x}"),
                "presoak_s": settings.presoak_s,
                "standstill_s": settings.standstill_s,
                "extraction_raw": settings.extraction_raw,
                "extraction_s": settings.extraction_seconds,
                "raw": settings.raw.hex(" "),
            },
        }

    def _changed(self) -> None:
        self._emit("state", state=self.snapshot())

    # -- connection --------------------------------------------------------
    async def scan(self, timeout: float = 8.0):
        found = await discover(timeout)
        return [
            {"address": f.address, "name": f.name, "rssi": f.rssi, "likely": f.likely_machine}
            for f in found
        ]

    async def connect(self, address: str) -> None:
        async with self._lock:
            if self._client and self._client.is_connected:
                await self._client.disconnect()
            self.address = address
            self.connecting = True
            self._changed()
            client = CeraClient(
                address,
                on_status=lambda _s: self._changed(),
                on_frame=self._frame,
                on_disconnect=self._on_lost,
            )
            try:
                await client.connect()
                self._client = client
            finally:
                self.connecting = False
                self._changed()

    def _on_lost(self) -> None:
        self.control_enabled = False
        self._emit("log", level="warn", msg="machine disconnected")
        self._changed()

    async def disconnect(self) -> None:
        async with self._lock:
            if self._client:
                await self._client.disconnect()
            self.control_enabled = False
            self._changed()

    def set_control(self, enabled: bool) -> None:
        self.control_enabled = bool(enabled)
        self._changed()

    # -- commands ------------------------------------------------------------
    def _need(self, write: bool) -> CeraClient:
        if not (self._client and self._client.is_connected):
            raise CeraError("not connected")
        if write and not self.control_enabled:
            raise ControlLocked("control is off: enable it before sending commands")
        return self._client

    async def refresh(self) -> None:
        async with self._lock:
            await self._need(write=False).get_settings()
        self._changed()

    async def set_target_c(self, celsius: float) -> None:
        async with self._lock:
            await self._need(write=True).set_target_temp_c(celsius)
        self._changed()

    async def update_settings(self, **fields: Any) -> None:
        async with self._lock:
            await self._need(write=True).update_settings(**fields)
        self._changed()

    async def start(self, mode: str) -> None:
        brew_mode = BrewMode[mode]
        async with self._lock:
            await self._need(write=True).start(brew_mode)
        self._emit("log", level="info", msg=f"start {brew_mode.name}")
        self._changed()

    async def stop(self) -> None:
        # Stop is always allowed while connected, even with control off.
        async with self._lock:
            await self._need(write=False).stop()
        self._emit("log", level="info", msg="stop sent")
        self._changed()

    @property
    def running(self) -> bool:
        c = self._client
        return bool(c and c.is_connected and c.status and c.status.state != State.IDLE)

    async def shutdown(self) -> None:
        if self.running:
            try:
                await self.stop()
            except Exception:
                pass
        if self._client:
            try:
                await self._client.disconnect()
            except Exception:
                pass


__all__ = ["MachineService", "ControlLocked", "CeraError", "CommandRejected"]

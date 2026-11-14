# SPDX-License-Identifier: GPL-3.0-or-later
"""Machine service: owns one CeraClient, serializes commands, publishes events.

No web code here, so another frontend (CLI, desktop, Home Assistant) can reuse it.
"""

from __future__ import annotations

import asyncio
import datetime as _dt
import time
from collections import deque
from typing import Any, Callable, Deque, Dict, List, Optional

from cera import BrewMode, discover
from cera.client import CeraClient, CeraError, CommandRejected
from cera.messages import FLOW_DEFAULT, FLOW_MAX, FLOW_MIN, Beep, ReservationMode, State

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
        self.stats = None  # cera.messages.Stats, read-only cup history
        self.reservation = None  # cera.messages.Reservation stored on the machine
        self.stats_date: Optional[_dt.date] = None

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
            "flow_range": {"min": FLOW_MIN, "max": FLOW_MAX, "default": FLOW_DEFAULT},
            "stats": self._stats_view() if connected else None,
            "reservation": self._reservation_view() if connected else None,
            "settings": None if settings is None else {
                "flow": settings.flow,
                "flow_name": {FLOW_MIN: "min", FLOW_DEFAULT: "normal", FLOW_MAX: "max"}.get(settings.flow, str(settings.flow)),
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
                await self._read_stats(client)
                await self._read_reservation(client)
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
            client = self._need(write=False)
            await client.get_settings()
            await self._read_stats(client)
            await self._read_reservation(client)
        self._changed()

    async def _read_reservation(self, client) -> None:
        """Read the stored reservation; a failure never breaks the connection."""
        try:
            self.reservation = await client.get_reservation()
        except Exception as exc:  # noqa: BLE001
            self._emit("log", level="warn", msg=f"could not read the reservation: {exc}")

    def _reservation_view(self):
        r = self.reservation
        if r is None:
            return None
        try:
            mode = ReservationMode(r.mode).name
        except ValueError:
            mode = f"UNKNOWN_{r.mode}"
        try:
            beep = Beep(r.beep).name
        except ValueError:
            beep = f"UNKNOWN_{r.beep}"
        next_run = r.next_run(_dt.datetime.now()) if r.enabled else None
        return {
            "enabled": r.enabled, "mode": mode, "time": r.time,
            "alarm": r.alarm, "beep": beep,
            "next_run": next_run.isoformat(timespec="minutes") if next_run else None,
        }

    async def set_reservation(self, mode: str, time_hhmm: str, alarm: bool, beep: str) -> None:
        """Schedule a start the machine runs BY ITSELF (needs control on)."""
        hour, minute = (int(x) for x in time_hhmm.split(":"))
        if not (0 <= hour <= 23 and 0 <= minute <= 59):
            raise ValueError("time must be 00:00-23:59")
        mode_value, beep_value = ReservationMode[mode], Beep[beep]  # KeyError -> 400
        async with self._lock:
            client = self._need(write=True)
            self.reservation = await client.set_reservation(
                mode_value, hour, minute, alarm=bool(alarm), beep=beep_value)
        self._emit("log", level="info", msg=f"reservation set: {mode} at {hour:02d}:{minute:02d}")
        self._changed()

    async def cancel_reservation(self) -> None:
        # Like STOP, cancelling only removes risk, so it is allowed with control off.
        async with self._lock:
            self.reservation = await self._need(write=False).cancel_reservation()
        self._emit("log", level="info", msg="reservation cancelled")
        self._changed()

    async def _read_stats(self, client) -> None:
        """Read the cup history; a failure here never breaks the connection."""
        try:
            self.stats = await client.get_stats()
            self.stats_date = _dt.date.today()
        except Exception as exc:  # noqa: BLE001
            self._emit("log", level="warn", msg=f"could not read cup stats: {exc}")

    def stats_period(self, period: str, offset: int = 0) -> Dict[str, Any]:
        """Chart data for one week, month or year of the cup history.

        ``offset`` 0 is the current period, -1 the one before, and so on.
        Each bar has ``cups`` (None = no data) and ``complete`` (False when
        part of it is older than what the machine stores).
        """
        s, day = self.stats, self.stats_date
        if s is None or day is None:
            raise CeraError("no cup stats yet: connect to the machine first")
        offset = min(int(offset), 0)
        series = {"week": s.week_series, "month": s.month_series, "year": s.year_series}
        if period not in series:
            raise ValueError("period must be week, month or year")

        def build(off):
            return series[period](day, off)

        buckets = build(offset)
        first, last = buckets[0].start, buckets[-1].start
        if period == "week":
            title = f"{first:%d %b} – {last:%d %b %Y}"
            labels = [f"{b.start:%a}" for b in buckets]
            names = [f"{b.start:%A %d %B %Y}" for b in buckets]
        elif period == "month":
            title = f"{first:%B %Y}"
            labels = [str(b.start.day) for b in buckets]
            names = [f"{b.start:%A %d %B %Y}" for b in buckets]
        else:
            title = str(first.year)
            labels = [f"{b.start:%b}" for b in buckets]
            names = [f"{b.start:%B %Y}" for b in buckets]
        known = [b.cups for b in buckets if b.cups is not None]
        return {
            "period": period,
            "offset": offset,
            "title": title,
            "total": sum(known),
            "partial": any(not b.complete for b in buckets),
            "can_prev": any(b.cups is not None for b in build(offset - 1)),
            "can_next": offset < 0,
            "oldest": s.oldest(day).isoformat(),
            "bars": [
                {"label": lab, "name": name, "start": b.start.isoformat(),
                 "cups": b.cups, "complete": b.complete, "future": b.start > day}
                for b, lab, name in zip(buckets, labels, names)
            ],
        }

    def _stats_view(self):
        s, day = self.stats, self.stats_date
        if s is None or day is None:
            return None
        return {
            "today": s.today,
            "week": s.week(day),  # Sunday to Saturday, like the vendor app
            "month": s.month(day),
            "year": s.year(day),
            "days_stored": len(s.daily),
            "last_7": [{"date": (day - _dt.timedelta(days=i)).isoformat(), "cups": s.daily[i]}
                       for i in range(min(7, len(s.daily)))],
            "as_of": day.isoformat(),
        }

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

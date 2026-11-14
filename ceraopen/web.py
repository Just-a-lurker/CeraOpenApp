# SPDX-License-Identifier: GPL-3.0-or-later
"""Local web frontend (aiohttp). Binds to 127.0.0.1 only."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any, Awaitable, Callable, Set

from aiohttp import WSMsgType, web

from .service import CeraError, CommandRejected, ControlLocked, MachineService

STATIC = Path(__file__).parent / "static"


def _json_error(exc: Exception) -> web.Response:
    status = 403 if isinstance(exc, ControlLocked) else 400 if isinstance(exc, (ValueError, KeyError)) else 502
    return web.json_response({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, status=status)


def _action(fn: Callable[[MachineService, Any], Awaitable[Any]]):
    async def handler(request: web.Request) -> web.Response:
        svc: MachineService = request.app["svc"]
        body = await request.json() if request.can_read_body else {}
        try:
            result = await fn(svc, body)
        except (CeraError, CommandRejected, ControlLocked, ValueError, KeyError, OSError) as exc:
            return _json_error(exc)
        return web.json_response({"ok": True, "result": result, "state": svc.snapshot()})
    return handler


async def ws_handler(request: web.Request) -> web.WebSocketResponse:
    svc: MachineService = request.app["svc"]
    sockets: Set[web.WebSocketResponse] = request.app["sockets"]
    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    sockets.add(ws)
    await ws.send_json({"type": "state", "state": svc.snapshot()})
    await ws.send_json({"type": "frames", "frames": list(svc.frames)})
    try:
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
    finally:
        sockets.discard(ws)
        # Safety: if the last browser tab closes while the machine runs, stop it.
        if not sockets and svc.running:
            asyncio.ensure_future(svc.stop())
    return ws


def build_app(svc: MachineService) -> web.Application:
    app = web.Application()
    app["svc"] = svc
    app["sockets"] = set()

    def broadcast(event):
        for ws in list(app["sockets"]):
            if not ws.closed:
                asyncio.ensure_future(ws.send_str(json.dumps(event)))

    svc.subscribe(broadcast)

    async def on_shutdown(_app):
        await svc.shutdown()
        for ws in list(app["sockets"]):
            await ws.close()

    app.on_shutdown.append(on_shutdown)

    async def index(_req):
        return web.FileResponse(STATIC / "index.html")

    app.router.add_get("/", index)
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/api/state", lambda r: web.json_response(svc.snapshot()))
    app.router.add_post("/api/scan", _action(lambda s, b: s.scan(float(b.get("timeout", 8)))))
    app.router.add_post("/api/connect", _action(lambda s, b: s.connect(str(b["address"]))))
    app.router.add_post("/api/disconnect", _action(lambda s, b: s.disconnect()))

    async def set_control(s, b):
        s.set_control(bool(b.get("enabled")))

    app.router.add_post("/api/control", _action(set_control))
    app.router.add_post("/api/refresh", _action(lambda s, b: s.refresh()))

    async def stats(request: web.Request) -> web.Response:
        try:
            data = svc.stats_period(request.query.get("period", "week"),
                                    int(request.query.get("offset", "0")))
        except (CeraError, ValueError) as exc:
            return _json_error(exc)
        return web.json_response({"ok": True, "result": data})

    app.router.add_get("/api/stats", stats)
    app.router.add_post("/api/target", _action(lambda s, b: s.set_target_c(float(b["celsius"]))))

    async def settings(s, b):
        allowed = {"flow", "presoak_s", "standstill_s", "extraction_raw"}
        fields = {k: int(v) for k, v in b.items() if k in allowed}
        if not fields:
            raise ValueError("no settings given")
        for k, v in fields.items():
            if not 0 <= v <= 255:
                raise ValueError(f"{k} out of range")
        await s.update_settings(**fields)

    app.router.add_post("/api/settings", _action(settings))
    app.router.add_post("/api/start", _action(lambda s, b: s.start(str(b["mode"]))))
    app.router.add_post("/api/stop", _action(lambda s, b: s.stop()))
    return app

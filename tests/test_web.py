# SPDX-License-Identifier: GPL-3.0-or-later
"""Hardware-free tests: API behavior and the control lock, with a fake client."""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from aiohttp.test_utils import TestClient, TestServer  # noqa: E402

from cera.messages import Settings, Status  # noqa: E402
from ceraopen.service import MachineService  # noqa: E402
from ceraopen.web import build_app  # noqa: E402


class FakeClient:
    """Stands in for CeraClient; records calls instead of using Bluetooth."""

    def __init__(self):
        self.is_connected = True
        self.status = Status.parse(bytes.fromhex("013c031e0000"))
        self.settings = Settings.parse(bytes.fromhex("0800000a00000a781efa010f"))
        self.info = None
        self.target_c = 95.0
        self.calls = []

    async def set_target_temp_c(self, c):
        self.calls.append(("target", c))

    async def update_settings(self, **kw):
        self.calls.append(("settings", kw))

    async def get_settings(self):
        return self.settings

    async def start(self, mode):
        self.calls.append(("start", mode.name))

    async def stop(self):
        self.calls.append(("stop",))

    async def disconnect(self):
        self.is_connected = False


class WebTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.svc = MachineService()
        self.client = TestClient(TestServer(build_app(self.svc)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    async def post(self, path, body=None):
        r = await self.client.post(f"/api/{path}", json=body or {})
        return r.status, await r.json()

    async def test_index_and_state(self):
        r = await self.client.get("/")
        self.assertEqual(r.status, 200)
        r = await self.client.get("/api/state")
        self.assertFalse((await r.json())["connected"])

    async def test_commands_need_connection(self):
        status, body = await self.post("target", {"celsius": 90})
        self.assertEqual(status, 502)
        self.assertFalse(body["ok"])

    async def test_control_lock_blocks_writes_but_not_stop(self):
        fake = FakeClient()
        self.svc._client = fake
        for path, body in [("target", {"celsius": 90}), ("settings", {"flow": 15}),
                           ("start", {"mode": "HEAT"})]:
            status, _ = await self.post(path, body)
            self.assertEqual(status, 403, path)
        self.assertEqual(fake.calls, [])
        status, _ = await self.post("stop")
        self.assertEqual(status, 200)
        self.assertEqual(fake.calls, [("stop",)])

    async def test_writes_after_enabling_control(self):
        fake = FakeClient()
        self.svc._client = fake
        await self.post("control", {"enabled": True})
        self.assertEqual((await self.post("target", {"celsius": 90}))[0], 200)
        self.assertEqual((await self.post("settings", {"presoak_s": 1}))[0], 200)
        self.assertEqual((await self.post("start", {"mode": "EXTRACT"}))[0], 200)
        self.assertEqual(fake.calls, [("target", 90.0), ("settings", {"presoak_s": 1}), ("start", "EXTRACT")])

    async def test_bad_input_rejected(self):
        self.svc._client = FakeClient()
        await self.post("control", {"enabled": True})
        self.assertEqual((await self.post("start", {"mode": "NOPE"}))[0], 400)
        self.assertEqual((await self.post("settings", {"flow": 999}))[0], 400)
        self.assertEqual((await self.post("settings", {}))[0], 400)

    async def test_disconnect_turns_control_off(self):
        self.svc._client = FakeClient()
        await self.post("control", {"enabled": True})
        self.svc._on_lost()
        self.assertFalse(self.svc.control_enabled)


if __name__ == "__main__":
    unittest.main()

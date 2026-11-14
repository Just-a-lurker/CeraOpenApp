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
        from cera.messages import Reservation
        self.reservation = Reservation(2, 21, 10, False, 0, False)

    async def set_target_temp_c(self, c):
        self.calls.append(("target", c))

    async def update_settings(self, **kw):
        self.calls.append(("settings", kw))

    async def get_settings(self):
        return self.settings

    async def start(self, mode):
        self.calls.append(("start", mode.name))

    async def get_reservation(self):
        return self.reservation

    async def set_reservation(self, mode, hour, minute, *, alarm=None, beep=None):
        from cera.messages import Reservation
        self.calls.append(("set_reservation", mode.name, hour, minute, alarm, beep.name))
        self.reservation = Reservation(int(mode), hour, minute, bool(alarm), int(beep), True)
        return self.reservation

    async def cancel_reservation(self):
        from cera.messages import Reservation
        r = self.reservation
        self.calls.append(("cancel_reservation",))
        self.reservation = Reservation(r.mode, r.hour, r.minute, r.alarm, r.beep, False)
        return self.reservation

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

    async def test_flow_slider_state(self):
        fake = FakeClient()
        self.svc._client = fake
        r = await self.client.get("/api/state")
        state = await r.json()
        self.assertEqual(state["flow_range"], {"min": 1, "max": 15, "default": 8})
        self.assertEqual(state["settings"]["flow_name"], "normal")

    async def test_stats_in_state(self):
        import datetime as dt
        from cera.messages import Stats
        self.svc._client = FakeClient()
        self.svc.stats = Stats(b"9", (2, 0, 0, 0, 0, 0, 1, 1) + (0,) * 6 + (5,))
        self.svc.stats_date = dt.date(2026, 11, 14)  # a Saturday
        st = await (await self.client.get("/api/state")).json()
        self.assertEqual(st["stats"]["today"], 2)
        self.assertEqual(st["stats"]["week"], 3)    # Sun 8 Nov to Sat 14 Nov
        self.assertEqual(st["stats"]["month"], 4)   # 1-14 Nov
        self.assertEqual(st["stats"]["year"], 9)
        self.assertEqual(st["stats"]["days_stored"], 15)

    async def test_stats_periods_api(self):
        import datetime as dt
        from cera.messages import Stats
        self.svc._client = FakeClient()
        r = await self.client.get("/api/stats?period=week")
        self.assertEqual(r.status, 502)                    # no stats read yet
        self.svc.stats = Stats(b"9", (2, 0, 0, 0, 0, 0, 1, 1) + (0,) * 6 + (5,))
        self.svc.stats_date = dt.date(2026, 11, 14)        # a Saturday
        j = (await (await self.client.get("/api/stats?period=week")).json())["result"]
        self.assertEqual([b["cups"] for b in j["bars"]], [1, 0, 0, 0, 0, 0, 2])
        self.assertEqual(j["bars"][0]["label"], "Sun")
        self.assertEqual(j["total"], 3)
        self.assertTrue(j["can_prev"])
        self.assertFalse(j["can_next"])
        j = (await (await self.client.get("/api/stats?period=month&offset=-1")).json())["result"]
        self.assertEqual(j["title"], "October 2026")
        self.assertTrue(j["partial"])
        self.assertFalse(j["can_prev"])                    # September has no stored days
        self.assertTrue(j["can_next"])
        j = (await (await self.client.get("/api/stats?period=year")).json())["result"]
        self.assertEqual(len(j["bars"]), 12)
        self.assertTrue(j["bars"][11]["future"])
        r = await self.client.get("/api/stats?period=decade")
        self.assertEqual(r.status, 400)

    async def test_schedule_needs_control_but_cancel_does_not(self):
        fake = FakeClient()
        self.svc._client = fake
        body = {"mode": "EXTRACT", "time": "19:30", "alarm": True, "beep": "BI_BI"}
        self.assertEqual((await self.post("reservation", body))[0], 403)
        self.assertEqual(fake.calls, [])
        await self.post("control", {"enabled": True})
        status, j = await self.post("reservation", body)
        self.assertEqual(status, 200)
        self.assertEqual(fake.calls[-1], ("set_reservation", "EXTRACT", 19, 30, True, "BI_BI"))
        r = j["state"]["reservation"]
        self.assertTrue(r["enabled"])
        self.assertEqual((r["mode"], r["time"], r["alarm"], r["beep"]), ("EXTRACT", "19:30", True, "BI_BI"))
        self.assertIsNotNone(r["next_run"])
        await self.post("control", {"enabled": False})
        status, j = await self.post("reservation/cancel")
        self.assertEqual(status, 200)
        self.assertFalse(j["state"]["reservation"]["enabled"])

    async def test_schedule_rejects_bad_input(self):
        self.svc._client = FakeClient()
        await self.post("control", {"enabled": True})
        fake = self.svc._client
        for body in [{"mode": "EXTRACT", "time": "7:5"}, {"mode": "EXTRACT", "time": "24:00"},
                     {"mode": "BOIL", "time": "07:00"}, {"mode": "EXTRACT", "time": "07:00", "beep": "LOUD"}]:
            self.assertEqual((await self.post("reservation", body))[0], 400, body)
        self.assertEqual(fake.calls, [])  # rejected before anything reached the machine

    async def test_malformed_requests_are_400(self):
        self.svc._client = FakeClient()
        await self.post("control", {"enabled": True})
        r = await self.client.post("/api/target", data="{", headers={"Content-Type": "application/json"})
        self.assertEqual(r.status, 400)
        self.assertFalse((await r.json())["ok"])
        self.assertEqual((await self.post("target", [1]))[0], 400)
        self.assertEqual((await self.post("target", {"celsius": None}))[0], 400)
        self.assertEqual((await self.post("settings", {"flow": [1]}))[0], 400)
        self.assertEqual(self.svc._client.calls, [])
        from cera.messages import Stats
        self.svc.stats = Stats(b"9", (1, 2, 3))
        import datetime as dt
        self.svc.stats_date = dt.date(2026, 11, 14)
        for query in ("period=week&offset=-99999999999", "period=month&offset=-99999999999", "period=week&offset=x"):
            r = await self.client.get("/api/stats?" + query)
            self.assertEqual(r.status, 400, query)

    async def test_cups_and_schedule_are_reread_when_a_run_ends(self):
        import asyncio
        from cera.messages import Reservation, Stats
        fake = FakeClient()
        reads = []

        async def get_stats():
            reads.append("stats")
            return Stats(b"9", (len(reads), 0, 0))

        fake.get_stats = get_stats
        fake.reservation = Reservation(2, 21, 10, False, 0, True)
        self.svc._client = fake
        idle = Status.parse(bytes.fromhex("013c031e0000"))
        extracting = Status.parse(bytes.fromhex("013c031e0300"))
        self.svc._on_status(idle)
        self.svc._on_status(extracting)
        self.svc._on_status(extracting)
        self.assertIsNone(self.svc._reread)                # still running: nothing read
        fake.reservation = Reservation(2, 21, 10, False, 0, False)  # the machine switched it off
        self.svc.REREAD_DELAY = 0.0
        self.svc._on_status(idle)
        await asyncio.wait_for(self.svc._reread, 2)
        self.assertEqual(reads, ["stats"])
        self.assertEqual(self.svc.stats.today, 1)
        self.assertFalse(self.svc.reservation.enabled)
        self.svc._on_status(idle)                           # idle to idle: no second read
        await asyncio.sleep(0.05)
        self.assertEqual(reads, ["stats"])

    async def test_frames_are_numbered(self):
        self.svc._frame("TX", b"\x7f")
        self.svc._frame("RX", b"\x7e")
        self.assertEqual([f["seq"] for f in self.svc.frames], [1, 2])

    async def test_connecting_forgets_the_previous_machine(self):
        from unittest import mock
        from cera.client import CeraError
        from cera.messages import Stats
        self.svc.stats = Stats(b"9", (7,))

        class Failing:
            def __init__(self, *a, **kw):
                pass

            async def connect(self):
                raise CeraError("no reply")

        with mock.patch("ceraopen.service.CeraClient", Failing):
            status, _ = await self.post("connect", {"address": "AA"})
        self.assertEqual(status, 502)
        self.assertIsNone(self.svc.stats)
        self.assertFalse(self.svc.connecting)

    async def test_disconnect_turns_control_off(self):
        self.svc._client = FakeClient()
        await self.post("control", {"enabled": True})
        self.svc._on_lost()
        self.assertFalse(self.svc.control_enabled)


if __name__ == "__main__":
    unittest.main()

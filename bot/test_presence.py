import asyncio
import os
import tempfile
import time
import unittest
from unittest import mock

import ingest


class PresenceTests(unittest.TestCase):
    def setUp(self):
        ingest._presence.update(last=0.0, pending=None, dirty=False, running=False)

    def test_is_presence_line(self):
        self.assertTrue(ingest.is_presence_line("[01:37:00] [Server thread/INFO]: Nani_San123 joined the game\n"))
        self.assertTrue(ingest.is_presence_line("[01:38:00] [Server thread/INFO]: NwebNotFound left the game"))
        self.assertFalse(ingest.is_presence_line("[01:38:00] [Server thread/INFO]: <Nani> joined the game"))
        self.assertFalse(ingest.is_presence_line("random"))

    def test_scan_presence_all_backends(self):
        with tempfile.TemporaryDirectory() as d:
            paths = {}
            for k in ("lobby", "survival"):
                os.makedirs(f"{d}/{k}/logs")
                paths[k] = f"{d}/{k}/logs/latest.log"
                open(paths[k], "w").write("[x] [Server thread/INFO]: Old joined the game\n")
            off = {}
            self.assertFalse(ingest.scan_presence(off, d))  # erster Lauf: nur Offsets
            self.assertFalse(ingest.scan_presence(off, d))
            with open(paths["lobby"], "a") as f:
                f.write("[x] [Server thread/INFO]: Nani_San123 joined the game\n")
            self.assertTrue(ingest.scan_presence(off, d))
            self.assertFalse(ingest.scan_presence(off, d))

    def test_debounce_and_no_parallel(self):
        calls, running, peak = [], [0], [0]

        async def fake_push(bot, session):
            running[0] += 1
            peak[0] = max(peak[0], running[0])
            await asyncio.sleep(0.01)
            calls.append(time.monotonic())
            running[0] -= 1

        async def run():
            with mock.patch.object(ingest, "push_once", fake_push), mock.patch.object(ingest, "DEBOUNCE", 0.2):
                t1 = ingest.trigger_presence(None)
                t2 = ingest.trigger_presence(None)  # gleicher Task
                self.assertIs(t1, t2)
                await t1
                t3 = ingest.trigger_presence(None)  # innerhalb Debounce -> verzoegert
                ingest.trigger_presence(None)
                await t3
        asyncio.run(run())
        self.assertEqual(len(calls), 2)
        self.assertGreaterEqual(calls[1] - calls[0], 0.15)
        self.assertEqual(peak[0], 1)

    def test_dirty_flag_repush(self):
        calls = []

        async def fake_push(bot, session):
            calls.append(time.monotonic())
            if len(calls) == 1:
                ingest.trigger_presence(None)  # Join waehrend laufendem Push
            await asyncio.sleep(0.01)

        async def run():
            with mock.patch.object(ingest, "push_once", fake_push), mock.patch.object(ingest, "DEBOUNCE", 0.1):
                await ingest.trigger_presence(None)
        asyncio.run(run())
        self.assertEqual(len(calls), 2)
        self.assertGreaterEqual(calls[1] - calls[0], 0.08)
        self.assertFalse(ingest._presence["dirty"])


class RealtimeTests(unittest.TestCase):
    def setUp(self):
        ingest._rate.update(last_post=0.0, backoff_until=0.0, fails=0, last_hash=None)

    def test_death_lines_trigger(self):
        self.assertTrue(ingest.is_trigger_line("[01:00:00] [Server thread/INFO]: NwebNotFound fell from a high place"))
        self.assertTrue(ingest.is_trigger_line("[01:00:00] [Server thread/INFO]: NwebNotFound was slain by Zombie"))
        self.assertTrue(ingest.is_trigger_line("[01:00:00] [Server thread/INFO]: NwebNotFound tried to swim in lava"))
        self.assertFalse(ingest.is_trigger_line("[01:00:00] [Server thread/INFO]: <Nani> NwebNotFound was slain lol"))
        self.assertFalse(ingest.is_trigger_line("[01:00:00] [Server thread/INFO]: Griefer was kicked from the game"))

    def test_scan_detects_death(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(f"{d}/survival/logs")
            p = f"{d}/survival/logs/latest.log"
            open(p, "w").write("")
            off = {}
            ingest.scan_presence(off, d)
            with open(p, "a") as f:
                f.write("[x] [Server thread/INFO]: NwebNotFound drowned\n")
            self.assertTrue(ingest.scan_presence(off, d))

    def test_hash_ignores_volatile(self):
        a = {"links": [], "servers": {"lobby": {"online": True, "uptime_seconds": 10, "tps": 19.9}}}
        b = {"links": [], "servers": {"lobby": {"online": True, "uptime_seconds": 99, "tps": 20.0}}}
        c = {"links": [], "servers": {"lobby": {"online": False}}}
        self.assertEqual(ingest.payload_hash(a), ingest.payload_hash(b))
        self.assertNotEqual(ingest.payload_hash(a), ingest.payload_hash(c))

    def test_backoff(self):
        self.assertEqual(ingest.note_status(429, now=100), 30)
        self.assertEqual(ingest.note_status(503, now=100), 60)
        self.assertEqual(ingest.note_status(500, retry_after="120", now=100), 120)
        self.assertEqual(ingest._rate["backoff_until"], 220)
        ingest.note_status(200)
        self.assertEqual(ingest._rate["fails"], 0)
        self.assertEqual(ingest._rate["backoff_until"], 0)
        self.assertEqual(ingest.note_status(400), 0)  # Client-Fehler: kein Backoff

    def test_post_rate_limit_and_backoff_skip(self):
        posts = []

        class R:
            status, headers = 200, {}
            async def text(self): return "{}"
            async def __aenter__(self): return self
            async def __aexit__(self, *a): return False

        class S:
            def post(self, *a, **k):
                posts.append(time.monotonic())
                return R()

        async def run():
            with mock.patch.object(ingest, "MIN_GAP", 0.2):
                await ingest._post(S(), "t", {}, "x")
                await ingest._post(S(), "t", {}, "x")
                ingest._rate["backoff_until"] = time.monotonic() + 10
                self.assertEqual(await ingest._post(S(), "t", {}, "x"), (0, "backoff"))
        asyncio.run(run())
        self.assertEqual(len(posts), 2)
        self.assertGreaterEqual(posts[1] - posts[0], 0.18)

    def test_history_throttle(self):
        calls = []

        async def fake_hist(session, token, now=None):
            calls.append(1)
            return True

        async def run():
            ingest._hist_push["last"] = None
            with mock.patch.object(ingest, "push_history", fake_hist):
                await ingest.maybe_push_history(None, "t", now=1000)
                await ingest.maybe_push_history(None, "t", now=1100)  # < 5 min
                await ingest.maybe_push_history(None, "t", now=1301)
        asyncio.run(run())
        self.assertEqual(len(calls), 2)

    def test_history_not_on_skip_or_fail(self):
        calls = []

        async def fake_hist(session, token):
            calls.append(1)

        async def fake_post(session, token, payload, label):
            return status[0], "{}"
        status = [500]

        class B:
            def server_mode(self): return None

        async def run():
            ingest._hist_push["last"] = None
            with mock.patch.dict(os.environ, {"MC_INGEST_TOKEN": "x"}), \
                 mock.patch.object(ingest, "push_history", fake_hist), \
                 mock.patch.object(ingest, "_post", fake_post), \
                 mock.patch.object(ingest, "build_payload", lambda b: {"links": []}), \
                 mock.patch.object(ingest, "fetch_rows", lambda n: None), \
                 mock.patch.object(ingest, "collect_servers", lambda m: None):
                await ingest.push_once(B(), session=object())  # Fehler
                self.assertEqual(calls, [])
                status[0] = 200
                await ingest.push_once(B(), session=object())  # Erfolg
                self.assertEqual(len(calls), 1)
                ingest._hist_push["last"] = None
                await ingest.push_once(B(), session=object())  # unveraendert -> Skip
                self.assertEqual(len(calls), 1)
        asyncio.run(run())

    def test_interval_env(self):
        with mock.patch.dict(os.environ, {"INGEST_INTERVAL_SEC": "45"}):
            self.assertEqual(ingest._env_int("INGEST_INTERVAL_SEC", 30), 45)
        with mock.patch.dict(os.environ, {"INGEST_INTERVAL_SEC": "abc"}):
            self.assertEqual(ingest._env_int("INGEST_INTERVAL_SEC", 30), 30)
        self.assertEqual(ingest.INTERVAL if "INGEST_INTERVAL_SEC" in os.environ else 30, ingest.INTERVAL)



class EconomyDailyTest(unittest.TestCase):
    def test_build_economy(self):
        import datetime, decimal
        cols = ["day", "money_supply_cp", "minted_cp", "burned_cp", "net_cp"]
        rows = [(datetime.date(2026, 10, 31), decimal.Decimal("1234.6000"), None, None, None),
                (datetime.date(2026, 10, 30), decimal.Decimal("1000"), decimal.Decimal("5"), None, None)]
        out = ingest.build_economy(cols, rows)
        self.assertEqual(out[0], {"day": "2026-10-30", "money_supply_cp": 1000, "minted_cp": 5,
                                  "burned_cp": None, "net_cp": None})
        self.assertEqual(out[1]["money_supply_cp"], 1235)
        many = [(datetime.date(2026, 1, 1) + datetime.timedelta(days=i), 1) for i in range(120)]
        self.assertEqual(len(ingest.build_economy(["day", "money_supply_cp"], many)), 90)

    def test_economy_fail_soft(self):
        posted = []

        async def fake_post(session, token, payload, label):
            posted.append(payload)
            return 200, "{}"

        async def run():
            ingest._econ["warned"] = False
            with mock.patch.object(ingest, "fetch_rows", lambda n: None), \
                 mock.patch.object(ingest, "_post", fake_post), \
                 self.assertLogs("creepy.ingest", "WARNING") as cm:
                await ingest.push_economy(None, "t")
                await ingest.push_economy(None, "t")
            self.assertEqual(len(cm.output), 1)
        asyncio.run(run())
        self.assertEqual(posted, [])


if __name__ == "__main__":
    unittest.main()

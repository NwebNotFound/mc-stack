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
        self.assertEqual(ingest.note_status(429, now=100), 5)
        self.assertEqual(ingest.note_status(503, now=100), 10)
        self.assertEqual(ingest.note_status(500, retry_after="60", now=100), 60)
        self.assertEqual(ingest._rate["backoff_until"], 160)
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

    def test_interval_env(self):
        with mock.patch.dict(os.environ, {"INGEST_INTERVAL_SEC": "45"}):
            self.assertEqual(ingest._env_int("INGEST_INTERVAL_SEC", 30), 45)
        with mock.patch.dict(os.environ, {"INGEST_INTERVAL_SEC": "abc"}):
            self.assertEqual(ingest._env_int("INGEST_INTERVAL_SEC", 30), 30)
        self.assertEqual(ingest.INTERVAL if "INGEST_INTERVAL_SEC" in os.environ else 30, ingest.INTERVAL)


if __name__ == "__main__":
    unittest.main()

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
            with mock.patch.object(ingest, "push_servers", fake_push), mock.patch.object(ingest, "DEBOUNCE", 0.2):
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
            with mock.patch.object(ingest, "push_servers", fake_push), mock.patch.object(ingest, "DEBOUNCE", 0.1):
                await ingest.trigger_presence(None)
        asyncio.run(run())
        self.assertEqual(len(calls), 2)
        self.assertGreaterEqual(calls[1] - calls[0], 0.08)
        self.assertFalse(ingest._presence["dirty"])


if __name__ == "__main__":
    unittest.main()

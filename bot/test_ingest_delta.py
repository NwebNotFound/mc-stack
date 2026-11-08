"""Ingest delta pushes: persistent Markt-History state, backoff growth, identical Ingest skipped (D1 write budget)."""
import asyncio
import json
import os
import tempfile
import unittest
from unittest import mock

import ingest

NOW = 1_800_000_000 - 1_800_000_000 % 3600 + 1200   # 20 min into an hour
H = NOW - NOW % 3600
COLS = ["item", "hour_ts", "avg_price", "min_price", "max_price", "volume_qty"]


def rows(hours=200):
    return [("STONE", H - i * 3600, 1.0, 1.0, 1.0, 5) for i in range(hours)] + [("DIRT", H - i * 3600, 2.0, 2.0, 2.0, 3) for i in range(hours)]


class DeltaTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "ingest-state.json")
        self.p = [mock.patch.object(ingest, "STATE_PATH", self.path),
                  mock.patch.dict(ingest._state, {"loaded": False, "hist_since": None, "hash": None}),
                  mock.patch.dict(ingest._hist_state, {"since": None}),
                  mock.patch.dict(ingest._hist_run, {"full_done": False, "hash": None}),
                  mock.patch.dict(ingest._rate, {"last_post": 0.0, "backoff_until": 0.0, "fails": 0, "last_hash": None}),
                  mock.patch.dict(os.environ, {"MC_INGEST_TOKEN": "t"}),
                  mock.patch.object(ingest, "MIN_GAP", 0.0)]
        for x in self.p:
            x.start()
        self.addCleanup(lambda: [x.stop() for x in self.p])

    def push(self, now=NOW):
        sent = []

        async def post(session, token, payload, label):
            sent.extend(payload["market_history"])
            return 200, "{}"
        with mock.patch.object(ingest, "fetch_rows", lambda v: (COLS, rows())), mock.patch.object(ingest, "_post", post):
            asyncio.run(ingest.push_history(object(), "t", now=now))
        return sent

    def test_restart_with_state_sends_only_new_hours(self):
        with open(self.path, "w") as f:
            json.dump({"hist_since": H - 3 * 3600}, f)
        sent = self.push()
        hours = {e["hour_ts"] for e in sent}
        self.assertEqual(min(hours), H - 4 * 3600)   # last acknowledged hour minus one (previous hour may still change)
        self.assertEqual(max(hours), H)
        self.assertLess(len(sent), 20)               # not the 90-day backfill (400 rows here)
        saved = json.load(open(self.path))
        self.assertEqual(saved["hist_since"], H)     # acknowledged hour persisted

    def test_no_state_pushes_recent_window_not_90_days(self):
        sent = self.push()
        self.assertGreaterEqual(min(e["hour_ts"] for e in sent), NOW - 6 * 3600 - 3600)   # 6 h window (D1 budget, 08.11.)
        self.assertLess(len(sent), 20)

    def test_full_backfill_only_by_flag(self):
        with mock.patch.dict(os.environ, {"INGEST_HISTORY_FULL": "1"}):
            sent = self.push()
        self.assertEqual(len(sent), 400)
        self.assertLess(len(self.push(now=NOW + 300)), 400)   # later runs are deltas

    def test_unchanged_window_not_resent(self):
        self.assertGreater(len(self.push()), 0)
        self.assertGreater(len(self.push(now=NOW + 300)), 0)   # window shrinks to the last hours once
        self.assertEqual(self.push(now=NOW + 600), [])

    def test_backoff_grows_on_500_and_caps(self):
        waits = []

        class R:
            status, headers = 500, {}
            async def text(self): return "boom"
            async def __aenter__(self): return self
            async def __aexit__(self, *a): return False

        class S:
            def post(self, *a, **k): return R()

        async def run():
            for _ in range(7):
                ingest._rate["backoff_until"] = 0.0
                await ingest._post(S(), "t", {}, "x")
                waits.append(round(ingest._rate["backoff_until"] - __import__("time").monotonic()))
        asyncio.run(run())
        self.assertEqual(waits[:4], [30, 60, 120, 240])
        self.assertLessEqual(max(waits), 900)
        self.assertEqual(waits[-1], 900)

    def test_backoff_logs_once_per_step(self):
        with self.assertLogs(ingest.log, level="WARNING") as cm:
            ingest.note_status(500, now=0)
            ingest.note_status(500, now=0)
        self.assertEqual(len(cm.output), 2)   # one line per step

        class R:
            status, headers = 200, {}
            async def text(self): return ""
            async def __aenter__(self): return self
            async def __aexit__(self, *a): return False

        class S:
            def post(self, *a, **k): return R()
        ingest._rate["backoff_until"] = __import__("time").monotonic() + 100
        with self.assertLogs(ingest.log, level="INFO") as cm:
            for _ in range(5):
                asyncio.run(ingest._post(S(), "t", {}, "x"))
        self.assertEqual(len(cm.output), 1)   # skipped attempts logged once

    def test_identical_ingest_skipped_also_after_restart(self):
        sent = []

        async def post(session, token, payload, label):
            sent.append(label)
            return 200, "{}"

        class B:
            def server_mode(self): return None

        def go():
            with mock.patch.object(ingest, "build_payload", lambda b: {"links": [1]}), \
                 mock.patch.object(ingest, "fetch_rows", lambda n: None), \
                 mock.patch.object(ingest, "collect_servers", lambda m: None), \
                 mock.patch.object(ingest, "maybe_push_history", mock.AsyncMock()), \
                 mock.patch.object(ingest, "_post", post):
                asyncio.run(ingest.push_once(B(), session=object()))
        go()
        go()
        self.assertEqual(sent, ["Ingest-Push"])          # second identical payload skipped
        ingest._state.update(loaded=False, hash=None)    # restart: hash comes back from the state file
        ingest._rate["last_hash"] = None
        go()
        self.assertEqual(sent, ["Ingest-Push"])

    def test_state_advances_per_accepted_chunk_and_requests_are_capped(self):
        many = [("I%d" % k, H - i * 3600, 1.0, 1.0, 1.0, 1) for i in range(40) for k in range(300)]   # 12000 rows, 40 hours
        calls = []

        async def post(session, token, payload, label):
            calls.append(payload["market_history"])
            return (200, "{}") if len(calls) <= 2 else (500, "boom")
        with mock.patch.dict(os.environ, {"INGEST_HISTORY_FULL": "1"}),              mock.patch.object(ingest, "fetch_rows", lambda v: (COLS, many)), mock.patch.object(ingest, "_post", post):
            asyncio.run(ingest.push_history(object(), "t", now=NOW))
        self.assertTrue(all(len(c) <= 5000 for c in calls))
        self.assertEqual(len(calls), 3)                    # third chunk failed
        saved = json.load(open(self.path))["hist_since"]
        self.assertEqual(saved, calls[1][-1]["hour_ts"])   # state sits after the 2nd accepted chunk, not at the end
        self.assertLess(saved, H)


if __name__ == "__main__":
    unittest.main()

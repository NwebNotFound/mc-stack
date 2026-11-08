"""Markt-History-Push bleibt inkrementell (D1 Free Tier: 100k geschriebene Zeilen/Tag auf der Website, 08.11.2026):
ein Neustart oder ein fehlgeschlagener Teil schickt nie wieder die ganze 90-Tage-Historie."""
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import ingest

H = 3600
NOW = 1_800_000_000 - 1_800_000_000 % H + 1200   # 20 min in eine Stunde
COLS = ["item", "hour_ts", "avg_price", "min_price", "max_price", "volume_qty"]
ROWS = [[it, NOW - NOW % H - h * H, 10, 9, 11, 1] for h in range(0, 24 * 90) for it in ("diamond", "dirt")]   # 90 Tage, 2 Items


class HistoryIncrementalTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.state = Path(self.dir.name) / "history-since.json"
        self.patches = [mock.patch.object(ingest, "HIST_STATE", self.state), mock.patch.object(ingest, "fetch_rows", lambda name: (COLS, ROWS))]
        for p in self.patches:
            p.start()
        ingest._hist_state.update(since=None, loaded=False)

    def tearDown(self):
        for p in self.patches:
            p.stop()
        self.dir.cleanup()

    def run_push(self, statuses=None, now=NOW, max_rows=None):
        sent = []
        statuses = list(statuses or [])

        async def post(session, token, body, label):
            sent.append(body["market_history"])
            return (statuses.pop(0) if statuses else 200), None
        real = ingest.chunk_history
        chunker = (lambda entries: real(entries, max_rows=max_rows)) if max_rows else real
        with mock.patch.object(ingest, "_post", post), mock.patch.object(ingest, "chunk_history", chunker):
            ok = asyncio.run(ingest.push_history(None, "t", now=now))
        return ok, sent

    def test_first_run_without_state_sends_only_the_backfill_window(self):
        ok, sent = self.run_push()
        rows = [r for c in sent for r in c]
        self.assertTrue(ok)
        self.assertEqual(min(r["hour_ts"] for r in rows), NOW - NOW % H - ingest.HIST_BACKFILL_S, "48 h by default, not 90 days")
        self.assertLessEqual(len(rows), 2 * (ingest.HIST_BACKFILL_S // H + 1))
        self.assertEqual(json.loads(self.state.read_text())["since"], NOW - NOW % H, "state saved after success")

    def test_restart_resumes_from_the_saved_state(self):
        self.state.write_text(json.dumps({"since": NOW - NOW % H - 2 * H}))
        ok, sent = self.run_push()
        rows = [r for c in sent for r in c]
        self.assertTrue(ok)
        self.assertEqual(sorted({r["hour_ts"] for r in rows}), [NOW - NOW % H - 2 * H, NOW - NOW % H - H, NOW - NOW % H], "only the hours since the saved state")

    def test_a_failed_chunk_keeps_the_progress_of_the_chunks_before(self):
        ok, sent = self.run_push(statuses=[200, 500], max_rows=20)
        self.assertFalse(ok)
        saved = json.loads(self.state.read_text())["since"]
        self.assertEqual(saved, sent[0][-1]["hour_ts"], "progress up to the last hour of the accepted chunk")
        ingest._hist_state.update(since=None, loaded=False)   # restart
        ok, sent2 = self.run_push()
        self.assertTrue(ok)
        self.assertGreaterEqual(min(r["hour_ts"] for c in sent2 for r in c), saved, "never again from 0 / 90 days")



class IngestHashTests(unittest.TestCase):
    def test_the_last_accepted_payload_hash_survives_a_restart(self):
        with tempfile.TemporaryDirectory() as d, mock.patch.object(ingest, "INGEST_HASH_FILE", Path(d) / "ingest-hash.json"):
            ingest._rate.update(last_hash=None, hash_loaded=False)
            self.assertIsNone(ingest._last_hash())
            ingest._save_hash("abc")
            ingest._rate.update(last_hash=None, hash_loaded=False)   # restart
            self.assertEqual(ingest._last_hash(), "abc", "an unchanged payload is not re-sent after a restart")
            ingest._rate.update(last_hash=None, hash_loaded=False)


if __name__ == "__main__":
    unittest.main()

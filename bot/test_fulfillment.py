import asyncio
import sqlite3
import unittest
from unittest import mock

import ingest
import relay

U1 = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"


class FulfillmentTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        relay.init_db(self.db)

    def st(self, rid):
        r = self.db.execute("SELECT status, acked FROM web_fulfillment_v2 WHERE request_id=? ORDER BY rowid DESC", (rid,)).fetchone()
        return r

    def test_final_never_overwritten_and_idempotent(self):
        self.assertTrue(relay.record_fulfillment(self.db, "o1", "ready"))
        self.assertFalse(relay.record_fulfillment(self.db, "o1", "ready"))
        self.assertTrue(relay.record_fulfillment(self.db, "o1", "collected"))
        self.assertFalse(relay.record_fulfillment(self.db, "o1", "ready"))
        self.assertFalse(relay.record_fulfillment(self.db, "o1", "refunded"))
        self.assertEqual(self.st("o1")[0], "collected")
        self.assertFalse(relay.record_fulfillment(self.db, "o2", "bogus"))

    def test_err_status(self):
        self.assertEqual(relay.err_status("NOT_FOUND"), "failed")
        self.assertEqual(relay.err_status("REFUNDED"), "refunded")

    def test_cursor_persisted(self):
        rows = [("o1", "ready", "2026-10-30 09:00:00"), ("o2", "failed", "2026-10-30 09:01:00")]
        self.assertEqual(relay.apply_fulfillment(self.db, rows), 2)
        self.assertEqual(relay.get_cursor(self.db), "2026-10-30 09:01:00")
        self.assertEqual(relay.apply_fulfillment(self.db, rows[1:]), 0)  # >= Wiederholung idempotent

    def test_sync_acks_once(self):
        bot = mock.Mock(db=self.db)
        sent = []

        async def fake_ack(session, token, path, acks):
            sent.append((path, acks))
            return True
        rows = [("o1", "ready", "t1")]
        with mock.patch.object(relay, "_ack", fake_ack), mock.patch.object(relay, "_fulfillment_rows", lambda since: rows):
            asyncio.run(relay.sync_fulfillment(bot, None, "x"))
            asyncio.run(relay.sync_fulfillment(bot, None, "x"))
        self.assertEqual(sent, [("mc-shop-orders/ack", [{"id": "o1", "status": "ready"}])])
        self.assertEqual(self.st("o1"), ("ready", 1))

    def test_unacked_ready_survives_collected(self):
        bot = mock.Mock(db=self.db)
        sent, ok = [], [False]

        async def fake_ack(session, token, path, acks):
            sent.append(acks)
            return ok[0]
        rows = [[("o1", "ready", "t1")]]
        with mock.patch.object(relay, "_ack", fake_ack), mock.patch.object(relay, "_fulfillment_rows", lambda since: rows[0]):
            asyncio.run(relay.sync_fulfillment(bot, None, "x"))  # Ack schlaegt fehl
            rows[0], ok[0] = [("o1", "collected", "t2")], True
            asyncio.run(relay.sync_fulfillment(bot, None, "x"))
            asyncio.run(relay.sync_fulfillment(bot, None, "x"))
        self.assertEqual(sent[1], [{"id": "o1", "status": "ready"}, {"id": "o1", "status": "collected"}])
        self.assertEqual(len(sent), 2)
        self.assertFalse(relay.record_fulfillment(self.db, "o1", "refunded"))

    def test_none_ts_does_not_block_cursor(self):
        rows = [("o1", "ready", "2026-10-30 09:00:00"), ("o2", "ready", None)]
        relay.apply_fulfillment(self.db, rows)
        self.assertEqual(relay.get_cursor(self.db), "2026-10-30 09:00:00")

    def test_migrates_old_table(self):
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE web_fulfillment (request_id TEXT PRIMARY KEY, status TEXT NOT NULL, acked INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)")
        db.execute("INSERT INTO web_fulfillment VALUES ('o1','ready',0,1)")
        relay.init_db(db)
        relay.init_db(db)
        self.assertEqual(db.execute("SELECT request_id, status, acked FROM web_fulfillment_v2").fetchall(), [("o1", "ready", 0)])

    def test_safe_url(self):
        d = "https://nwebnotfound.dev/api"
        self.assertEqual(ingest.safe_url("http://evil.example/api", d), d)
        self.assertEqual(ingest.safe_url("ftp://x", d), d)
        self.assertEqual(ingest.safe_url(None, d), d)
        self.assertEqual(ingest.safe_url("https://staging.nwebnotfound.dev/api/", d), "https://staging.nwebnotfound.dev/api")
        self.assertEqual(ingest.safe_url("http://localhost:8787/api", d), "http://localhost:8787/api")

    def test_missing_view_warns(self):
        bot = mock.Mock(db=self.db)

        def boom(since):
            raise RuntimeError("no view")
        with mock.patch.object(relay, "_fulfillment_rows", boom), self.assertLogs("creepy.relay", "WARNING"):
            self.assertEqual(asyncio.run(relay.sync_fulfillment(bot, None, "x")), [])

    def test_order_err_records_failed(self):
        bot = mock.Mock(db=self.db)
        acked = []

        async def fake_ack(session, token, path, acks):
            acked.append(acks)
            return True
        with mock.patch.object(relay, "_ack", fake_ack), mock.patch.object(relay, "_run", lambda *a: ("rejected", "NOT_FOUND")), \
                mock.patch.dict("sys.modules", {"buy": mock.Mock(RCON=None, HOST=None)}):
            asyncio.run(relay.process(bot, None, "x", "order", [{"request_id": "o9"}], None))
        self.assertEqual(acked[0][0]["status"], "rejected")  # bestehendes Ack bleibt
        self.assertEqual(self.st("o9")[0], "failed")


class IngestExtrasTests(unittest.TestCase):
    def test_merge(self):
        stats = {U1.replace("-", ""): {"kills": 3}}
        bal = (["uuid", "name", "balance_cp", "updated_at"], [(U1, "Nani", "9.00", None)])
        inv = (["uuid", "slot", "material", "amount", "meta_summary", "updated_at"],
               [(U1, i, "minecraft:dirt", 1, None, None) for i in range(70, 0, -1)])
        out = ingest.merge_player_extras(stats, bal, inv)
        p = out[U1.replace("-", "")]
        self.assertEqual(p["money"], 9)
        self.assertEqual(len(p["inventory"]), 64)
        self.assertEqual(p["inventory"][0], {"slot": 1, "item": "dirt", "count": 1})
        self.assertNotIn("pickup", p)
        self.assertEqual(p["kills"], 3)

    def test_player_cap(self):
        stats = {str(i): {} for i in range(200)}
        bal = (["uuid", "balance_cp"], [(U1, 1)])
        out = ingest.merge_player_extras(stats, bal)
        self.assertEqual(len(out), 200)
        self.assertNotIn(U1.replace("-", ""), out)
        self.assertNotIn(U1, out)


if __name__ == "__main__":
    unittest.main()

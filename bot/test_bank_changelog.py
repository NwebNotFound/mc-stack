import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

import bank
import changelog
import relay

U = "11111111-2222-3333-4444-555555555555"


class ChangelogEmbedTests(unittest.TestCase):
    TABLE = "Neue Preise:\n| Item | Kauf vorher | Kauf neu |\n|---|---:|---:|\n| Eisenbarren | 6,00 | 9,00 |\n| Goldbarren | 9,00 | 18,00 |"

    def test_table_becomes_fields_no_codeblock(self):
        entry = {"version": "9", "date": "30.10.", "changes": [{"type": "verbessert", "text": self.TABLE}, {"type": "neu", "text": "x"}]}
        for e in (changelog.embed(entry), changelog.mc_embed(entry)):
            names = [f.name for f in e.fields]
            self.assertIn("Eisenbarren", names)
            self.assertIn("Goldbarren", names)
            gold = next(f for f in e.fields if f.name == "Goldbarren")
            self.assertEqual(gold.value, "Kauf vorher: **9,00** \u00b7 Kauf neu: **18,00**")
            self.assertFalse(any("```" in f.value or "|" in f.value for f in e.fields))
            self.assertLessEqual(len(e.fields), 25)

    def test_codeblock_table_too(self):
        e = changelog.mc_embed({"version": "1", "date": "d", "changes": [{"type": "neu", "text": "```\n" + self.TABLE + "\n```"}]})
        self.assertFalse(any("```" in f.value for f in e.fields))

    def test_utf8_sig_and_umlauts(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "changelog-mc.json"
            p.write_bytes(json.dumps([{"text": "Größer \u2013 schöner"}], ensure_ascii=False).encode("utf-8-sig"))
            self.assertEqual(changelog.load_json(p)[0]["text"], "Größer \u2013 schöner")
            p.write_text(json.dumps([{"text": "Äpfel"}], ensure_ascii=False), encoding="utf-8")
            self.assertEqual(changelog.load_json(p)[0]["text"], "Äpfel")


class BankBuyTests(unittest.TestCase):
    def db(self):
        d = sqlite3.connect(":memory:")
        relay.init_db(d)
        bank.init_db(d)
        return d

    def test_make_order_format(self):
        o = bank.make_order("minecraft:IRON_INGOT", 16, U, "bank-abc")
        self.assertEqual(o, {"type": "bank_buy", "item": "iron_ingot", "amount": 16, "mc_uuid": U, "request_id": "bank-abc"})
        self.assertEqual(bank.command(o), f"bank buy {U} iron_ingot 16 bank-abc")
        for bad in (("iron ingot; op x", 1, U), ("iron_ingot", 0, U), ("iron_ingot", 99999, U), ("iron_ingot", 1, "nope")):
            with self.assertRaises(ValueError):
                bank.make_order(*bad)

    def test_queue_idempotent_and_status(self):
        d = self.db()
        o = bank.make_order("iron_ingot", 8, U, "bank-1")
        bank.enqueue(d, 42, o)
        bank.enqueue(d, 42, o)
        self.assertEqual(d.execute("SELECT COUNT(*) FROM dc_orders").fetchone()[0], 1)
        calls = []
        rcon = lambda host, cmd: calls.append(cmd) or "PENDING bank-1"
        self.assertEqual(bank.process_queue(d, rcon, "survival"), [])
        self.assertEqual(bank.status_of(d, "bank-1"), (None, None))
        rcon = lambda host, cmd: calls.append(cmd) or "OK 8 7200 0"
        self.assertEqual(bank.process_queue(d, rcon, "survival"), [("bank-1", "accepted", None)])
        self.assertEqual(bank.process_queue(d, rcon, "survival"), [])  # nicht doppelt
        self.assertEqual(len(calls), 2)
        relay.apply_fulfillment(d, [("bank-1", "ready", "2026-10-30 14:00:00")])
        self.assertEqual(bank.status_of(d, "bank-1")[0], "ready")

    def test_rejected_records_failed_and_not_acked_to_web(self):
        d = self.db()
        bank.enqueue(d, 1, bank.make_order("gold_ingot", 1, U, "bank-2"))
        self.assertEqual(bank.process_queue(d, lambda h, c: "ERR NO_FUNDS", "s"), [("bank-2", "rejected", "NO_FUNDS")])
        self.assertEqual(bank.status_of(d, "bank-2")[0], "failed")
        import asyncio
        from unittest import mock
        sent = []

        async def fake_ack(session, token, path, acks):
            sent.append(acks)
            return True
        bot = mock.Mock(db=d)
        with mock.patch.object(relay, "_ack", fake_ack), mock.patch.object(relay, "_fulfillment_rows", lambda since: []):
            asyncio.run(relay.sync_fulfillment(bot, None, "t"))
        self.assertEqual(sent, [])

    def test_server_down_keeps_queue(self):
        d = self.db()
        bank.enqueue(d, 1, bank.make_order("coal", 1, U, "bank-3"))

        def boom(h, c):
            raise ConnectionRefusedError()
        self.assertEqual(bank.process_queue(d, boom, "s"), [])
        self.assertEqual(d.execute("SELECT status FROM dc_orders").fetchone()[0], None)


if __name__ == "__main__":
    unittest.main()

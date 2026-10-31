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


class FakeResp:
    def __init__(self, status, data):
        self.status, self.data = status, data

    async def json(self, content_type=None):
        return self.data

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class FakeSession:
    def __init__(self, status, data):
        self.resp, self.calls = FakeResp(status, data), []

    def post(self, url, json=None, headers=None, timeout=None):
        self.calls.append((url, json, headers))
        return self.resp


class BankBuyTests(unittest.TestCase):
    def test_make_order_format(self):
        o = bank.make_order("minecraft:IRON_INGOT", 16, U, "r-abc")
        self.assertEqual(o, {"type": "bank_buy", "item": "iron_ingot", "amount": 16, "mc_uuid": U, "request_id": "r-abc"})
        for bad in (("iron ingot; op x", 1, U), ("iron_ingot", 0, U), ("iron_ingot", 99999, U), ("iron_ingot", 1, "nope")):
            with self.assertRaises(ValueError):
                bank.make_order(*bad)

    def test_submit_posts_to_shared_queue(self):
        import asyncio
        o = bank.make_order("iron_ingot", 8, U, "r-1")
        s = FakeSession(202, {"ok": True, "request_id": "srv-1", "status": "pending"})
        self.assertEqual(asyncio.run(bank.submit(s, o, 123456789012345678, base="https://x/api", token="t")), ("ok", "srv-1"))
        url, body, headers = s.calls[0]
        self.assertEqual(url, "https://x/api/mc-shop-order")
        self.assertEqual(headers, {"x-ingest-token": "t"})
        self.assertEqual(body, {"type": "bank_buy", "item": "iron_ingot", "amount": 8, "mc_uuid": U, "request_id": "r-1", "discord_id": "123456789012345678"})

    def test_submit_errors(self):
        import asyncio
        o = bank.make_order("coal", 1, U)
        self.assertEqual(asyncio.run(bank.submit(FakeSession(404, {"error": "Shop nicht gefunden"}), o, 1, base="b", token="t")), ("err", "Shop nicht gefunden"))
        self.assertEqual(asyncio.run(bank.submit(FakeSession(500, {}), o, 1, base="b", token="t")), ("soon", None))
        self.assertEqual(asyncio.run(bank.submit(FakeSession(202, {"ok": True}), o, 1, base="b", token="")), ("soon", None))

    def test_status_via_fulfillment(self):
        d = sqlite3.connect(":memory:")
        relay.init_db(d)
        self.assertIsNone(bank.status_of(d, "srv-1"))
        relay.apply_fulfillment(d, [("srv-1", "ready", "2026-10-30 14:00:00")])
        self.assertEqual(bank.status_of(d, "srv-1"), "ready")
        relay.apply_fulfillment(d, [("srv-1", "collected", "2026-10-30 14:05:00")])
        self.assertEqual(bank.status_of(d, "srv-1"), "collected")

    def test_disabled_by_default(self):
        import os
        from unittest import mock
        with mock.patch.dict(os.environ, {"BANK_BUY_ENABLED": ""}):
            self.assertFalse(bank.enabled())
        with mock.patch.dict(os.environ, {"BANK_BUY_ENABLED": "1"}):
            self.assertTrue(bank.enabled())


class UniformFormatTest(unittest.TestCase):
    def test_all_entries_same_format(self):
        import json
        from pathlib import Path
        ents = json.loads(Path(__file__).with_name("changelog.json").read_text(encoding="utf-8"))
        # Tabellen (table_fields) haben eigene Feldnamen -> fuer den Format-Test herausnehmen
        plain = [dict(x, table=None, changes=[c for c in x["changes"] if not changelog._is_table(c.get("text"))]) for x in ents]
        es = [changelog.embed(x) for x in plain] + [changelog.mc_embed({"version": "1.14", "date": "31.10.2026", "changes": [{"type": "fix", "text": "a"}], "discord": ["b"]})]
        self.assertEqual(es[-1].title, "Update 31.10.2026 \u00b7 v1.14")
        for e in es:
            self.assertRegex(e.title, r"^Update \d\d\.\d\d\.\d{4}( · v[\w.]+)?$")
            self.assertEqual(e.color.value, changelog.COLOR)
            self.assertEqual(e.footer.text, changelog.FOOTER)
            for f in e.fields:
                self.assertRegex(f.name, r"^(⛏️ Minecraft|🤖 Discord) · ")


if __name__ == "__main__":
    unittest.main()

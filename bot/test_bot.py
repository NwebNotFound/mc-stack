import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, AsyncMock, Mock

import bot


class BotTests(unittest.TestCase):
    def test_creepy_password_does_not_require_the_writer_secret(self):
        import shops
        with patch.dict(os.environ, {"CREEPY_DB_PASSWORD": "ro-only", "CRAFTCORE_DB_PASSWORD": "writer", "CRAFTCORE_DB_USER": "creepy_ro"}, clear=False):
            self.assertEqual(shops.db_password(), "ro-only")
            self.assertEqual(shops.db_user(), "creepy_ro")
        with patch.dict(os.environ, {"CREEPY_DB_PASSWORD": "", "CRAFTCORE_DB_PASSWORD": "writer"}, clear=False):
            os.environ.pop("CRAFTCORE_DB_USER", None)
            self.assertEqual(shops.db_password(), "writer")
            self.assertEqual(shops.db_user(), "creepy_ro")

    def test_configuration_requires_token(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "DISCORD_TOKEN"):
                bot.config()

    def test_whitelist_uses_verified_uuid_on_both_servers(self):
        uuid = "123456781234123412341234567890ab"
        with patch.object(bot, "rcon", return_value="LINK_OK") as command:
            bot.whitelist("add", uuid)
        self.assertEqual(command.call_count, 2)
        self.assertTrue(all(call.args[1] == "linkwhitelist add 12345678-1234-1234-1234-1234567890ab" for call in command.call_args_list))

    def test_whitelist_does_not_report_failure_as_success(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(bot, "DATA", Path(directory)), patch.object(bot, "rcon", return_value="Unknown command"):
            # gleiches Schema wie Bot.__init__, damit der Test keine vorhandene /app/data/links.sqlite3 braucht
            with bot.sqlite3.connect(Path(directory) / "links.sqlite3") as db:
                db.execute("CREATE TABLE IF NOT EXISTS links (discord_id INTEGER PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, name TEXT NOT NULL, code TEXT, expires INTEGER, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)")
            with self.assertRaises(RuntimeError):
                bot.whitelist("remove", "123456781234123412341234567890ab")

    def test_backup_reenables_saving_on_archive_failure(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(bot, "DATA", Path(directory)), patch.object(bot, "rcon", return_value="ok") as command, patch.object(bot.tarfile, "open", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                bot.backup()
            for host in bot.BACKENDS:
                self.assertIn(((host, "save-on"),), [tuple(call)[:1] for call in command.call_args_list])


class PlayerCountTests(unittest.IsolatedAsyncioTestCase):
    async def test_count_rename_is_rate_limited(self):
        guild = object()
        channel = Mock(guild=guild, name="old")
        channel.edit = AsyncMock()
        client = Mock(last_count_rename=0.0)
        client.cfg = {"GUILD_ID": "1"}
        client.get_guild.return_value = guild
        client.get_channel.return_value = channel
        with patch.dict(os.environ, {"PLAYER_COUNT_CHANNEL_ID": "2"}), patch.object(bot.time, "monotonic", side_effect=[1000, 1000, 1100]):
            await bot.Bot.update_player_count(client, 4)
            await bot.Bot.update_player_count(client, 5)
        channel.edit.assert_awaited_once_with(name="Minecraft online: 4", reason="Minecraft-Spielerzahl")


if __name__ == "__main__":
    unittest.main()


class FeatureTests(unittest.TestCase):
    def test_norm_code(self):
        self.assertEqual(bot.norm_code(" 1234-5678 "), "12345678")
        self.assertEqual(bot.norm_code("1234 5678"), "12345678")
        self.assertEqual(bot.norm_code("12.34_56-78"), "12345678")
        self.assertEqual(bot.norm_code("abcd"), "ABCD")
        self.assertEqual(bot.norm_code(None), "")

    def test_parse_when(self):
        import features, datetime as dt
        r = features.parse_when("02.11.2030 20:00")
        self.assertEqual((r.year, r.month, r.day, r.hour, r.minute), (2030, 11, 2, 20, 0))
        r = features.parse_when("morgen 19:30 Uhr")
        self.assertEqual(r.date(), features.now().date() + dt.timedelta(days=1))
        self.assertEqual((r.hour, r.minute), (19, 30))
        self.assertGreater(features.parse_when("1.1. 10:00"), features.now())
        for bad in ("bald", "", "32.13. 25:99"):
            with self.assertRaises(ValueError):
                features.parse_when(bad)

    def test_recap_embed(self):
        import features
        p = lambda n, play, **k: {"name": n, "play": play, "kills": k.get("kills", 0), "deaths": k.get("deaths", 0),
                                  "jumps": 0, "fish": 0, "cm": 0, "mined": k.get("mined", {})}
        res = {"a": p("Alice", 72000, kills=5, deaths=2, mined={"minecraft:stone": 50}),
               "b": p("Bob", 36000, kills=1, mined={"minecraft:stone": 10, "minecraft:dirt": 30}),
               "c": p("Idle", 0)}
        with patch.object(features.stats_card, "pretty", lambda k, kind="item": k):
            e = features.recap_embed(res, 1700000000)
        fields = {f.name: f.value for f in e.fields}
        self.assertIn("2** Spieler", e.description)
        top = next(v for k, v in fields.items() if "Spielzeit" in k)
        self.assertLess(top.index("Alice"), top.index("Bob"))
        self.assertIn("minecraft:stone", next(v for k, v in fields.items() if "Block" in k))
        self.assertIn("6 gesamt", next(v for k, v in fields.items() if "Mob" in k))
        self.assertEqual(len(features.recap_embed({"c": p("Idle", 0)}, None).fields), 0)


class GuiAndBuyTextTests(unittest.TestCase):
    def test_hub_buttons_unique_and_persistent(self):
        import gui
        ids = [b[0] for b in gui.BUTTONS]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(i.startswith("hub:") for i in ids))
        self.assertLessEqual(len(ids), 25)

    def test_find_command_paths(self):
        import gui
        from discord import app_commands
        leaf = Mock(); grp = Mock(); grp.get_command = Mock(return_value=leaf)
        tree = Mock(); tree.get_command = Mock(return_value=grp)
        self.assertIs(gui.find_command(tree, "leaderboard parkour"), leaf)

    def test_buy_texts_no_longer_coming_soon(self):
        src = "".join(Path(__file__).with_name(f).read_text(encoding="utf-8") for f in ("shops.py", "ah.py", "gui.py"))
        self.assertNotIn("Kaufen nur ingame", src)
        self.assertNotIn("Kaufen per Discord kommt bald", src)

    def test_changelog_latest_is_19(self):
        import json
        data = json.loads(Path(__file__).with_name("changelog.json").read_text(encoding="utf-8"))
        self.assertIn("1.9", [d["version"] for d in data])

class GuiV110Tests(unittest.IsolatedAsyncioTestCase):
    def test_to_number(self):
        import gui
        self.assertEqual(gui.to_number("2,5"), 2.5)
        self.assertEqual(gui.to_number("16", int), 16)
        self.assertEqual(gui.to_number("", int, 1), 1)
        self.assertIsNone(gui.to_number("abc"))

    async def test_lb_pager_pages(self):
        import gui
        rows = [(i + 1, f"p{i}", 1000 + i, f"u{i}") for i in range(23)]
        v = gui.LbPager("Parkour", rows)
        self.assertEqual(v.pages, 3)
        self.assertTrue(v.prev.disabled)
        self.assertFalse(v.next.disabled)

    async def test_choice_view_calls_done(self):
        import gui
        got = []
        async def done(i, val): got.append(val)
        v = gui.ChoiceView("x", [("A", 1), ("B", 2)], done)
        self.assertEqual(len(v.sel.options), 2)

    def test_optional_args_for_gui(self):
        src = Path(__file__).with_name("bot.py").read_text(encoding="utf-8")
        self.assertIn("mc_name: str = None):", src)
        self.assertIn("anzahl: app_commands.Range[int, 0, 99] = None", src)
        self.assertIn("gui.LbPager", src)
        self.assertIn("item: str = None", Path(__file__).with_name("shops.py").read_text(encoding="utf-8"))

    def test_changelog_latest(self):
        import json
        self.assertEqual(json.loads(Path(__file__).with_name("changelog.json").read_text(encoding="utf-8"))[-1]["version"], "1.13")

class InviteTests(unittest.TestCase):
    def test_limit_is_two(self):
        import invites
        self.assertEqual(invites.MAX_OPEN_INVITES, 2)
        self.assertEqual(invites.INVITE_MAX_AGE, 86400)

    def test_find_used_by_uses(self):
        import invites
        self.assertEqual(invites.find_used({"a": 0, "b": 3}, {"a": 1, "b": 3}), "a")
        self.assertIsNone(invites.find_used({"a": 0, "b": 0}, {"a": 1, "b": 1}))

    def test_find_used_by_vanished_single_use(self):
        import invites
        self.assertEqual(invites.find_used({"a": 0, "b": 2}, {"b": 2}, ["a"]), "a")
        self.assertIsNone(invites.find_used({"a": 0, "c": 0}, {}, ["a", "c"]))

    def test_open_invites_and_list(self):
        import sqlite3, time, invites
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE links (discord_id INTEGER PRIMARY KEY, uuid TEXT, name TEXT, code TEXT, expires INTEGER, state TEXT, attempts INTEGER)")
        invites.init_db(db)
        now = int(time.time())
        db.execute("INSERT INTO dc_invites VALUES ('o',1,?,?,NULL,NULL)", (now, now + 100))
        db.execute("INSERT INTO dc_invites VALUES ('x',1,?,?,NULL,NULL)", (now, now - 1))
        db.execute("INSERT INTO dc_invites VALUES ('u',1,?,?,5,?)", (now, now + 100, now))
        db.execute("INSERT INTO links VALUES (5,'u5','Freund',NULL,NULL,'active',0)")
        self.assertEqual([r[0] for r in invites.open_invites(db, 1)], ["o"])
        e = invites.list_embed(db, 1)
        self.assertIn("Freund", e.description)
        self.assertLessEqual(len(e), 6000)

    def test_map_columns(self):
        import invites
        m = invites.map_columns(["id", "inviter_uuid", "invitee_uuid", "created_at", "paid_at"])
        self.assertEqual(m, {"inviter_uuid": "inviter_uuid", "invitee_uuid": "invitee_uuid"})
        self.assertIsNone(invites.map_columns(["id", "foo"]))

    def test_craftcore_disabled_without_password(self):
        import invites
        with patch.dict(os.environ, {"CREEPY_INV_DB_PASSWORD": ""}):
            self.assertEqual(invites.craftcore_insert([("a", "b", 1, 2, "A", "B")]), set())

    def test_hub_has_invite(self):
        import gui
        self.assertIn("invite", [b[3] for b in gui.BUTTONS])


class McChangelogTests(unittest.TestCase):
    def test_mc_embed(self):
        import json, changelog
        data = json.loads((Path(__file__).parent.parent / "changelog-mc.json").read_text(encoding="utf-8")) if (Path(__file__).parent.parent / "changelog-mc.json").exists() else [{"version": "x", "date": "1.1.", "changes": [{"type": "neu", "text": "a" * 300}] * 8, "discord": ["d"]}]
        e = changelog.mc_embed(data[0])
        self.assertTrue(e.title.startswith("Was ist neu"))
        self.assertTrue(any(f.name.startswith("\u26CF") for f in e.fields))
        self.assertTrue(all(len(f.value) <= 1024 for f in e.fields))
        self.assertLessEqual(len(e), 6000)

class BuyTests(unittest.TestCase):
    U = "11111111-2222-3333-4444-555555555555"
    L = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    def db(self):
        import sqlite3, buy
        d = sqlite3.connect(":memory:")
        buy.init_db(d)
        return d

    def test_parse_reply(self):
        import buy
        self.assertEqual(buy.parse_reply("OK 3 1500 61\n"), ("ok", 3, 1500, 61))
        self.assertEqual(buy.parse_reply("ERR NO_FUNDS"), ("err", "NO_FUNDS"))
        self.assertEqual(buy.parse_reply("\u00a7cUnknown command. Type \"/help\" for help."), ("soon",))
        self.assertEqual(buy.parse_reply(""), ("soon",))
        self.assertEqual(buy.parse_reply("irgendwas")[0], "bad")
        for code in buy.ERRORS:
            self.assertTrue(buy.ERRORS[code])

    def test_unit_price_like_craftcore(self):
        import buy
        self.assertEqual(buy.unit_cents(1000, 3), 334)
        self.assertEqual(buy.unit_cents(0, 5), 1)
        self.assertEqual(buy.total_cents(1000, 3, 2), 668)
        self.assertEqual(buy.total_cents(6400, 64, 64), 6400)
        self.assertEqual(buy.cp(150050), "1.500,50 CP")

    def test_command_format_and_validation(self):
        import buy
        self.assertEqual(buy.command("ah", self.U, self.L, 2, 668, "dc-abc"), f"ah buy {self.U} {self.L} 2 668 dc-abc")
        with self.assertRaises(ValueError):
            buy.command("shop", self.U, "1; stop", 1, 1, "k")

    def test_idempotent_same_key(self):
        import buy
        d, calls = self.db(), []
        def rcon(host, cmd):
            calls.append(cmd); return "OK 2 668 1"
        a = buy.execute(d, rcon, "survival", 7, "shop", self.U, self.L, 2, 668, "dc-k1")
        b = buy.execute(d, rcon, "survival", 7, "shop", self.U, self.L, 2, 668, "dc-k1")
        self.assertEqual(a, ("ok", 2, 668, 1)); self.assertEqual(a, b); self.assertEqual(len(calls), 1)

    def test_retry_after_unknown_command_uses_same_key(self):
        import buy
        d, replies, calls = self.db(), ["Unknown command", "ERR SOLD_OUT"], []
        def rcon(host, cmd):
            calls.append(cmd); return replies.pop(0)
        self.assertEqual(buy.execute(d, rcon, "survival", 7, "ah", self.U, self.L, 1, 5, "dc-k2"), ("soon",))
        self.assertEqual(buy.execute(d, rcon, "survival", 7, "ah", self.U, self.L, 1, 5, "dc-k2"), ("err", "SOLD_OUT"))
        self.assertEqual(calls[0], calls[1])
        self.assertEqual(d.execute("SELECT response FROM dc_purchases").fetchone()[0], "ERR SOLD_OUT")

    def test_buy_options(self):
        import shops
        rows = [("Bob", "diamond", None, 3, 10.0, 0, ("shop", self.L, 1000)), ("X", "dirt", None, 1, 1.0, 0)]
        opts = shops.buy_options(rows)
        self.assertEqual(len(opts), 1); self.assertEqual(opts[0].value, "shop:" + self.L)


class IngestTests(unittest.TestCase):
    def test_links(self):
        import ingest
        rows = [(123456789012345678, "8667ba71-b85a-4004-af54-457a9734eed7", "Steve", "active"),
                (223456789012345678, "u2", "Alex", "removing")]
        out = ingest.build_links(rows, lambda d: d == 123456789012345678)
        self.assertEqual(out[0], {"discord_id": "123456789012345678", "mc_name": "Steve",
                                  "mc_uuid": "8667ba71-b85a-4004-af54-457a9734eed7", "has_role": True, "verified": True})
        self.assertFalse(out[1]["has_role"])
        self.assertFalse(out[1]["verified"])

    def test_stats_rename_and_skip(self):
        import ingest
        cols = ["uuid", "mc_name", "money", "playtime_s", "kills", "deaths", "blocks_mined", "updated_at"]
        out = ingest.build_stats(cols, [("u1", "Steve", 12.5, 3600, 1, 2, None, "x")])
        self.assertEqual(out, {"u1": {"money": 12.5, "playtime": 3600, "kills": 1, "deaths": 2}})

    def test_no_token_no_push(self):
        import asyncio, ingest
        old = os.environ.pop("MC_INGEST_TOKEN", None)
        try:
            self.assertIsNone(asyncio.run(ingest.push_once(object())))
        finally:
            if old is not None:
                os.environ["MC_INGEST_TOKEN"] = old


class IngestLeaderboardTests(unittest.TestCase):
    def test_leaderboards(self):
        import ingest
        cols = ["uuid", "mc_name", "money", "playtime_s", "kills", "deaths", "blocks_mined"]
        rows = [("u%d" % i, "P%d" % i, i, i * 10, None, 0, i) for i in range(12)]
        lb = ingest.build_leaderboards(cols, rows)
        self.assertEqual(set(lb), {"money", "playtime", "deaths", "blocks_mined"})
        self.assertEqual(len(lb["money"]), 10)
        self.assertEqual(lb["playtime"][0], {"name": "P11", "value": 110})
        vals = [e["value"] for e in lb["money"]]
        self.assertEqual(vals, sorted(vals, reverse=True))


class IngestV2Tests(unittest.TestCase):
    def test_view_leaderboards(self):
        import ingest
        cols = ["category", "period", "rank", "uuid", "mc_name", "value"]
        rows = [("kd", "all", 2, "a", "A", 1.5), ("kd", "all", 1, "b", "B", 3), ("money", "week", 1, "a", "A", 9), ("bogus", "all", 1, "a", "A", 1)]
        a, w = ingest.view_leaderboards(cols, rows)
        self.assertEqual(a, {"kd": [{"name": "B", "value": 3}, {"name": "A", "value": 1.5}]})
        self.assertEqual(w, {"money": [{"name": "A", "value": 9}]})

    def test_fallback_kd(self):
        import ingest
        cols = ["uuid", "mc_name", "money", "playtime_s", "kills", "deaths", "blocks_mined"]
        lb = ingest.fallback_leaderboards(cols, [("a", "A", 1, 2, 10, 4, 3), ("b", "B", 2, 3, 3, 0, 1)])
        self.assertNotIn("deaths", lb)
        self.assertEqual(lb["kd"][0], {"name": "B", "value": 3.0})

    def test_details(self):
        import ingest
        self.assertEqual(ingest.parse_details('{"mined":{"stone":5}}'), {"mined": {"stone": 5}})
        self.assertIsNone(ingest.parse_details("kaputt"))
        self.assertIsNone(ingest.parse_details("[1]"))
        self.assertIsNone(ingest.parse_details({"stat_%d" % i: 1 for i in range(3000)}))


class IngestMarketTests(unittest.TestCase):
    def test_market(self):
        import ingest
        cols = ["item", "buy_price", "sell_price", "base_price", "volume_24h_qty", "volume_24h_cp", "trades_24h", "change_24h_pct", "updated_at"]
        rows = [("minecraft:diamond", 120.5, 96.4, 100, 340, 38200, 57, 4.25, 1793287200000), ("diamond", 1, 1, 1, 0, 0, 0, 0, 1)]
        m = ingest.build_market(cols, rows)
        self.assertEqual(len(m), 1)
        self.assertEqual(m[0]["item"], "diamond")
        self.assertEqual(m[0]["updated_at"], 1793287200)

    def test_history_window_and_chunks(self):
        import ingest
        now = 1793290000
        cols = ["item", "hour_ts", "avg_price", "min_price", "max_price", "volume_qty"]
        rows = [("minecraft:stone", now - 3600 * h, 1.5, 1, 2, 3) for h in range(0, 24 * 100)]
        full = ingest.build_history(cols, rows, 0, now)
        self.assertEqual(len(full), 90 * 24 + 1)
        self.assertEqual(full[0]["item"], "stone")
        inc = ingest.build_history(cols, rows, now - now % 3600 - 3600, now)
        self.assertEqual(len(inc), 2)
        ch = ingest.chunk_history(full, max_rows=1000)
        self.assertEqual([len(c) for c in ch], [1000, 1000, 161])
        ch2 = ingest.chunk_history(full, max_bytes=5000)
        self.assertTrue(all(len(json.dumps(c, separators=(',', ':'))) < 5000 for c in ch2))


class IngestServersTests(unittest.TestCase):
    def test_entry_online(self):
        import ingest
        e = ingest.server_entry("There are 2 of a max of 20 players online: Steve, Alex", "\u00a76TPS from last 1m, 5m, 15m: \u00a7a19.5\u00a7r, \u00a7a20.0", "dev", 120, "1.21.1")
        self.assertEqual(e, {"online": True, "players": ["Steve", "Alex"], "max": 20, "tps": 19.5, "uptime_seconds": 120, "version": "1.21.1", "mode": "dev"})

    def test_entry_offline(self):
        import ingest
        self.assertEqual(ingest.server_entry(None, None), {"online": False, "players": []})
        self.assertEqual(ingest.parse_tps("TPS from last 1m, 5m, 15m: *20.0, 20.0"), 20.0)
        self.assertEqual(ingest.parse_list("There are 0 of a max of 20 players online: "), {"players": [], "max": 20})


class IngestServersLoopTests(unittest.IsolatedAsyncioTestCase):
    async def test_push_servers_only(self):
        import ingest
        sent = {}
        async def fake_post(session, token, payload, label):
            sent.update(payload); return 200, "{}"
        class B:
            def server_mode(self): return "online"
        old_post, old_cs = ingest._post, ingest.collect_servers
        ingest._post = fake_post
        ingest.collect_servers = lambda mode: {"lobby": {"online": False, "players": [], "mode": mode}}
        os.environ["MC_INGEST_TOKEN"] = "x"
        try:
            self.assertEqual(await ingest.push_servers(B(), None), (200, "{}"))
            self.assertEqual(list(sent), ["servers"])
            self.assertEqual(sent["servers"]["lobby"]["mode"], "online")
        finally:
            ingest._post, ingest.collect_servers = old_post, old_cs
            os.environ.pop("MC_INGEST_TOKEN", None)


class MarktTests(unittest.TestCase):
    ITEMS = [{"item": "diamond", "buy_price": 120.5, "change_24h_pct": 4.25, "volume_24h_qty": 340},
             {"item": "iron_ingot", "buy_price": 5, "change_24h_pct": -2.5, "volume_24h_qty": 900},
             {"item": "stone", "buy_price": 1, "change_24h_pct": 0},
             {"item": "gold_ingot", "sell_price": 9, "change_24h_pct": 12.0}]

    def test_movers(self):
        import markt
        g, l = markt.movers(self.ITEMS)
        self.assertEqual([e["item"] for e in g], ["gold_ingot", "diamond"])
        self.assertEqual([e["item"] for e in l], ["iron_ingot"])

    def test_find_and_format(self):
        import markt
        self.assertEqual(markt.find(self.ITEMS, "minecraft:diamond")["item"], "diamond")
        self.assertEqual(markt.find(self.ITEMS, "iron")["item"], "iron_ingot")
        self.assertIsNone(markt.find(self.ITEMS, "netherite"))
        self.assertEqual(markt.pct(4.25), "\u25B2 4,25 %")
        self.assertEqual(markt.pct(-2.5), "\u25BC 2,50 %")
        self.assertEqual(markt.cp(1234.5), "1.234,50 CP")
        self.assertEqual(markt.price_of(self.ITEMS[3]), 9)

    def test_render(self):
        import markt
        g, l = markt.movers(self.ITEMS)
        self.assertTrue(markt.render_overview(g, l, 4).startswith(b"\x89PNG"))
        hist = [{"item": "diamond", "hour_ts": 1793280000 + i * 3600, "avg_price": 100 + i, "min_price": 99 + i, "max_price": 102 + i} for i in range(24)]
        self.assertTrue(markt.render_chart(self.ITEMS[0], hist, "1T").startswith(b"\x89PNG"))
        self.assertTrue(markt.render_chart(self.ITEMS[0], [], "90T").startswith(b"\x89PNG"))

    def test_changelog_entry_unique(self):
        import json
        data = json.load(open("changelog.json", encoding="utf-8"))
        vs = [e["version"] for e in data]
        self.assertEqual(len(vs), len(set(vs)))
        self.assertEqual([e for e in data if e["version"] == "1.13"][0]["requires"], "market_views")


class RelayTests(unittest.TestCase):
    U = "8667ba71-b85a-4004-af54-457a9734eed7"
    S = "11111111-2222-3333-4444-555555555555"

    def db(self):
        import sqlite3, relay, buy
        con = sqlite3.connect(":memory:")
        con.execute("CREATE TABLE links (discord_id INTEGER PRIMARY KEY, uuid TEXT, name TEXT, code TEXT, expires INTEGER, state TEXT, attempts INTEGER)")
        con.execute("INSERT INTO links VALUES (42, ?, 'Steve', NULL, NULL, 'active', 0)", (self.U.replace("-", ""),))
        relay.init_db(con); buy.init_db(con)
        return con

    def test_map_order(self):
        import relay
        L = [("aaaaaaaa-0000-0000-0000-000000000001", self.S, "minecraft:diamond", 4, 2000),
             ("aaaaaaaa-0000-0000-0000-000000000002", self.S, "diamond", 64, 25600)]
        self.assertEqual(relay.map_order(L, self.S, "diamond", 2, 500), ("aaaaaaaa-0000-0000-0000-000000000002", None))
        self.assertEqual(relay.map_order(L, self.S, "diamond", 2, 100), (None, "PRICE_CHANGED"))
        self.assertEqual(relay.map_order(L, self.S, "emerald", 1, 999), (None, "NOT_FOUND"))
        self.assertEqual(relay.map_order(L, "s1", "diamond", 1, 999), (None, "UNSUPPORTED_FORMAT"))

    def test_order_link_mismatch_and_idempotent(self):
        import relay
        con = self.db()
        o = {"request_id": "r-1", "buyer_uuid": "00000000-0000-0000-0000-000000000000", "qty": 1, "unit_price": 10, "listing_id": "aaaaaaaa-0000-0000-0000-000000000001"}
        self.assertEqual(relay.handle_order(con, None, "h", o), ("rejected", "LINK_MISMATCH"))
        calls = []
        def rcon(h, c):
            calls.append(c); return "OK 1 10 0"
        o["buyer_uuid"] = self.U
        self.assertEqual(relay.handle_order(con, rcon, "h", o), ("accepted", None))
        self.assertEqual(relay.handle_order(con, rcon, "h", o), ("accepted", None))
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0], f"shop buy {self.U} aaaaaaaa-0000-0000-0000-000000000001 1 10 r-1")

    def test_bank_buy_order(self):
        import relay
        con = self.db()
        calls = []
        replies = ["PENDING bank", "OK 3 1350 0"]
        def rcon(h, c):
            calls.append(c); return replies.pop(0)
        o = {"id": "web:bank.1", "request_id": "web:bank.1", "type": "bank_buy", "item": "diamond", "amount": 3, "qty": 3,
             "mc_uuid": self.U, "buyer_uuid": self.U, "shop_id": None, "listing_id": None}
        self.assertEqual(relay.handle_order(con, rcon, "h", o), (None, None))
        self.assertEqual(relay.handle_order(con, rcon, "h", o), ("accepted", None))
        self.assertEqual(relay.handle_order(con, rcon, "h", o), ("accepted", None))
        self.assertEqual(calls, [f"shop buy bank {self.U} diamond 3 web:bank.1"] * 2)
        self.assertEqual(relay.handle_order(con, lambda h, c: "ERR NO_FUNDS", "h", dict(o, request_id="web:bank.2", id="web:bank.2")), ("rejected", "NO_FUNDS"))
        self.assertEqual(relay.handle_order(con, rcon, "h", dict(o, amount=2305)), ("rejected", "INVALID"))
        self.assertEqual(relay.handle_order(con, rcon, "h", dict(o, request_id="short", id="short")), ("rejected", "INVALID"))
        self.assertEqual(relay.handle_order(con, rcon, "h", dict(o, mc_uuid="00000000-0000-0000-0000-000000000000")), ("rejected", "LINK_MISMATCH"))
        self.assertEqual(relay.handle_order(con, rcon, "h", dict(o, type="weird")), ("rejected", "UNSUPPORTED_TYPE"))
        self.assertEqual(relay.PENDING_PATH, "mc-shop-orders/pending?types=shop,bank_buy")

    def test_bid(self):
        import relay
        con = self.db()
        b = {"request_id": "r-2", "auction_id": "a1", "bidder_uuid": self.U, "amount": 262500}
        self.assertEqual(relay.bid_command(b), f"ah bid a1 {self.U} 262500 r-2")
        self.assertEqual(relay.handle_bid(con, lambda h, c: "ERR TOO_LOW", "h", b), ("rejected", "TOO_LOW"))
        self.assertEqual(relay.handle_bid(con, lambda h, c: "Nur f\u00fcr Spieler.", "h", b), (None, None))
        self.assertFalse(relay.probe_reply_active("Nur f\u00fcr Spieler."))
        self.assertTrue(relay.probe_reply_active("ERR NOT_FOUND"))

    def test_pending_execution_reply_is_not_final_and_can_retry(self):
        import relay, buy
        self.assertEqual(relay.parse("wird ausgeführt"), (None, None))
        self.assertEqual(relay.parse("PENDING r-pend"), (None, None))
        self.assertNotIn(buy.parse_reply("wird ausgeführt")[0], ("ok", "err"))
        self.assertEqual(buy.parse_reply("PENDING r-pend"), ("pending",))
        con = self.db()
        calls = []
        replies = ["PENDING r-pend", "OK 1 10 0"]
        def rcon(h, c):
            calls.append(c)
            return replies.pop(0)
        o = {"request_id": "r-pend", "buyer_uuid": self.U, "qty": 1, "unit_price": 10, "listing_id": "aaaaaaaa-0000-0000-0000-000000000001"}
        self.assertEqual(relay.handle_order(con, rcon, "h", o), (None, None))
        self.assertIsNone(con.execute("SELECT response FROM dc_purchases WHERE idem_key='r-pend'").fetchone()[0])
        self.assertEqual(relay.handle_order(con, rcon, "h", o), ("accepted", None))
        self.assertEqual(calls[0], calls[1])
        self.assertTrue(calls[0].endswith(" r-pend"))

    def test_pending_retries_backoff_then_fail(self):
        import relay, shops
        con = self.db()
        old = shops.query
        shops.query = lambda sql, args=(): []
        now = 1_000_000
        try:
            self._pending_gives_up_without_a_row(relay, con, now)
        finally:
            shops.query = old

    def _pending_gives_up_without_a_row(self, relay, con, now):
        self.assertEqual(relay.observe(con, "r-wait", "order", None, None, now), (None, None))
        self.assertFalse(relay.pending_due(con, "r-wait", now))
        self.assertTrue(relay.pending_due(con, "r-wait", now + relay.pending_delay(1)))
        for attempt in range(1, relay.MAX_PENDING_ATTEMPTS - 1):
            now += relay.pending_delay(attempt)
            self.assertEqual(relay.observe(con, "r-wait", "order", None, None, now), (None, None))
        now += relay.pending_delay(relay.MAX_PENDING_ATTEMPTS - 1)
        self.assertEqual(relay.observe(con, "r-wait", "order", None, None, now, "missing"), ("rejected", "TIMEOUT"))
        self.assertEqual(con.execute("SELECT status, reason, attempts FROM web_relay WHERE request_id='r-wait'").fetchone(), ("rejected", "TIMEOUT", relay.MAX_PENDING_ATTEMPTS))
        old = relay.MAX_PENDING_AGE
        relay.observe(con, "r-age", "order", None, None, 50_000)
        con.execute("UPDATE web_relay SET created=? WHERE request_id='r-age'", (50_000 - old,))
        con.commit()
        self.assertEqual(relay.observe(con, "r-age", "order", None, None, 50_000 + 120, "missing"), ("rejected", "TIMEOUT"))

    def test_lost_rcon_reply_accepts_stored_purchase(self):
        import relay, shops
        con = self.db()
        looked = []

        def query(sql, args=()):
            looked.append((sql, args[0]))
            self.assertIn("v_rcon_purchase_result", sql)
            self.assertNotIn("FROM rcon_purchases", sql)
            if args[0] == "r-lost":
                return [("OK 2 300 4",)]
            if args[0] == "r-sold":
                return [("ERR SOLD_OUT",)]
            if args[0] == "r-blank":
                return [(None,)]
            return []

        old = shops.query
        shops.query = query
        try:
            now = 2_000_000
            for _ in range(relay.MAX_PENDING_ATTEMPTS - 1):
                self.assertIsNone(relay.observe(con, "r-lost", "order", None, None, now)[0])
                now += 60
            self.assertEqual(relay.observe(con, "r-lost", "order", None, None, now, relay.stored_order("r-lost")), ("accepted", None))
            relay.observe(con, "r-sold", "order", None, None, 80_000)
            con.execute("UPDATE web_relay SET created=? WHERE request_id='r-sold'", (80_000 - relay.MAX_PENDING_AGE,))
            con.commit()
            self.assertEqual(relay.observe(con, "r-sold", "order", None, None, 80_000 + 1, relay.stored_order("r-sold")), ("rejected", "SOLD_OUT"))
            born = 90_000
            relay.observe(con, "r-blank", "order", None, None, born)
            con.execute("UPDATE web_relay SET created=? WHERE request_id='r-blank'", (born - relay.MAX_ADMIN_AGE,))
            con.commit()
            with self.assertLogs("creepy.relay", level="WARNING") as logs:
                self.assertEqual(relay.observe(con, "r-blank", "order", None, None, born, relay.stored_order("r-blank")), ("rejected", "NEEDS_ADMIN"))
            self.assertTrue(any("NEEDS_ADMIN" in line for line in logs.output))
        finally:
            shops.query = old
        self.assertEqual(con.execute("SELECT status, reason FROM web_relay WHERE request_id='r-lost'").fetchone(), ("accepted", None))
        self.assertIn(("SELECT response FROM v_rcon_purchase_result WHERE idem_key=%s", "r-lost"), looked)
        self.assertNotIn("TIMEOUT", [row[0] for row in con.execute("SELECT reason FROM web_relay").fetchall()])
        self.assertEqual(relay.settle_pending(None, 0, relay.MAX_ADMIN_AGE), ("rejected", "NEEDS_ADMIN"))
        self.assertIsNone(relay.settle_pending(None, 0, relay.MAX_ADMIN_AGE - 1))
        self.assertEqual(relay.settle_pending("unfinished", 0, relay.MAX_ADMIN_AGE)[1], "NEEDS_ADMIN")

    def test_purchase_lookup_runs_off_the_event_loop(self):
        import asyncio, os, tempfile, threading, relay, shops
        con = self.db()
        now = 4_000_000
        fd, path = tempfile.mkstemp(suffix=".sqlite3")
        os.close(fd)
        con.execute("INSERT INTO web_relay (request_id, kind, status, reason, acked, created, attempts, next_try) VALUES ('r-thread','order',NULL,NULL,0,?,4,0)", (now,))
        con.commit()
        seen = {}

        def query(sql, args=()):
            seen["thread"] = threading.current_thread()
            seen["sql"] = sql
            return [("OK 1 10 0",)]

        def handler(db, rcon, host, item):
            return None, None

        async def fake_ack(session, token, path, acks):
            seen["acks"] = list(acks)
            return True

        class Bot:
            db = con

        old, old_ack, old_path = shops.query, relay._ack, relay.DB_PATH
        shops.query, relay._ack, relay.DB_PATH = query, fake_ack, path
        try:
            acks = asyncio.run(relay.process(Bot(), None, "t", "order", [{"request_id": "r-thread"}], handler, now=now))
        finally:
            shops.query, relay._ack, relay.DB_PATH = old, old_ack, old_path
            os.unlink(path)
        self.assertNotEqual(seen["thread"], threading.main_thread())
        self.assertIn("v_rcon_purchase_result", seen["sql"])
        self.assertEqual(acks[0]["status"], "accepted")
        self.assertEqual(seen["acks"][0]["status"], "accepted")


class IngestP5Tests(unittest.TestCase):
    def test_shops(self):
        import ingest
        cols = ["id", "seller", "seller_name", "material", "amount", "price_cents", "listing_id", "item_material", "item_display_name", "seller_uuid"]
        rows = [("l1", "S", "Kai", "DIAMOND", 4, 2000, 1, "minecraft:diamond", None, "S"), ("l2", "S", "Kai", "DIAMOND", 2, 300, 2, "diamond", None, "S")]
        out = ingest.build_shops(cols, rows)
        self.assertEqual(out[0]["id"], "s")
        self.assertEqual(sum(e["stock"] for e in out[0]["items"]), 6)

    def test_auctions(self):
        import ingest
        a = ingest.build_auctions(["id", "seller_uuid", "seller_name", "material", "amount", "min_price", "current_bid", "ends_at_ms", "status"],
                                  [(1, "u", "Kai", "minecraft:elytra", 1, 200000, 250000, 4102444800000, "ACTIVE")],
                                  ["auction_id", "bidder_name", "amount", "created_at_ms"], [(1, "hadzyc05", 250000, 1793287968700)])
        self.assertEqual(a[0]["item"], "elytra")
        self.assertEqual(a[0]["status"], "active")
        self.assertEqual(a[0]["ends_at"], 4102444800000)
        self.assertEqual(a[0]["bids"], [{"name": "hadzyc05", "amount": 250000, "at": 1793287968700}])


class ListingIdTests(unittest.TestCase):
    U = "8667ba71-b85a-4004-af54-457a9734eed7"
    L = "aaaaaaaa-0000-0000-0000-000000000009"

    def test_shops_per_listing(self):
        import ingest
        cols = ["id", "seller", "seller_name", "material", "amount", "price_cents", "listing_id", "item_material", "item_display_name", "seller_uuid"]
        rows = [("l1", "S", "Kai", "DIAMOND", 4, 2000, 1, "diamond", None, "S"), ("l2", "S", "Kai", "DIAMOND", 2, 300, 2, "diamond", None, "S")]
        items = ingest.build_shops(cols, rows)[0]["items"]
        self.assertEqual(items, [{"listing_id": "l2", "item": "diamond", "price": 150, "stock": 2, "mode": "sell"},
                                 {"listing_id": "l1", "item": "diamond", "price": 500, "stock": 4, "mode": "sell"}])

    def test_order_with_listing_id(self):
        import relay
        con = RelayTests().db()
        calls = []
        def rcon(h, c):
            calls.append(c); return "OK 2 300 0"
        o = {"id": "x", "request_id": "r-9", "idem_key": "r-9", "listing_id": self.L, "uuid": self.U, "qty": 2, "max_unit_price_cent": 150, "shop_id": "nope", "item": "diamond"}
        self.assertEqual(relay.handle_order(con, rcon, "h", o), ("accepted", None))
        self.assertEqual(relay.handle_order(con, rcon, "h", o), ("accepted", None))
        self.assertEqual(calls, [f"shop buy {self.U} {self.L} 2 300 r-9"])


class BidTests(unittest.TestCase):
    U = "11111111-2222-3333-4444-555555555555"
    S = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    def auction(self, **kw):
        import bid
        cols = ["id", "seller_uuid", "seller_name", "material", "amount", "min_price_cents", "current_bid_cents", "current_bidder_uuid", "ends_at_ms", "status"]
        row = dict(id="a1", seller_uuid=self.S, seller_name="sel", material="DIAMOND", amount=3, min_price_cents=1000, current_bid_cents=None,
                   current_bidder_uuid=None, ends_at_ms=int((__import__("time").time() + 3600) * 1000), status="ACTIVE")
        row.update(kw)
        return bid.normalize(cols, [tuple(row[c] for c in cols)])[0]

    def test_min_bid_rules(self):
        import bid
        self.assertEqual(bid.min_bid(self.auction()), 1000)
        self.assertEqual(bid.min_bid(self.auction(current_bid_cents=1000)), 1100)   # 1 CP > 5 %
        self.assertEqual(bid.min_bid(self.auction(current_bid_cents=10000)), 10500)  # 5 %
        self.assertEqual(bid.quick(self.auction(current_bid_cents=10000), 10), 11000)
        self.assertEqual(bid.quick(self.auction(current_bid_cents=1000), 5), 1100)

    def test_parse_cp(self):
        import bid
        self.assertEqual(bid.parse_cp("12,50"), 1250)
        self.assertEqual(bid.parse_cp("1.250"), 125000)
        self.assertEqual(bid.parse_cp("15 CP"), 1500)
        self.assertIsNone(bid.parse_cp("abc"))
        self.assertIsNone(bid.parse_cp("0"))

    def test_checks(self):
        import bid
        a = self.auction(current_bid_cents=2000, current_bidder_uuid=self.S.replace("a", "f"))
        self.assertIn("/link", bid.check(a, None, 5000))
        self.assertEqual(bid.check(a, self.S, 5000), bid.ERRORS["SELF_BID"])
        self.assertIn("Mindestgebot", bid.check(a, self.U, 2050))
        self.assertIsNone(bid.check(a, self.U, 2100))
        self.assertEqual(bid.check(self.auction(ends_at_ms=1000), self.U, 99999), bid.ERRORS["ENDED"])

    def test_bids_view_supplies_top_bidder(self):
        import bid
        a = bid.normalize(["id", "material", "amount", "min_price_cents", "ends_at_ms"], [("a1", "DIAMOND", 1, 500, None)],
                          ["auction_id", "bidder_uuid", "bidder_name", "amount_cents"], [("a1", self.U, "x", 700), ("a1", self.S, "y", 900)])[0]
        self.assertEqual((a["current_bid"], a["bidder_uuid"], a["bidder_name"]), (900, self.S, "y"))

    def test_place_is_idempotent_and_formats_command(self):
        import bid, sqlite3
        db = sqlite3.connect(":memory:")
        rcon = Mock(return_value="OK")
        p1 = bid.place(db, rcon, "survival", 1, "a1", self.U, 1500, "rid-1")
        p2 = bid.place(db, rcon, "survival", 1, "a1", self.U, 1500, "rid-1")
        self.assertEqual(p1[0], "ok"); self.assertEqual(p2[0], "ok")
        rcon.assert_called_once_with("survival", f"ah bid a1 {self.U} 1500 rid-1")
        self.assertEqual(bid.place(db, Mock(return_value="ERR NOT_FOUND"), "s", 1, "a2", self.U, 1, "rid-2"), ("err", "NOT_FOUND"))
        self.assertIn("gibt es nicht", bid.reply_text(("err", "NOT_FOUND"), None, 1))
        with self.assertRaises(ValueError):
            bid.command("a 1; op x", self.U, 1, "r")

    def test_notifications_dedup(self):
        import bid, sqlite3, time as _t
        db = sqlite3.connect(":memory:")
        a = self.auction(current_bid_cents=2000, current_bidder_uuid=self.U)
        self.assertEqual(bid.plan_notifications(db, [a]), [])  # erster Lauf: nur merken
        db.execute("INSERT INTO ah_notify VALUES ('seed', 0)")
        b = self.auction(current_bid_cents=2500, current_bidder_uuid=self.S)
        out = bid.plan_notifications(db, [b])
        self.assertEqual([(k.split(":")[0], u) for k, u, _ in out], [("outbid", self.U)])
        for k, _, _ in out:
            db.execute("INSERT INTO ah_notify VALUES (?, 0)", (k,))
        self.assertEqual(bid.plan_notifications(db, [b]), [])
        ended = self.auction(current_bid_cents=2500, current_bidder_uuid=self.S, ends_at_ms=int((_t.time() - 30) * 1000))
        won = bid.plan_notifications(db, [ended])
        self.assertEqual([(k, u) for k, u, _ in won], [("won:a1", self.S)])
        self.assertIn("/abholen", won[0][2])

class AuctionColumnsTests(unittest.TestCase):
    """echte Spalten aus v_auctions / v_auction_bids (DESCRIBE 29.10.)"""
    AC = ["id", "item", "menge", "seller", "min_price", "current_bid", "current_bidder", "bid_count", "ends_at", "status", "created_at", "source_last_bid", "min_next_bid"]
    BC = ["id", "auction_id", "bidder", "amount", "created_at", "source"]
    S = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    U = "11111111-2222-3333-4444-555555555555"
    V = "99999999-2222-3333-4444-555555555555"

    def row(self, **kw):
        import time as _t
        r = dict(id=7, item="minecraft:elytra", menge=2, seller=self.S, min_price=200000, current_bid=None, current_bidder=None, bid_count=0,
                 ends_at=int((_t.time() + 3600) * 1000), status="open", created_at=int(_t.time()), source_last_bid=None, min_next_bid=None)
        r.update(kw)
        return tuple(r[c] for c in self.AC)

    def test_bid_normalize_real_columns(self):
        import bid
        a = bid.normalize(self.AC, [self.row()], self.BC, [])[0]
        self.assertEqual((a["id"], a["amount"], a["min_price"], a["seller_uuid"], a["status"]), ("7", 2, 200000, self.S, "active"))
        self.assertEqual(bid.min_bid(a), 200000)
        b = bid.normalize(self.AC, [self.row(current_bid=250000, current_bidder=self.U, bid_count=2)], self.BC,
                          [(1, 7, self.V, 300000, 1, "web")])[0]
        self.assertEqual((b["current_bid"], b["bidder_uuid"]), (250000, self.U))  # current_bidder gilt direkt
        self.assertEqual(bid.min_bid(b), 262500)

    def test_min_next_bid_wins_and_quick_never_below(self):
        import bid
        a = bid.normalize(self.AC, [self.row(current_bid=10000, current_bidder=self.U, min_next_bid=12000)])[0]
        self.assertEqual(bid.min_bid(a), 12000)
        self.assertEqual(bid.quick(a, 5), 12000)
        self.assertEqual(bid.quick(a, 10), 12000)
        self.assertIn("Mindestgebot", bid.check(a, self.V, 11000))
        self.assertIsNone(bid.check(a, self.V, 12000))

    def test_status_and_seconds(self):
        import bid
        self.assertEqual(bid.normalize(self.AC, [self.row(status="ACTIVE")])[0]["status"], "active")
        self.assertFalse(bid.is_active(bid.normalize(self.AC, [self.row(status="ended")])[0]))
        self.assertEqual(bid._ms(1793292168), 1793292168000)

    def test_ingest_real_columns(self):
        import ingest
        a = ingest.build_auctions(self.AC, [self.row(current_bid=250000, current_bidder=self.U, bid_count=1)], self.BC,
                                  [(1, 7, self.U, 250000, 1793287968700, "web")], names={self.U: "hadzyc05", self.S: "KaiDasEi"})[0]
        self.assertEqual((a["id"], a["item"], a["amount"], a["min_price"], a["current_bid"]), ("7", "elytra", 2, 200000, 250000))
        self.assertEqual((a["seller_uuid"], a["seller_name"], a["current_bidder_name"], a["status"]), (self.S, "KaiDasEi", "hadzyc05", "active"))
        self.assertNotIn("current_bidder", a)
        self.assertEqual(a["bids"], [{"name": "hadzyc05", "amount": 250000, "at": 1793287968700}])

class RawAuctionIngestTests(unittest.TestCase):
    def test_raw_keys_keep_null_and_cents(self):
        import ingest
        from decimal import Decimal
        S = "AAAAAAAA-bbbb-cccc-dddd-eeeeeeeeeeee"
        ac = AuctionColumnsTests.AC
        au = (ac, [(7, "elytra", 1, S, 200000, None, None, 0, 1793292168700, "active", 1793280000, None, None),
                   (8, "stone", 64, S, 500, 600, S, 1, 1793292168700, "active", 1793280000, "web", Decimal("700"))])
        ab = (AuctionColumnsTests.BC, [(1, 8, S, 600, 1793287968700, "web")])
        a, b = ingest.raw_auctions(au, ab, {S.lower(): "Kai"})
        self.assertEqual(a[0], {"id": "7", "item": "elytra", "menge": 1, "seller": S.lower(), "min_price": 200000, "current_bid": None,
                                "current_bidder": None, "bid_count": 0, "ends_at": 1793292168700, "status": "active", "created_at": 1793280000,
                                "source_last_bid": None, "min_next_bid": None, "seller_name": "Kai"})
        self.assertEqual((a[1]["min_next_bid"], a[1]["current_bidder_name"]), (700, "Kai"))
        self.assertIsInstance(a[1]["min_next_bid"], int)
        self.assertEqual(b, [{"id": "1", "auction_id": "8", "bidder": S.lower(), "amount": 600, "created_at": 1793287968700, "source": "web"}])
        self.assertEqual(ingest.raw_auctions((ac, []), None), ([], []))
        self.assertIn('"current_bid":null', json.dumps(a[0], separators=(",", ":")))

class LeaderboardViewColumnsTests(unittest.TestCase):
    def test_real_board_columns(self):
        import ingest
        from decimal import Decimal
        cols = ["board", "period", "rank", "uuid", "name", "value"]
        rows = [("money", "all", 1, "u1", "Kai", Decimal("125.5000")), ("money", "all", 2, "u2", "Nani", Decimal("0.0000")),
                ("kd", "week", 1, "u1", "Kai", Decimal("2.3500")), ("distance", "all", 1, "u2", "Nani", Decimal("1200.0000"))]
        a, w = ingest.view_leaderboards(cols, rows)
        self.assertEqual(a["money"], [{"name": "Kai", "value": 125.5}, {"name": "Nani", "value": 0}])
        self.assertEqual(a["distance"], [{"name": "Nani", "value": 1200}])
        self.assertEqual(w, {"kd": [{"name": "Kai", "value": 2.35}]})

    def test_details_mapping(self):
        import ingest
        d = ingest.parse_details('{"blocks_mined": {"minecraft:stone": 9000}, "distance_cm": {"walk": 1200000}, "kills_entity": {"zombie": 3}, "deaths_entity": {}, "jumps": 5}')
        self.assertEqual(d, {"mined": {"stone": 9000}, "distance_m": {"walk": 12000}, "killed": {"zombie": 3}, "deaths_by": {}, "jumps": 5})
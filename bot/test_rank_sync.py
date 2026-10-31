import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import bot
import ingest

VIP, MVP, BUILDER = 1565044626736292021, 1566125881540476948, 1565044625511809127
U1, U2 = "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"


class PlanTests(unittest.TestCase):
    def test_role_ids_env_defaults(self):
        with mock.patch.dict(os.environ, {"VIP_ROLE_ID": "", "MVP_ROLE_ID": "", "BUILDER_ROLE_ID": ""}):
            self.assertEqual(bot.sync_role_ids(), {"vip": VIP, "mvp": MVP, "builder": BUILDER})
        with mock.patch.dict(os.environ, {"VIP_ROLE_ID": "42"}):
            self.assertEqual(bot.sync_role_ids()["vip"], 42)

    def test_wanted(self):
        ids = bot.sync_role_ids()
        self.assertEqual(bot.wanted_groups({VIP, BUILDER, 999}, ids), ["builder", "vip"])
        self.assertEqual(bot.wanted_groups(set(), ids), [])

    def test_plan_diff_only(self):
        self.assertEqual(bot.plan_lp(U1, ["vip"], ["builder"]),
                         [f"lp user {U1} parent add vip", f"lp user {U1} parent remove builder"])
        self.assertEqual(bot.plan_lp(U1, ["vip"], ["vip"]), [])

    def test_plan_full_and_top_skip(self):
        full = bot.plan_lp(U1, ["mvp"], None)
        self.assertEqual(full, [f"lp user {U1} parent remove vip", f"lp user {U1} parent add mvp", f"lp user {U1} parent remove builder"])
        self.assertNotIn(f"lp user {U1} parent add mvp", bot.plan_lp(U1, ["mvp"], None, top="mvp"))

    def test_never_admin_default_or_set(self):
        for want in ([], ["vip", "mvp", "builder"]):
            for have in (None, [], ["vip"]):
                for c in bot.plan_lp(U1, want, have):
                    self.assertNotRegex(c, r"admin|default| set |rank")

    def test_log_changes(self):
        self.assertEqual(bot.log_changes([f"lp user {U1} parent add vip", f"lp user {U1} parent remove mvp"], ["vip"]), [])
        self.assertEqual(bot.log_changes([f"lp user {U1} parent remove vip"], ["vip"]), ["remove vip"])


class SyncLpTests(unittest.TestCase):
    def run_sync(self, links, roles, state, force=False, view=None):
        sent = []
        with tempfile.TemporaryDirectory() as d:
            data = Path(d)
            (data / "lp-sync.json").write_text(json.dumps(state))
            import sqlite3
            db = sqlite3.connect(":memory:")
            db.execute("CREATE TABLE links (discord_id INTEGER PRIMARY KEY, uuid TEXT, name TEXT, code TEXT, expires INTEGER, state TEXT, attempts INTEGER)")
            for did, uuid, name in links:
                db.execute("INSERT INTO links VALUES (?,?,?,NULL,NULL,'active',0)", (did, uuid, name))
            members = {did: SimpleNamespace(roles=[SimpleNamespace(id=r) for r in rs]) for did, rs in roles.items()}
            guild = mock.Mock()
            guild.get_member.side_effect = members.get
            client = mock.Mock(db=db, cfg={"GUILD_ID": "1"})
            client.get_guild.return_value = guild
            client.ensure_sync_roles = mock.AsyncMock()
            client.rank_log = mock.AsyncMock()

            async def go():
                with mock.patch.object(bot, "DATA", data), \
                     mock.patch.object(bot, "rcon", lambda h, c: sent.append((h, c))), \
                     mock.patch.object(bot, "player_top_ranks", lambda: view or {}), \
                     mock.patch.object(bot.asyncio, "sleep", mock.AsyncMock()):
                    await bot.Bot.sync_lp(client, force=force)
            asyncio.run(go())
            return sent, json.loads((data / "lp-sync.json").read_text()), client.rank_log.await_args.args[1]

    def test_role_add_and_remove(self):
        sent, st, lines = self.run_sync([(5, U1, "Kai")], {5: [MVP]}, {U1: ["vip"]})
        self.assertEqual([c for _, c in sent], [f"lp user {U1} parent remove vip", f"lp user {U1} parent add mvp"])
        self.assertTrue(all(h == bot.LP_HOST for h, _ in sent))
        self.assertEqual(st, {U1: ["mvp"]})
        self.assertEqual(len(lines), 1)
        self.assertIn("add mvp", lines[0])

    def test_no_change_no_rcon(self):
        sent, st, lines = self.run_sync([(5, U1, "Kai")], {5: [VIP]}, {U1: ["vip"]})
        self.assertEqual((sent, lines), ([], []))

    def test_unlinked_removes_only_bot_groups(self):
        sent, st, lines = self.run_sync([], {}, {U2: ["builder"]})
        self.assertEqual([c for _, c in sent], [f"lp user {U2} parent remove builder"])
        self.assertEqual(st, {})

    def test_full_reconcile_quiet_log(self):
        sent, st, lines = self.run_sync([(5, U1, "Kai")], {5: [VIP]}, {U1: ["vip"]}, force=True, view={U1.replace("-", ""): "vip"})
        self.assertEqual([c for _, c in sent], [f"lp user {U1} parent remove mvp", f"lp user {U1} parent remove builder"])
        self.assertEqual(lines, [])


class RanksIngestTests(unittest.TestCase):
    def test_build_ranks(self):
        cols = ["uuid", "name", "rank", "color", "weight"]
        rows = [(U2, "Nani", "default", "&7", 0), (U1, "Kai", "vip", "&6", 40), (None, "x", "vip", "&6", 1)]
        self.assertEqual(ingest.build_ranks(cols, rows), [
            {"uuid": U1, "name": "Kai", "rank": "vip", "color": "&6", "weight": 40},
            {"uuid": U2, "name": "Nani", "rank": "default", "color": "&7", "weight": 0}])
        self.assertEqual(ingest.build_ranks(["foo"], [(1,)]), [])

    def test_push_ranks_and_fail_soft(self):
        posted = []

        async def fake_post(session, token, payload, label):
            posted.append(payload)
            return 200, "{}"

        async def run():
            ingest._ranks["warned"] = False
            with mock.patch.object(ingest, "_post", fake_post):
                with mock.patch.object(ingest, "fetch_rows", lambda n: None), self.assertLogs("creepy.ingest", "WARNING") as cm:
                    self.assertIsNone(await ingest.push_ranks(None, "t"))
                    self.assertIsNone(await ingest.push_ranks(None, "t"))
                self.assertEqual(len(cm.output), 1)
                with mock.patch.object(ingest, "fetch_rows", lambda n: (["uuid", "name", "rank", "color", "weight"], [(U1, "Kai", "mvp", "&d", 60)])):
                    self.assertTrue(await ingest.push_ranks(None, "t"))
        asyncio.run(run())
        self.assertEqual(posted, [{"player_ranks": [{"uuid": U1, "name": "Kai", "rank": "mvp", "color": "&d", "weight": 60}]}])

    def test_never_empty_list(self):
        posted = []

        async def fake_post(session, token, payload, label):
            posted.append(payload)
            return 200, "{}"

        def raise_(n):
            raise RuntimeError("db weg")

        async def run():
            with mock.patch.object(ingest, "_post", fake_post):
                for fr in (lambda n: None, lambda n: (["uuid", "name", "rank"], []), lambda n: (["foo"], [(1,)])):
                    ingest._ranks["warned"] = False
                    with mock.patch.object(ingest, "fetch_rows", fr):
                        self.assertIsNone(await ingest.push_ranks(None, "t"))
                with mock.patch.object(ingest, "fetch_rows", raise_), \
                     mock.patch.object(ingest, "push_history", mock.AsyncMock(return_value=True)), \
                     mock.patch.object(ingest, "push_economy", mock.AsyncMock()):
                    ingest._hist_push["last"] = None
                    self.assertTrue(await ingest.maybe_push_history(None, "t", now=1.0))
        asyncio.run(run())
        self.assertEqual(posted, [])

    def test_history_survives_rank_error(self):
        async def boom(*a):
            raise RuntimeError("x")

        async def run():
            ingest._hist_push["last"] = None
            with mock.patch.object(ingest, "push_history", mock.AsyncMock(return_value=True)), \
                 mock.patch.object(ingest, "push_economy", mock.AsyncMock()), \
                 mock.patch.object(ingest, "push_ranks", boom):
                return await ingest.maybe_push_history(None, "t", now=1.0)
        self.assertTrue(asyncio.run(run()))


if __name__ == "__main__":
    unittest.main()

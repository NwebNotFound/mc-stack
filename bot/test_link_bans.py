"""#149: /whitelist remove writes a persistent block, re-linking is refused, /invite needs an active link."""
import asyncio
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, Mock, patch

import bot

UUID = "0123456789abcdef0123456789abcdef"
LINKS = "CREATE TABLE links (discord_id INTEGER PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, name TEXT NOT NULL, code TEXT, expires INTEGER, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)"


def fake_bot(db):
    b = Mock()
    b.db, b.lock, b.dm_state = db, asyncio.Lock(), {}
    b.cfg = {"GUILD_ID": "1", "PLAYER_ROLE_ID": "10", "ADMIN_ROLE_ID": "20"}
    b.save = lambda sql, params=(): bot.Bot.save(b, sql, params)
    b.link_banned = lambda did, uuid: bot.Bot.link_banned(b, did, uuid)
    b.remove_pending = Mock()
    b.reconcile = AsyncMock()
    return b


def new_db():
    db = sqlite3.connect(":memory:")
    db.execute(LINKS)
    bot.init_link_bans(db)
    bot.init_link_starts(db)
    return db


class LinkBanTests(unittest.IsolatedAsyncioTestCase):
    async def test_whitelist_remove_blocks_relink(self):
        db = new_db()
        db.execute("INSERT INTO links VALUES (42,?,'Griefer',NULL,NULL,'active',0)", (UUID,))
        b = fake_bot(db)
        b.role = lambda member, key: key == "ADMIN_ROLE_ID"
        bot.register(b)
        group = next(c.args[0] for c in b.tree.add_command.call_args_list if c.args[0].name == "whitelist")
        remove = next(c for c in group.commands if c.name == "remove")
        inter = Mock()
        inter.user.id = 99
        inter.response.defer = AsyncMock()
        inter.followup.send = AsyncMock()
        with patch.object(bot, "mojang", AsyncMock(return_value=(UUID, "Griefer"))), patch.object(bot, "whitelist") as wl:
            await remove.callback(inter, "Griefer")
        wl.assert_called_once_with("remove", UUID)
        self.assertIsNone(db.execute("SELECT 1 FROM links").fetchone())
        self.assertTrue(bot.is_link_banned(db, 42, None))
        self.assertTrue(bot.is_link_banned(db, 7, UUID))

        # same player, new Discord account -> refused; same Discord account, other MC account -> refused
        user = Mock(id=7)
        with patch.object(bot, "mojang", AsyncMock(return_value=(UUID, "Griefer"))):
            ok, text = await bot.Bot.start_link(b, user, "Griefer")
        self.assertFalse(ok); self.assertEqual(text, bot.BANNED_TEXT)
        with patch.object(bot, "mojang", AsyncMock(return_value=("f" * 32, "Alt"))):
            ok, _ = await bot.Bot.start_link(b, Mock(id=42), "Alt")
        self.assertFalse(ok)
        self.assertIsNone(db.execute("SELECT 1 FROM links").fetchone())

    async def test_unbanned_user_can_still_link(self):
        db = new_db()
        b = fake_bot(db)
        with tempfile.TemporaryDirectory() as d, patch.object(bot, "PENDING", Path(d)), \
                patch.object(bot, "mojang", AsyncMock(return_value=(UUID, "Neu"))):
            ok, _ = await bot.Bot.start_link(b, Mock(id=5), "Neu")
        self.assertTrue(ok)
        self.assertEqual(db.execute("SELECT state FROM links WHERE discord_id=5").fetchone()[0], "pending")

    async def test_verify_code_refuses_banned_pending_link(self):
        db = new_db()
        db.execute("INSERT INTO links VALUES (5,?,'Neu','12345678',?,'pending',0)", (UUID, int(time.time()) + 600))
        bot.add_link_ban(db, UUID)
        b = fake_bot(db)
        ok, text = await bot.Bot.verify_code(b, Mock(id=5), "12345678")
        self.assertFalse(ok); self.assertEqual(text, bot.BANNED_TEXT)
        self.assertIsNone(db.execute("SELECT 1 FROM links").fetchone())
        b.reconcile.assert_not_awaited()

    def test_whitelist_add_lifts_ban_and_uuid_format_is_normalised(self):
        db = new_db()
        bot.add_link_ban(db, "01234567-89ab-cdef-0123-456789abcdef", 42)
        self.assertTrue(bot.is_link_banned(db, None, UUID))
        bot.lift_link_ban(db, UUID)
        self.assertFalse(bot.is_link_banned(db, 42, UUID))


class InviteGateTests(unittest.TestCase):
    def test_self_assigned_role_alone_does_not_allow_invites(self):
        import invites
        db = new_db()
        b = Mock(db=db)
        b.role = lambda member, key: key == "PLAYER_ROLE_ID"
        self.assertFalse(invites.allowed(b, Mock(id=5)))
        db.execute("INSERT INTO links VALUES (5,?,'Neu',NULL,NULL,'pending',0)", (UUID,))
        self.assertFalse(invites.allowed(b, Mock(id=5)))
        db.execute("UPDATE links SET state='active'")
        self.assertTrue(invites.allowed(b, Mock(id=5)))
        b.role = lambda member, key: key == "ADMIN_ROLE_ID"
        self.assertTrue(invites.allowed(b, Mock(id=6)))
        b.role = lambda member, key: False
        self.assertFalse(invites.allowed(b, Mock(id=5)))


class WhitelistFallbackTests(unittest.TestCase):
    """#150: vanilla fallback by (possibly stale) name must not count "not whitelisted" as a removal."""
    def run_fallback(self, action, reply):
        def rcon(host, cmd):
            return "LINK_ERROR" if cmd.startswith("linkwhitelist") else reply
        with tempfile.TemporaryDirectory() as d, patch.object(bot, "DATA", Path(d)), patch.object(bot, "rcon", side_effect=rcon):
            con = sqlite3.connect(Path(d) / "links.sqlite3")
            con.execute(LINKS)
            con.execute("INSERT INTO links VALUES (1,?,'OldName',NULL,NULL,'removing',0)", (UUID,))
            con.commit(); con.close()
            bot.whitelist(action, UUID)

    def test_remove_not_whitelisted_is_failure(self):
        with self.assertRaises(RuntimeError):
            self.run_fallback("remove", "Player is not whitelisted")

    def test_remove_and_add_success(self):
        self.run_fallback("remove", "Removed OldName from the whitelist")
        self.run_fallback("add", "Player is already whitelisted")
        with self.assertRaises(RuntimeError):
            self.run_fallback("add", "Removed OldName from the whitelist")


class LinkStartLimitTests(unittest.IsolatedAsyncioTestCase):
    async def test_renewals_and_starts_are_limited(self):
        db = new_db()
        b = fake_bot(db)
        with tempfile.TemporaryDirectory() as d, patch.object(bot, "PENDING", Path(d)):
            with patch.object(bot, "mojang", AsyncMock(return_value=(UUID, "Owner"))):
                for _ in range(bot.MAX_STARTS_PER_PAIR):
                    ok, _ = await bot.Bot.start_link(b, Mock(id=5), "Owner")
                    self.assertTrue(ok)
                ok, text = await bot.Bot.start_link(b, Mock(id=5), "Owner")
                self.assertFalse(ok); self.assertEqual(text, bot.LIMIT_TEXT)
                # the still-open code is kept when the limit hits
                self.assertEqual(db.execute("SELECT state FROM links WHERE discord_id=5").fetchone()[0], "pending")
            others = [("%032x" % i, f"Name{i}") for i in range(1, 10)]
            db.execute("DELETE FROM links")
            results = []
            for u, n in others:
                with patch.object(bot, "mojang", AsyncMock(return_value=(u, n))):
                    results.append((await bot.Bot.start_link(b, Mock(id=6), n))[0])
            self.assertEqual(results.count(True), bot.MAX_STARTS_PER_USER)

    def test_window_expires(self):
        db = new_db()
        for i in range(bot.MAX_STARTS_PER_USER):
            bot.record_link_start(db, 7, "%032x" % i, now=1000)
        self.assertFalse(bot.link_start_allowed(db, 7, UUID, now=1001))
        self.assertTrue(bot.link_start_allowed(db, 7, UUID, now=1000 + bot.LINK_WINDOW + 1))


if __name__ == "__main__":
    unittest.main()

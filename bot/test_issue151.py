"""#151: no blocking DB calls in the loop, single-use bank select, markdown escaping, AH owner check, bid > 0."""
import asyncio
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, Mock, patch

import bank
import bid
import bot
import changelog
import relay
import shops

U = "11111111-2222-3333-4444-555555555555"


class ChangelogThreadTests(unittest.IsolatedAsyncioTestCase):
    async def test_requires_checks_run_off_the_event_loop(self):
        loop_thread = threading.get_ident()
        threads = []

        def query(sql, args=()):
            threads.append(threading.get_ident())
            return [(1,)]
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / "c.json"
            src.write_text(json.dumps([{"version": "9.9", "date": "1.1.2026", "requires": "market_views", "changes": [{"type": "neu", "text": "x"}]}]), encoding="utf-8")
            ch = Mock(); ch.send = AsyncMock()
            b = Mock(); b.get_channel.return_value = ch
            with patch.dict(os.environ, {"CHANGELOG_CHANNEL_ID": "5"}), patch.object(changelog, "SRC", src), \
                    patch.object(changelog, "STATE", Path(d) / "state.json"), patch.object(shops, "query", query):
                n = await changelog.post_new(b)
        self.assertEqual(n, 1)
        self.assertEqual(len(threads), 2)
        self.assertTrue(all(t != loop_thread for t in threads))


class BankSelectTests(unittest.IsolatedAsyncioTestCase):
    async def test_second_choice_does_not_place_another_order(self):
        view = bank.discord.ui.View(timeout=300)
        sel = bank.AmountSelect(Mock(), "iron_ingot")
        view.add_item(sel)
        with patch.object(type(sel), "values", property(lambda s: ["16"])), patch.object(bank, "place", AsyncMock()) as place:
            i1 = Mock(); i1.response.edit_message = AsyncMock()
            await sel.callback(i1)
            i2 = Mock(); i2.response.send_message = AsyncMock()
            await sel.callback(i2)
        place.assert_awaited_once()
        self.assertTrue(view.is_finished())
        self.assertTrue(all(c.disabled for c in view.children))
        i1.response.edit_message.assert_awaited_once()
        self.assertIn("schon benutzt", i2.response.send_message.call_args.args[0])

    async def test_place_after_edit_uses_followup(self):
        b = Mock(); b.db = Mock()
        inter = Mock(); inter.user.id = 1
        inter.response.is_done.return_value = True
        inter.response.defer = AsyncMock(); inter.followup.send = AsyncMock()
        with patch("buy.linked_uuid", return_value=U), patch.object(bank, "submit", AsyncMock(return_value=("err", "nope"))):
            await bank.place(b, inter, "iron_ingot", 16)
        inter.response.defer.assert_not_awaited()
        inter.followup.send.assert_awaited_once()


class EscapingTests(unittest.IsolatedAsyncioTestCase):
    EVIL = "[Gratis Nitro](https://evil.example)"

    async def test_relayed_chat_is_escaped(self):
        hook = Mock(); hook.send = AsyncMock()
        client = Mock(); client.get_webhook = AsyncMock(return_value=hook)
        await bot.Bot.post_chat(client, Mock(), "Steve", "survival", self.EVIL + " **fett**")
        sent = hook.send.call_args.args[0]
        self.assertTrue(sent.startswith("\\["))
        self.assertIn("\\*\\*fett\\*\\*", sent)

    async def test_shop_event_item_name_is_escaped(self):
        sent = []
        ch = Mock()
        async def send(msg, **kw):
            sent.append(msg)
            raise asyncio.CancelledError  # stop the endless loop after the first message
        ch.send = send
        b = Mock(); b.cfg = {"CHAT_CHANNEL_ID": "3"}; b.wait_until_ready = AsyncMock()
        b.is_closed.return_value = False; b.get_channel.return_value = ch
        rows = [(2, "CREATED", "Bob", None, 10, "diamond", self.EVIL, 1, "l", None)]
        with tempfile.TemporaryDirectory() as d:
            state = Path(d) / "s.json"; state.write_text(json.dumps({"last": 1}))
            with patch.object(shops, "STATE", state), patch.object(shops, "query", lambda sql, args=(): rows):
                with self.assertRaises(asyncio.CancelledError):
                    await shops.event_loop(b)
        self.assertIn("\\[Gratis Nitro]", sent[0])


class AuctionOwnerTests(unittest.IsolatedAsyncioTestCase):
    async def test_only_owner_can_use_view(self):
        v = bid.AuctionView(Mock(), [], owner_id=1)
        other = Mock(); other.user.id = 2; other.response.send_message = AsyncMock()
        self.assertFalse(await v.interaction_check(other))
        other.response.send_message.assert_awaited_once()
        owner = Mock(); owner.user.id = 1
        self.assertTrue(await v.interaction_check(owner))


class RelayBidTests(unittest.TestCase):
    def test_non_positive_bid_rejected(self):
        for amount in (0, -5):
            self.assertIsNone(relay.bid_command({"request_id": "r-2", "auction_id": "a1", "bidder_uuid": U, "amount": amount}))


if __name__ == "__main__":
    unittest.main()

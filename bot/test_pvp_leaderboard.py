import asyncio
import unittest
from unittest import mock

import ingest
import pvp

U1, U2, U3 = "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333"
COLS = ["uuid", "name", "wins", "losses", "draws", "matches", "winrate_pct", "favorite_kit"]
ROWS = [(U1, "Kai", 3, 1, 0, 4, None, "Sword"), (U2, "Nani", 5, 2, 1, 8, 71.4, None),
        (U3, "Ben", 5, 0, 0, 5, 100.0, "Bow"), (None, "x", 9, 0, 0, 9, 100.0, None)]


class BuildStatsTest(unittest.TestCase):
    def test_sort_and_fields(self):
        out = pvp.build_stats(COLS, ROWS)
        self.assertEqual([e["name"] for e in out], ["Ben", "Nani", "Kai"])
        self.assertEqual(out[1], {"uuid": U2, "name": "Nani", "wins": 5, "losses": 2, "draws": 1, "matches": 8,
                                  "winrate_pct": 71.4, "favorite_kit": None})

    def test_without_favorite_kit_column(self):
        out = pvp.build_stats(COLS[:-1], [r[:-1] for r in ROWS])
        self.assertTrue(all(e["favorite_kit"] is None for e in out))
        self.assertEqual(len(out), 3)

    def test_unexpected_columns(self):
        self.assertEqual(pvp.build_stats(["foo"], [(1,)]), [])

    def test_labels(self):
        rows = pvp.rank(pvp.build_stats(COLS, ROWS))
        self.assertEqual(rows[0], (1, "Ben", "5S 0N 0U \u00b7 100,0 % \u00b7 Bow", U3))
        self.assertEqual(rows[2][2], "3S 1N 0U \u00b7 \u2013 \u00b7 Sword")


class PvpIngestTest(unittest.TestCase):
    def run_push(self, fetch):
        posted = []

        async def fake_post(session, token, payload, label):
            posted.append(payload)
            return 200, "{}"

        async def run():
            ingest._pvp["warned"] = False
            with mock.patch.object(ingest, "_post", fake_post), mock.patch.object(ingest, "fetch_rows", fetch):
                return await ingest.push_pvp(None, "t")
        return asyncio.run(run()), posted

    def test_push(self):
        ok, posted = self.run_push(lambda n: (COLS, ROWS[:1]))
        self.assertTrue(ok)
        self.assertEqual(posted, [{"pvp_stats": [{"uuid": U1, "name": "Kai", "wins": 3, "losses": 1, "draws": 0,
                                                  "matches": 4, "winrate_pct": None, "favorite_kit": "Sword"}]}])

    def test_omitted_when_view_missing_or_empty(self):
        for fetch in (lambda n: None, lambda n: (COLS, []), lambda n: (["foo"], [(1,)])):
            with self.assertLogs("creepy.ingest", "WARNING"):
                ok, posted = self.run_push(fetch)
            self.assertIsNone(ok)
            self.assertEqual(posted, [])

    def test_history_fail_soft(self):
        async def boom(*a):
            raise RuntimeError("db weg")

        async def run():
            ingest._hist_push["last"] = None
            with mock.patch.object(ingest, "push_history", mock.AsyncMock(return_value=True)), \
                 mock.patch.object(ingest, "push_economy", mock.AsyncMock()), \
                 mock.patch.object(ingest, "push_ranks", mock.AsyncMock()), \
                 mock.patch.object(ingest, "push_pvp", boom):
                return await ingest.maybe_push_history(None, "t", now=1)
        self.assertTrue(asyncio.run(run()))


if __name__ == "__main__":
    unittest.main()

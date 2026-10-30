import datetime
import unittest

import ingest


class PriceResetTest(unittest.TestCase):
    def test_datetime_naive_is_utc(self):
        r = (["effective_from"], [(datetime.datetime(2026, 11, 1, 12, 0, 0),)])
        self.assertEqual(ingest.build_price_reset(r), "2026-11-01T12:00:00Z")

    def test_string_with_offset(self):
        r = (["effective_from"], [("2026-11-01T13:00:00+01:00",)])
        self.assertEqual(ingest.build_price_reset(r), "2026-11-01T12:00:00Z")

    def test_missing_or_null(self):
        for r in (None, (["effective_from"], []), (["effective_from"], [(None,)]),
                  (["effective_from"], [("",)]), (["other"], [(1,)]),
                  (["effective_from"], [("kaputt",)]),
                  (["effective_from"], [(datetime.datetime(1970, 1, 1),)])):
            self.assertIsNone(ingest.build_price_reset(r), r)

    def test_market_null_change(self):
        out = ingest.build_market(["item", "buy_price", "change_24h_pct"], [("minecraft:stone", 2, None)])
        self.assertEqual(out, [{"item": "stone", "buy_price": 2}])


if __name__ == "__main__":
    unittest.main()

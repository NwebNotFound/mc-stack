import datetime
import unittest
import ingest

COLS = ["uuid", "course", "name", "best_ms", "updated_at"]
U1 = "11111111-1111-1111-1111-111111111111"
U2 = "22222222-2222-2222-2222-222222222222"


class ParkourIngestTest(unittest.TestCase):
    def test_shape_and_iso_utc(self):
        rows = [(U1, "test", "Nils", 12345, datetime.datetime(2026, 10, 30, 13, 5, 7, 250000))]
        self.assertEqual(ingest.build_parkour(COLS, rows), [
            {"course": "test", "mc_uuid": U1, "mc_name": "Nils", "time_ms": 12345, "set_at": "2026-10-30T13:05:07.250Z", "course_name": "Test"}])

    def test_best_per_player_and_course(self):
        t = datetime.datetime(2026, 10, 30, 12, 0, 0)
        rows = [(U1, "Test", "Nils", 9000, t), (U1, "test", "Nils", 8000, t), (U1, "lobby", "Nils", 20000, t),
                (U2, "test", "Bob", 8500, t)]
        out = ingest.build_parkour(COLS, rows)
        self.assertEqual([(e["course"], e["mc_uuid"], e["time_ms"]) for e in out],
                         [("lobby", U1, 20000), ("test", U1, 8000), ("test", U2, 8500)])

    def test_offset_string_and_bad_rows(self):
        rows = [(U1, "test", "Nils", 100, "2026-10-30T14:00:00+01:00"), (U2, "", "x", 5, None),
                (U2, "test", "x", 0, None), (None, "test", "x", 5, None), (U2, "test", "Bob", 50, "kaputt")]
        out = ingest.build_parkour(COLS, rows)
        self.assertEqual(out[0], {"course": "test", "mc_uuid": U2, "mc_name": "Bob", "time_ms": 50, "course_name": "Test"})
        self.assertEqual(out[1]["set_at"], "2026-10-30T13:00:00.000Z")
        self.assertEqual(len(out), 2)

    def test_course_id_longer_than_32_is_skipped(self):
        t = datetime.datetime(2026, 10, 30, 12, 0, 0)
        rows = [(U1, "a" * 32, "Nils", 100, t), (U1, "b" * 33, "Nils", 50, t)]
        self.assertEqual([e["course"] for e in ingest.build_parkour(COLS, rows)], ["a" * 32])

    def test_empty(self):
        self.assertEqual(ingest.build_parkour(COLS, []), [])


if __name__ == "__main__":
    unittest.main()

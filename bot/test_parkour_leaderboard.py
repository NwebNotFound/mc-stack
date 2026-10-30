import sqlite3
import unittest

import parkour


def raw_from_sqlite(with_course=True):
    con = sqlite3.connect(":memory:")
    cols = "uuid TEXT, name TEXT, best_ms INT, updated_at TEXT" + (", course TEXT" if with_course else "")
    con.execute(f"CREATE TABLE parkour_times ({cols})")
    data = [
        ("u1", "Anna", 9000, "2026-10-30 10:00", "test"),
        ("u1", "Anna", 7000, "2026-10-30 11:00", "berg"),
        ("u2", "Ben", 8000, "2026-10-30 10:00", "test"),
        ("u2", "Ben", 12000, "2026-10-30 10:00", "berg"),
        ("u3", "Cem", 0, "2026-10-30 10:00", "test"),
    ]
    if with_course:
        con.executemany("INSERT INTO parkour_times VALUES (?,?,?,?,?)", data)
        return [tuple(r) for r in con.execute("SELECT uuid, name, best_ms, updated_at, course FROM parkour_times WHERE best_ms > 0")]
    con.executemany("INSERT INTO parkour_times VALUES (?,?,?,?)", [d[:4] for d in data[::2]])
    return [tuple(r) + (None,) for r in con.execute("SELECT uuid, name, best_ms, updated_at FROM parkour_times WHERE best_ms > 0")]


class ParkourLeaderboardTest(unittest.TestCase):
    def test_all_courses_no_duplicates(self):
        rows = parkour.rank(raw_from_sqlite())
        self.assertEqual(rows, [(1, "Anna", 7000, "u1"), (2, "Ben", 8000, "u2")])

    def test_per_course(self):
        raw = raw_from_sqlite()
        self.assertEqual(parkour.rank(raw, "test"), [(1, "Ben", 8000, "u2"), (2, "Anna", 9000, "u1")])
        self.assertEqual(parkour.rank(raw, " BERG "), [(1, "Anna", 7000, "u1"), (2, "Ben", 12000, "u2")])
        self.assertEqual(parkour.rank(raw, "gibtsnicht"), [])
        self.assertEqual(parkour.courses(raw), ["berg", "test"])

    def test_legacy_table_without_course(self):
        raw = raw_from_sqlite(with_course=False)
        self.assertEqual(parkour.rank(raw), [(1, "Anna", 9000, "u1"), (2, "Ben", 12000, "u2")])
        self.assertEqual(parkour.courses(raw), [])

    def test_uuid_dashes_dedup(self):
        raw = [("ab-cd", "X", 500, "", "a"), ("abcd", "X", 400, "", "b")]
        self.assertEqual(parkour.rank(raw), [(1, "X", 400, "abcd")])


if __name__ == "__main__":
    unittest.main()

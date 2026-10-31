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
        self.assertEqual(parkour.courses(raw), [("berg", "Berg"), ("test", "Test")])

    def test_legacy_table_without_course(self):
        raw = raw_from_sqlite(with_course=False)
        self.assertEqual(parkour.rank(raw), [(1, "Ben", 8000, "u2"), (2, "Anna", 9000, "u1")])
        self.assertEqual(parkour.courses(raw), [])

    def test_uuid_dashes_dedup(self):
        raw = [("ab-cd", "X", 500, "", "a"), ("abcd", "X", 400, "", "b")]
        self.assertEqual(parkour.rank(raw), [(1, "X", 400, "abcd")])



class CourseNameTest(unittest.TestCase):
    """Mock-Daten: altes Format (ID = kleingeschriebener Name / kurs1) und neues (Slug + course_name)."""
    OLD = [("u1", "Joni81", 37864, "2026-10-29 10:00", "cave rush"), ("u2", "Anna", 50000, "2026-10-29 10:00", "kurs1")]
    NEW = [("u3", "Ben", 30000, "2026-10-31 10:00", "cave_rush", "Cave Rush"), ("u1", "Joni81", 36000, "2026-10-31 11:00", "cave_rush", "Cave Rush")]

    def test_slug(self):
        for v in ("Cave Rush", " cave rush ", "cave_rush", "cave-rush"):
            self.assertEqual(parkour.slug(v), "cave_rush")
        self.assertEqual(parkour.slug("kurs1"), "kurs1")

    def test_old_format_falls_back_to_id(self):
        self.assertEqual(parkour.courses(self.OLD), [("cave_rush", "Cave Rush"), ("kurs1", "Kurs1")])
        self.assertEqual(parkour.rank(self.OLD, "kurs1"), [(1, "Anna", 50000, "u2")])

    def test_new_format_shows_course_name(self):
        self.assertEqual(parkour.courses(self.NEW), [("cave_rush", "Cave Rush")])
        self.assertEqual(parkour.display(self.NEW, "cave_rush"), "Cave Rush")

    def test_mixed_formats_merge(self):
        raw = self.OLD + self.NEW
        self.assertEqual(parkour.courses(raw), [("cave_rush", "Cave Rush"), ("kurs1", "Kurs1")])
        self.assertEqual(parkour.rank(raw, "cave_rush"), [(1, "Ben", 30000, "u3"), (2, "Joni81", 36000, "u1")])
        self.assertEqual(parkour.rank(raw, "Cave Rush"), parkour.rank(raw, "cave_rush"))
        self.assertEqual([r[1] for r in parkour.rank(raw, "*")], ["Ben", "Joni81", "Anna"])
        self.assertIsNone(parkour.display(raw, "*"))
        self.assertEqual(parkour.display(raw, "gibtsnicht"), "Gibtsnicht")

    def test_empty_course_name_falls_back(self):
        raw = [("u1", "X", 100, "", "kurs2", None), ("u2", "Y", 200, "", "kurs2", "  ")]
        self.assertEqual(parkour.courses(raw), [("kurs2", "Kurs2")])

    def test_status_courses(self):
        self.assertEqual(parkour.status_courses(None), ["*"])
        self.assertEqual(parkour.status_courses(" * , Cave Rush, cave_rush "), ["*", "cave_rush"])
        self.assertEqual(parkour.marker_name("*"), "leaderboard-message.txt")
        self.assertEqual(parkour.marker_name("cave_rush"), "leaderboard-message-cave_rush.txt")

    def test_select_menu_labels(self):
        import gui
        v = gui.LbPager("Parkour", [], courses=parkour.courses(self.OLD + self.NEW), raw=self.OLD + self.NEW, selected="cave rush")
        opts = [(o.label, o.value, o.default) for o in v.course_menu.options]
        self.assertEqual(opts, [("Alle Kurse (beste Zeit je Spieler)", "*", False), ("Cave Rush", "cave_rush", True), ("Kurs1", "kurs1", False)])

    def test_select_menu_with_single_course(self):
        import gui
        v = gui.LbPager("Parkour", [], courses=parkour.courses(self.NEW), raw=self.NEW)
        self.assertEqual([o.label for o in v.course_menu.options], ["Alle Kurse (beste Zeit je Spieler)", "Cave Rush"])


if __name__ == "__main__":
    unittest.main()

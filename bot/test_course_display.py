import unittest
import ingest, parkour


class CourseDisplayTests(unittest.TestCase):
    def test_pretty(self):
        self.assertEqual(parkour.pretty("cave_rush"), "Cave Rush")
        self.assertEqual(parkour.pretty("kurs1"), "Kurs1")
        self.assertEqual(parkour.pretty(""), "")

    def test_display_never_slug(self):
        raw = [("u1", "A", 1000, "t", "cave_rush", None, None), ("u2", "B", 900, "t", "sky_run", "Sky Run!", "feather")]
        self.assertEqual(parkour.display(raw, "cave_rush"), "Cave Rush")
        self.assertEqual(parkour.display(raw, "sky_run"), "Sky Run!")
        self.assertEqual(parkour.display(raw, "unknown_one"), "Unknown One")
        for _, n in parkour.courses(raw):
            self.assertNotIn("_", n)

    def test_icon(self):
        self.assertEqual(parkour.icon("minecraft:GOLDEN_BOOTS"), "golden_boots")
        self.assertIsNone(parkour.icon(None))
        self.assertIsNone(parkour.icon("bad icon!"))

    def test_ingest_name_and_icon(self):
        cols = ["uuid", "name", "best_ms", "updated_at", "course", "course_name", "course_icon"]
        out = ingest.build_parkour(cols, [("U1", "A", 1000, None, "Cave Rush", None, None),
                                          ("U2", "B", 900, None, "sky_run", "Sky Run", "minecraft:feather")])
        e = {x["course"]: x for x in out}
        self.assertEqual(e["cave_rush"]["course_name"], "Cave Rush")
        self.assertNotIn("course_icon", e["cave_rush"])
        self.assertEqual(e["sky_run"]["course_icon"], "feather")

    def test_ingest_without_columns(self):
        out = ingest.build_parkour(["uuid", "name", "best_ms", "updated_at", "course"], [("U1", "A", 5, None, "cave_rush")])
        self.assertEqual(out[0]["course_name"], "Cave Rush")
        self.assertNotIn("course_icon", out[0])

    def test_view_meta(self):
        meta = parkour.course_meta(["slug", "course_name", "course_icon"],
                                   [("cave_rush", "Höhlen\u202eRausch", "golden_boots"), ("x", "", "BAD ICON"), ("y", "Y", "a" * 49)])
        self.assertEqual(meta["cave_rush"], ("HöhlenRausch", "golden_boots"))
        self.assertEqual(meta["x"], ("", None))
        self.assertIsNone(meta["y"][1])
        cols = ["uuid", "name", "best_ms", "updated_at", "course"]
        out = ingest.build_parkour(cols, [("U1", "A", 5, None, "cave_rush"), ("U2", "B", 5, None, "x")], meta=meta)
        e = {x["course"]: x for x in out}
        self.assertEqual(e["cave_rush"]["course_name"], "HöhlenRausch")
        self.assertEqual(e["cave_rush"]["course_icon"], "golden_boots")
        self.assertEqual(e["x"]["course_name"], "X")
        self.assertNotIn("course_icon", e["x"])
        raw = parkour.apply_meta([("u", "A", 5, "t", "cave_rush")], meta)
        self.assertEqual(parkour.display(raw, "cave_rush"), "HöhlenRausch")
        self.assertEqual(raw[0][6], "golden_boots")


if __name__ == "__main__":
    unittest.main()

import unittest

import parkour


class NewRecordTest(unittest.TestCase):
    """#mc-chat nur bei neuer Kursbestzeit. Leader: (rang, name, ms, uuid)."""

    def test_faster_time_posts(self):
        prev = (1, "Ben", 8000, "u2")
        leader = (1, "Anna", 7000, "u1")
        self.assertTrue(parkour.is_new_record(prev, leader))

    def test_same_player_faster_posts(self):
        prev = (1, "Anna", 8000, "u1")
        leader = (1, "Anna", 7000, "u1")
        self.assertTrue(parkour.is_new_record(prev, leader))

    def test_leader_change_without_faster_time_does_not_post(self):
        prev = (1, "Anna", 7000, "u1")
        leader = (1, "Ben", 9000, "u2")
        self.assertFalse(parkour.is_new_record(prev, leader))

    def test_slower_or_equal_does_not_post(self):
        prev = (1, "Anna", 7000, "u1")
        self.assertFalse(parkour.is_new_record(prev, (1, "Anna", 8000, "u1")))
        self.assertFalse(parkour.is_new_record(prev, (1, "Anna", 7000, "u1")))
        self.assertFalse(parkour.is_new_record(prev, (1, "Ben", 7000, "u2")))

    def test_first_time_posts(self):
        self.assertTrue(parkour.is_new_record(None, (1, "Anna", 7000, "u1")))

    def test_startup_does_not_post(self):
        self.assertFalse(parkour.is_new_record("unset", (1, "Anna", 7000, "u1")))
        self.assertFalse(parkour.is_new_record("unset", None))

    def test_no_leader_does_not_post(self):
        self.assertFalse(parkour.is_new_record((1, "Anna", 7000, "u1"), None))
        self.assertFalse(parkour.is_new_record(None, None))


if __name__ == "__main__":
    unittest.main()

import ingest

COLS = ["uuid", "team", "team_color", "team_color_hex"]


def row(color, hexv):
    return ingest.build_stats(COLS, [("u", "Blau", color, hexv)])["u"]


def test_hex_column_wins():
    s = row("&9", "#5555ff")
    assert s["team_color"] == "#5555FF" and s["team_color_code"] == "&9"


def test_invalid_hex_falls_back_to_code():
    assert row("&c", "rot")["team_color"] == "#FF5555"
    assert row("&C", None)["team_color"] == "#FF5555"


def test_section_sign_code():
    s = row("\u00a7a", None)
    assert s["team_color"] == "#55FF55" and s["team_color_code"] == "&a"


def test_all_16_codes():
    for k, v in ingest.MC_COLORS.items():
        assert row("&" + k, None)["team_color"] == v
    assert len(ingest.MC_COLORS) == 16


def test_raw_hex_normalized():
    s = row(" #aa00aa ", None)
    assert s["team_color"] == "#AA00AA" and s["team_color_hex"] == "#AA00AA" and s["team_color_code"] == "&5"
    s = row("#123abc", None)
    assert s["team_color"] == "#123ABC" and s["team_color_code"] is None


def test_garbage_and_null():
    for c in ("&z", "blue", "#12345", "", None):
        s = row(c, None)
        assert s["team_color"] is None and s["team_color_code"] is None


def test_missing_columns():
    s = ingest.build_stats(["uuid"], [("u",)])["u"]
    assert s["team_color"] is None and s["team_color_code"] is None


def test_hex_only_without_team_color_column():
    s = ingest.build_stats(["uuid", "team_color_hex"], [("u", "#00aa00")])["u"]
    assert s["team_color"] == "#00AA00" and s["team_color_code"] == "&2"


def test_craft_new_shape_hex_plus_code_column():
    s = ingest.build_stats(["uuid", "team_color", "team_color_code"], [("u", "#5555ff", "&9")])["u"]
    assert s["team_color"] == s["team_color_hex"] == "#5555FF" and s["team_color_code"] == "&9"


def test_code_column_only():
    s = ingest.build_stats(["uuid", "team_color_code"], [("u", "\u00a7e")])["u"]
    assert s["team_color_hex"] == "#FFFF55" and s["team_color_code"] == "&e"


def test_hex_and_color_always_equal():
    for c, h in (("&9", "#5555ff"), ("&c", None), (None, None), ("#abcdef", None)):
        s = row(c, h)
        assert s["team_color"] == s["team_color_hex"]

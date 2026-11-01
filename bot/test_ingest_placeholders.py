import datetime as dt
import ingest

COLS = ["uuid", "mc_name", "team", "team_color", "team_color_hex", "team_color_code", "last_seen", "online"]


def one(row, cols=COLS, names=None):
    return next(iter(ingest.build_stats(cols, [row], names).values()))


def test_zero_placeholders_become_null():
    for z in (0, "0", "", None, "  ", 0.0):
        s = one(["u", "A", z, z, z, z, z, 0])
        assert s["team"] is None and s["team_color"] is None
        assert s["team_color_hex"] is None and s["team_color_code"] is None
        assert s["last_seen"] is None
        assert s["online"] is False


def test_epoch_zero_never_a_date():
    for v in (0, "0", dt.datetime(1970, 1, 1), "1970-01-01T00:00:00Z", "0000-00-00 00:00:00", -5):
        assert ingest._iso_utc(v) is None


def test_real_values_kept():
    s = one(["u", "A", "ChickenJockey", "#ffffff", "#FFFFFF", "&f", "2026-11-01T00:47:33Z", 1])
    assert s["team"] == "ChickenJockey" and s["team_color"] == "#FFFFFF"
    assert s["team_color_code"] == "&f" and s["last_seen"] == "2026-11-01T00:47:33Z"
    assert s["online"] is True


def test_online_from_snapshot_when_column_missing():
    cols = COLS[:-1]
    assert one(["u", "Joni81", None, None, None, None, None], cols, {"joni81"})["online"] is True
    assert one(["u", "Kai", None, None, None, None, None], cols, {"joni81"})["online"] is False
    assert one(["u", "Kai", None, None, None, None, None], cols)["online"] is None

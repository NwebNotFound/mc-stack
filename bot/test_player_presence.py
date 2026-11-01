import datetime as dt
import ingest

COLS = ["uuid", "name", "money"]


def test_missing_columns_give_null():
    s = ingest.build_stats(COLS, [("u1", "A", 5)])["u1"]
    assert s["team"] is None and s["team_color"] is None
    assert s["last_seen"] is None and s["online"] is None
    assert s["money"] == 5


def test_present_columns():
    cols = COLS + ["team", "team_color", "last_seen", "online"]
    s = ingest.build_stats(cols, [("u1", "A", 1, "Rot", "#ff0000", dt.datetime(2026, 11, 1, 0, 5, 3), 1)])["u1"]
    assert s["team"] == "Rot" and s["team_color"] == "#ff0000"
    assert s["last_seen"] == "2026-11-01T00:05:03Z"
    assert s["online"] is True


def test_null_values():
    cols = COLS + ["team", "team_color", "last_seen", "online"]
    s = ingest.build_stats(cols, [("u1", "A", 1, None, None, None, None)])["u1"]
    assert s["team"] is None and s["last_seen"] is None and s["online"] is None


def test_online_zero_and_bytes():
    assert ingest._bool(0) is False and ingest._bool(b"\x01") is True and ingest._bool("0") is False


def test_last_seen_variants():
    assert ingest._iso_utc(1761955503000) == ingest._iso_utc(1761955503)
    assert ingest._iso_utc("2026-11-01 00:05:03") == "2026-11-01T00:05:03Z"
    aware = dt.datetime(2026, 11, 1, 1, 5, 3, tzinfo=dt.timezone(dt.timedelta(hours=1)))
    assert ingest._iso_utc(aware) == "2026-11-01T00:05:03Z"
    assert ingest._iso_utc("kaputt") is None

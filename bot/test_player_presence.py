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
    assert s["team"] == "Rot" and s["team_color"] == "#FF0000"
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


def test_view_fields_reach_serialized_export():
    import json
    cols = COLS + ["team", "team_color", "team_color_hex", "team_color_code", "last_seen", "online"]
    row = ("u1", "A", 1, "Gr?n", "#55ff55", "#55FF55", "&a", "2026-11-01T00:05:03Z", 0)
    payload = json.loads(json.dumps({"players_stats": ingest.build_stats(cols, [row])}, ensure_ascii=False))
    player = payload["players_stats"]["u1"]
    assert {k: player[k] for k in cols[3:]} == {
        "team": "Gr?n", "team_color": "#55FF55", "team_color_hex": "#55FF55",
        "team_color_code": "&a", "last_seen": "2026-11-01T00:05:03Z", "online": False,
    }
    assert ingest.build_stats(cols, [("u2", "B", 0, None, None, None, None, None, None)])["u2"]["last_seen"] is None
    assert ingest.build_stats(cols, [("u3", "C", 0, None, None, None, None, "verbatim", 1)])["u3"]["last_seen"] == "verbatim"


def test_push_exports_view_presence_fields():
    import asyncio
    from unittest.mock import patch

    cols = COLS + ["team", "team_color", "team_color_hex", "team_color_code", "last_seen", "online"]
    row = ("u1", "A", 1, "Gr?n", "#55FF55", "#55FF55", "&a", "2026-11-01T00:05:03Z", 1)
    sent = []

    async def post(session, token, payload, label):
        sent.append(payload)
        return (400, None)

    class Bot:
        def server_mode(self):
            return None

    with patch.dict("os.environ", {"MC_INGEST_TOKEN": "test"}), \
         patch.object(ingest, "build_payload", return_value={}), \
         patch.object(ingest, "fetch_rows", side_effect=lambda name: (cols, [row]) if name == "v_player_stats" else None), \
         patch.object(ingest, "collect_servers", return_value={}), \
         patch.object(ingest, "_post", side_effect=post), \
         patch.dict(ingest._rate, {"last_hash": None}):
        asyncio.run(ingest.push_once(Bot(), session=object()))
    player = sent[0]["players_stats"]["u1"]
    assert [player[k] for k in cols[3:]] == list(row[3:-1]) + [True]

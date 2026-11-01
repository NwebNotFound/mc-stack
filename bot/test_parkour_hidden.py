import parkour
from ingest import build_parkour

PC_COLS = ["slug", "course_name", "course_icon", "hidden"]
PC_ROWS = [("cave_rush", "Cave Rush", None, 0), ("test", "Test", None, 1)]


def test_hidden_slugs():
    assert parkour.hidden_slugs(PC_COLS, PC_ROWS) == {"test"}
    assert parkour.hidden_slugs(["slug"], [("cave_rush",)]) == set()


def test_drop_hidden():
    raw = [("u1", "A", 1000, None, "cave_rush"), ("u2", "B", 900, None, "test")]
    assert [r[4] for r in parkour.drop_hidden(raw, {"test"})] == ["cave_rush"]
    assert parkour.drop_hidden(raw, set()) == raw


def test_build_parkour_hidden():
    cols = ["course", "uuid", "name", "best_ms", "updated_at"]
    rows = [("cave_rush", "u1", "A", 49530, None), ("test", "u2", "B", 900, None)]
    out = build_parkour(cols, rows, hidden={"test"})
    assert [e["course"] for e in out] == ["cave_rush"]

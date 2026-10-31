"""PvP-Stats aus v_pvp_stats: gemeinsame Aufbereitung fuer /leaderboard pvp und den Ingest-Push (pvp_stats)."""

PVP_MAX = 500


def _int(v):
    try:
        return int(v or 0)
    except (TypeError, ValueError):
        return 0


def _rate(v):
    if v is None:
        return None
    try:
        return round(float(v), 1)
    except (TypeError, ValueError):
        return None


def build_stats(cols, rows, limit=PVP_MAX):
    """v_pvp_stats -> [{uuid, name, wins, losses, draws, matches, winrate_pct, favorite_kit}].
    Sortiert nach Siegen absteigend, dann Winrate (NULL zuletzt), dann Name. favorite_kit fehlt -> None."""
    idx = {c: i for i, c in enumerate(cols)}
    if "uuid" not in idx or "wins" not in idx:
        return []
    out = []
    for r in rows:
        g = lambda c: r[idx[c]] if c in idx else None
        if not g("uuid"):
            continue
        kit = g("favorite_kit")
        out.append({"uuid": str(g("uuid")), "name": str(g("name") or ""), "wins": _int(g("wins")),
                    "losses": _int(g("losses")), "draws": _int(g("draws")), "matches": _int(g("matches")),
                    "winrate_pct": _rate(g("winrate_pct")), "favorite_kit": str(kit) if kit else None})
    out.sort(key=lambda e: (-e["wins"], -(e["winrate_pct"] if e["winrate_pct"] is not None else -1), e["name"].lower()))
    return out[:limit]


def label(e):
    """'5S 2N 1U · 71.4 % · Kit' – Winrate '–' unter 5 Matches."""
    wr = "\u2013" if e["winrate_pct"] is None else f"{e['winrate_pct']:.1f} %".replace(".", ",")
    s = f"{e['wins']}S {e['losses']}N {e['draws']}U \u00b7 {wr}"
    return s + (f" \u00b7 {e['favorite_kit']}" if e.get("favorite_kit") else "")


def rank(stats):
    """-> [(platz, name, label, uuid)] fuer LbPager."""
    return [(i + 1, e["name"] or e["uuid"][:8], label(e), e["uuid"]) for i, e in enumerate(stats)]

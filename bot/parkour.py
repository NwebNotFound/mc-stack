"""Parkour-Bestenliste: Bestzeit pro Spieler (und Kurs) ohne Duplikate.

raw = [(uuid, name, best_ms, updated_at, course)]; course darf None sein (alte Tabelle ohne Spalte).
Ergebnis wie bisher: [(rang, name, ms, uuid)].
"""


def norm_course(c):
    return str(c or "").strip().lower()


def courses(raw):
    return sorted({norm_course(r[4]) for r in raw if norm_course(r[4])})


def rank(raw, course=None):
    """course=None -> beste Zeit je Spieler ueber alle Kurse (MIN(best_ms) GROUP BY uuid)."""
    want = norm_course(course) if course else None
    best = {}
    for uuid, name, ms, upd, c in raw:
        try:
            ms = int(ms)
        except (TypeError, ValueError):
            continue
        if ms <= 0 or not uuid:
            continue
        if want is not None and norm_course(c) != want:
            continue
        key = str(uuid).replace("-", "").lower()
        old = best.get(key)
        cand = (ms, str(upd or ""), str(name or ""), str(uuid))
        if old is None or cand[:2] < old[:2]:
            best[key] = cand
    out = sorted(best.values(), key=lambda e: (e[0], e[1], e[3]))
    return [(i + 1, e[2], e[0], e[3]) for i, e in enumerate(out)]

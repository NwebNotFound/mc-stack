"""Parkour-Bestenliste: Bestzeit pro Spieler (und Kurs) ohne Duplikate.

raw = [(uuid, name, best_ms, updated_at, course[, course_name])]
- course: Kurs-ID. Alt: kleingeschriebener Lobby-Parkour-Name ("cave rush", "kurs1"),
  neu: fester Slug von Craft ("cave_rush"). Beide werden ueber slug() zusammengefuehrt.
- course_name: Anzeigename ("Cave Rush"), optional; fehlt er, wird die ID angezeigt.
- course darf None sein (alte Tabelle ohne Spalte).
Ergebnis von rank() wie bisher: [(rang, name, ms, uuid)].
"""
import re

ALL = "*"


def slug(c):
    """'Cave Rush' / 'cave rush' / 'cave_rush' / 'cave-rush' -> 'cave_rush'; 'kurs1' bleibt 'kurs1'."""
    return re.sub(r"[\s\-]+", "_", str(c or "").strip().lower()).strip("_")


norm_course = slug  # Abwaertskompatibel


def _name(r):
    return str(r[5]).strip() if len(r) > 5 and r[5] and str(r[5]).strip() else ""


def course_names(raw):
    """{slug: Anzeigename}. Anzeige = course_name, sonst die (original geschriebene) ID."""
    out = {}
    for r in raw:
        s = slug(r[4] if len(r) > 4 else None)
        if not s:
            continue
        n = _name(r)
        if n:
            out[s] = n  # echter Name gewinnt immer
        elif s not in out:
            out[s] = str(r[4]).strip()
    return out


def courses(raw):
    """[(slug, anzeigename)] sortiert nach Anzeigename."""
    return sorted(course_names(raw).items(), key=lambda kv: (kv[1].lower(), kv[0]))


def display(raw, course):
    """Anzeigename fuer eine Kurs-Eingabe (Slug, alte ID oder Name); unbekannt -> Eingabe selbst."""
    if not course or course == ALL:
        return None
    return course_names(raw).get(slug(course), str(course).strip())


def rank(raw, course=None):
    """course=None/'*' -> beste Zeit je Spieler ueber alle Kurse (MIN(best_ms) GROUP BY uuid)."""
    want = slug(course) if course and course != ALL else None
    best = {}
    for r in raw:
        uuid, name, ms, upd = r[:4]
        c = r[4] if len(r) > 4 else None
        try:
            ms = int(ms)
        except (TypeError, ValueError):
            continue
        if ms <= 0 or not uuid:
            continue
        if want is not None and slug(c) != want:
            continue
        key = str(uuid).replace("-", "").lower()
        old = best.get(key)
        cand = (ms, str(upd or ""), str(name or ""), str(uuid))
        if old is None or cand[:2] < old[:2]:
            best[key] = cand
    out = sorted(best.values(), key=lambda e: (e[0], e[1], e[3]))
    return [(i + 1, e[2], e[0], e[3]) for i, e in enumerate(out)]


def status_courses(env_value):
    """PARKOUR_STATUS_COURSES='*,cave_rush' -> ['*', 'cave_rush']; leer -> ['*'] (bisheriges Verhalten)."""
    out = []
    for p in str(env_value or "").split(","):
        p = p.strip()
        s = ALL if p == ALL else slug(p)
        if s and s not in out:
            out.append(s)
    return out or [ALL]


def marker_name(course):
    """Datei fuer die angepinnte Nachricht; '*' behaelt den alten Namen."""
    return "leaderboard-message.txt" if course == ALL else f"leaderboard-message-{slug(course)}.txt"

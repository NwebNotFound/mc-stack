"""Parkour-Bestenliste: Bestzeit pro Spieler (und Kurs) ohne Duplikate.

raw = [(uuid, name, best_ms, updated_at, course[, course_name])]
- course: Kurs-ID. Alt: kleingeschriebener Lobby-Parkour-Name ("cave rush", "kurs1"),
  neu: fester Slug von Craft ("cave_rush"). Beide werden ueber slug() zusammengefuehrt.
- course_name: Anzeigename ("Cave Rush"), optional; fehlt er, wird der Slug huebsch gemacht (pretty()).
- course_icon (Index 6, optional): Minecraft-Material, nur fuer die Website.
- course darf None sein (alte Tabelle ohne Spalte).
Ergebnis von rank() wie bisher: [(rang, name, ms, uuid)].
"""
import re

ALL = "*"


def slug(c):
    """'Cave Rush' / 'cave rush' / 'cave_rush' / 'cave-rush' -> 'cave_rush'; 'kurs1' bleibt 'kurs1'."""
    return re.sub(r"[\s\-]+", "_", str(c or "").strip().lower()).strip("_")


norm_course = slug  # Abwaertskompatibel


def pretty(c):
    """Slug -> Anzeige ohne Unterstriche: 'cave_rush' -> 'Cave Rush'."""
    s = re.sub(r"[_\s\-]+", " ", str(c or "")).strip()
    return " ".join(w[:1].upper() + w[1:] for w in s.split(" ")) if s else ""


ICON_RE = re.compile(r"^[a-z0-9_]{1,48}$")


def icon(v):
    """Minecraft-Material kleingeschrieben ohne 'minecraft:'; ungueltig -> None."""
    v = str(v or "").strip().lower()
    v = v[10:] if v.startswith("minecraft:") else v
    return v if ICON_RE.match(v) else None


CTRL_RE = re.compile(r"[\x00-\x1f\x7f\u200b-\u200f\u2028-\u202e\u2066-\u2069]")


def clean_name(v):
    """Anzeigename: Steuer-/Bidi-Zeichen raus, max. 48 Zeichen; Markdown-Escaping macht die Ausgabe."""
    return re.sub(r"\s+", " ", CTRL_RE.sub("", str(v or ""))).strip()[:48]


def course_meta(cols, rows):
    """v_parkour_courses (slug, course_name, course_icon) -> {slug: (name, icon)}; Icon validiert."""
    out = {}
    for row in rows or ():
        r = dict(zip(cols, row))
        s = slug(r.get("slug"))
        if s:
            out[s] = (clean_name(r.get("course_name")), icon(r.get("course_icon")))
    return out


def apply_meta(raw, meta):
    """Name/Icon aus v_parkour_courses in raw-Tupel (Index 5/6) einsetzen; View gewinnt."""
    if not meta:
        return raw
    out = []
    for r in raw:
        r = tuple(r) + (None,) * (7 - len(r))
        n, ic = meta.get(slug(r[4]), (None, None))
        out.append(r[:5] + (n or r[5], ic or r[6]) + r[7:])
    return out


def _name(r):
    return clean_name(r[5]) if len(r) > 5 and r[5] else ""


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
            out[s] = pretty(s)
    return out


def courses(raw):
    """[(slug, anzeigename)] sortiert nach Anzeigename."""
    return sorted(course_names(raw).items(), key=lambda kv: (kv[1].lower(), kv[0]))


def display(raw, course):
    """Anzeigename fuer eine Kurs-Eingabe (Slug, alte ID oder Name); unbekannt -> Eingabe selbst."""
    if not course or course == ALL:
        return None
    return course_names(raw).get(slug(course)) or pretty(slug(course))


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

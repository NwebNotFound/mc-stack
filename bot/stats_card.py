"""Stats-Karten (PNG) aus Minecraft-Statistiken, mit deutschen Namen und Item-Icons."""
import io, json, time, urllib.request
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path("/minecraft/survival")
CACHE = Path("/app/data/mcassets")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
UA = {"User-Agent": "Creepy-Bot"}
PAGES = {"overview": "\u00dcbersicht", "blocks": "Bl\u00f6cke", "crafting": "Crafting", "combat": "Kampf"}
BG, PANEL, TXT, SUB, ACC = (30, 31, 34), (43, 45, 49), (242, 243, 245), (148, 155, 164), (88, 101, 242)
_lang = None


def _get(url, timeout=8):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout).read()


def lang():
    global _lang
    if _lang is not None:
        return _lang
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / "de_de.json"
    try:
        if not f.exists():
            m = json.loads(_get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
            rel = m["latest"]["release"]
            v = next(x for x in m["versions"] if x["id"] == rel)
            ai = json.loads(_get(json.loads(_get(v["url"]))["assetIndex"]["url"]))
            h = ai["objects"]["minecraft/lang/de_de.json"]["hash"]
            f.write_bytes(_get(f"https://resources.download.minecraft.net/{h[:2]}/{h}"))
        _lang = json.loads(f.read_text(encoding="utf-8"))
    except Exception:
        _lang = {}
    return _lang


def pretty(key, kind="item"):
    short = key.split(":", 1)[-1]
    L = lang()
    prefixes = ("entity",) if kind == "entity" else ("stat",) if kind == "stat" else ("block", "item")
    for p in prefixes:
        v = L.get(f"{p}.minecraft.{short}")
        if v:
            return v
    return short.replace("_", " ").title()


def icon(key, kind="item"):
    short = key.split(":", 1)[-1]
    name = f"{short}_spawn_egg" if kind == "entity" else short
    d = CACHE / "icons"
    d.mkdir(parents=True, exist_ok=True)
    f = d / f"{name}.png"
    miss = d / f"{name}.missing"
    if f.exists():
        try:
            return Image.open(f).convert("RGBA")
        except Exception:
            return None
    if miss.exists() and time.time() - miss.stat().st_mtime < 86400:
        return None
    try:
        f.write_bytes(_get(f"https://mc.nerothe.com/img/1.21/minecraft_{name}.png", 5))
        return Image.open(f).convert("RGBA")
    except Exception:
        miss.write_text("")
        return None


def find_uuid(name):
    try:
        for e in json.loads((ROOT / "usercache.json").read_text(encoding="utf-8")):
            if e["name"].lower() == name.lower():
                return e["uuid"], e["name"]
    except Exception:
        pass
    return None


def load_stats(uuid):
    for p in (ROOT / "world/players/stats" / f"{uuid}.json", ROOT / "world/stats" / f"{uuid}.json"):
        if p.exists():
            return json.loads(p.read_text(encoding="utf-8")).get("stats", {}), p.stat().st_mtime
    return None, None


def dur(ticks):
    m = ticks // 20 // 60
    d, rest = divmod(m, 1440)
    h, m = divmod(rest, 60)
    return f"{d}d {h}h" if d else f"{h}h {m}m"


def summarize(stats):
    c = stats.get("minecraft:custom", {})
    mined = stats.get("minecraft:mined", {})
    killed = stats.get("minecraft:killed", {})
    cm = sum(v for k, v in c.items() if k.endswith("_one_cm") and k != "minecraft:fall_one_cm")
    return [
        ("Spielzeit", dur(c.get("minecraft:play_time", 0))),
        ("Tode", c.get("minecraft:deaths", 0)),
        ("Mob-Kills", c.get("minecraft:mob_kills", sum(killed.values()))),
        ("Spieler-Kills", c.get("minecraft:player_kills", 0)),
        ("Unterwegs", f"{cm / 100000:.2f} km"),
        ("Abgebaut", sum(mined.values())),
        ("Gecraftet", sum(stats.get("minecraft:crafted", {}).values())),
        ("Diamanten", mined.get("minecraft:diamond_ore", 0) + mined.get("minecraft:deepslate_diamond_ore", 0)),
        ("Sprünge", c.get("minecraft:jump", 0)),
        ("Fische", c.get("minecraft:fish_caught", 0)),
        ("Villager-Handel", c.get("minecraft:traded_with_villager", 0)),
        ("Seit letztem Tod", dur(c.get("minecraft:time_since_death", 0))),
    ]


def top(stats, cat, n=5):
    return sorted(stats.get(f"minecraft:{cat}", {}).items(), key=lambda kv: -kv[1])[:n]


def fmt(v):
    return f"{v:,}".replace(",", ".") if isinstance(v, int) else str(v)


def _f(size, bold=True):
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def _fit(d, text, font, width):
    if d.textlength(text, font=font) <= width:
        return text
    while text and d.textlength(text + "…", font=font) > width:
        text = text[:-1]
    return text + "…"


def _list(img, d, x, y, w, title, items, kind):
    d.rounded_rectangle([x, y, x + w, y + 330], 14, fill=PANEL)
    d.text((x + 16, y + 14), title.upper(), font=_f(13), fill=SUB)
    if not items:
        d.text((x + 16, y + 56), "Noch nichts", font=_f(18, False), fill=SUB)
        return
    for i, (key, val) in enumerate(items):
        ry = y + 46 + i * 56
        ic = icon(key, kind)
        d.rounded_rectangle([x + 14, ry, x + 58, ry + 44], 10, fill=(56, 58, 64))
        if ic:
            ic = ic.resize((34, 34), Image.NEAREST)
            img.alpha_composite(ic, (x + 19, ry + 5))
        else:
            d.text((x + 36, ry + 22), pretty(key, kind)[:1], font=_f(20), fill=TXT, anchor="mm")
        vs = fmt(val)
        vw = d.textlength(vs, font=_f(18))
        d.text((x + 70, ry + 11), _fit(d, pretty(key, kind), _f(17, False), w - 100 - vw), font=_f(17, False), fill=TXT)
        d.text((x + w - 16, ry + 11), vs, font=_f(18), fill=TXT, anchor="ra")


def render(name, stats, status_text, online, body_png=None, page="overview"):
    W, H = 1000, 470
    img = Image.new("RGBA", (W, H), BG + (255,))
    d = ImageDraw.Draw(img)
    for x in range(W):
        t = x / W
        d.line([(x, 0), (x, 6)], fill=(int(88 + 60 * t), int(101 + 100 * t), int(242 - 120 * t)))
    d.rounded_rectangle([20, 26, 240, H - 20], 18, fill=PANEL)
    if body_png:
        try:
            body = Image.open(io.BytesIO(body_png)).convert("RGBA")
            body.thumbnail((170, 380))
            img.alpha_composite(body, (130 - body.width // 2, 40 + (390 - body.height) // 2))
        except Exception:
            pass
    d.text((265, 32), name, font=_f(36), fill=TXT)
    d.ellipse([267, 86, 279, 98], fill=(35, 165, 90) if online else (128, 132, 142))
    d.text((287, 82), status_text, font=_f(16, False), fill=(181, 186, 193))
    d.text((W - 30, 40), f"SURVIVAL · {PAGES[page].upper()}", font=_f(15), fill=ACC, anchor="ra")
    X0, Y0, AW = 265, 114, W - 265 - 25
    if page == "overview":
        rows = summarize(stats)
        cw, chh, gap = (AW - 3 * 10) // 4, 100, 10
        for i, (label, value) in enumerate(rows):
            col, row = i % 4, i // 4
            x, y = X0 + col * (cw + gap), Y0 + row * (chh + gap)
            d.rounded_rectangle([x, y, x + cw, y + chh], 14, fill=PANEL)
            d.text((x + 14, y + 14), label.upper(), font=_f(12), fill=SUB)
            val = fmt(value)
            size = 28 if len(val) < 8 else 22
            d.text((x + 14, y + 46), val, font=_f(size), fill=TXT)
    else:
        c = stats.get("minecraft:custom", {})
        left, right = {
            "blocks": (("Meist abgebaut", top(stats, "mined"), "item"), ("Meist benutzt", top(stats, "used"), "item")),
            "crafting": (("Meist gecraftet", top(stats, "crafted"), "item"), ("Meist aufgesammelt", top(stats, "picked_up"), "item")),
            "combat": (("Getötete Mobs", top(stats, "killed"), "entity"), ("Getötet von", top(stats, "killed_by"), "entity")),
        }[page]
        hw = (AW - 12) // 2
        _list(img, d, X0, Y0, hw, *left)
        _list(img, d, X0 + hw + 12, Y0, hw, *right)
        if page == "combat":
            extra = f"Schaden ausgeteilt {c.get('minecraft:damage_dealt', 0) / 20:.0f} ❤   ·   genommen {c.get('minecraft:damage_taken', 0) / 20:.0f} ❤   ·   Betten {c.get('minecraft:sleep_in_bed', 0)}"
            d.text((X0, H - 22), extra, font=_f(14, False), fill=SUB, anchor="ld")
    d.text((W - 24, H - 8), "Creepy · mc.nwebnotfound.dev", font=_f(12, False), fill=(100, 104, 112), anchor="rd")
    out = io.BytesIO()
    img.convert("RGB").save(out, "PNG")
    return out.getvalue()


def status_line(mtime, online):
    if online:
        return "Gerade online"
    if not mtime:
        return "Noch nie gesehen"
    mins = int((time.time() - mtime) // 60)
    if mins < 60:
        return f"Zuletzt online vor {mins} Min."
    if mins < 1440:
        return f"Zuletzt online vor {mins // 60} Std."
    return "Zuletzt online " + time.strftime("%d.%m.%Y", time.localtime(mtime))


def head(name):
    d = CACHE / "heads"
    d.mkdir(parents=True, exist_ok=True)
    f = d / f"{name}.png"
    try:
        if not f.exists() or time.time() - f.stat().st_mtime > 86400:
            f.write_bytes(_get(f"https://mc-heads.net/avatar/{name}/48", 5))
        return Image.open(f).convert("RGBA")
    except Exception:
        return None


def render_status(online, servers, players, version, uptime):
    """servers: [(name, online, count)], players: [names]"""
    W, H = 1000, 300
    img = Image.new("RGBA", (W, H), BG + (255,))
    d = ImageDraw.Draw(img)
    col = (35, 165, 90) if online else (237, 66, 69)
    for x in range(W):
        t = x / W
        d.line([(x, 0), (x, 6)], fill=(int(88 + 60 * t), int(101 + 100 * t), int(242 - 120 * t)))
    d.text((30, 28), "HangoutNotFound", font=_f(18), fill=SUB)
    d.ellipse([32, 72, 56, 96], fill=col)
    d.text((68, 62), "ONLINE" if online else "OFFLINE", font=_f(40), fill=col)
    d.text((32, 120), "mc.nwebnotfound.dev", font=_f(24), fill=TXT)
    sub = f"Version {version}" + (f"  ·  Uptime {uptime}" if uptime and online else "")
    d.text((32, 156), sub, font=_f(15, False), fill=SUB)
    d.text((W - 30, 30), f"{len(players)}/20", font=_f(44), fill=TXT, anchor="ra")
    d.text((W - 30, 84), "SPIELER ONLINE", font=_f(13), fill=SUB, anchor="ra")
    for i, (sname, son, cnt) in enumerate(servers):
        x = 520 + i * 235
        d.rounded_rectangle([x, 112, x + 225, 172], 12, fill=PANEL)
        d.ellipse([x + 14, 134, x + 26, 146], fill=(35, 165, 90) if son else (237, 66, 69))
        d.text((x + 36, 128), sname, font=_f(18), fill=TXT)
        d.text((x + 211, 128), str(cnt) if son else "aus", font=_f(18), fill=TXT if son else SUB, anchor="ra")
    d.rounded_rectangle([20, 190, W - 20, H - 20], 14, fill=PANEL)
    if not players:
        d.text((40, 230), "Gerade ist niemand online – sei der Erste!", font=_f(18, False), fill=SUB)
    for i, name in enumerate(players[:8]):
        x = 36 + i * 118
        h = head(name)
        if h:
            img.alpha_composite(h.resize((48, 48), Image.NEAREST), (x + 30, 202))
        d.text((x + 54, 258), _fit(d, name, _f(13, False), 110), font=_f(13, False), fill=TXT, anchor="mt")
    if len(players) > 8:
        d.text((W - 40, 230), f"+{len(players) - 8}", font=_f(20), fill=SUB, anchor="ra")
    out = io.BytesIO()
    img.convert("RGB").save(out, "PNG")
    return out.getvalue()


def fmt_ms(ms):
    m, rest = divmod(int(ms), 60000)
    return f"{m:02d}:{rest // 1000:02d}.{rest % 1000:03d}"


def render_leaderboard(title, rows, me=None):
    """rows: [(rank, name, best_ms)], me: optional (rank, name, best_ms) ausserhalb der Top 10"""
    W = 1000
    H = 150 + max(1, len(rows)) * 50 + (70 if me else 0) + 30
    img = Image.new("RGBA", (W, H), BG + (255,))
    d = ImageDraw.Draw(img)
    for x in range(W):
        t = x / W
        d.line([(x, 0), (x, 6)], fill=(int(88 + 60 * t), int(101 + 100 * t), int(242 - 120 * t)))
    d.text((30, 28), "HangoutNotFound \u00b7 Lobby", font=_f(16), fill=SUB)
    d.text((30, 54), title, font=_f(36), fill=TXT)
    d.text((W - 30, 40), "BESTENLISTE", font=_f(15), fill=ACC, anchor="ra")
    medals = {1: (255, 196, 0), 2: (192, 199, 208), 3: (205, 127, 50)}
    y = 120
    if not rows:
        d.rounded_rectangle([20, y, W - 20, y + 50], 12, fill=PANEL)
        d.text((40, y + 14), "Noch keine Zeiten \u2013 lauf den Parkour als Erste:r!", font=_f(18, False), fill=SUB)
    def row(y, rank, name, ms, hl=False):
        d.rounded_rectangle([20, y, W - 20, y + 44], 12, fill=(52, 55, 70) if hl else PANEL)
        c = medals.get(rank)
        if c:
            d.ellipse([34, y + 8, 62, y + 36], fill=c)
            d.text((48, y + 22), str(rank), font=_f(16), fill=(30, 31, 34), anchor="mm")
        else:
            d.text((48, y + 22), str(rank), font=_f(18), fill=SUB, anchor="mm")
        h = head(name)
        if h:
            img.alpha_composite(h.resize((32, 32), Image.NEAREST), (80, y + 6))
        d.text((126, y + 10), name, font=_f(20), fill=TXT)
        d.text((W - 40, y + 10), fmt_ms(ms), font=_f(20), fill=c or TXT, anchor="ra")
    for rank, name, ms in rows:
        row(y, rank, name, ms, me is not None and False)
        y += 50
    if me:
        d.text((30, y + 8), "DEIN PLATZ", font=_f(12), fill=SUB)
        row(y + 26, *me, hl=True)
    d.text((W - 24, H - 8), "Creepy \u00b7 mc.nwebnotfound.dev", font=_f(12, False), fill=(100, 104, 112), anchor="rd")
    out = io.BytesIO()
    img.convert("RGB").save(out, "PNG")
    return out.getvalue()

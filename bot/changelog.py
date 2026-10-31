"""Changelog: postet neue Eintraege aus changelog.json in CHANGELOG_CHANNEL_ID (einmalig, Status in data/)."""
import json, logging, os
from pathlib import Path
import discord

log = logging.getLogger("changelog")
SRC = Path(__file__).with_name("changelog.json")
STATE = Path("/app/data/changelog-posted.json")
def load_json(path):
    """changelog*.json immer als UTF-8 lesen, BOM (utf-8-sig, z.B. aus Windows-Editor) wird toleriert."""
    return json.loads(Path(path).read_bytes().decode("utf-8-sig"))


def _cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def table_fields(e, text, limit=24):
    """Markdown-Tabelle (| a | b |) oder ```-Block -> Embed-Felder statt breitem Codeblock. True wenn umgesetzt."""
    lines = [l for l in str(text or "").replace("```", "").splitlines() if l.strip()]
    rows = [l for l in lines if l.strip().startswith("|")]
    if len(rows) < 2:
        return False
    head = _cells(rows[0])
    body = [_cells(r) for r in rows[1:] if not set(r.replace("|", "").strip()) <= set("-: ")]
    intro = "\n".join(l for l in lines if not l.strip().startswith("|")).strip()
    if intro:
        e.add_field(name="\u200b", value=intro[:1024], inline=False)
    for r in body[:max(0, min(limit, 25 - len(e.fields)))]:
        name = (r[0] or "\u2013")[:256]
        val = " \u00b7 ".join(f"{h}: **{v}**" for h, v in zip(head[1:], r[1:]) if v) or "\u2013"
        e.add_field(name=name, value=val[:1024], inline=True)
    return True


GROUPS = (("neu", "\u2728 Neu"), ("verbessert", "\U0001F527 Verbessert"), ("fix", "\U0001F41B Fix"))
AREAS = (("minecraft", "\u26CF\uFE0F Minecraft"), ("discord", "\U0001F916 Discord"))
COLOR = 0xFFAA00
FOOTER = "Creepy \u00b7 Changelog"


def render(date, items, table=None, version=None):
    """Einheitliches Format fuer ALLE Changelog-Posts:
    Titel "Update DD.MM.YYYY \u00b7 vX.Y" (Version optional), Felder "<Bereich> \u00b7 <Art>", gleiche Farbe, gleicher Footer.
    items: [{"area": "minecraft"|"discord", "type": "neu"|"verbessert"|"fix", "text": ...}]"""
    e = discord.Embed(title=f"Update {date}" + (f" \u00b7 v{version}" if version else ""), color=COLOR)
    for c in items:
        if _is_table(c.get("text")):
            table_fields(e, c["text"])
    for akey, alabel in AREAS:
        for gkey, glabel in GROUPS:
            lines = [c["text"] for c in items if c.get("area", "discord") == akey
                     and c.get("type", "neu") == gkey and not _is_table(c.get("text"))]
            chunk, part = [], 0
            for t in lines + [None]:
                if t is None or sum(len(x) + 3 for x in chunk) + len(t) + 3 > 1000:
                    if chunk:
                        e.add_field(name=f"{alabel} \u00b7 {glabel}" + (f" ({part + 1})" if part else ""),
                                    value="\n".join("\u2022 " + x for x in chunk), inline=False)
                        part += 1
                    chunk = []
                if t is not None:
                    chunk.append(t)
    if table:
        table_fields(e, table)
    e.set_footer(text=FOOTER)
    return e


def embed(entry):
    """Eintrag aus changelog.json; "area" pro Change optional (Default discord)."""
    return render(entry["date"], [dict(c, area=c.get("area", "discord")) for c in entry["changes"]], entry.get("table"), entry.get("version"))


def _is_table(text):
    t = str(text or "")
    return "```" in t or sum(1 for l in t.splitlines() if l.strip().startswith("|")) >= 2


async def post_new(bot, force_version=None):
    cid = os.getenv("CHANGELOG_CHANNEL_ID", "").strip()
    if not cid:
        return 0
    entries = load_json(SRC)
    posted = set(json.loads(STATE.read_text(encoding="utf-8"))) if STATE.exists() else set()
    channel = bot.get_channel(int(cid)) or await bot.fetch_channel(int(cid))
    n = 0
    for entry in entries:
        v = entry["version"]
        if (force_version and v != force_version) or (not force_version and v in posted):
            continue
        if entry.get("requires") == "ah_data" and not force_version:
            try:
                import shops
                if not shops.query("SELECT 1 FROM v_ah_listings LIMIT 1"):
                    continue
            except Exception:
                continue
        if entry.get("requires") == "shop_views" and not force_version:
            try:
                import shops
                shops.query("SELECT 1 FROM v_shop_events LIMIT 1")
            except Exception:
                continue  # erst posten, wenn die Shop-Views existieren
        if entry.get("requires") == "market_views" and not force_version:
            try:
                import shops
                shops.query("SELECT 1 FROM v_market LIMIT 1")
                shops.query("SELECT 1 FROM v_market_history LIMIT 1")
            except Exception:
                continue  # erst posten, wenn die Markt-Views existieren
        await channel.send(embed=embed(entry))
        posted.add(v)
        STATE.write_text(json.dumps(sorted(posted)), encoding="utf-8")
        n += 1
        log.info("Changelog v%s gepostet", v)
    return n

MC_SRC = Path("/app/mc/changelog-mc.json")


def mc_embed(entry):
    """Eintrag aus changelog-mc.json -> gleiches Format wie embed()."""
    items = [dict(c, area="minecraft") for c in entry.get("changes", [])]
    items += [{"area": "discord", "type": "neu", "text": t} for t in entry.get("discord", [])]
    return render(entry["date"], items, entry.get("table"), entry.get("version"))


async def post_mc(bot):
    cid = os.getenv("CHANGELOG_CHANNEL_ID", "").strip()
    if not cid or not MC_SRC.exists():
        return 0
    try:
        entries = load_json(MC_SRC)
    except Exception:
        log.warning("changelog-mc.json ist kein g\u00fcltiges JSON - \u00fcbersprungen")
        return 0
    posted = set(json.loads(STATE.read_text(encoding="utf-8"))) if STATE.exists() else set()
    channel = bot.get_channel(int(cid)) or await bot.fetch_channel(int(cid))
    n = 0
    for entry in entries:
        key = "mc:" + str(entry["version"])
        if key in posted:
            continue
        await channel.send(embed=mc_embed(entry))
        posted.add(key)
        STATE.write_text(json.dumps(sorted(posted)), encoding="utf-8")
        n += 1
        log.info("MC-Changelog %s gepostet", entry["version"])
    return n


async def watch(bot, every=60):
    import asyncio
    await bot.wait_until_ready()
    while not bot.is_closed():
        try:
            await post_mc(bot)
        except Exception:
            log.exception("MC-Changelog fehlgeschlagen")
        await asyncio.sleep(every)
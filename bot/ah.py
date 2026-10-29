"""Auktionshaus auf Discord (Anzeige + Kaufen per Discord, siehe buy.py). Liest v_ah_listings (CraftCore, read-only)."""
import asyncio, json, logging, time
from pathlib import Path
import discord
from discord import app_commands
import shops
import stats_card as sc

log = logging.getLogger("ah")
STATE = Path("/app/data/ah-seen.json")
SOON = "🏛️ Das Auktionshaus kommt bald – sobald es live ist, siehst du es hier."


def ts(v):
    v = int(v or 0)
    return v / 1000 if v > 1e12 else v


def left(exp):
    s = int(ts(exp) - time.time())
    if s <= 0:
        return "abgelaufen"
    d, r = divmod(s, 86400); h, r = divmod(r, 3600); m = r // 60
    return f"noch {d}d {h}h" if d else f"noch {h}h {m}m" if h else f"noch {m}m"


def rows(order="new", search=None):
    sql = "SELECT id, seller, seller_name, material, amount, price_cents, created_at_ms, expires_at_ms FROM v_ah_listings"
    a = []
    if search:
        sql += " WHERE LOWER(material) LIKE %s"
        key = shops.norm_item(search) or search.lower().replace(" ", "_")
        a.append("%" + key + "%")
    sql += " ORDER BY " + ("expires_at_ms ASC" if order == "ending" else "created_at_ms DESC") + " LIMIT 200"
    out = []
    for lid, suuid, seller, mat, amount, cents, created, exp in shops.query(sql, a):
        if ts(exp) and ts(exp) < time.time():
            continue
        out.append((seller, mat, f"{sc.pretty(str(mat).lower())} · {left(exp)}", amount, (cents or 0) / 100, created, ("ah", lid, int(cents or 0))))
    return out


def register(bot, tree):
    @tree.command(name="ah", description="Auktionen ansehen, per Discord bieten oder sofort kaufen")
    @app_commands.describe(ansicht="Auktionen mit Bieten (Standard) oder Sofortkauf-Angebote", sortierung="Neueste oder bald endend", suche="Item suchen")
    @app_commands.choices(ansicht=[app_commands.Choice(name="Auktionen (bieten)", value="bid"), app_commands.Choice(name="Sofort kaufen", value="buy")])
    @app_commands.choices(sortierung=[app_commands.Choice(name="Neueste", value="new"), app_commands.Choice(name="Endet bald", value="ending")])
    async def ah(interaction: discord.Interaction, ansicht: str = None, sortierung: str = "new", suche: str = None):
        await interaction.response.defer()
        if ansicht == "bid" or (ansicht is None and not suche and sortierung == "new"):
            import bid
            return await bid.show(bot, interaction)
        try:
            data = await asyncio.to_thread(rows, sortierung, suche)
        except shops.NotReady:
            return await interaction.followup.send(SOON)
        title = f"AH-Suche: {suche}" if suche else ("Auktionshaus · endet bald" if sortierung == "ending" else "Auktionshaus · neueste")
        await shops.send(interaction, title, f"{len(data)} Angebote · Sofortkauf-Preis", data)


async def alarm_loop(bot):
    """Neue AH-Angebote gegen /alarm pruefen."""
    await bot.wait_until_ready()
    while not bot.is_closed():
        try:
            res = await asyncio.to_thread(shops.query, "SELECT id, seller, seller_name, material, amount, price_cents, created_at_ms FROM v_ah_listings ORDER BY created_at_ms")
            st = json.loads(STATE.read_text()) if STATE.exists() else None
            if st is None:  # Erststart: Bestand nicht melden
                st = {"last": max([int(r[6] or 0) for r in res] or [0])}
            else:
                for lid, suuid, seller, mat, amount, cents, created in res:
                    if int(created or 0) > st["last"]:
                        await shops.check_alarms(bot, "ah:" + str(lid), seller, suuid, mat, None, amount, (cents or 0) / 100, hint="/ah")
                        st["last"] = int(created)
            STATE.write_text(json.dumps(st))
            if res and not getattr(bot, "_ah_changelog", False):
                bot._ah_changelog = True
                import changelog
                await changelog.post_new(bot)
        except shops.NotReady:
            await asyncio.sleep(240)
        except Exception:
            log.exception("AH-Alarme fehlgeschlagen")
        await asyncio.sleep(30)
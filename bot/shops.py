"""Spielershops auf Discord (Anzeige + Kaufen per Discord, siehe buy.py). Liest v_shop_listings / v_shop_events (CraftCore, read-only)."""
import asyncio, io, json, logging, os, re, time
from pathlib import Path
import discord
from discord import app_commands
from PIL import Image, ImageDraw
import stats_card as sc

log = logging.getLogger("shops")
STATE = Path("/app/data/shop-events.json")
SOON = "🛒 Shops kommen bald – sobald die Spielershops live sind, siehst du sie hier."
PER_PAGE = 8
_cache = {"t": 0, "sellers": []}


class NotReady(Exception):
    pass


def db_password():
    """creepy_ro's own password, or the CraftCore writer password when that is unset."""
    return os.getenv("CREEPY_DB_PASSWORD") or os.getenv("CRAFTCORE_DB_PASSWORD", "")


def db_user():
    return os.getenv("CRAFTCORE_DB_USER", "creepy_ro")


def query(sql, args=()):
    import pymysql
    pw = db_password()
    if not pw:
        raise NotReady()
    try:
        con = pymysql.connect(host=os.getenv("CRAFTCORE_DB_HOST", "craftcore-db"), user=db_user(),
                              password=pw, database="craftcore", connect_timeout=5, read_timeout=5)
    except Exception:
        raise NotReady()
    try:
        with con.cursor() as cur:
            cur.execute(sql, args)
            return cur.fetchall()
    except (pymysql.err.ProgrammingError, pymysql.err.OperationalError):
        raise NotReady()  # View fehlt oder keine Rechte
    finally:
        con.close()


def listings(seller=None, search=None):
    # v_shop_listings (CraftCore): id, seller, seller_name, material, amount, price_cents - nur offene Angebote
    sql = "SELECT seller_name, material, NULL, amount, price_cents, 0, id FROM v_shop_listings WHERE 1=1"
    a = []
    if seller:
        sql += " AND LOWER(seller_name)=LOWER(%s)"; a.append(seller)
    if search:
        key = norm_item(search) or search.lower().replace(" ", "_")
        sql += " AND LOWER(material) LIKE %s"; a.append("%" + key + "%")
    sql += " ORDER BY seller_name, material LIMIT 200"
    return [(s, m, d, am, (c or 0) / 100, t, ("shop", lid, int(c or 0))) for s, m, d, am, c, t, lid in query(sql, a)]


def sellers():
    if time.time() - _cache["t"] > 60:
        try:
            _cache["sellers"] = [r[0] for r in query("SELECT seller_name, COUNT(*) c FROM v_shop_listings GROUP BY seller_name ORDER BY c DESC")]
        except NotReady:
            _cache["sellers"] = []
        _cache["t"] = time.time()
    return _cache["sellers"]


def item_name(mat, disp):
    return disp or sc.pretty(str(mat).lower())


def price(p):
    p = float(p)
    return (f"{p:,.0f}" if p == int(p) else f"{p:,.2f}").replace(",", ".") + " CP"


def render(title, sub, rows, page, pages):
    W, H = 1000, 140 + max(1, len(rows)) * 62 + 40
    img = Image.new("RGBA", (W, H), sc.BG + (255,))
    d = ImageDraw.Draw(img)
    for x in range(W):
        t = x / W
        d.line([(x, 0), (x, 6)], fill=(int(87 + 80 * t), int(242 - 60 * t), int(135 - 40 * t)))
    d.text((30, 28), sub, font=sc._f(16), fill=sc.SUB)
    d.text((30, 54), title, font=sc._f(34), fill=sc.TXT)
    d.text((W - 30, 40), f"SEITE {page + 1}/{pages}", font=sc._f(15), fill=sc.ACC, anchor="ra")
    y = 116
    if not rows:
        d.rounded_rectangle([20, y, W - 20, y + 54], 12, fill=sc.PANEL)
        d.text((40, y + 16), "Gerade keine Angebote.", font=sc._f(18, False), fill=sc.SUB)
    for seller, mat, disp, amount, pr, *_ in rows:
        d.rounded_rectangle([20, y, W - 20, y + 54], 12, fill=sc.PANEL)
        ic = sc.icon(str(mat).lower())
        if ic:
            img.alpha_composite(ic.resize((38, 38), Image.NEAREST), (34, y + 8))
        d.text((88, y + 6), sc._fit(d, f"{amount}× {item_name(mat, disp)}", sc._f(20), 560), font=sc._f(20), fill=sc.TXT)
        d.text((88, y + 32), f"von {seller}", font=sc._f(14, False), fill=sc.SUB)
        d.text((W - 40, y + 16), price(pr), font=sc._f(20), fill=(87, 242, 135), anchor="ra")
        y += 62
    d.text((W - 24, H - 8), "Kaufen: Men\u00fc unten \u00b7 abholen ingame mit /abholen \u00b7 mc.nwebnotfound.dev", font=sc._f(12, False), fill=(100, 104, 112), anchor="rd")
    out = io.BytesIO(); img.convert("RGB").save(out, "PNG")
    return out.getvalue()


def buy_options(part):
    """Select-Optionen fuer Zeilen mit Kauf-Info (7. Feld: (kind, listing_id, preis_cent))"""
    out = []
    for r in part:
        if len(r) < 7 or not r[6]:
            continue
        kind, lid, cents = r[6]
        label = f"{r[3]}\u00d7 {item_name(r[1], r[2])} \u2013 {price(cents / 100)}"
        out.append(discord.SelectOption(label=label[:100], description=f"von {r[0]}"[:100], value=f"{kind}:{lid}", emoji="\U0001F6D2"))
    return out


class Pager(discord.ui.View):
    def __init__(self, title, sub, rows, bot=None):
        super().__init__(timeout=600)
        self.bot = bot
        self.title, self.sub, self.rows, self.page = title, sub, rows, 0
        self.pages = max(1, (len(rows) + PER_PAGE - 1) // PER_PAGE)
        self.sync()

    def sync(self):
        self.prev.disabled = self.page == 0
        self.next.disabled = self.page >= self.pages - 1
        if getattr(self, "buy_select", None):
            self.remove_item(self.buy_select)
        self.buy_select = None
        opts = buy_options(self.rows[self.page * PER_PAGE:(self.page + 1) * PER_PAGE])
        if opts and getattr(self, "bot", None):
            import buy
            self.buy_select = buy.BuySelect(self.bot, opts)
            self.add_item(self.buy_select)

    async def file(self):
        part = self.rows[self.page * PER_PAGE:(self.page + 1) * PER_PAGE]
        png = await asyncio.to_thread(render, self.title, self.sub, part, self.page, self.pages)
        return discord.File(io.BytesIO(png), filename="shop.png")

    async def flip(self, interaction, delta):
        self.page += delta; self.sync()
        await interaction.response.edit_message(attachments=[await self.file()], view=self)

    @discord.ui.button(label="◀", style=discord.ButtonStyle.secondary)
    async def prev(self, interaction, _):
        await self.flip(interaction, -1)

    @discord.ui.button(label="▶", style=discord.ButtonStyle.secondary)
    async def next(self, interaction, _):
        await self.flip(interaction, 1)


async def send(interaction, title, sub, rows):
    v = Pager(title, sub, rows, interaction.client)
    await interaction.followup.send(file=await v.file(), view=v if (v.pages > 1 or v.buy_select) else discord.utils.MISSING)


def register(bot, tree):
    register_alarms(bot, tree)
    async def seller_ac(interaction, current):
        names = await asyncio.to_thread(sellers)
        return [app_commands.Choice(name=n, value=n) for n in names if current.lower() in n.lower()][:25]

    @tree.command(name="shop", description="Shop eines Spielers ansehen und per Discord kaufen")
    @app_commands.describe(spieler="Verkäufer (Standard: dein verknüpftes Konto)", suche="Item suchen")
    @app_commands.autocomplete(spieler=seller_ac)
    async def shop(interaction: discord.Interaction, spieler: str = None, suche: str = None):
        await interaction.response.defer()
        if not spieler and not suche:
            row = bot.db.execute("SELECT name FROM links WHERE discord_id=? AND state='active'", (interaction.user.id,)).fetchone()
            if not row:
                return await interaction.followup.send("Gib `spieler:<Name>` an oder verknüpfe dein Konto mit `/link`.")
            spieler = row[0]
        try:
            rows = await asyncio.to_thread(listings, spieler, suche)
        except NotReady:
            return await interaction.followup.send(SOON)
        if suche:
            await send(interaction, f"Suche: {suche}", f"{len(rows)} Angebote" + (f" · {spieler}" if spieler else ""), rows)
        else:
            await send(interaction, f"Shop von {spieler}", f"{len(rows)} Angebote", rows)

    @tree.command(name="shops", description="Alle Spielershops auf einen Blick")
    async def shops_cmd(interaction: discord.Interaction):
        await interaction.response.defer()
        try:
            rows = await asyncio.to_thread(listings)
            top = await asyncio.to_thread(query, "SELECT seller_name, COUNT(*) FROM v_shop_listings GROUP BY seller_name ORDER BY 2 DESC LIMIT 5")
        except NotReady:
            return await interaction.followup.send(SOON)
        sub = "Top-Verkäufer: " + (", ".join(f"{n} ({c})" for n, c in top) or "–")
        await send(interaction, "Neueste Angebote", sub[:90], rows)


async def event_loop(bot):
    await bot.wait_until_ready()
    cid = int(os.getenv("SHOP_CHANNEL_ID") or bot.cfg["CHAT_CHANNEL_ID"])
    while not bot.is_closed():
        try:
            last = json.loads(STATE.read_text()).get("last") if STATE.exists() else None
            if last is None:  # Erststart: keine Altlasten posten
                r = await asyncio.to_thread(query, "SELECT COALESCE(MAX(event_id),0) FROM v_shop_events")
                STATE.write_text(json.dumps({"last": int(r[0][0])}))
                log.info("Shop-Events bereit (ab ID %s)", r[0][0])
                try:
                    import changelog
                    await changelog.post_new(bot)  # gesperrte Eintraege (requires shop_views) jetzt posten
                except Exception:
                    log.exception("Changelog nach Shop-Start fehlgeschlagen")
            else:
                rows = await asyncio.to_thread(query, "SELECT e.event_id, e.type, e.seller_name, e.buyer_name, e.price, l.item_material, l.item_display_name, l.amount, e.listing_id, l.seller_uuid "
                                               "FROM v_shop_events e LEFT JOIN v_shop_listings l ON l.listing_id=e.listing_id "
                                               "WHERE e.event_id > %s AND e.type IN ('CREATED','SOLD') ORDER BY e.event_id LIMIT 20", (last,))
                ch = bot.get_channel(cid) or await bot.fetch_channel(cid)
                for eid, typ, seller, buyer, pr, mat, disp, amount, lid, suuid in rows:
                    esc = discord.utils.escape_markdown
                    item = f"{amount or ''}× {esc(item_name(mat, disp))}" if mat else "ein Item"  # Anvil-Name ist spielergesteuert (#151)
                    if typ == "CREATED":
                        msg = f"-# 🛒 **{esc(seller)}** bietet **{item}** für **{price(pr)}** an"
                    else:
                        msg = f"-# 💰 **{esc(buyer or '?')}** hat **{item}** von **{esc(seller)}** für **{price(pr)}** gekauft"
                    await ch.send(msg, allowed_mentions=discord.AllowedMentions.none())
                    if typ == "CREATED" and mat:
                        await check_alarms(bot, lid, seller, suuid, mat, disp, amount, pr)
                    last = eid
                    STATE.write_text(json.dumps({"last": int(last)}))
        except NotReady:
            await asyncio.sleep(240)  # Views noch nicht da
        except Exception:
            log.exception("Shop-Events fehlgeschlagen")
        await asyncio.sleep(15)

# ---------- Preisalarme ----------
MAX_ALARMS = 10


def init_alarms(db):
    # auto_buy_amount: reserviert fuer spaeteres Auto-Kaufen (bewusst nicht in der UI)
    db.execute("CREATE TABLE IF NOT EXISTS shop_alarms (id INTEGER PRIMARY KEY AUTOINCREMENT, discord_id INTEGER NOT NULL, item TEXT NOT NULL, max_unit REAL NOT NULL, min_amount INTEGER NOT NULL DEFAULT 1, auto_buy_amount INTEGER, created INTEGER NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS shop_alarm_sent (alarm_id INTEGER NOT NULL, listing_id INTEGER NOT NULL, PRIMARY KEY (alarm_id, listing_id))")
    db.commit()


_items = None


def items():
    global _items
    if _items is None:
        L = sc.lang()
        seen = {}
        for k, v in L.items():
            m = re.fullmatch(r"(?:item|block)\.minecraft\.([a-z0-9_]+)", k)
            if m and m.group(1) not in seen:
                seen[m.group(1)] = v
        _items = sorted(seen.items(), key=lambda x: x[1])
    return _items


def norm_item(text):
    t = text.strip().lower().removeprefix("minecraft:")
    for key, name in items():
        if t in (key, name.lower()):
            return key
    return None


async def check_alarms(bot, lid, seller, suuid, mat, disp, amount, pr, hint=None):
    key = str(mat).lower().removeprefix("minecraft:")
    amount = int(amount or 1)
    unit = float(pr) / max(1, amount)
    seller_did = None
    if suuid:
        r = bot.db.execute("SELECT discord_id FROM links WHERE replace(uuid,'-','')=? AND state='active'", (str(suuid).replace("-", ""),)).fetchone()
        seller_did = r[0] if r else None
    rows = bot.db.execute("SELECT id, discord_id FROM shop_alarms WHERE item=? AND max_unit>=? AND min_amount<=?", (key, unit + 1e-9, amount)).fetchall()
    for aid, did in rows:
        if did == seller_did:
            continue  # eigenes Angebot
        try:
            bot.db.execute("INSERT INTO shop_alarm_sent (alarm_id, listing_id) VALUES (?,?)", (aid, lid if isinstance(lid, str) else int(lid)))
            bot.db.commit()
        except Exception:
            continue  # schon benachrichtigt
        e = discord.Embed(title=f"🔔 {discord.utils.escape_markdown(item_name(mat, disp))} im Angebot", color=0x57F287,
                          description=f"**{amount}×** für **{price(pr)}** · {price(unit)} pro Stück")
        e.add_field(name="Verkäufer", value=discord.utils.escape_markdown(seller))
        e.set_footer(text=f"Klick auf Jetzt kaufen oder ingame: {hint or '/shop ' + seller} · Alarm #{aid}")
        e.set_thumbnail(url=f"https://mc.nerothe.com/img/1.21/minecraft_{key}.png")
        try:
            user = bot.get_user(did) or await bot.fetch_user(did)
            import buy
            sl = str(lid)
            await user.send(embed=e, view=buy.buy_view("ah", sl[3:]) if sl.startswith("ah:") else buy.buy_view("shop", sl))
        except Exception:
            log.info("Alarm-DM an %s nicht moeglich", did)


def register_alarms(bot, tree):
    init_alarms(bot.db)
    group = app_commands.Group(name="alarm", description="Preisalarme für Spielershops")

    async def item_ac(interaction, current):
        c = current.lower()
        res = await asyncio.to_thread(lambda: [(k, n) for k, n in items() if c in n.lower() or c in k][:25])
        return [app_commands.Choice(name=f"{n} ({k})"[:100], value=k) for k, n in res]

    @group.command(name="add", description="DM bekommen, wenn ein Item billig angeboten wird - ohne Angaben kommt ein Formular")
    @app_commands.describe(item="Item", max_preis="Höchstpreis in CP pro Stück", min_menge="Mindestmenge im Angebot")
    @app_commands.autocomplete(item=item_ac)
    async def add(interaction: discord.Interaction, item: str = None, max_preis: app_commands.Range[float, 0.01, 1e9] = None, min_menge: app_commands.Range[int, 1, 100000] = 1):
        if not item or max_preis is None:
            import gui
            async def done(i, d):
                mp, mm = gui.to_number(d["p"], float), gui.to_number(d["m"], int, 1)
                if not mp or mp <= 0 or not mm or mm < 1:
                    return await i.response.send_message("Preis und Menge bitte als Zahl, z.B. 2,5 und 16.", ephemeral=True)
                await add.callback(i, item=d["i"], max_preis=mp, min_menge=mm)
            return await interaction.response.send_modal(gui.FormModal("Neuer Preisalarm", [("i", "Item", "z.B. Diamant oder diamond", True), ("p", "H\u00f6chstpreis pro St\u00fcck (CP)", "z.B. 2,5", True), ("m", "Mindestmenge (optional)", "1", False)], done))
        key = await asyncio.to_thread(norm_item, item)
        if not key:
            return await interaction.response.send_message("Das Item kenne ich nicht – nimm einen Vorschlag aus der Liste.", ephemeral=True)
        n = bot.db.execute("SELECT COUNT(*) FROM shop_alarms WHERE discord_id=?", (interaction.user.id,)).fetchone()[0]
        if n >= MAX_ALARMS:
            return await interaction.response.send_message(f"Du hast schon {MAX_ALARMS} Alarme – lösch erst einen mit `/alarm remove`.", ephemeral=True)
        bot.db.execute("INSERT INTO shop_alarms (discord_id, item, max_unit, min_amount, created) VALUES (?,?,?,?,?)", (interaction.user.id, key, float(max_preis), int(min_menge), int(time.time())))
        bot.db.commit()
        await interaction.response.send_message(f"🔔 Alarm gesetzt: **{sc.pretty(key)}** bis **{price(max_preis)}**/Stück" + (f", ab {min_menge} Stück" if min_menge > 1 else "") + ". Ich schreib dir per DM.", ephemeral=True)

    @group.command(name="list", description="Deine Preisalarme ansehen, l\u00f6schen oder neu anlegen")
    async def lst(interaction: discord.Interaction):
        rows = bot.db.execute("SELECT id, item, max_unit, min_amount FROM shop_alarms WHERE discord_id=? ORDER BY id", (interaction.user.id,)).fetchall()
        if not rows:
            return await interaction.response.send_message("Du hast keine Alarme. Leg einen an mit `/alarm add`.", ephemeral=True)
        lines = [f"`#{i}` **{sc.pretty(k)}** ≤ {price(m)}/Stück" + (f" · ab {a} Stk." if a > 1 else "") for i, k, m, a in rows]
        import gui
        v = gui.ChoiceView("Alarm l\u00f6schen ...", [(f"#{i} {sc.pretty(k)}", i) for i, k, m, a in rows], lambda it, val: rm.callback(it, alarm=int(val)), interaction.user.id)
        btn = discord.ui.Button(label="Neuer Alarm", emoji="\u2795", style=discord.ButtonStyle.success)
        btn.callback = lambda it: add.callback(it)
        v.add_item(btn)
        await interaction.response.send_message(embed=discord.Embed(title="🔔 Deine Preisalarme", description="\n".join(lines), color=0x5865F2), view=v, ephemeral=True)

    async def own_ac(interaction, current):
        rows = bot.db.execute("SELECT id, item, max_unit FROM shop_alarms WHERE discord_id=?", (interaction.user.id,)).fetchall()
        return [app_commands.Choice(name=f"#{i} {sc.pretty(k)} ≤ {price(m)}"[:100], value=i) for i, k, m in rows if current in str(i) or current.lower() in k][:25]

    @group.command(name="remove", description="Preisalarm löschen")
    @app_commands.autocomplete(alarm=own_ac)
    async def rm(interaction: discord.Interaction, alarm: int):
        cur = bot.db.execute("DELETE FROM shop_alarms WHERE id=? AND discord_id=?", (alarm, interaction.user.id))
        bot.db.execute("DELETE FROM shop_alarm_sent WHERE alarm_id=? AND NOT EXISTS (SELECT 1 FROM shop_alarms WHERE id=?)", (alarm, alarm))
        bot.db.commit()
        await interaction.response.send_message("🗑️ Alarm gelöscht." if cur.rowcount else "Diesen Alarm hast du nicht.", ephemeral=True)

    tree.add_command(group)

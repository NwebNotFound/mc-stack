"""/markt - Boersenansicht fuer den Servermarkt (v_market / v_market_history, CraftCore read-only)."""
import asyncio, io, logging, os, time
import discord
from discord import app_commands
from PIL import Image, ImageDraw
import stats_card as sc

log = logging.getLogger("markt")
SOON = "\U0001F4C8 Marktdaten kommen bald - sobald der Servermarkt live ist, siehst du hier die Kurse."
WEB = "https://mc.nwebnotfound.dev/markt"
RANGES = {"1T": 1, "7T": 7, "30T": 30, "90T": 90}
UP, DOWN = (87, 242, 135), (237, 66, 69)


class NotReady(Exception):
    pass


def q(sql, args=()):
    """(cols, rows); NotReady wenn DB/View fehlt."""
    import pymysql
    import shops
    pw = shops.db_password()
    if not pw:
        raise NotReady()
    try:
        con = pymysql.connect(host=os.getenv("CRAFTCORE_DB_HOST", "craftcore-db"), user=shops.db_user(),
                              password=pw, database="craftcore", connect_timeout=5, read_timeout=5)
    except Exception:
        raise NotReady()
    try:
        with con.cursor() as cur:
            cur.execute(sql, args)
            return [d[0] for d in cur.description], cur.fetchall()
    except Exception:
        raise NotReady()
    finally:
        con.close()


def market():
    import ingest
    return ingest.build_market(*q("SELECT * FROM v_market"))


def history(item, days, now=None):
    import ingest
    now = int(now or time.time())
    cols, rows = q("SELECT * FROM v_market_history WHERE item IN (%s, %s)", (item, "minecraft:" + item))
    return ingest.build_history(cols, rows, now - days * 86400, now)


def price_of(e):
    return e.get("buy_price", e.get("sell_price", e.get("base_price")))


def movers(items, n=5):
    ch = [e for e in items if e.get("change_24h_pct") is not None]
    gain = sorted([e for e in ch if e["change_24h_pct"] > 0], key=lambda e: -e["change_24h_pct"])[:n]
    lose = sorted([e for e in ch if e["change_24h_pct"] < 0], key=lambda e: e["change_24h_pct"])[:n]
    return gain, lose


def find(items, text):
    t = (text or "").strip().lower().replace(" ", "_")
    if t.startswith("minecraft:"):
        t = t[10:]
    if not t:
        return None
    for e in items:
        if e["item"] == t:
            return e
    for e in items:
        if t in e["item"] or t in sc.pretty(e["item"]).lower().replace(" ", "_"):
            return e
    return None


def cp(v):
    if v is None:
        return "-"
    v = float(v)
    return (f"{v:,.0f}" if v == int(v) else f"{v:,.2f}").replace(",", "X").replace(".", ",").replace("X", ".") + " CP"


def pct(v):
    if v is None:
        return "-"
    arrow = "\u25B2" if v > 0 else ("\u25BC" if v < 0 else "\u25CF")
    return f"{arrow} {abs(v):.2f} %".replace(".", ",")


def _icon(img, item, x, y, size=34):
    try:
        ic = sc.icon(item)
        if ic:
            img.alpha_composite(ic.resize((size, size), Image.NEAREST), (x, y))
    except Exception:
        pass


def render_overview(gain, lose, total):
    W, rowh = 1000, 54
    n = max(len(gain), len(lose), 1)
    H = 150 + n * rowh + 50
    img = Image.new("RGBA", (W, H), sc.BG + (255,))
    d = ImageDraw.Draw(img)
    for x in range(W):
        t = x / W
        d.line([(x, 0), (x, 6)], fill=(int(87 + 150 * t), int(242 - 176 * t), int(135 - 66 * t)))
    d.text((30, 26), f"SERVERMARKT \u00b7 {total} ITEMS \u00b7 24 H", font=sc._f(16), fill=sc.SUB)
    d.text((30, 50), "B\u00f6rse", font=sc._f(34), fill=sc.TXT)
    for col, (title, lst, color) in enumerate((("\u25B2 Top-Gewinner", gain, UP), ("\u25BC Top-Verlierer", lose, DOWN))):
        x0 = 20 + col * 490
        d.text((x0 + 10, 108), title, font=sc._f(20), fill=color)
        y = 140
        if not lst:
            d.rounded_rectangle([x0, y, x0 + 470, y + rowh - 8], 10, fill=sc.PANEL)
            d.text((x0 + 16, y + 14), "Gerade keine Bewegung.", font=sc._f(16, False), fill=sc.SUB)
        for e in lst:
            d.rounded_rectangle([x0, y, x0 + 470, y + rowh - 8], 10, fill=sc.PANEL)
            _icon(img, e["item"], x0 + 8, y + 6)
            d.text((x0 + 52, y + 4), sc._fit(d, sc.pretty(e["item"]), sc._f(17), 220), font=sc._f(17), fill=sc.TXT)
            vol = e.get("volume_24h_qty")
            d.text((x0 + 52, y + 26), f"Vol. {int(vol)} Stk." if vol is not None else "Vol. -", font=sc._f(12, False), fill=sc.SUB)
            d.text((x0 + 455, y + 4), cp(price_of(e)), font=sc._f(17), fill=sc.TXT, anchor="ra")
            d.text((x0 + 455, y + 26), pct(e["change_24h_pct"]), font=sc._f(14), fill=color, anchor="ra")
            y += rowh
    d.text((W - 24, H - 10), "Item w\u00e4hlen: Men\u00fc unten \u00b7 mc.nwebnotfound.dev/markt", font=sc._f(12, False), fill=(100, 104, 112), anchor="rd")
    out = io.BytesIO(); img.convert("RGB").save(out, "PNG")
    return out.getvalue()


def render_chart(e, hist, label):
    W, H = 1000, 520
    L, R, T, B = 90, 970, 140, 470
    img = Image.new("RGBA", (W, H), sc.BG + (255,))
    d = ImageDraw.Draw(img)
    ch = e.get("change_24h_pct") or 0
    color = UP if ch >= 0 else DOWN
    _icon(img, e["item"], 30, 26, 48)
    d.text((92, 22), sc.pretty(e["item"]), font=sc._f(30), fill=sc.TXT)
    d.text((92, 62), f"Kaufen {cp(e.get('buy_price'))} \u00b7 Verkaufen {cp(e.get('sell_price'))} \u00b7 Vol. 24h {e.get('volume_24h_qty', '-')} Stk. \u00b7 {e.get('trades_24h', '-')} Trades", font=sc._f(14, False), fill=sc.SUB)
    d.text((W - 30, 24), cp(price_of(e)), font=sc._f(28), fill=sc.TXT, anchor="ra")
    d.text((W - 30, 60), pct(e.get("change_24h_pct")) + " (24h)", font=sc._f(16), fill=color, anchor="ra")
    d.text((W - 30, 100), label, font=sc._f(15), fill=sc.ACC, anchor="ra")
    d.rounded_rectangle([L - 10, T - 20, R + 10, B + 10], 12, fill=sc.PANEL)
    pts = [h for h in hist if h.get("avg_price") is not None]
    if len(pts) < 2:
        d.text(((L + R) // 2, (T + B) // 2), "Noch zu wenig Kursdaten f\u00fcr diesen Zeitraum.", font=sc._f(18, False), fill=sc.SUB, anchor="mm")
    else:
        lo = min(h.get("min_price", h["avg_price"]) for h in pts)
        hi = max(h.get("max_price", h["avg_price"]) for h in pts)
        if hi - lo < 1e-9:
            hi, lo = hi + 1, max(0, lo - 1)
        t0, t1 = pts[0]["hour_ts"], pts[-1]["hour_ts"]
        span = max(1, t1 - t0)
        X = lambda t: L + (t - t0) / span * (R - L)
        Y = lambda v: B - (v - lo) / (hi - lo) * (B - T)
        for i in range(5):
            v = lo + (hi - lo) * i / 4
            d.line([(L, Y(v)), (R, Y(v))], fill=(60, 63, 70))
            d.text((L - 8, Y(v)), cp(v).replace(" CP", ""), font=sc._f(12, False), fill=sc.SUB, anchor="rm")
        band = [(X(h["hour_ts"]), Y(h.get("max_price", h["avg_price"]))) for h in pts] + \
               [(X(h["hour_ts"]), Y(h.get("min_price", h["avg_price"]))) for h in reversed(pts)]
        ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(ov).polygon(band, fill=color + (50,))
        img.alpha_composite(ov)
        d = ImageDraw.Draw(img)
        line = [(X(h["hour_ts"]), Y(h["avg_price"])) for h in pts]
        d.line(line, fill=color, width=3, joint="curve")
        for t, anchor in ((t0, "la"), (t1, "ra")):
            d.text((X(t), B + 16), time.strftime("%d.%m. %H:%M", time.localtime(t)), font=sc._f(12, False), fill=sc.SUB, anchor=anchor)
    out = io.BytesIO(); img.convert("RGB").save(out, "PNG")
    return out.getvalue()


async def overview_payload(bot):
    items = await asyncio.to_thread(market)
    if not items:
        raise NotReady()
    gain, lose = movers(items)
    png = await asyncio.to_thread(render_overview, gain, lose, len(items))
    return discord.File(io.BytesIO(png), filename="markt.png"), MarketView(bot, items, gain, lose)


async def item_payload(bot, e, rng="7T"):
    hist = await asyncio.to_thread(history, e["item"], RANGES[rng])
    png = await asyncio.to_thread(render_chart, e, hist, rng)
    return discord.File(io.BytesIO(png), filename="kurs.png"), ItemView(bot, e, rng)


async def show_item(bot, interaction, items, text, edit=False):
    e = find(items, text)
    if not e:
        return await interaction.response.send_message(f"\u201e{text}\u201c finde ich nicht im Markt.", ephemeral=True)
    await interaction.response.defer()
    try:
        f, v = await item_payload(bot, e)
    except NotReady:
        return await interaction.followup.send(SOON, ephemeral=True)
    if edit:
        await interaction.edit_original_response(attachments=[f], view=v)
    else:
        await interaction.followup.send(file=f, view=v)


class ItemSelect(discord.ui.Select):
    def __init__(self, bot, items, opts):
        super().__init__(placeholder="\U0001F50E Item ansehen ...", options=opts[:25], row=0)
        self.bot, self.items = bot, items

    async def callback(self, interaction):
        await show_item(self.bot, interaction, self.items, self.values[0])


class MarketView(discord.ui.View):
    def __init__(self, bot, items, gain, lose):
        super().__init__(timeout=900)
        self.bot, self.items = bot, items
        pick, seen = [], set()
        rest = sorted(items, key=lambda e: -(e.get("volume_24h_cp") or 0))
        for e in gain + lose + rest:
            if e["item"] not in seen and len(pick) < 25:
                seen.add(e["item"]); pick.append(e)
        opts = [discord.SelectOption(label=sc.pretty(e["item"])[:100], value=e["item"],
                                     description=f"{cp(price_of(e))} \u00b7 {pct(e.get('change_24h_pct'))}"[:100]) for e in pick]
        if opts:
            self.add_item(ItemSelect(bot, items, opts))
        self.add_item(discord.ui.Button(label="Web-Markt", url=WEB, emoji="\U0001F310", row=1))

    @discord.ui.button(label="Item suchen", emoji="\U0001F50D", style=discord.ButtonStyle.primary, row=1)
    async def search(self, interaction, _):
        import gui
        async def done(i, data):
            await show_item(self.bot, i, self.items, data["i"])
        await interaction.response.send_modal(gui.FormModal("Item im Markt suchen", [("i", "Item", "z.B. Diamant oder diamond", True)], done))

    @discord.ui.button(label="Aktualisieren", emoji="\U0001F504", style=discord.ButtonStyle.secondary, row=1)
    async def refresh(self, interaction, _):
        await interaction.response.defer()
        try:
            f, v = await overview_payload(self.bot)
        except NotReady:
            return await interaction.followup.send(SOON, ephemeral=True)
        await interaction.edit_original_response(attachments=[f], view=v)


class RangeButton(discord.ui.Button):
    def __init__(self, rng, active):
        super().__init__(label=rng, style=discord.ButtonStyle.success if active else discord.ButtonStyle.secondary, row=0)
        self.rng = rng

    async def callback(self, interaction):
        v = self.view
        await interaction.response.defer()
        try:
            f, nv = await item_payload(v.bot, v.e, self.rng)
        except NotReady:
            return await interaction.followup.send(SOON, ephemeral=True)
        await interaction.edit_original_response(attachments=[f], view=nv)


class ItemView(discord.ui.View):
    def __init__(self, bot, e, rng):
        super().__init__(timeout=900)
        self.bot, self.e = bot, e
        for r in RANGES:
            self.add_item(RangeButton(r, r == rng))
        self.add_item(discord.ui.Button(label="Auf der Website", url=f"{WEB}?item={e['item']}", emoji="\U0001F310", row=1))
        import bank
        if bank.enabled() and e.get("buy_price") is not None:
            self.add_item(BankButton(bot, e))

    @discord.ui.button(label="Preisalarm", emoji="\U0001F514", style=discord.ButtonStyle.primary, row=1)
    async def alarm(self, interaction, _):
        import gui
        cmd = gui.find_command(self.bot.tree, "alarm add")
        if cmd is None:
            return await interaction.response.send_message("Preisalarme gibt's mit `/alarm add`.", ephemeral=True)
        await cmd.callback(interaction)

    @discord.ui.button(label="Kaufen", emoji="\U0001F6D2", style=discord.ButtonStyle.success, row=1)
    async def kaufen(self, interaction, _):
        import shops
        try:
            rows = await asyncio.to_thread(shops.listings, None, self.e["item"])
        except shops.NotReady:
            rows = []
        rows = sorted([r for r in rows if str(r[1]).lower().removeprefix("minecraft:") == self.e["item"]], key=lambda r: r[4] / max(1, r[3]))[:25]
        opts = shops.buy_options(rows)
        if not opts:
            return await interaction.response.send_message(f"Gerade bietet kein Spielershop **{sc.pretty(self.e['item'])}** an. Setz dir einen Preisalarm \U0001F514", ephemeral=True)
        import buy
        v = discord.ui.View(timeout=300)
        v.add_item(buy.BuySelect(self.bot, opts))
        await interaction.response.send_message(f"\U0001F6D2 G\u00fcnstigste Spielershop-Angebote f\u00fcr **{sc.pretty(self.e['item'])}**:", view=v, ephemeral=True)


class BankButton(discord.ui.Button):
    def __init__(self, bot, e):
        super().__init__(label="Bei der Bank kaufen", emoji="\U0001F3E6", style=discord.ButtonStyle.success, row=2)
        self.bot, self.e = bot, e

    async def callback(self, interaction):
        import bank
        p = self.e.get("buy_price")
        await bank.start(self.bot, interaction, self.e["item"], f"{cp(p)} pro St\u00fcck" if p is not None else "")


def register(bot, tree):
    async def item_ac(interaction, current):
        try:
            items = await asyncio.to_thread(market)
        except NotReady:
            return []
        c = current.lower()
        return [app_commands.Choice(name=sc.pretty(e["item"])[:100], value=e["item"]) for e in items
                if c in e["item"] or c in sc.pretty(e["item"]).lower()][:25]

    @tree.command(name="markt", description="Servermarkt im B\u00f6rsenstil: Gewinner, Verlierer und Kurscharts")
    @app_commands.describe(item="Optional: direkt ein Item ansehen")
    @app_commands.autocomplete(item=item_ac)
    async def markt(interaction: discord.Interaction, item: str = None):
        await interaction.response.defer()
        try:
            if item:
                items = await asyncio.to_thread(market)
                e = find(items, item)
                if not e:
                    return await interaction.followup.send(f"\u201e{item}\u201c finde ich nicht im Markt.", ephemeral=True)
                f, v = await item_payload(bot, e)
            else:
                f, v = await overview_payload(bot)
        except NotReady:
            return await interaction.followup.send(SOON)
        await interaction.followup.send(file=f, view=v)

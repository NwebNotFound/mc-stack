"""Kaufen per Discord: Shop und AH ueber Crafts RCON-Befehle `shop buy` / `ah buy` (Survival)."""
import asyncio, logging, os, re, time, uuid
import discord

log = logging.getLogger("buy")
RCON = None  # wird von bot.py gesetzt: rcon(host, command)
HOST = os.getenv("SURVIVAL_HOST", "survival")
SOON = "\u23f3 Kaufen per Discord ist gleich verf\u00fcgbar - der Server startet gerade neu. Versuch es in ein paar Minuten nochmal."
ERRORS = {
    "NO_FUNDS": "\U0001F4B8 Du hast nicht genug CP f\u00fcr diesen Kauf.",
    "SOLD_OUT": "\U0001F4E6 So viel ist nicht mehr da - jemand war schneller. Schau nochmal in die Liste.",
    "PRICE_CHANGED": "\U0001F4C8 Der Preis hat sich ge\u00e4ndert. Es wurde nichts gekauft - schau dir das Angebot nochmal an.",
    "NOT_FOUND": "\u274c Das Angebot gibt es nicht mehr (verkauft, zur\u00fcckgezogen oder abgelaufen).",
    "SELF_BUY": "\U0001F643 Das ist dein eigenes Angebot - das kannst du nicht kaufen.",
    "DUPLICATE": "\u26a0\ufe0f Dieser Kauf wurde schon mit anderen Angaben bearbeitet. Starte den Kauf bitte neu.",
    "INVALID": "\u26a0\ufe0f Da ist was schiefgelaufen, es wurde nichts gekauft. Versuch es nochmal oder kauf ingame.",
}
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def parse_reply(text):
    """('ok', menge, gesamt_cent, rest) | ('err', CODE) | ('soon',) | ('bad', text)"""
    t = re.sub(r"\u00a7.", "", str(text or "")).strip()
    m = re.fullmatch(r"OK (\d+) (\d+) (\d+)", t)
    if m:
        return ("ok", int(m[1]), int(m[2]), int(m[3]))
    m = re.fullmatch(r"ERR ([A-Z_]+)", t)
    if m:
        return ("err", m[1])
    if re.fullmatch(r"PENDING [A-Za-z0-9_-]{1,64}", t):
        return ("pending",)
    low = t.lower()
    if not t or "unknown" in low or "unbekannt" in low or "usage" in low:
        return ("soon",)
    return ("bad", t)


def unit_cents(total, amount):
    """wie CraftCore UnitPrice.fromTotal: aufgerundet, min. 1 Cent"""
    total, amount = int(total), int(amount)
    if amount <= 0:
        raise ValueError("amount")
    return max(1, -(-total // amount))


def total_cents(total, amount, qty):
    return unit_cents(total, amount) * int(qty)


def cp(cents):
    v = int(cents) / 100
    return (f"{v:,.0f}" if v == int(v) else f"{v:,.2f}").replace(",", "X").replace(".", ",").replace("X", ".") + " CP"


def init_db(db):
    db.execute("CREATE TABLE IF NOT EXISTS dc_purchases (idem_key TEXT PRIMARY KEY, discord_id INTEGER NOT NULL, uuid TEXT NOT NULL, kind TEXT NOT NULL, listing_id TEXT NOT NULL, qty INTEGER NOT NULL, max_cents INTEGER NOT NULL, response TEXT, created INTEGER NOT NULL, done INTEGER)")
    db.commit()


def command(kind, buyer, lid, qty, max_c, key):
    if kind not in ("shop", "ah") or not UUID_RE.match(buyer) or not UUID_RE.match(lid) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", key):
        raise ValueError("ungueltige Kaufparameter")
    return f"{kind} buy {buyer} {lid} {int(qty)} {int(max_c)} {key}"


def execute(db, rcon, host, discord_id, kind, buyer, lid, qty, max_c, key):
    """Fuehrt den Kauf aus (blockierend). Gleicher Key -> gespeicherte Antwort, kein zweiter Kauf."""
    row = db.execute("SELECT response FROM dc_purchases WHERE idem_key=?", (key,)).fetchone()
    if row and row[0]:
        p = parse_reply(row[0])
        if p[0] in ("ok", "err"):
            return p
    cmd = command(kind, buyer, lid, qty, max_c, key)
    db.execute("INSERT OR IGNORE INTO dc_purchases (idem_key, discord_id, uuid, kind, listing_id, qty, max_cents, created) VALUES (?,?,?,?,?,?,?,?)",
               (key, discord_id, buyer, kind, lid, int(qty), int(max_c), int(time.time())))
    db.commit()
    reply = rcon(host, cmd)
    p = parse_reply(reply)
    if p[0] in ("ok", "err"):
        db.execute("UPDATE dc_purchases SET response=?, done=? WHERE idem_key=?", (str(reply).strip(), int(time.time()), key))
        db.commit()
    return p


def lookup(kind, lid):
    """(seller_name, material, amount, price_cents, seller_uuid) oder None"""
    import shops
    view = "v_shop_listings" if kind == "shop" else "v_ah_listings"
    r = shops.query(f"SELECT seller_name, material, amount, price_cents, seller FROM {view} WHERE id=%s", (lid,))
    return r[0] if r else None


def linked_uuid(bot, discord_id):
    r = bot.db.execute("SELECT uuid FROM links WHERE discord_id=? AND state='active'", (discord_id,)).fetchone()
    if not r:
        return None
    u = str(r[0]).lower()
    if len(u) == 32:
        u = f"{u[:8]}-{u[8:12]}-{u[12:16]}-{u[16:20]}-{u[20:]}"
    return u if UUID_RE.match(u) else None


def item_label(mat):
    import stats_card as sc
    return sc.pretty(str(mat or "?").lower().removeprefix("minecraft:"))


async def admin_log(bot, user, kind, item, qty, total, seller):
    try:
        import invites
        g = bot.get_guild(int(bot.cfg["GUILD_ID"]))
        ch = invites.channel_by(g, "ADMIN_LOG_CHANNEL_ID", invites.ADMIN_LOG_NAME) if g else None
        if ch:
            await ch.send(f"\U0001F6D2 Discord-Kauf ({'Shop' if kind == 'shop' else 'AH'}): {user.mention} kauft **{qty}\u00d7 {item}** von **{discord.utils.escape_markdown(str(seller))}** f\u00fcr **{cp(total)}**",
                          allowed_mentions=discord.AllowedMentions.none())
    except Exception:
        log.warning("admin-log fuer Kauf nicht beschreibbar")


async def start(bot, interaction, kind, lid):
    """Einstieg: Kaufen-Button/Select. Prueft Link + Angebot, fragt Menge ab."""
    buyer = linked_uuid(bot, interaction.user.id)
    if not buyer:
        return await interaction.response.send_message("\U0001F517 Zum Kaufen musst du erst dein Minecraft-Konto mit `/link` verkn\u00fcpfen.", ephemeral=True)
    import shops
    try:
        info = await asyncio.to_thread(lookup, kind, lid)
    except shops.NotReady:
        return await interaction.response.send_message(SOON, ephemeral=True)
    if not info:
        return await interaction.response.send_message(ERRORS["NOT_FOUND"], ephemeral=True)
    seller, mat, amount, cents, suuid = info
    if str(suuid).lower() == buyer:
        return await interaction.response.send_message(ERRORS["SELF_BUY"], ephemeral=True)
    amount = int(amount or 1)
    if amount == 1:
        return await confirm(bot, interaction, kind, lid, buyer, info, 1)
    await interaction.response.send_modal(QtyModal(bot, kind, lid, buyer, info))


class QtyModal(discord.ui.Modal, title="Wie viele kaufen?"):
    def __init__(self, bot, kind, lid, buyer, info):
        super().__init__()
        self.bot, self.kind, self.lid, self.buyer, self.info = bot, kind, lid, buyer, info
        amount, cents = int(info[2] or 1), int(info[3])
        self.qty = discord.ui.TextInput(label=f"Menge (1-{amount}, {cp(unit_cents(cents, amount))} pro St\u00fcck)"[:45], default=str(amount), max_length=6)
        self.add_item(self.qty)

    async def on_submit(self, interaction):
        v = str(self.qty.value).strip()
        amount = int(self.info[2] or 1)
        if not v.isdigit() or not 1 <= int(v) <= amount:
            return await interaction.response.send_message(f"Bitte eine Zahl von 1 bis {amount} eingeben.", ephemeral=True)
        await confirm(self.bot, interaction, self.kind, self.lid, self.buyer, self.info, int(v))


async def confirm(bot, interaction, kind, lid, buyer, info, qty):
    seller, mat, amount, cents, _ = info
    total = total_cents(cents, amount, qty)
    e = discord.Embed(title="\U0001F6D2 Kauf best\u00e4tigen", color=0xFEE75C,
                      description=f"**{qty}\u00d7 {item_label(mat)}** von **{discord.utils.escape_markdown(str(seller))}**\n"
                                  f"Gesamtpreis: **{cp(total)}** ({cp(unit_cents(cents, amount))} pro St\u00fcck)")
    e.set_footer(text=("Shop" if kind == "shop" else "Auktionshaus") + " \u00b7 Wenn der Preis steigt, wird nichts gekauft.")
    await interaction.response.send_message(embed=e, view=ConfirmView(bot, kind, lid, buyer, info, qty, total), ephemeral=True)


class ConfirmView(discord.ui.View):
    def __init__(self, bot, kind, lid, buyer, info, qty, total):
        super().__init__(timeout=300)
        self.bot, self.kind, self.lid, self.buyer, self.info, self.qty, self.total = bot, kind, lid, buyer, info, qty, total
        self.key = "dc-" + uuid.uuid4().hex  # ein Key pro Bestaetigung; Doppelklick/Retry nutzt denselben
        self.lock = asyncio.Lock()

    @discord.ui.button(label="Best\u00e4tigen", emoji="\u2705", style=discord.ButtonStyle.success)
    async def ok(self, interaction, button):
        async with self.lock:
            for c in self.children:
                c.disabled = True
            await interaction.response.edit_message(view=self)
            try:
                p = await asyncio.to_thread(execute, self.bot.db, RCON, HOST, interaction.user.id, self.kind, self.buyer, self.lid, self.qty, self.total, self.key)
            except Exception:
                log.exception("Kauf per RCON fehlgeschlagen")
                p = ("soon",)
            seller, mat = self.info[0], self.info[1]
            if p[0] == "ok":
                self.stop()
                e = discord.Embed(title="\u2705 Gekauft!", color=0x57F287,
                                  description=f"**{p[1]}\u00d7 {item_label(mat)}** f\u00fcr **{cp(p[2])}**.\nHol es ingame mit **/abholen** ab.")
                await interaction.edit_original_response(embed=e, view=None)
                await admin_log(self.bot, interaction.user, self.kind, item_label(mat), p[1], p[2], seller)
                return
            if p[0] == "err":
                self.stop()
                return await interaction.edit_original_response(content=ERRORS.get(p[1], ERRORS["INVALID"]), embed=None, view=None)
            # Server nicht erreichbar / Befehl noch nicht da: gleicher Key, erneut moeglich
            for c in self.children:
                c.disabled = False
            await interaction.edit_original_response(content=SOON if p[0] in ("soon", "pending") else ERRORS["INVALID"], view=self)

    @discord.ui.button(label="Abbrechen", emoji="\u2716\ufe0f", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction, button):
        self.stop()
        await interaction.response.edit_message(content="Kauf abgebrochen - nichts passiert.", embed=None, view=None)


class BuySelect(discord.ui.Select):
    """Select mit den Angeboten einer Seite; value = 'kind:listing_id'"""
    def __init__(self, bot, options):
        super().__init__(placeholder="\U0001F6D2 Kaufen ...", options=options[:25], row=1)
        self.bot = bot

    async def callback(self, interaction):
        kind, lid = self.values[0].split(":", 1)
        await start(self.bot, interaction, kind, lid)


class BuyButton(discord.ui.DynamicItem[discord.ui.Button], template=r"buy:(?P<kind>shop|ah):(?P<lid>[0-9a-f-]{36})"):
    """Persistenter 'Jetzt kaufen'-Button (Alarm-DMs)."""
    def __init__(self, kind, lid):
        super().__init__(discord.ui.Button(label="Jetzt kaufen", emoji="\U0001F6D2", style=discord.ButtonStyle.success, custom_id=f"buy:{kind}:{lid}"))
        self.kind, self.lid = kind, lid

    @classmethod
    async def from_custom_id(cls, interaction, item, match):
        return cls(match["kind"], match["lid"])

    async def callback(self, interaction):
        await start(interaction.client, interaction, self.kind, self.lid)


def buy_view(kind, lid):
    lid = str(lid)
    if not UUID_RE.match(lid):
        return discord.utils.MISSING
    v = discord.ui.View(timeout=None)
    v.add_item(BuyButton(kind, lid))
    return v

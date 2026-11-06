"""Bank-Direktkauf per Discord: Order `bank_buy` {item, amount, mc_uuid, request_id} in die gemeinsame
Order-Queue der Website (POST /api/mc-shop-order, wie Shop-Orders; Bot = x-ingest-token + discord_id).
Status wie bei Shop-Orders ueber v_order_fulfillment -> web_fulfillment_v2 (bestehender Relay-/Ack-Code).

Freigabe per BANK_BUY_ENABLED=1, erst wenn Website (Typ bank_buy in mc_shop_orders_pending) und
Server (Ausfuehrung) das koennen. Vorher zeigt /markt den Button nicht.
"""
import logging, os, re, uuid
import discord

log = logging.getLogger("bank")
ITEM_RE = re.compile(r"^[a-z0-9_]{1,64}$")
AMOUNTS = (1, 8, 16, 32, 64)
MAX_AMOUNT = 2304  # wie qty-Limit der Queue
STATUS_TEXT = {
    None: "\u23f3 In der Warteschlange",
    "ready": "\U0001F4E6 Bereit - hol es ingame mit **/abholen** ab",
    "collected": "\u2705 Abgeholt",
    "failed": "\u274c Fehlgeschlagen - nichts abgebucht",
    "refunded": "\U0001F4B8 Erstattet",
}
SOON = "\U0001F3E6 Bank-Kauf per Discord ist gleich verf\u00fcgbar. Bis dahin: ingame mit **/bank**."


def enabled():
    return os.getenv("BANK_BUY_ENABLED", "").strip().lower() in ("1", "true", "yes", "on")


def norm_item(item):
    i = str(item or "").strip().lower().removeprefix("minecraft:")
    return i if ITEM_RE.match(i) else None


def make_order(item, amount, mc_uuid, request_id=None):
    import buy
    it = norm_item(item)
    u = str(mc_uuid or "").lower()
    try:
        amount = int(amount)
    except (TypeError, ValueError):
        raise ValueError("amount")
    if not it or not buy.UUID_RE.match(u) or not 1 <= amount <= MAX_AMOUNT:
        raise ValueError("ungueltige Bank-Order")
    rid = request_id or str(uuid.uuid4())
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", rid):
        raise ValueError("request_id")
    return {"type": "bank_buy", "item": it, "amount": amount, "mc_uuid": u, "request_id": rid}


def request_body(order, discord_id):
    """Body fuer POST /api/mc-shop-order. mc_uuid nur informativ: die Website nimmt die UUID aus mc_links."""
    return dict(order, discord_id=str(int(discord_id)))


async def submit(session, order, discord_id, base=None, token=None):
    """('ok', request_id) | ('err', text) | ('soon', None). Schreibt in die gemeinsame Queue."""
    import aiohttp, relay
    token = token if token is not None else os.getenv("MC_INGEST_TOKEN", "").strip()
    if not token:
        return "soon", None
    try:
        async with session.post(f"{base or relay.BASE}/mc-shop-order", json=request_body(order, discord_id),
                                headers={"x-ingest-token": token}, timeout=aiohttp.ClientTimeout(total=15)) as r:
            try:
                data = await r.json(content_type=None)
            except Exception:
                data = {}
            if r.status in (200, 202) and isinstance(data, dict) and data.get("ok"):
                return "ok", str(data.get("request_id") or order["request_id"])
            if r.status in (400, 403, 404, 409, 429) and isinstance(data, dict) and data.get("error"):
                return "err", str(data["error"])[:200]
            log.warning("bank_buy POST HTTP %s", r.status)
            return "soon", None
    except Exception as e:
        log.warning("bank_buy POST fehlgeschlagen: %s", type(e).__name__)
        return "soon", None


def status_of(db, rid):
    have = {r[0] for r in db.execute("SELECT status FROM web_fulfillment_v2 WHERE request_id=?", (rid,))}
    for st in ("refunded", "failed", "collected", "ready"):
        if st in have:
            return st
    return None


def status_text(db, rid):
    try:
        return STATUS_TEXT.get(status_of(db, rid), STATUS_TEXT[None])
    except Exception:
        return STATUS_TEXT[None]


class StatusView(discord.ui.View):
    def __init__(self, bot, rid):
        super().__init__(timeout=900)
        self.bot, self.rid = bot, rid

    @discord.ui.button(label="Status pr\u00fcfen", emoji="\U0001F504", style=discord.ButtonStyle.secondary)
    async def check(self, interaction, _):
        await interaction.response.edit_message(content=f"Bank-Kauf `{self.rid}`: {status_text(self.bot.db, self.rid)}", view=self)


async def place(bot, interaction, item, amount):
    import aiohttp, buy
    u = buy.linked_uuid(bot, interaction.user.id)
    if not u:
        return await _reply(interaction, "\U0001F517 Zum Kaufen musst du erst dein Minecraft-Konto mit `/link` verkn\u00fcpfen.")
    try:
        o = make_order(item, amount, u)
    except ValueError:
        return await _reply(interaction, "Bitte eine Menge von 1 bis %d w\u00e4hlen." % MAX_AMOUNT)
    if not interaction.response.is_done():
        await interaction.response.defer(ephemeral=True, thinking=True)
    async with aiohttp.ClientSession() as session:
        st, val = await submit(session, o, interaction.user.id)
    if st == "ok":
        msg = f"\U0001F3E6 Bank-Kauf **{o['amount']}\u00d7 {buy.item_label(o['item'])}** ist bestellt.\nStatus: {status_text(bot.db, val)}"
        return await interaction.followup.send(msg, view=StatusView(bot, val), ephemeral=True)
    await interaction.followup.send(f"\u274c {val}" if st == "err" else SOON, ephemeral=True)


USED = "Diese Auswahl wurde schon benutzt. Starte den Bank-Kauf bitte neu."


async def _reply(interaction, msg, **kw):
    if interaction.response.is_done():
        return await interaction.followup.send(msg, ephemeral=True, **kw)
    return await interaction.response.send_message(msg, ephemeral=True, **kw)


def _close(view):
    """Auswahl nur einmal nutzbar (#151): sonst bestellt jede weitere Wahl erneut (neue request_id)."""
    if view is None:
        return
    for c in view.children:
        c.disabled = True
    view.stop()


class AmountModal(discord.ui.Modal, title="Wie viele bei der Bank kaufen?"):
    def __init__(self, bot, item, parent=None):
        super().__init__()
        self.bot, self.item, self.parent = bot, item, parent
        self.qty = discord.ui.TextInput(label=f"Menge (1-{MAX_AMOUNT})", default="1", max_length=5)
        self.add_item(self.qty)

    async def on_submit(self, interaction):
        v = str(self.qty.value).strip()
        if not v.isdigit():
            return await interaction.response.send_message(f"Bitte eine Zahl von 1 bis {MAX_AMOUNT} eingeben.", ephemeral=True)
        if self.parent is not None:
            if self.parent.is_finished():
                return await interaction.response.send_message(USED, ephemeral=True)
            _close(self.parent)
        await place(self.bot, interaction, self.item, int(v))


class AmountSelect(discord.ui.Select):
    def __init__(self, bot, item, price_text=""):
        opts = [discord.SelectOption(label=f"{a}\u00d7", value=str(a)) for a in AMOUNTS]
        opts.append(discord.SelectOption(label="Andere Menge \u2026", value="custom", emoji="\u270F\uFE0F"))
        super().__init__(placeholder="Menge w\u00e4hlen" + (f" ({price_text})" if price_text else ""), options=opts)
        self.bot, self.item = bot, item

    async def callback(self, interaction):
        v = self.values[0]
        if self.view is not None and self.view.is_finished():
            return await interaction.response.send_message(USED, ephemeral=True)
        if v == "custom":
            return await interaction.response.send_modal(AmountModal(self.bot, self.item, self.view))
        _close(self.view)
        await interaction.response.edit_message(view=self.view)
        await place(self.bot, interaction, self.item, int(v))


async def start(bot, interaction, item, price_text=""):
    import buy
    if not enabled():
        return await interaction.response.send_message(SOON, ephemeral=True)
    if not buy.linked_uuid(bot, interaction.user.id):
        return await interaction.response.send_message("\U0001F517 Zum Kaufen musst du erst dein Minecraft-Konto mit `/link` verkn\u00fcpfen.", ephemeral=True)
    if not norm_item(item):
        return await interaction.response.send_message("Dieses Item kann die Bank nicht verkaufen.", ephemeral=True)
    v = discord.ui.View(timeout=300)
    v.add_item(AmountSelect(bot, item, price_text))
    await interaction.response.send_message(f"\U0001F3E6 Wie viel **{buy.item_label(item)}** m\u00f6chtest du bei der Bank kaufen?", view=v, ephemeral=True)

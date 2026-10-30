"""Bank-Direktkauf per Discord: Order `bank_buy` {item, amount, mc_uuid, request_id} in die Order-Queue.

Die Queue (dc_orders) wird vom Relay-Loop abgearbeitet (RCON `bank buy <uuid> <item> <amount> <request_id>`,
Serverseite baut Craft). Status danach wie bei Shop-Orders ueber v_order_fulfillment -> web_fulfillment_v2.
"""
import json, logging, re, time, uuid
import discord

log = logging.getLogger("bank")
ITEM_RE = re.compile(r"^[a-z0-9_]{1,64}$")
AMOUNTS = (1, 8, 16, 32, 64)
MAX_AMOUNT = 2304  # 36 Stacks
STATUS_TEXT = {
    None: "\u23f3 In der Warteschlange",
    "accepted": "\u2705 Vom Server angenommen",
    "ready": "\U0001F4E6 Bereit - hol es ingame mit **/abholen** ab",
    "collected": "\u2705 Abgeholt",
    "failed": "\u274c Fehlgeschlagen - nichts abgebucht",
    "refunded": "\U0001F4B8 Erstattet",
    "rejected": "\u274c Abgelehnt",
}


def init_db(db):
    db.execute("CREATE TABLE IF NOT EXISTS dc_orders (request_id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, discord_id INTEGER NOT NULL, created INTEGER NOT NULL, status TEXT, reason TEXT)")
    db.commit()


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
    rid = request_id or "bank-" + uuid.uuid4().hex
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", rid):
        raise ValueError("request_id")
    return {"type": "bank_buy", "item": it, "amount": amount, "mc_uuid": u, "request_id": rid}


def enqueue(db, discord_id, order):
    db.execute("INSERT OR IGNORE INTO dc_orders (request_id, kind, payload, discord_id, created) VALUES (?,?,?,?,?)",
               (order["request_id"], order["type"], json.dumps(order), int(discord_id), int(time.time())))
    db.commit()
    return order["request_id"]


def command(o):
    return f"bank buy {o['mc_uuid']} {o['item']} {int(o['amount'])} {o['request_id']}"


def process_queue(db, rcon, host, limit=20):
    """Offene bank_buy-Orders per RCON ausfuehren (blockierend). Rueckgabe [(rid, status, reason)]."""
    import relay
    done = []
    rows = db.execute("SELECT request_id, payload FROM dc_orders WHERE kind='bank_buy' AND status IS NULL ORDER BY created LIMIT ?", (limit,)).fetchall()
    for rid, payload in rows:
        try:
            o = make_order(**{k: v for k, v in json.loads(payload).items() if k != "type"})
            status, reason = relay.parse(rcon(host, command(o)))
        except ValueError:
            status, reason = "rejected", "INVALID"
        except Exception:
            log.warning("bank buy %s: Server nicht erreichbar", rid)
            break
        if status is None:
            continue  # PENDING / Befehl noch nicht da -> naechste Runde
        db.execute("UPDATE dc_orders SET status=?, reason=? WHERE request_id=?", (status, reason, rid))
        if status == "rejected":
            relay.record_fulfillment(db, rid, relay.err_status(reason))
        db.commit()
        done.append((rid, status, reason))
    return done


def status_of(db, rid):
    """Neuester Status: Fulfillment (v_order_fulfillment) vor Queue-Status."""
    import relay
    have = [r[0] for r in db.execute("SELECT status FROM web_fulfillment_v2 WHERE request_id=? ORDER BY updated, rowid", (rid,))]
    for st in ("refunded", "failed", "collected", "ready"):
        if st in have:
            return st, None
    r = db.execute("SELECT status, reason FROM dc_orders WHERE request_id=?", (rid,)).fetchone()
    return (r[0], r[1]) if r else (None, None)


def status_text(db, rid):
    st, reason = status_of(db, rid)
    t = STATUS_TEXT.get(st, str(st))
    return t + (f" (`{reason}`)" if reason and st == "rejected" else "")


class StatusView(discord.ui.View):
    def __init__(self, bot, rid):
        super().__init__(timeout=900)
        self.bot, self.rid = bot, rid

    @discord.ui.button(label="Status pr\u00fcfen", emoji="\U0001F504", style=discord.ButtonStyle.secondary)
    async def check(self, interaction, _):
        await interaction.response.edit_message(content=f"Bank-Kauf `{self.rid}`: {status_text(self.bot.db, self.rid)}", view=self)


async def place(bot, interaction, item, amount):
    import buy
    u = buy.linked_uuid(bot, interaction.user.id)
    if not u:
        return await interaction.response.send_message("\U0001F517 Zum Kaufen musst du erst dein Minecraft-Konto mit `/link` verkn\u00fcpfen.", ephemeral=True)
    try:
        o = make_order(item, amount, u)
    except ValueError:
        return await interaction.response.send_message("Bitte eine Menge von 1 bis %d w\u00e4hlen." % MAX_AMOUNT, ephemeral=True)
    init_db(bot.db)
    rid = enqueue(bot.db, interaction.user.id, o)
    msg = f"\U0001F3E6 Bank-Kauf **{o['amount']}\u00d7 {buy.item_label(o['item'])}** ist in der Warteschlange.\nStatus: {status_text(bot.db, rid)}"
    msg_obj = getattr(interaction, "message", None)
    if msg_obj is not None and getattr(msg_obj.flags, "ephemeral", False) and not interaction.response.is_done():
        await interaction.response.edit_message(content=msg, view=StatusView(bot, rid))
    else:
        await interaction.response.send_message(msg, view=StatusView(bot, rid), ephemeral=True)


class AmountModal(discord.ui.Modal, title="Wie viele bei der Bank kaufen?"):
    def __init__(self, bot, item):
        super().__init__()
        self.bot, self.item = bot, item
        self.qty = discord.ui.TextInput(label=f"Menge (1-{MAX_AMOUNT})", default="1", max_length=5)
        self.add_item(self.qty)

    async def on_submit(self, interaction):
        v = str(self.qty.value).strip()
        if not v.isdigit():
            return await interaction.response.send_message(f"Bitte eine Zahl von 1 bis {MAX_AMOUNT} eingeben.", ephemeral=True)
        await place(self.bot, interaction, self.item, int(v))


class AmountSelect(discord.ui.Select):
    def __init__(self, bot, item, price_text=""):
        opts = [discord.SelectOption(label=f"{a}\u00d7", value=str(a)) for a in AMOUNTS]
        opts.append(discord.SelectOption(label="Andere Menge \u2026", value="custom", emoji="\u270F\uFE0F"))
        super().__init__(placeholder="Menge w\u00e4hlen" + (f" ({price_text})" if price_text else ""), options=opts)
        self.bot, self.item = bot, item

    async def callback(self, interaction):
        v = self.values[0]
        if v == "custom":
            return await interaction.response.send_modal(AmountModal(self.bot, self.item))
        await place(self.bot, interaction, self.item, int(v))


async def start(bot, interaction, item, price_text=""):
    import buy
    if not buy.linked_uuid(bot, interaction.user.id):
        return await interaction.response.send_message("\U0001F517 Zum Kaufen musst du erst dein Minecraft-Konto mit `/link` verkn\u00fcpfen.", ephemeral=True)
    if not norm_item(item):
        return await interaction.response.send_message("Dieses Item kann die Bank nicht verkaufen.", ephemeral=True)
    v = discord.ui.View(timeout=300)
    v.add_item(AmountSelect(bot, item, price_text))
    await interaction.response.send_message(f"\U0001F3E6 Wie viel **{buy.item_label(item)}** m\u00f6chtest du bei der Bank kaufen?", view=v, ephemeral=True)

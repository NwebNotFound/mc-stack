"""Auktionen mit Bieten per Discord: /ah, Bieten-Modal, Schnellgebote, Ueberboten-/Gewonnen-DMs.
Liest v_auctions / v_auction_bids (CraftCore, read-only), bietet via RCON `ah bid <auction_id> <uuid> <cent> <request_id>`."""
import asyncio, logging, math, os, re, sqlite3, time, uuid as uuidlib
import discord
import buy
import stats_card as sc

log = logging.getLogger("bid")
SOON = "\u23f3 Auktionen kommen gleich - sobald sie freigeschaltet sind, siehst du sie hier."
PER_PAGE = 5
FEE_PCT = 3
SNIPE_S = 120
DB_PATH = "/app/data/links.sqlite3"
ERRORS = {
    "NOT_FOUND": "\u274c Diese Auktion gibt es nicht mehr (beendet oder zur\u00fcckgezogen).",
    "ENDED": "\u231b Die Auktion ist schon vorbei.",
    "TOO_LOW": "\U0001F4C8 Dein Gebot ist zu niedrig - jemand hat schon mehr geboten. Schau nochmal rein.",
    "BID_TOO_LOW": "\U0001F4C8 Dein Gebot ist zu niedrig - jemand hat schon mehr geboten. Schau nochmal rein.",
    "NO_FUNDS": "\U0001F4B8 Du hast nicht genug CP f\u00fcr dieses Gebot.",
    "SELF_BID": "\U0001F643 Auf deine eigene Auktion kannst du nicht bieten.",
    "OWN_AUCTION": "\U0001F643 Auf deine eigene Auktion kannst du nicht bieten.",
    "ALREADY_HIGHEST": "\U0001F451 Du bist schon H\u00f6chstbietender.",
    "DUPLICATE": "\u26a0\ufe0f Dieses Gebot wurde schon mit anderen Angaben bearbeitet. Bitte neu bieten.",
    "INVALID": "\u26a0\ufe0f Da ist was schiefgelaufen, es wurde nicht geboten. Versuch es nochmal oder biete ingame.",
}

ALIASES = {"id": ("id", "auction_id"), "seller_uuid": ("seller_uuid", "seller"), "seller_name": ("seller_name",),
           "item": ("item", "material", "item_material"), "amount": ("menge", "amount"),
           "min_price": ("min_price", "min_price_cents", "start_price_cents", "start_price", "starting_bid_cents"),
           "current_bid": ("current_bid", "current_bid_cents", "highest_bid_cents", "top_bid_cents"),
           "bidder_uuid": ("current_bidder", "current_bidder_uuid", "highest_bidder_uuid", "highest_bidder", "top_bidder_uuid"),
           "bidder_name": ("current_bidder_name", "highest_bidder_name", "top_bidder_name"),
           "bid_count": ("bid_count", "bids"), "ends_at": ("ends_at", "ends_at_ms", "expires_at_ms", "end_at_ms"),
           "status": ("status", "state"), "min_next": ("min_next_bid",)}
B_ALIASES = {"auction_id": ("auction_id",), "uuid": ("bidder", "bidder_uuid", "uuid"), "name": ("bidder_name", "name"),
             "amount": ("amount_cents", "amount", "bid_cents"), "at": ("created_at_ms", "created_at", "at")}


def _pick(r, keys):
    for k in keys:
        if r.get(k) is not None:
            return r[k]
    return None


def _ms(v):
    if v is None:
        return None
    if hasattr(v, "timestamp"):
        return int(v.timestamp() * 1000)
    n = float(v)
    return int(n) if n > 1e10 else int(n * 1000)


def norm_uuid(u):
    u = str(u or "").lower().replace("-", "")
    return f"{u[:8]}-{u[8:12]}-{u[12:16]}-{u[16:20]}-{u[20:]}" if len(u) == 32 else ""


def select(view):
    """(cols, rows); shops.NotReady wenn View fehlt / keine Rechte."""
    import shops, pymysql
    pw = shops.db_password()
    if not pw:
        raise shops.NotReady()
    try:
        con = pymysql.connect(host=os.getenv("CRAFTCORE_DB_HOST", "craftcore-db"), user=shops.db_user(),
                              password=pw, database="craftcore", connect_timeout=5, read_timeout=5)
    except Exception:
        raise shops.NotReady()
    try:
        with con.cursor() as cur:
            cur.execute("SELECT * FROM " + view)
            return [d[0] for d in cur.description], cur.fetchall()
    except (pymysql.err.ProgrammingError, pymysql.err.OperationalError):
        raise shops.NotReady()
    finally:
        con.close()


def normalize(cols, rows, bcols=None, brows=()):
    """Auktionen als Dicts (Cent), inkl. Hoechstbietendem (aus View oder aus v_auction_bids)."""
    top = {}
    for row in brows or ():
        b = {k: _pick(dict(zip(bcols, row)), ks) for k, ks in B_ALIASES.items()}
        if b["auction_id"] is None or b["amount"] is None:
            continue
        aid = str(b["auction_id"])
        if aid not in top or int(b["amount"]) > top[aid]["amount"]:
            top[aid] = {"amount": int(b["amount"]), "uuid": norm_uuid(b["uuid"]), "name": str(b["name"] or "")}
    out = []
    for row in rows:
        a = {k: _pick(dict(zip(cols, row)), ks) for k, ks in ALIASES.items()}
        if a["id"] is None or not a["item"]:
            continue
        a["id"] = str(a["id"])
        a["seller_uuid"] = norm_uuid(a["seller_uuid"])
        a["amount"] = int(a["amount"] or 1)
        a["min_price"] = int(a["min_price"] or 0)
        a["current_bid"] = int(a["current_bid"]) if a["current_bid"] is not None else None
        a["bidder_uuid"] = norm_uuid(a["bidder_uuid"])
        a["ends_at"] = _ms(a["ends_at"])
        a["min_next"] = int(a["min_next"]) if a["min_next"] is not None else None
        t = top.get(a["id"])
        if t and a["current_bid"] is None:  # nur Fallback: current_bid/current_bidder der View gelten direkt
            a["current_bid"] = t["amount"]
            a["bidder_uuid"] = a["bidder_uuid"] or t["uuid"]
        if t and not a["bidder_name"] and (not a["bidder_uuid"] or t["uuid"] == a["bidder_uuid"]):
            a["bidder_name"] = t["name"] or None
        if a["current_bid"] == 0:
            a["current_bid"] = None
        st = str(a["status"] or "active").lower()
        a["status"] = "active" if st in ("active", "open", "running", "") else st
        out.append(a)
    return out


def is_active(a, now=None):
    now = time.time() if now is None else now
    return a["status"] == "active" and (not a["ends_at"] or a["ends_at"] / 1000 > now)


def min_bid(a):
    """Mindestgebot: min_next_bid aus der View, sonst Startpreis ohne Gebote, sonst Hoechstgebot + max(1 CP, 5 % aufgerundet)."""
    if a.get("min_next"):
        return int(a["min_next"])
    if not a.get("current_bid"):
        return max(1, int(a.get("min_price") or 0))
    c = int(a["current_bid"])
    return c + max(100, math.ceil(c * 5 / 100))


def quick(a, pct):
    base = int(a.get("current_bid") or 0) or max(1, int(a.get("min_price") or 0))
    return max(min_bid(a), math.ceil(base * (100 + pct) / 100))


def parse_cp(text):
    """'12,50' / '1.250' / '1250 CP' -> Cent, None wenn ungueltig."""
    t = str(text or "").lower().replace("cp", "").replace(" ", "").strip()
    if not t:
        return None
    if "," in t:
        t = t.replace(".", "").replace(",", ".")
    elif re.fullmatch(r"\d{1,3}(\.\d{3})+", t):
        t = t.replace(".", "")
    try:
        v = round(float(t) * 100)
    except ValueError:
        return None
    return v if v > 0 else None


def left(ends_ms, now=None):
    if not ends_ms:
        return "offen"
    s = int(ends_ms / 1000 - (time.time() if now is None else now))
    if s <= 0:
        return "endet gerade"
    d, r = divmod(s, 86400); h, r = divmod(r, 3600); m = r // 60
    return f"{d}d {h}h" if d else f"{h}h {m}m" if h else f"{max(m, 1)}m" if m else f"{s}s"


def check(a, buyer, cents, now=None):
    """Fehlertext oder None."""
    if not buyer:
        return "\U0001F517 Zum Bieten musst du dein Minecraft-Konto verkn\u00fcpfen: `/link`."
    if not a or not is_active(a, now):
        return ERRORS["ENDED"]
    if a["seller_uuid"] and a["seller_uuid"] == buyer:
        return ERRORS["SELF_BID"]
    if a.get("bidder_uuid") and a["bidder_uuid"] == buyer:
        return ERRORS["ALREADY_HIGHEST"]
    if cents is None:
        return "\u270f\ufe0f Bitte einen Betrag in CP angeben, z. B. `12,50`."
    if cents < min_bid(a):
        return f"\U0001F4C9 Zu wenig - das Mindestgebot ist **{buy.cp(min_bid(a))}**."
    return None


def command(aid, buyer, cents, rid):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", str(aid)) or not buy.UUID_RE.match(buyer) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", rid) or int(cents) <= 0:
        raise ValueError("ungueltige Gebotsparameter")
    return f"ah bid {aid} {buyer} {int(cents)} {rid}"


def parse_reply(text):
    """('ok', text) | ('err', CODE) | ('soon',) | ('bad', text)"""
    t = re.sub(r"\u00a7.", "", str(text or "")).strip()
    if re.match(r"^OK\b", t):
        return ("ok", t)
    m = re.match(r"^ERR ([A-Z_]+)", t)
    if m:
        return ("err", m[1])
    low = t.lower()
    if not t or "unknown" in low or "unbekannt" in low or "usage" in low:
        return ("soon",)
    return ("bad", t)


def init_db(db):
    db.execute("CREATE TABLE IF NOT EXISTS dc_bids (request_id TEXT PRIMARY KEY, discord_id INTEGER NOT NULL, uuid TEXT NOT NULL, auction_id TEXT NOT NULL, cents INTEGER NOT NULL, response TEXT, created INTEGER NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS ah_notify (key TEXT PRIMARY KEY, created INTEGER NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS ah_top (auction_id TEXT PRIMARY KEY, bidder_uuid TEXT, cents INTEGER)")
    db.commit()


def place(db, rcon, host, discord_id, aid, buyer, cents, rid):
    """Gebot absetzen (blockierend, idempotent ueber request_id)."""
    init_db(db)
    row = db.execute("SELECT response FROM dc_bids WHERE request_id=?", (rid,)).fetchone()
    if row and row[0]:
        p = parse_reply(row[0])
        if p[0] in ("ok", "err"):
            return p
    cmd = command(aid, buyer, cents, rid)
    db.execute("INSERT OR IGNORE INTO dc_bids (request_id, discord_id, uuid, auction_id, cents, created) VALUES (?,?,?,?,?,?)",
               (rid, discord_id, buyer, str(aid), int(cents), int(time.time())))
    db.commit()
    p = parse_reply(rcon(host, cmd))
    if p[0] in ("ok", "err"):
        db.execute("UPDATE dc_bids SET response=? WHERE request_id=?", (p[1] if p[0] == "ok" else "ERR " + p[1], rid))
        db.commit()
    return p


def reply_text(p, a, cents):
    item = buy.item_label(a["item"]) if a else "Auktion"
    if p[0] == "ok":
        return (f"\u2705 Gebot \u00fcber **{buy.cp(cents)}** auf **{a['amount']}\u00d7 {item}** abgegeben - du bist H\u00f6chstbietender!\n"
                f"Gewinnst du, holst du das Item mit **/abholen** ab. Bieten in den letzten 2 Min. verl\u00e4ngert die Auktion um 2 Min.")
    if p[0] == "err":
        return ERRORS.get(p[1], f"\u26a0\ufe0f Gebot abgelehnt ({p[1]}). Es wurde nichts abgebucht.")
    if p[0] == "soon":
        return "\u23f3 Bieten per Discord ist gleich verf\u00fcgbar - der Server startet gerade. Versuch es in ein paar Minuten."
    return "\u26a0\ufe0f Unerwartete Antwort vom Server. Pr\u00fcf ingame mit /ah, ob dein Gebot angekommen ist."


async def load():
    cols, rows = await asyncio.to_thread(select, "v_auctions")
    try:
        bcols, brows = await asyncio.to_thread(select, "v_auction_bids")
    except Exception:
        bcols, brows = None, ()
    return normalize(cols, rows, bcols, brows)


def describe(a):
    hb = f"{buy.cp(a['current_bid'])} ({a.get('bidder_name') or '?'})" if a.get("current_bid") else "noch kein Gebot"
    return (f"\U0001F464 {a.get('seller_name') or '?'} \u00b7 \u23f1 {left(a['ends_at'])}\n"
            f"H\u00f6chstgebot: **{hb}** \u00b7 Mindestgebot: **{buy.cp(min_bid(a))}**")


def embed(auctions, page):
    pages = max(1, math.ceil(len(auctions) / PER_PAGE))
    e = discord.Embed(title="\U0001F528 Auktionshaus - laufende Auktionen", color=0xF1C40F,
                      description=f"Mindesterh\u00f6hung 5 % (min. 1 CP) \u00b7 Anti-Snipe 2 Min. \u00b7 {FEE_PCT} % Geb\u00fchr \u00b7 Gewinne per **/abholen**")
    for i, a in enumerate(auctions[page * PER_PAGE:(page + 1) * PER_PAGE], start=page * PER_PAGE + 1):
        e.add_field(name=f"{i}. {a['amount']}\u00d7 {buy.item_label(a['item'])}", value=describe(a), inline=False)
    if not auctions:
        e.description += "\n\nGerade l\u00e4uft keine Auktion."
    e.set_footer(text=f"Seite {page + 1}/{pages} \u00b7 Auktion w\u00e4hlen, dann bieten")
    return e


async def fresh(aid):
    for a in await load():
        if a["id"] == aid:
            return a
    return None


async def confirm_bid(bot, interaction, aid, cents):
    """Pruefen + bieten, Antwort ephemeral."""
    if not interaction.response.is_done():
        await interaction.response.defer(ephemeral=True, thinking=True)
    import shops
    try:
        a = await fresh(aid)
    except shops.NotReady:
        return await interaction.followup.send(SOON, ephemeral=True)
    buyer = buy.linked_uuid(bot, interaction.user.id)
    err = check(a, buyer, cents)
    if err:
        return await interaction.followup.send(err, ephemeral=True)
    rid = str(uuidlib.uuid4())

    def run():
        db = sqlite3.connect(DB_PATH)
        try:
            return place(db, buy.RCON, buy.HOST, interaction.user.id, aid, buyer, cents, rid)
        finally:
            db.close()
    try:
        p = await asyncio.to_thread(run)
    except Exception:
        log.exception("Gebot fehlgeschlagen")
        p = ("soon",)
    log.info("Gebot %s auf %s: %s", rid, aid, p[0] if p[0] != "err" else p[1])
    await interaction.followup.send(reply_text(p, a, cents), ephemeral=True)


class ConfirmView(discord.ui.View):
    def __init__(self, bot, aid, cents):
        super().__init__(timeout=120)
        self.bot, self.aid, self.cents = bot, aid, cents

    @discord.ui.button(label="Gebot best\u00e4tigen", style=discord.ButtonStyle.success, emoji="\u2705")
    async def ok(self, interaction, button):
        self.stop()
        await interaction.response.edit_message(content=f"\u23f3 Biete {buy.cp(self.cents)} \u2026", view=None)
        await confirm_bid(self.bot, interaction, self.aid, self.cents)

    @discord.ui.button(label="Abbrechen", style=discord.ButtonStyle.secondary)
    async def no(self, interaction, button):
        self.stop()
        await interaction.response.edit_message(content="Abgebrochen - kein Gebot abgegeben.", view=None)


class BidModal(discord.ui.Modal, title="Gebot abgeben"):
    def __init__(self, bot, a):
        super().__init__()
        self.bot, self.aid = bot, a["id"]
        self.amount = discord.ui.TextInput(label=f"Betrag in CP (min. {buy.cp(min_bid(a))})"[:45],
                                           default=f"{min_bid(a) / 100:.2f}".replace(".", ",").replace(",00", ""), max_length=16)
        self.add_item(self.amount)

    async def on_submit(self, interaction):
        await confirm_bid(self.bot, interaction, self.aid, parse_cp(self.amount.value))


class AuctionView(discord.ui.View):
    def __init__(self, bot, auctions, owner_id, page=0):
        super().__init__(timeout=900)
        self.bot, self.auctions, self.owner_id, self.page, self.sel = bot, auctions, owner_id, page, None
        self.build()

    def current(self):
        return next((a for a in self.auctions if a["id"] == self.sel), None)

    def build(self):
        self.clear_items()
        chunk = self.auctions[self.page * PER_PAGE:(self.page + 1) * PER_PAGE]
        pages = max(1, math.ceil(len(self.auctions) / PER_PAGE))
        if chunk:
            s = discord.ui.Select(placeholder="Auktion w\u00e4hlen \u2026", row=0, options=[
                discord.SelectOption(label=f"{self.page * PER_PAGE + i + 1}. {a['amount']}\u00d7 {buy.item_label(a['item'])}"[:100],
                                     description=f"min. {buy.cp(min_bid(a))} \u00b7 {left(a['ends_at'])}"[:100], value=a["id"], default=a["id"] == self.sel)
                for i, a in enumerate(chunk)])
            s.callback = self.on_select
            self.add_item(s)
        a = self.current()
        for label, style, cb, dis in (("Bieten", discord.ButtonStyle.primary, self.on_bid, a is None),
                                      ("+5 %", discord.ButtonStyle.secondary, self.on_q5, a is None),
                                      ("+10 %", discord.ButtonStyle.secondary, self.on_q10, a is None)):
            b = discord.ui.Button(label=label, style=style, row=1, disabled=dis, emoji="\U0001F528" if label == "Bieten" else None)
            b.callback = cb
            self.add_item(b)
        for label, cb, dis in (("\u25c0", self.on_prev, self.page == 0), ("\u25b6", self.on_next, self.page >= pages - 1),
                               ("\U0001F504", self.on_refresh, False)):
            b = discord.ui.Button(label=label, style=discord.ButtonStyle.secondary, row=2, disabled=dis)
            b.callback = cb
            self.add_item(b)
        b = discord.ui.Button(label="Sofort kaufen", style=discord.ButtonStyle.success, row=2, emoji="\U0001F6D2")
        b.callback = self.on_instant
        self.add_item(b)

    async def redraw(self, interaction):
        self.build()
        await interaction.response.edit_message(embed=embed(self.auctions, self.page), view=self)

    async def on_select(self, interaction):
        self.sel = interaction.data["values"][0]
        await self.redraw(interaction)

    async def on_prev(self, interaction):
        self.page, self.sel = max(0, self.page - 1), None
        await self.redraw(interaction)

    async def on_next(self, interaction):
        self.page, self.sel = self.page + 1, None
        await self.redraw(interaction)

    async def on_refresh(self, interaction):
        import shops
        try:
            self.auctions = fill_names(self.bot.db, [a for a in await load() if is_active(a)])
        except shops.NotReady:
            return await interaction.response.send_message(SOON, ephemeral=True)
        self.page = min(self.page, max(0, math.ceil(len(self.auctions) / PER_PAGE) - 1))
        await self.redraw(interaction)

    async def on_bid(self, interaction):
        a = self.current()
        if a:
            await interaction.response.send_modal(BidModal(self.bot, a))

    async def _quick(self, interaction, pct):
        a = self.current()
        if not a:
            return
        cents = quick(a, pct)
        await interaction.response.send_message(
            f"\U0001F528 **{buy.cp(cents)}** auf **{a['amount']}\u00d7 {buy.item_label(a['item'])}** bieten? (+{pct} %, Mindestgebot {buy.cp(min_bid(a))})",
            view=ConfirmView(self.bot, a["id"], cents), ephemeral=True)

    async def on_q5(self, interaction):
        await self._quick(interaction, 5)

    async def on_q10(self, interaction):
        await self._quick(interaction, 10)

    async def on_instant(self, interaction):
        import ah, shops
        await interaction.response.defer()
        try:
            data = await asyncio.to_thread(ah.rows, "new", None)
        except shops.NotReady:
            return await interaction.followup.send(ah.SOON, ephemeral=True)
        await shops.send(interaction, "Auktionshaus \u00b7 Sofortkauf", f"{len(data)} Angebote \u00b7 Sofortkauf-Preis", data)


def fill_names(db, auctions):
    for a in auctions:
        for uk, nk in (("seller_uuid", "seller_name"), ("bidder_uuid", "bidder_name")):
            if a.get(uk) and not a.get(nk):
                r = db.execute("SELECT name FROM links WHERE lower(replace(uuid,'-',''))=?", (a[uk].replace("-", ""),)).fetchone()
                a[nk] = r[0] if r else None
    return auctions


async def show(bot, interaction):
    """/ah (Standardansicht): laufende Auktionen mit Bieten."""
    import shops
    try:
        auctions = fill_names(bot.db, [a for a in await load() if is_active(a)])
    except shops.NotReady:
        return await interaction.followup.send(SOON)
    auctions.sort(key=lambda a: a["ends_at"] or 1 << 62)
    v = AuctionView(bot, auctions, interaction.user.id)
    await interaction.followup.send(embed=embed(auctions, 0), view=v)


# ---------- Benachrichtigungen ----------
def plan_notifications(db, auctions, now=None):
    """Vergleicht mit ah_top; liefert [(key, uuid, text)] fuer neue Ueberboten-/Gewonnen-Meldungen.
    Erster Lauf (ah_top leer) merkt nur den Stand."""
    init_db(db)
    now = time.time() if now is None else now
    first = db.execute("SELECT COUNT(*) FROM ah_top").fetchone()[0] == 0 and not db.execute("SELECT 1 FROM ah_notify LIMIT 1").fetchone()
    out = []
    for a in auctions:
        prev = db.execute("SELECT bidder_uuid, cents FROM ah_top WHERE auction_id=?", (a["id"],)).fetchone()
        cur_u, cur_c = a.get("bidder_uuid") or "", int(a.get("current_bid") or 0)
        item = f"{a['amount']}\u00d7 {buy.item_label(a['item'])}"
        if not first and prev and prev[0] and cur_u and prev[0] != cur_u and cur_c > int(prev[1] or 0):
            out.append((f"outbid:{a['id']}:{prev[0]}:{cur_c}", prev[0],
                        f"\U0001F514 Du wurdest bei **{item}** \u00fcberboten! Neues H\u00f6chstgebot: **{buy.cp(cur_c)}**.\n"
                        f"Mindestgebot jetzt {buy.cp(min_bid(a))} \u00b7 Restzeit {left(a['ends_at'], now)} - biete mit **/ah** nach."))
        ended = not is_active(a, now) and a["status"] not in ("cancelled", "canceled")
        recent = not a.get("ends_at") or now - a["ends_at"] / 1000 < 86400
        if ended and cur_u and recent and not first:
            out.append((f"won:{a['id']}", cur_u,
                        f"\U0001F3C6 Gl\u00fcckwunsch! Du hast die Auktion **{item}** f\u00fcr **{buy.cp(cur_c)}** gewonnen.\n"
                        f"Hol dein Item ingame mit **/abholen** ab."))
        db.execute("INSERT OR REPLACE INTO ah_top (auction_id, bidder_uuid, cents) VALUES (?,?,?)", (a["id"], cur_u, cur_c))
    db.commit()
    return [o for o in out if not db.execute("SELECT 1 FROM ah_notify WHERE key=?", (o[0],)).fetchone()]


def discord_id_for(db, u):
    r = db.execute("SELECT discord_id FROM links WHERE state='active' AND lower(replace(uuid,'-',''))=?", (u.replace("-", ""),)).fetchone()
    return int(r[0]) if r else None


async def notify_loop(bot):
    import shops
    await bot.wait_until_ready()
    while not bot.is_closed():
        try:
            auctions = await load()
            for key, u, text in plan_notifications(bot.db, auctions):
                did = discord_id_for(bot.db, u)
                if did:
                    try:
                        user = bot.get_user(did) or await bot.fetch_user(did)
                        await user.send(text)
                    except discord.HTTPException:
                        log.info("DM %s nicht zustellbar", key)
                bot.db.execute("INSERT OR IGNORE INTO ah_notify (key, created) VALUES (?,?)", (key, int(time.time())))
                bot.db.commit()
        except shops.NotReady:
            await asyncio.sleep(240)
        except Exception:
            log.exception("Auktions-Benachrichtigungen fehlgeschlagen")
        await asyncio.sleep(60)

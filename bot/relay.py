"""Relay Web -> Server: Shop-Bestellungen und AH-Gebote aus der Pending-Queue der Website per RCON ausfuehren und acken."""
import asyncio, logging, os, re, sqlite3, time
import aiohttp

log = logging.getLogger("creepy.relay")
BASE = os.getenv("MC_WEB_API", "https://nwebnotfound.dev/api")
INTERVAL = 60
PROBE_EVERY = 600
MAX_PENDING_ATTEMPTS = 5
MAX_PENDING_AGE = 15 * 60
KEY_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
DB_PATH = "/app/data/links.sqlite3"
_state = {"bid_active": False, "probe_at": 0}


def init_db(db):
    db.execute("CREATE TABLE IF NOT EXISTS web_relay (request_id TEXT PRIMARY KEY, kind TEXT NOT NULL, status TEXT, reason TEXT, response TEXT, acked INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_try INTEGER NOT NULL DEFAULT 0)")
    cols = {row[1] for row in db.execute("PRAGMA table_info(web_relay)")}
    if "attempts" not in cols:
        db.execute("ALTER TABLE web_relay ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
    if "next_try" not in cols:
        db.execute("ALTER TABLE web_relay ADD COLUMN next_try INTEGER NOT NULL DEFAULT 0")
    db.commit()


def pending_delay(attempts):
    return min(INTERVAL * (2 ** (attempts - 1)), 480)


def pending_due(db, rid, now):
    """False while a non-final attempt is still inside its backoff window."""
    row = db.execute("SELECT status, next_try FROM web_relay WHERE request_id=?", (rid,)).fetchone()
    if row is None or row[0]:
        return True
    return (row[1] or 0) <= now


def stored_order(key):
    """Final result already committed in rcon_purchases.

    ('accepted', None) or ('rejected', CODE) when that row has a reply.
    'missing' when the key has no row. None when the database cannot be read
    or the row has no final OK/ERR yet.
    """
    import shops
    if not KEY_RE.match(str(key or "")):
        return "missing"
    try:
        rows = shops.query("SELECT response FROM rcon_purchases WHERE idem_key=%s", (str(key).encode("ascii"),))
    except shops.NotReady:
        return None
    if not rows:
        return "missing"
    raw = rows[0][0]
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8", "replace")
    status, reason = parse(raw)
    return (status, reason) if status else None


def observe(db, rid, kind, status, reason, now):
    """Store a final result, or count a PENDING reply until it is given up."""
    row = db.execute("SELECT attempts, created FROM web_relay WHERE request_id=?", (rid,)).fetchone()
    attempts = row[0] if row else 0
    created = row[1] if row and row[1] else now
    if status is None:
        attempts += 1
        if attempts >= MAX_PENDING_ATTEMPTS or now - created >= MAX_PENDING_AGE:
            found = stored_order(rid)
            if found == "missing":
                status, reason = "rejected", "TIMEOUT"
            elif found:
                status, reason = found
            next_try = 0 if status else now + pending_delay(min(attempts, 4))
        else:
            next_try = now + pending_delay(attempts)
    else:
        next_try = 0
    db.execute("INSERT OR REPLACE INTO web_relay (request_id, kind, status, reason, response, acked, created, attempts, next_try) VALUES (?,?,?,?,NULL,0,?,?,?)",
               (rid, kind, status, reason, created, attempts, next_try))
    db.commit()
    return status, reason


def norm_uuid(u):
    u = str(u or "").strip().lower().replace("-", "")
    if not re.fullmatch(r"[0-9a-f]{32}", u):
        return None
    return f"{u[:8]}-{u[8:12]}-{u[12:16]}-{u[16:20]}-{u[20:]}"


def linked_discord(db, uuid):
    """discord_id fuer eine aktiv verknuepfte UUID, sonst None."""
    nu = norm_uuid(uuid)
    if not nu:
        return None
    for did, u in db.execute("SELECT discord_id, uuid FROM links WHERE state='active'").fetchall():
        if norm_uuid(u) == nu:
            return did
    return None


def map_order(listings, shop_id, item, qty, max_unit):
    """listings: [(listing_id, seller_uuid, material, amount, price_cents)] -> (listing_id, None) oder (None, CODE)."""
    import buy
    sid = norm_uuid(shop_id)
    if not sid:
        return None, "UNSUPPORTED_FORMAT"
    item = str(item or "").lower().removeprefix("minecraft:")
    cands = [(buy.unit_cents(p, a), lid) for lid, s, m, a, p in listings
             if norm_uuid(s) == sid and str(m).lower().removeprefix("minecraft:") == item and int(a or 0) >= qty and int(a or 0) > 0]
    if not cands:
        return None, "NOT_FOUND"
    cands.sort()
    unit, lid = cands[0]
    if unit > max_unit:
        return None, "PRICE_CHANGED"
    return str(lid), None


def parse(reply):
    """('accepted', None) | ('rejected', CODE) | (None, None) = nicht final, spaeter nochmal."""
    t = re.sub(r"\u00a7.", "", str(reply or "")).strip()
    if re.match(r"^OK\b", t):
        return "accepted", None
    m = re.match(r"^ERR ([A-Z_]+)", t)
    if m:
        return "rejected", m[1]
    if re.match(r"^PENDING\b", t):
        return None, None
    return None, None


def probe_reply_active(reply):
    t = re.sub(r"\u00a7.", "", str(reply or "")).strip()
    return bool(re.match(r"^(OK|ERR)\b", t))


def _listings():
    import shops
    return shops.query("SELECT id, seller_uuid, material, amount, price_cents FROM v_shop_listings")


def handle_order(db, rcon, host, o):
    """Fuehrt eine Order aus (blockierend). Rueckgabe (status, reason) oder (None, None) fuer spaeter."""
    import buy
    rid = str(o.get("idem_key") or o.get("request_id") or o.get("id") or "")
    if not KEY_RE.match(rid):
        return "rejected", "INVALID"
    buyer = o.get("buyer_uuid") or o.get("uuid")
    did = linked_discord(db, buyer)
    if did is None or (o.get("uuid") and o.get("buyer_uuid") and norm_uuid(o["uuid"]) != norm_uuid(o["buyer_uuid"])):
        return "rejected", "LINK_MISMATCH"
    try:
        qty = int(o.get("qty"))
        max_unit = int(o["max_unit_price_cent"] if o.get("max_unit_price_cent") is not None else o.get("unit_price"))
    except (TypeError, ValueError, KeyError):
        return "rejected", "INVALID"
    if qty < 1 or max_unit < 0:
        return "rejected", "INVALID"
    o = dict(o, buyer_uuid=buyer)
    if o.get("listing_id"):
        lid, err = str(o["listing_id"]), None
    else:
        try:
            lid, err = map_order(_listings(), o.get("shop_id"), o.get("item"), qty, max_unit)
        except Exception:
            return None, None  # DB gerade nicht erreichbar
    if err:
        return "rejected", err
    try:
        p = buy.execute(db, rcon, host, did, "shop", norm_uuid(o["buyer_uuid"]), lid, qty, max_unit * qty, rid)
    except ValueError:
        return "rejected", "INVALID"
    if p[0] == "ok":
        return "accepted", None
    if p[0] == "err":
        return "rejected", p[1]
    return None, None


def bid_command(b):
    aid, rid, u = str(b.get("auction_id") or ""), str(b.get("request_id") or b.get("id") or ""), norm_uuid(b.get("bidder_uuid"))
    if not KEY_RE.match(aid) or not KEY_RE.match(rid) or not u:
        return None
    return f"ah bid {aid} {u} {int(b['amount'])} {rid}"


def handle_bid(db, rcon, host, b):
    if linked_discord(db, b.get("bidder_uuid")) is None:
        return "rejected", "LINK_MISMATCH"
    try:
        cmd = bid_command(b)
    except (TypeError, ValueError, KeyError):
        cmd = None
    if not cmd:
        return "rejected", "INVALID"
    return parse(rcon(host, cmd))


async def _get(session, token, path):
    async with session.get(f"{BASE}/{path}", headers={"x-ingest-token": token}, timeout=aiohttp.ClientTimeout(total=20)) as r:
        if r.status >= 300:
            log.warning("GET %s HTTP %s", path, r.status)
            return r.status, []
        data = await r.json(content_type=None)
        return r.status, list(data.get("items") or [])


async def _ack(session, token, path, acks):
    async with session.post(f"{BASE}/{path}", json={"acks": acks}, headers={"x-ingest-token": token}, timeout=aiohttp.ClientTimeout(total=20)) as r:
        body = (await r.text())[:200]
        if r.status >= 300:
            log.warning("ACK %s HTTP %s: %s", path, r.status, body)
        return r.status < 300


def _run(handler, rcon, host, item):
    """Handler mit eigener SQLite-Verbindung (sqlite3 ist thread-gebunden)."""
    con = sqlite3.connect(DB_PATH, timeout=10)
    try:
        return handler(con, rcon, host, item)
    finally:
        con.close()


async def process(bot, session, token, kind, items, handler, now=None):
    db = bot.db
    import buy
    acks = []
    now = int(time.time()) if now is None else int(now)
    for it in items:
        rid = str(it.get("request_id") or it.get("id") or "")
        if not rid:
            continue
        row = db.execute("SELECT status, reason FROM web_relay WHERE request_id=?", (rid,)).fetchone()
        if row and row[0]:
            status, reason = row
        elif not pending_due(db, rid, now):
            continue  # Backoff nach PENDING
        else:
            status, reason = await asyncio.to_thread(_run, handler, buy.RCON, buy.HOST, it)
            status, reason = observe(db, rid, kind, status, reason, now)
            if status is None:
                continue  # Server nicht bereit - naechste Runde, bis zum Versuchslimit
            log.info("%s %s -> %s %s", kind, rid, status, reason or "")
        acks.append({"id": rid, "status": status, "reason": reason or ("OK" if status == "accepted" else "")})
    # bereits entschiedene, aber noch nicht bestaetigte Acks nachreichen
    for rid, status, reason in db.execute("SELECT request_id, status, reason FROM web_relay WHERE kind=? AND acked=0 AND status IS NOT NULL", (kind,)).fetchall():
        if not any(a["id"] == rid for a in acks):
            acks.append({"id": rid, "status": status, "reason": reason or ("OK" if status == "accepted" else "")})
    if acks:
        path = "mc-shop-orders/ack" if kind == "order" else "mc-bids/ack"
        if await _ack(session, token, path, acks[:200]):
            db.executemany("UPDATE web_relay SET acked=1 WHERE request_id=?", [(a["id"],) for a in acks[:200]])
            db.commit()
    return acks


async def probe_bid(rcon, host):
    try:
        reply = await asyncio.to_thread(rcon, host, "ah bid 00000000-0000-0000-0000-000000000000 00000000-0000-0000-0000-000000000000 1 probe-0")
    except Exception:
        return False
    return probe_reply_active(reply)


async def run_once(bot, session):
    token = os.getenv("MC_INGEST_TOKEN", "").strip()
    if not token:
        return None
    import buy
    st, orders = await _get(session, token, "mc-shop-orders/pending")
    await process(bot, session, token, "order", orders, handle_order)
    if time.time() - _state["probe_at"] > PROBE_EVERY:
        _state["probe_at"] = time.time()
        was = _state["bid_active"]
        _state["bid_active"] = await probe_bid(buy.RCON, buy.HOST)
        if was != _state["bid_active"]:
            log.info("ah bid ist jetzt %s", "aktiv" if _state["bid_active"] else "inaktiv")
    if _state["bid_active"]:
        _, bids = await _get(session, token, "mc-bids/pending")
        await process(bot, session, token, "bid", bids, handle_bid)
    return st, len(orders)


async def loop(bot):
    init_db(bot.db)
    await bot.wait_until_ready()
    async with aiohttp.ClientSession() as session:
        while not bot.is_closed():
            try:
                await run_once(bot, session)
            except Exception as e:
                log.warning("Relay fehlgeschlagen: %s", type(e).__name__)
            await asyncio.sleep(INTERVAL)

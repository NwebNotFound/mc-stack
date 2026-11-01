"""Push von Verknuepfungen und Spieler-Stats an https://nwebnotfound.dev/api/mc-ingest."""
import asyncio, datetime, hashlib, json, logging, os, time
import aiohttp

log = logging.getLogger("creepy.ingest")
_LOCAL_HOSTS = ("localhost", "127.0.0.1", "::1")


def safe_url(url, default):
    """Nur https (http nur fuer localhost), sonst Default."""
    from urllib.parse import urlsplit
    try:
        u = urlsplit(str(url or "").strip())
        ok = u.scheme == "https" and u.hostname or (u.scheme == "http" and u.hostname in _LOCAL_HOSTS)
    except ValueError:
        ok = False
    if not ok:
        if url:
            log.warning("Unsichere/ungueltige URL verworfen, nutze Default")
        return default
    return str(url).strip().rstrip("/")


URL = safe_url(os.getenv("MC_INGEST_URL"), "https://nwebnotfound.dev/api/mc-ingest")
def _env_int(name, default, lo=5):
    try:
        return max(lo, int(os.getenv(name, default)))
    except (TypeError, ValueError):
        return default


INTERVAL = _env_int("INGEST_INTERVAL_SEC", 30)
MIN_GAP = 3.0  # max. 1 POST pro 3 s
_rate = {"last_post": 0.0, "backoff_until": 0.0, "fails": 0, "last_hash": None}
VOLATILE = ("uptime_seconds", "tps")


def payload_hash(payload):
    """Hash ohne fluechtige Server-Felder (Uptime/TPS), damit unveraenderte Daten nicht neu gesendet werden."""
    p = dict(payload)
    if isinstance(p.get("servers"), dict):
        p["servers"] = {k: {f: v for f, v in e.items() if f not in VOLATILE} if isinstance(e, dict) else e
                        for k, e in p["servers"].items()}
    return hashlib.sha256(json.dumps(p, sort_keys=True, separators=(",", ":"), default=str).encode()).hexdigest()


def note_status(status, retry_after=None, now=None):
    """429/5xx -> exponentielles Backoff (5 s .. 300 s, Retry-After wird beachtet); Erfolg setzt zurueck."""
    now = now if now is not None else time.monotonic()
    if status == 429 or status >= 500:
        _rate["fails"] += 1
        wait = min(300.0, 5.0 * 2 ** (_rate["fails"] - 1))
        try:
            wait = max(wait, min(300.0, float(retry_after)))
        except (TypeError, ValueError):
            pass
        _rate["backoff_until"] = now + wait
        return wait
    if status < 300:
        _rate["fails"] = 0
        _rate["backoff_until"] = 0.0
    return 0.0
STAT_FIELDS = ("money", "playtime_s", "kills", "deaths", "blocks_mined")
RENAME = {"playtime_s": "playtime"}


def build_links(rows, has_role):
    """rows: (discord_id, uuid, name, state); has_role: callable(discord_id)->bool"""
    out = []
    for did, uuid, name, state in rows[:200]:
        out.append({"discord_id": str(did), "mc_name": name, "mc_uuid": uuid,
                    "has_role": bool(has_role(did)), "verified": state in ("active", "adding")})
    return out


def build_stats(cols, rows):
    """cols: Spaltennamen aus v_player_stats; nur bekannte Felder, playtime_s -> playtime."""
    out = {}
    for row in rows[:200]:
        r = dict(zip(cols, row))
        uuid = r.get("uuid")
        if not uuid:
            continue
        s = {}
        for f in STAT_FIELDS:
            v = r.get(f)
            if v is None:
                continue
            try:
                v = float(v)
                v = int(v) if v.is_integer() else round(v, 2)
            except (TypeError, ValueError):
                continue
            s[RENAME.get(f, f)] = v
        s.update(presence_fields(r))
        out[str(uuid)] = s
    return out


def _text(v):
    if v is None:
        return None
    if isinstance(v, (bytes, bytearray)):
        v = v.decode("utf-8", "replace")
    v = str(v).strip()
    return v or None


def _iso_utc(v):
    """UTC-Zeitstempel (datetime, Unix-s/ms, String) -> ISO 8601 mit Z, sonst None."""
    import datetime as _dt
    if v is None:
        return None
    try:
        if isinstance(v, _dt.datetime):
            d = v if v.tzinfo is None else v.astimezone(_dt.timezone.utc).replace(tzinfo=None)
        elif isinstance(v, (int, float)) and not isinstance(v, bool):
            x = float(v)
            d = _dt.datetime.fromtimestamp(x / 1000 if x > 1e11 else x, _dt.timezone.utc).replace(tzinfo=None)
        elif isinstance(v, str) and v.strip():
            t = v.strip().replace(" ", "T")
            if t.endswith("Z"):
                t = t[:-1] + "+00:00"
            d = _dt.datetime.fromisoformat(t)
            if d.tzinfo is not None:
                d = d.astimezone(_dt.timezone.utc).replace(tzinfo=None)
        else:
            return None
    except (ValueError, OverflowError, OSError):
        return None
    return d.replace(microsecond=0).isoformat() + "Z"


def _bool(v):
    if v is None:
        return None
    if isinstance(v, (bytes, bytearray)):
        v = int.from_bytes(v, "little") if v else 0
    if isinstance(v, str):
        t = v.strip().lower()
        if t in ("1", "true", "yes", "on"):
            return True
        if t in ("0", "false", "no", "off"):
            return False
        return None
    try:
        return bool(int(v))
    except (TypeError, ValueError):
        return None


MC_COLORS = {
    "0": "#000000", "1": "#0000AA", "2": "#00AA00", "3": "#00AAAA",
    "4": "#AA0000", "5": "#AA00AA", "6": "#FFAA00", "7": "#AAAAAA",
    "8": "#555555", "9": "#5555FF", "a": "#55FF55", "b": "#55FFFF",
    "c": "#FF5555", "d": "#FF55FF", "e": "#FFFF55", "f": "#FFFFFF",
}


def _hex(v):
    import re
    t = _text(v)
    if t and re.fullmatch(r"#[0-9A-Fa-f]{6}", t):
        return t.upper()
    return None


def team_color_code(v):
    """Roher &-Code (auch §-Code -> &), sonst None."""
    t = _text(v)
    if t and len(t) == 2 and t[0] in "&\u00a7" and t[1].lower() in MC_COLORS:
        return "&" + t[1].lower()
    return None


def team_color_hex(hex_col, raw):
    """#RRGGBB (gross) aus team_color_hex, sonst aus &-Code/Hex in team_color, sonst None."""
    h = _hex(hex_col)
    if h:
        return h
    c = team_color_code(raw)
    if c:
        return MC_COLORS[c[1]]
    return _hex(raw)


HEX_TO_CODE = {v: "&" + k for k, v in MC_COLORS.items()}


def team_colors(r):
    """Alle Quellformen (team_color als Hex/&-Code, team_color_hex, team_color_code) ->
    team_color == team_color_hex (#RRGGBB oder None), team_color_code (&x oder None)."""
    raw, hcol, ccol = r.get("team_color"), r.get("team_color_hex"), r.get("team_color_code")
    code = team_color_code(ccol) or team_color_code(raw)
    h = _hex(hcol) or _hex(raw) or (MC_COLORS[code[1]] if code else None)
    if not code and h:
        code = HEX_TO_CODE.get(h)
    return {"team_color": h, "team_color_hex": h, "team_color_code": code}


def presence_fields(r):
    """team/team_color/last_seen/online aus v_player_stats; fehlende Spalten -> None."""
    return {
        "team": _text(r.get("team")),
        **team_colors(r),
        "last_seen": _iso_utc(r.get("last_seen")),
        "online": _bool(r.get("online")),
    }


LB_FIELDS = ("money", "playtime_s", "kills", "deaths", "blocks_mined")


def _num(v):
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    return int(v) if v.is_integer() else round(v, 2)


def build_leaderboards(cols, rows):
    """Top 10 absteigend je Feld; NULL ignoriert; playtime_s -> playtime."""
    out = {}
    for f in LB_FIELDS:
        entries = []
        for row in rows:
            r = dict(zip(cols, row))
            name, v = r.get("mc_name") or r.get("name"), _num(r.get(f))
            if name and v is not None:
                entries.append({"name": str(name)[:16], "value": v})
        entries.sort(key=lambda e: e["value"], reverse=True)
        if entries:
            out[RENAME.get(f, f)] = entries[:10]
    return out


LB_KEYS = ("money", "playtime", "blocks_mined", "kills", "kd", "distance")
MAX_DETAILS = 16 * 1024


def fallback_leaderboards(cols, rows):
    """Aus v_player_stats: money, playtime, blocks_mined, kills, kd (kills/deaths)."""
    lb = build_leaderboards(cols, rows)
    lb.pop("deaths", None)
    kd = []
    for row in rows:
        r = dict(zip(cols, row))
        k, d, name = _num(r.get("kills")), _num(r.get("deaths")), r.get("mc_name")
        if name and k is not None and d is not None:
            kd.append({"name": str(name)[:16], "value": round(k / max(d, 1), 2)})
    kd.sort(key=lambda e: e["value"], reverse=True)
    if kd:
        lb["kd"] = kd[:10]
    return lb


def view_leaderboards(cols, rows):
    """v_leaderboards (board|category, period all|week, rank, uuid, name|mc_name, value) -> (all, week)."""
    res = {"all": {}, "week": {}}
    for row in rows:
        r = dict(zip(cols, row))
        cat = str(r.get("board") or r.get("category") or "").lower()
        per = {"weekly": "week", "total": "all"}.get(str(r.get("period") or "").lower(), str(r.get("period") or "").lower())
        v, name = _num(r.get("value")), r.get("name") or r.get("mc_name")
        if cat in LB_KEYS and per in res and name and v is not None:
            res[per].setdefault(cat, []).append((_num(r.get("rank")) or 999, {"name": str(name)[:16], "value": v}))
    out = []
    for per in ("all", "week"):
        d = {}
        for cat, lst in res[per].items():
            lst.sort(key=lambda t: (-t[1]["value"], t[0]))
            d[cat] = [e for _, e in lst[:10]]
        out.append(d)
    return out[0], out[1]


DETAIL_MAPS = {"blocks_mined": "mined", "mined": "mined", "blocks_placed": "placed", "placed": "placed",
               "kills_entity": "killed", "killed": "killed", "deaths_entity": "deaths_by", "deaths_by": "deaths_by",
               "crafted": "crafted", "playtime_by_world": "playtime_by_world", "distance_cm": "distance_m", "distance_m": "distance_m"}


def map_details(d):
    """Crafts details-Keys auf INGEST.md abbilden (blocks_mined->mined, kills_entity->killed, deaths_entity->deaths_by,
    distance_cm->distance_m in Metern), IDs ohne minecraft:, max. 25 groesste je Map, Zahlen >= 0."""
    out = {}
    for k, v in d.items():
        key = DETAIL_MAPS.get(k)
        if key and isinstance(v, dict):
            m = {}
            for i, n in v.items():
                n = _num(n)
                if n is None or n < 0:
                    continue
                if k == "distance_cm":
                    n = _num(n / 100)
                m[_item(i)] = n
            m = dict(sorted(m.items(), key=lambda e: -e[1])[:25])
            out.setdefault(key, {}).update(m)
        elif not isinstance(v, (dict, list)):
            n = _num(v)
            if n is not None and n >= 0:
                out[k] = n
    return out


def parse_details(raw):
    """details-JSON absichern: dict, <=16 KB, sonst None."""
    if raw is None:
        return None
    try:
        d = json.loads(raw) if isinstance(raw, (str, bytes)) else raw
    except ValueError:
        return None
    if not isinstance(d, dict):
        return None
    d = map_details(d)
    if len(json.dumps(d, separators=(",", ":"))) > MAX_DETAILS:
        log.info("details > 16 KB verworfen")
        return None
    return d

# ---------- Markt (Phase 4) ----------
MARKET_FIELDS = ("buy_price", "sell_price", "base_price", "volume_24h_qty", "volume_24h_cp", "trades_24h", "change_24h_pct", "updated_at")
HIST_FIELDS = ("avg_price", "min_price", "max_price", "volume_qty")
HIST_MAX_ROWS = 60000
MAX_BYTES = 7_500_000  # Puffer unter 8 MB
BACKFILL_S = 90 * 86400
_hist_state = {"since": None}  # Stundenbeginn des letzten erfolgreichen History-Pushs


def _item(v):
    v = str(v or "").strip().lower()
    return v[10:] if v.startswith("minecraft:") else v


def _ts(v):
    if hasattr(v, "timestamp"):
        return int(v.timestamp())
    n = _num(v)
    if n is None:
        return None
    return int(n // 1000) if n > 10_000_000_000 else int(n)


def build_market(cols, rows):
    out, seen = [], set()
    for row in rows:
        r = dict(zip(cols, row))
        it = _item(r.get("item"))
        if not it or it in seen:
            continue
        seen.add(it)
        e = {"item": it}
        for f in MARKET_FIELDS:
            v = _ts(r.get(f)) if f == "updated_at" else _num(r.get(f))
            if v is not None:
                e[f] = v
        out.append(e)
        if len(out) >= 2000:
            break
    return out


def build_history(cols, rows, since, now):
    """Zeilen mit hour_ts >= since (und nicht aelter als 90 T), nach Zeit sortiert."""
    lo = max(since, now - BACKFILL_S)
    out = []
    for row in rows:
        r = dict(zip(cols, row))
        it, ts = _item(r.get("item")), _ts(r.get("hour_ts"))
        if not it or ts is None or ts < lo or ts > now + 3600:
            continue
        e = {"item": it, "hour_ts": ts}
        for f in HIST_FIELDS:
            v = _num(r.get(f))
            if v is not None:
                e[f] = v
        out.append(e)
    out.sort(key=lambda e: (e["hour_ts"], e["item"]))
    return out


def chunk_history(entries, max_rows=HIST_MAX_ROWS, max_bytes=MAX_BYTES):
    chunks, cur, size = [], [], 30
    for e in entries:
        n = len(json.dumps(e, separators=(",", ":"))) + 1
        if cur and (len(cur) >= max_rows or size + n > max_bytes):
            chunks.append(cur)
            cur, size = [], 30
        cur.append(e)
        size += n
    if cur:
        chunks.append(cur)
    return chunks

# ---------- servers ----------
import re
SERVER_KEYS = (("lobby", "LOBBY_HOST", "lobby"), ("survival", "SURVIVAL_HOST", "survival"),
               ("vip1", "VIP1_HOST", None), ("vip2", "VIP2_HOST", None), ("vip3", "VIP3_HOST", None))
_COLOR = re.compile(r"\u00a7.")


def parse_list(out):
    m = re.search(r"There are (\d+) of a max of (\d+) players online:?(.*)", _COLOR.sub("", out or ""), re.S)
    if not m:
        return None
    names = [n.strip() for n in m.group(3).split(",") if n.strip()]
    return {"players": names, "max": int(m.group(2))}


def parse_tps(out):
    m = re.search(r"TPS from last[^:]*:\s*\*?([\d.]+)", _COLOR.sub("", out or ""))
    return min(float(m.group(1)), 20.0) if m else None


def log_uptime(path, now=None):
    try:
        first = open(path, encoding="utf-8", errors="replace").readline()
        hh, mm, ss = map(int, re.match(r"\[(\d+):(\d+):(\d+)\]", first).groups())
    except Exception:
        return None
    now = now or time.time()
    t = time.localtime(now)
    start = time.mktime((t.tm_year, t.tm_mon, t.tm_mday, hh, mm, ss, 0, 0, -1))
    if start > now:
        start -= 86400
    return int(now - start)


def server_entry(list_out, tps_out, mode=None, uptime=None, version=None):
    info = parse_list(list_out) if list_out is not None else None
    if not info:
        e = {"online": False, "players": []}
    else:
        e = {"online": True, "players": info["players"], "max": info["max"]}
        tps = parse_tps(tps_out)
        if tps is not None:
            e["tps"] = tps
        if uptime is not None:
            e["uptime_seconds"] = uptime
        if version:
            e["version"] = version
    if mode:
        e["mode"] = mode
    return e


def _rcon(host, cmd):
    import bot as _b
    try:
        return _b.rcon(host, cmd)
    except Exception:
        return None


def _version(host):
    try:
        from mcstatus import JavaServer
        st = JavaServer.lookup(f"{host}:25565").status()
        return re.sub(r"^\D+", "", st.version.name) or st.version.name
    except Exception:
        return None


def collect_servers(mode=None):
    out = {}
    for key, env, default in SERVER_KEYS:
        host = os.getenv(env, default or "")
        if not host:
            continue
        lst = _rcon(host, "list")
        if lst is None and default is None:
            continue  # VIP-Welt nicht vorhanden
        tps = _rcon(host, "tps") if lst is not None else None
        up = log_uptime(f"/minecraft/{key}/logs/latest.log") if lst is not None else None
        ver = _version(host) if lst is not None else None
        out[key] = server_entry(lst, tps, mode, up, ver)
    return out

# ---------- Phase 5: shops / auctions ----------
def _ms(v):
    if hasattr(v, "timestamp"):
        return int(v.timestamp() * 1000)
    n = _num(v)
    if n is None:
        return None
    return int(n) if n > 10_000_000_000 else int(n * 1000)


def build_shops(cols, rows):
    """v_shop_listings -> ein Shop pro Verkaeufer (id = seller_uuid), ein Item-Eintrag pro Angebot mit listing_id; price = Cent pro Stueck."""
    import buy
    shops = {}
    for row in rows:
        r = dict(zip(cols, row))
        sid = str(r.get("seller_uuid") or r.get("seller") or "").lower()
        it = _item(r.get("item_material") or r.get("material"))
        amount = int(_num(r.get("amount")) or 0)
        if not sid or not it or amount <= 0 or r.get("price_cents") is None:
            continue
        unit = buy.unit_cents(r["price_cents"], amount)
        s = shops.setdefault(sid, {"id": sid, "owner_uuid": sid, "owner_name": str(r.get("seller_name") or ""),
                                   "name": f"Shop von {r.get('seller_name') or '?'}", "items": {}})
        lid = r.get("id") or r.get("listing_id")
        if lid is None:
            continue
        s["items"][str(lid)] = {"listing_id": str(lid), "item": it, "price": unit, "stock": amount, "mode": "sell"}
    now = int(time.time() * 1000)
    out = []
    for s in shops.values():
        s["items"] = sorted(s["items"].values(), key=lambda e: (e["item"], e["price"], e["listing_id"]))
        s["updated_at"] = now
        out.append(s)
    return out


A_ALIASES = {"id": ("id", "auction_id"), "seller_uuid": ("seller_uuid", "seller"), "seller_name": ("seller_name",),
             "item": ("item", "material", "item_material"), "amount": ("menge", "amount"), "nbt_summary": ("nbt_summary",),
             "min_price": ("min_price", "min_price_cents", "start_price_cents"), "current_bid": ("current_bid", "current_bid_cents"),
             "current_bidder_name": ("current_bidder_name",), "current_bidder": ("current_bidder",), "bid_count": ("bid_count",),
             "ends_at": ("ends_at", "ends_at_ms", "expires_at_ms"), "status": ("status",)}


def norm(u):
    u = str(u or "").lower().replace("-", "")
    return f"{u[:8]}-{u[8:12]}-{u[12:16]}-{u[16:20]}-{u[20:]}" if len(u) == 32 else u


def _pick(r, keys):
    for k in keys:
        if r.get(k) is not None:
            return r[k]
    return None


def _raw(v):
    """ganze Cent / ms als int, NULL bleibt None"""
    if v is None or isinstance(v, (bool, int, str)):
        return v
    if hasattr(v, "timestamp"):
        return int(v.timestamp() * 1000)
    n = _num(v)
    return int(n) if n is not None and n == int(n) else (n if n is not None else str(v))


def raw_auctions(au, ab, names=None):
    """Top-Level v_auctions / v_auction_bids fuer den Ingest: alle aktuellen Zeilen (auch leer), Spalten 1:1, NULL bleibt null."""
    names = {str(k).lower(): v for k, v in (names or {}).items()}
    auctions = []
    for row in au[1]:
        r = {c: _raw(v) for c, v in zip(au[0], row)}
        for c in ("id", "seller", "current_bidder"):
            if r.get(c) is not None:
                r[c] = str(r[c]).lower() if c != "id" else str(r[c])
        if r.get("seller") and names.get(r["seller"]):
            r["seller_name"] = names[r["seller"]]
        if r.get("current_bidder") and names.get(r["current_bidder"]):
            r["current_bidder_name"] = names[r["current_bidder"]]
        auctions.append(r)
    bids = []
    for row in (ab[1] if ab else ()):
        r = {c: _raw(v) for c, v in zip(ab[0], row)}
        for c in ("id", "auction_id"):
            if r.get(c) is not None:
                r[c] = str(r[c])
        if r.get("bidder") is not None:
            r["bidder"] = str(r["bidder"]).lower()
        bids.append(r)
    return auctions, bids


def build_auctions(cols, rows, bcols=None, brows=(), names=None):
    """v_auctions + v_auction_bids -> INGEST-Schema; UUIDs (seller/current_bidder/bidder) per names {uuid: name} aufloesen."""
    names = {str(k).lower(): v for k, v in (names or {}).items()}
    nm = lambda u: names.get(str(u or "").lower(), "")
    bids = {}
    for row in brows or ():
        b = dict(zip(bcols, row))
        aid = str(_pick(b, ("auction_id",)) or "")
        amt = _num(_pick(b, ("amount", "amount_cents")))
        if aid and amt is not None:
            bids.setdefault(aid, []).append({"name": str(_pick(b, ("name", "bidder_name")) or nm(b.get("bidder"))), "amount": int(amt),
                                             "at": _ms(_pick(b, ("at", "created_at", "created_at_ms")))})
    out = []
    for row in rows:
        r = dict(zip(cols, row))
        a = {k: _pick(r, ks) for k, ks in A_ALIASES.items()}
        if a["id"] is None or not a["item"]:
            continue
        a["id"] = str(a["id"]); a["item"] = _item(a["item"])
        for k in ("amount", "min_price", "current_bid", "bid_count"):
            if a[k] is not None:
                a[k] = int(_num(a[k]) or 0)
        a["ends_at"] = _ms(a["ends_at"])
        a["seller_uuid"] = str(a["seller_uuid"]).lower() if a["seller_uuid"] else None
        a["seller_name"] = a["seller_name"] or nm(a["seller_uuid"]) or None
        cb = a.pop("current_bidder")
        a["current_bidder_name"] = a["current_bidder_name"] or nm(cb) or None
        st = str(a["status"] or "active").lower()
        st = {"open": "active", "running": "active", "canceled": "cancelled", "expired": "ended", "sold": "ended", "closed": "ended"}.get(st, st)
        a["status"] = st if st in ("active", "ended", "cancelled") else "active"
        if a["status"] == "active" and a.get("ends_at") and a["ends_at"] < time.time() * 1000:
            a["status"] = "ended"
        a = {k: v for k, v in a.items() if v is not None}
        bl = sorted(bids.get(a["id"], []), key=lambda b: -b["amount"])
        a["bids"] = [{k: v for k, v in b.items() if v is not None} for b in bl]
        out.append(a)
    return out


def build_price_reset(res):
    """ISO-8601-UTC aus v_price_meta.effective_from, None wenn nicht sicher lesbar (Key dann weglassen)."""
    try:
        cols, rows = res
        if not rows:
            return None
        v = dict(zip(cols, rows[0])).get("effective_from")
        if v is None or v == "":
            return None
        if isinstance(v, str):
            v = datetime.datetime.fromisoformat(v.strip().replace("Z", "+00:00"))
        if not isinstance(v, datetime.datetime):
            return None
        if v.tzinfo is None:
            v = v.replace(tzinfo=datetime.timezone.utc)
        v = v.astimezone(datetime.timezone.utc)
        if v.timestamp() <= 0:
            return None
        return v.strftime("%Y-%m-%dT%H:%M:%SZ")
    except Exception:
        return None


PARKOUR_MAX = 1000
PARKOUR_COURSE_MAX = 32  # Website-Limit fuer Kurs-IDs


import parkour


def build_parkour(cols, rows, meta=None, hidden=None):
    """Bestzeit pro Spieler und Kurs aus parkour_times: [{course, mc_uuid, mc_name, time_ms, set_at}] (set_at ISO-UTC)."""
    best = {}
    for row in rows or ():
        r = dict(zip(cols, row))
        course = parkour.slug(r.get("course"))
        uuid = str(r.get("uuid") or "").strip().lower()
        ms = _num(r.get("best_ms"))
        if not course or course in (hidden or ()) or len(course) > PARKOUR_COURSE_MAX or not uuid or ms is None or ms <= 0:
            continue
        v = r.get("updated_at")
        try:
            if isinstance(v, str):
                v = datetime.datetime.fromisoformat(v.strip().replace("Z", "+00:00"))
            if v.tzinfo is None:
                v = v.replace(tzinfo=datetime.timezone.utc)
            set_at = v.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (v.microsecond // 1000)
        except Exception:
            set_at = None
        e = {"course": course, "mc_uuid": uuid, "mc_name": str(r.get("name") or "")[:16], "time_ms": int(ms), "set_at": set_at}
        mn, mi = (meta or {}).get(course, (None, None))
        cn = mn or parkour.clean_name(r.get("course_name"))
        e["course_name"] = cn or parkour.pretty(course)
        ic = mi or parkour.icon(r.get("course_icon"))
        if ic:
            e["course_icon"] = ic
        old = best.get((course, uuid))
        if old is None or e["time_ms"] < old["time_ms"]:
            best[(course, uuid)] = e
    out = sorted(best.values(), key=lambda e: (e["course"], e["time_ms"], e["set_at"] or "", e["mc_uuid"]))
    return [{k: v for k, v in e.items() if v is not None} for e in out][:PARKOUR_MAX]


def fetch_rows(view):
    """(cols, rows) aus einer View, None wenn nicht lesbar."""
    return _fetch(view)


def _fetch(view):
    import pymysql
    import shops
    pw = shops.db_password()
    if not pw:
        return None
    try:
        con = pymysql.connect(host=os.getenv("CRAFTCORE_DB_HOST", "craftcore-db"), user=shops.db_user(),
                              password=pw, database="craftcore", connect_timeout=5, read_timeout=5,
                              init_command="SET time_zone='+00:00'")  # TIMESTAMP-Spalten immer als UTC lesen
    except Exception:
        return None
    try:
        with con.cursor() as cur:
            cur.execute("SELECT * FROM " + view)
            return [d[0] for d in cur.description], cur.fetchall()
    except Exception as e:
        log.info("%s nicht verfuegbar (%s)", view, type(e).__name__)
        return None
    finally:
        con.close()


INV_MAX = 64
PLAYERS_MAX = 200


def _key_map(stats):
    return {norm(k): k for k in stats}


def merge_player_extras(stats, bal=None, inv=None, pickup=None):
    """Kontostand/Inventar/Abholungen in players_stats[uuid] mergen (Inventar nur Ingest, nie Discord)."""
    keys = _key_map(stats)

    def slot(uuid):
        k = keys.get(norm(uuid))
        if k is None:
            if len(stats) >= PLAYERS_MAX:
                return None
            k = keys[norm(uuid)] = norm(uuid)
            stats[k] = {}
        return stats[k]

    if bal:
        for row in bal[1]:
            r = dict(zip(bal[0], row))
            v = _num(r.get("balance_cp"))
            st = slot(r.get("uuid")) if r.get("uuid") and v is not None else None
            if st is not None:
                st["money"] = int(v) if float(v).is_integer() else round(float(v), 2)
    for data, field, keyset in ((inv, "inventory", True), (pickup, "pickup", False)):
        if not data:
            continue
        per = {}
        for row in data[1]:
            r = dict(zip(data[0], row))
            if not r.get("uuid") or not r.get("material"):
                continue
            e = {"item": _item(r.get("material")), "count": int(_num(r.get("amount")) or 0)}
            if keyset:
                e = {"slot": int(_num(r.get("slot")) or 0), **e}
            per.setdefault(norm(r["uuid"]), []).append(e)
        for u, items in per.items():
            st = slot(u)
            if st is not None:
                if keyset:
                    items.sort(key=lambda e: e["slot"])
                st[field] = items[:INV_MAX]
    return stats


def build_payload(bot):
    rows = bot.db.execute("SELECT discord_id, uuid, name, state FROM links WHERE state != 'pending'").fetchall()
    guild = bot.get_guild(int(bot.cfg["GUILD_ID"]))
    rid = int(bot.cfg["PLAYER_ROLE_ID"])

    def has_role(did):
        m = guild.get_member(int(did)) if guild else None
        return bool(m and any(r.id == rid for r in m.roles))

    payload = {"links": build_links(rows, has_role), "links_full": True}
    return payload


async def push_once(bot, session=None):
    token = os.getenv("MC_INGEST_TOKEN", "").strip()
    if not token:
        log.warning("MC_INGEST_TOKEN fehlt - kein Push")
        return None
    payload = build_payload(bot)
    res = await asyncio.to_thread(fetch_rows, "v_player_stats")
    if res:
        stats = build_stats(*res)
        if stats:
            payload["players_stats"] = stats
        lbv = await asyncio.to_thread(fetch_rows, "v_leaderboards")
        if lbv:
            lb_all, lb_week = view_leaderboards(*lbv)
            if lb_week:
                payload["leaderboards_weekly"] = lb_week
        else:
            lb_all = fallback_leaderboards(*res)
        if lb_all:
            payload["leaderboards"] = lb_all
        ext = await asyncio.to_thread(fetch_rows, "v_player_stats_ext")
        if ext and stats:
            for row in ext[1]:
                r = dict(zip(ext[0], row))
                d = parse_details(r.get("details"))
                if d and str(r.get("uuid")) in stats:
                    stats[str(r.get("uuid"))]["details"] = d
    bal = await asyncio.to_thread(fetch_rows, "v_player_balance")
    inv = await asyncio.to_thread(fetch_rows, "v_player_inventory")
    for name, v in (("v_player_balance", bal), ("v_player_inventory", inv)):
        if v is None:
            log.warning("%s fehlt oder nicht lesbar - Feld wird nicht gesendet", name)
    if bal or inv:
        ps = merge_player_extras(payload.get("players_stats") or {}, bal, inv)
        if ps:
            payload["players_stats"] = ps
    try:
        mode = await asyncio.to_thread(bot.server_mode)
    except Exception:
        mode = None
    servers = await asyncio.to_thread(collect_servers, mode)
    if servers:
        payload["servers"] = servers
    sh = await asyncio.to_thread(fetch_rows, "v_shop_listings")
    if sh:
        payload["shops"] = build_shops(*sh)
    au = await asyncio.to_thread(fetch_rows, "v_auctions")
    if au:
        ab = await asyncio.to_thread(fetch_rows, "v_auction_bids")
        names = {str(u).lower(): n for u, n in bot.db.execute("SELECT uuid, name FROM links WHERE name IS NOT NULL").fetchall()}
        names.update({norm(u): n for u, n in names.items()})
        payload["auctions"] = build_auctions(*au, *(ab or (None, ())), names=names)
        payload["v_auctions"], payload["v_auction_bids"] = raw_auctions(au, ab, names)
    mk = await asyncio.to_thread(fetch_rows, "v_market")
    if mk:
        payload["market"] = build_market(*mk)
    pm = await asyncio.to_thread(fetch_rows, "v_price_meta")
    reset = build_price_reset(pm) if pm else None
    if reset:
        payload["price_reset_at"] = reset
    pk = await asyncio.to_thread(fetch_rows, "parkour_times")
    if pk:
        pc = await asyncio.to_thread(fetch_rows, "v_parkour_courses")
        payload["parkour"] = build_parkour(*pk, meta=parkour.course_meta(*pc) if pc else None,
                                           hidden=parkour.hidden_slugs(*pc) if pc else None)
    h = payload_hash(payload)
    own = session is None
    session = session or aiohttp.ClientSession()
    try:
        if h == _rate["last_hash"]:
            log.debug("Ingest-Push uebersprungen: unveraendert")
            result = None
        else:
            result = await _post(session, token, payload, "Ingest-Push")
            if result and result[0] < 300:
                _rate["last_hash"] = h
                await maybe_push_history(session, token)
        return result
    finally:
        if own:
            await session.close()


async def _post(session, token, payload, label):
    now = time.monotonic()
    if now < _rate["backoff_until"]:
        log.info("%s uebersprungen: Backoff noch %.0f s", label, _rate["backoff_until"] - now)
        return 0, "backoff"
    wait = _rate["last_post"] + MIN_GAP - now
    if wait > 0:
        await asyncio.sleep(wait)
    _rate["last_post"] = time.monotonic()
    async with session.post(URL, json=payload, headers={"x-ingest-token": token},
                            timeout=aiohttp.ClientTimeout(total=120)) as r:
        body = (await r.text())[:300]
        note_status(r.status, r.headers.get("Retry-After") if r.headers else None)
        if r.status < 300:
            log.info("%s HTTP %s: %s", label, r.status, body)
        else:
            log.warning("%s HTTP %s: %s", label, r.status, body)
        return r.status, body


HISTORY_EVERY = 300
_hist_push = {"last": None}


async def maybe_push_history(session, token, now=None):
    """Markt-History nur nach echtem Erfolg und hoechstens alle HISTORY_EVERY s."""
    now = now if now is not None else time.monotonic()
    if _hist_push["last"] is not None and now - _hist_push["last"] < HISTORY_EVERY:
        return None
    _hist_push["last"] = now
    ok = await push_history(session, token)
    try:
        await push_economy(session, token)
    except Exception as e:  # fail-soft: Economy darf History nie stoeren
        log.warning("economy_daily Push fehlgeschlagen (%s)", type(e).__name__)
    try:
        await push_ranks(session, token)
    except Exception as e:  # fail-soft: Raenge duerfen History nie stoeren
        log.warning("player_ranks Push fehlgeschlagen (%s)", type(e).__name__)
    try:
        await push_pvp(session, token)
    except Exception as e:  # fail-soft: PvP darf History nie stoeren
        log.warning("pvp_stats Push fehlgeschlagen (%s)", type(e).__name__)
    return ok


ECONOMY_DAYS = 90
ECONOMY_COLS = ("money_supply_cp", "minted_cp", "burned_cp", "net_cp")
_econ = {"warned": False}


def _cp(v):
    if v is None:
        return None
    try:
        return int(round(float(v)))
    except (TypeError, ValueError):
        return None


def build_economy(cols, rows, days=ECONOMY_DAYS):
    """v_economy_daily -> [{day: 'YYYY-MM-DD', money_supply_cp: int, ...}], letzte `days` Tage, aufsteigend."""
    idx = {c: i for i, c in enumerate(cols)}
    if "day" not in idx:
        return []
    out = []
    for r in rows:
        d = r[idx["day"]]
        if d is None:
            continue
        day = d.isoformat()[:10] if hasattr(d, "isoformat") else str(d)[:10]
        e = {"day": day}
        for c in ECONOMY_COLS:
            if c in idx:
                e[c] = _cp(r[idx[c]])
        out.append(e)
    out.sort(key=lambda e: e["day"])
    return out[-days:]


async def push_economy(session, token):
    """economy_daily im History-Takt; View-Fehler -> einmal loggen, Key weglassen."""
    res = await asyncio.to_thread(fetch_rows, "v_economy_daily")
    if not res:
        if not _econ["warned"]:
            log.warning("v_economy_daily nicht lesbar, economy_daily wird weggelassen")
            _econ["warned"] = True
        return None
    _econ["warned"] = False
    data = build_economy(*res)
    st, _ = await _post(session, token, {"economy_daily": data}, "Economy-Daily")
    return st < 300


RANKS_MAX = 500
_ranks = {"warned": False}


def build_ranks(cols, rows, limit=RANKS_MAX):
    """v_player_rank -> [{uuid, name, rank, color, weight}], sortiert nach weight absteigend, dann Name."""
    idx = {c: i for i, c in enumerate(cols)}
    if "uuid" not in idx or "rank" not in idx:
        return []
    out = []
    for r in rows:
        g = lambda c: r[idx[c]] if c in idx else None
        if not g("uuid") or not g("rank"):
            continue
        try:
            w = int(g("weight") or 0)
        except (TypeError, ValueError):
            w = 0
        out.append({"uuid": str(g("uuid")), "name": str(g("name") or ""), "rank": str(g("rank")),
                    "color": str(g("color") or "&7"), "weight": w})
    out.sort(key=lambda e: (-e["weight"], e["name"].lower()))
    return out[:limit]


async def push_ranks(session, token):
    """player_ranks im 5-Minuten-Takt; fehlt v_player_rank -> einmal loggen, Key weglassen."""
    res = await asyncio.to_thread(fetch_rows, "v_player_rank")
    if not res:
        if not _ranks["warned"]:
            log.warning("v_player_rank nicht lesbar, player_ranks wird weggelassen")
            _ranks["warned"] = True
        return None
    data = build_ranks(*res)
    if not data:  # Website nimmt jeden Push als Vollbild: leere Liste wuerde alle Raenge loeschen
        if not _ranks["warned"]:
            log.warning("v_player_rank leer oder unerwartete Spalten, player_ranks wird weggelassen")
            _ranks["warned"] = True
        return None
    _ranks["warned"] = False
    st, _ = await _post(session, token, {"player_ranks": data}, "Player-Ranks")
    return st < 300


_pvp = {"warned": False}


async def push_pvp(session, token):
    """pvp_stats im 5-Minuten-Takt; View-Fehler/leer -> einmal loggen, Key weglassen (nie [])."""
    import pvp
    res = await asyncio.to_thread(fetch_rows, "v_pvp_stats")
    data = pvp.build_stats(*res) if res else []
    if not data:
        if not _pvp["warned"]:
            log.warning("v_pvp_stats nicht lesbar oder leer, pvp_stats wird weggelassen")
            _pvp["warned"] = True
        return None
    _pvp["warned"] = False
    st, _ = await _post(session, token, {"pvp_stats": data}, "PvP-Stats")
    return st < 300


async def push_history(session, token, now=None):
    """Erster Lauf: Backfill 90 T in Teilen; danach nur Stunden seit letztem Erfolg inkl. laufender Stunde."""
    hist = await asyncio.to_thread(fetch_rows, "v_market_history")
    if not hist:
        return None
    now = int(now or time.time())
    since = _hist_state["since"] if _hist_state["since"] is not None else 0
    entries = build_history(*hist, since, now)
    ok = True
    for i, chunk in enumerate(chunk_history(entries)):
        st, _ = await _post(session, token, {"market_history": chunk}, "Markt-History %d" % (i + 1))
        if st >= 300:
            ok = False
            break
    if ok:
        _hist_state["since"] = now - now % 3600
    return ok

SERVERS_INTERVAL = 60


async def push_servers(bot, session):
    token = os.getenv("MC_INGEST_TOKEN", "").strip()
    if not token:
        return None
    try:
        mode = await asyncio.to_thread(bot.server_mode)
    except Exception:
        mode = None
    servers = await asyncio.to_thread(collect_servers, mode)
    if not servers:
        return None
    return await _post(session, token, {"servers": servers}, "Servers-Push")


async def servers_loop(bot):
    await bot.wait_until_ready()
    async with aiohttp.ClientSession() as session:
        while not bot.is_closed():
            try:
                async with _push_lock:
                    await push_servers(bot, session)
            except Exception as e:
                log.warning("Servers-Push fehlgeschlagen: %s", type(e).__name__)
            await asyncio.sleep(SERVERS_INTERVAL)


# ---------- Sofort-Push bei Join/Leave/Tod ----------
DEBOUNCE = 3
PRESENCE_POLL = 1
JOIN_LEAVE_RE = re.compile(r"\]: ([A-Za-z0-9_]{3,16}) (joined|left) the game\s*$")
# wie bot.DEATH_RE (Chat-Sync), aber nur Server-Thread-Zeilen ohne Chat-Prefix
DEATH_RE = re.compile(r"\[Server thread/INFO\]: ([A-Za-z0-9_]{3,16}) (?:was |were |died|drowned|fell |blew up|burned|went up in flames|went off|hit the ground|experienced kinetic|froze|starved|suffocated|walked into|tried to swim|withered|discovered the floor|left the confines|didn't want to live)")
NOT_DEATH = re.compile(r" was (?:kicked|banned|unbanned|whitelisted|opped|de-opped)|\]: \S+ was (?:added|removed) ")
_push_lock = asyncio.Lock()
_presence = {"last": 0.0, "pending": None, "dirty": False, "running": False}


def is_presence_line(line):
    return bool(JOIN_LEAVE_RE.search(line or ""))


def is_death_line(line):
    line = line or ""
    return bool(DEATH_RE.search(line)) and not NOT_DEATH.search(line)


def is_trigger_line(line):
    return is_presence_line(line) or is_death_line(line)


async def _presence_push(bot):
    async with _push_lock:
        _presence["last"] = time.monotonic()
        _presence["running"] = True
        try:
            async with aiohttp.ClientSession() as session:
                return await push_once(bot, session)
        finally:
            _presence["running"] = False


def trigger_presence(bot):
    """Max. ein Extra-Push pro DEBOUNCE s; spaetere Events werden nachgeholt, nie parallel."""
    if _presence["pending"] and not _presence["pending"].done():
        if _presence["running"]:
            _presence["dirty"] = True  # Event waehrend laufendem Push -> danach noch einmal
        return _presence["pending"]

    async def run():
        while True:
            wait = max(0.0, _presence["last"] + DEBOUNCE - time.monotonic())
            if wait:
                await asyncio.sleep(wait)
            _presence["dirty"] = False
            try:
                await _presence_push(bot)
            except Exception as e:
                log.warning("Presence-Push fehlgeschlagen: %s", type(e).__name__)
            if not _presence["dirty"]:
                break

    _presence["pending"] = asyncio.create_task(run())
    return _presence["pending"]


def scan_presence(offsets, base="/minecraft"):
    """Liest neue Logzeilen aller Backends (Lobby, Survival, VIP); True bei Join/Leave/Tod."""
    hit = False
    for key, _env, _d in SERVER_KEYS:
        path = f"{base}/{key}/logs/latest.log"
        try:
            st = os.stat(path)
        except OSError:
            continue
        old = offsets.get(key)
        if old is None:
            offsets[key] = (st.st_ino, st.st_size)
            continue
        ino, off = old
        if ino != st.st_ino or st.st_size < off:
            off = 0
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                f.seek(off)
                for line in f:
                    hit = is_trigger_line(line) or hit
                offsets[key] = (st.st_ino, f.tell())
        except OSError:
            continue
    return hit


async def presence_loop(bot):
    await bot.wait_until_ready()
    offsets = {}
    while not bot.is_closed():
        try:
            if await asyncio.to_thread(scan_presence, offsets):
                trigger_presence(bot)
        except Exception as e:
            log.warning("Presence-Scan fehlgeschlagen: %s", type(e).__name__)
        await asyncio.sleep(PRESENCE_POLL)


async def loop(bot):
    asyncio.create_task(servers_loop(bot))
    asyncio.create_task(presence_loop(bot))
    import relay
    asyncio.create_task(relay.loop(bot))
    await bot.wait_until_ready()
    while not bot.is_closed():
        try:
            async with _push_lock:
                await push_once(bot)
        except Exception as e:
            log.warning("Ingest-Push fehlgeschlagen: %s", type(e).__name__)
        await asyncio.sleep(INTERVAL)

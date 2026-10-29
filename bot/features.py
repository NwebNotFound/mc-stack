"""Wochen-Recap und /event fuer Creepy."""
import asyncio, datetime as dt, json, logging, re, time
import discord
from discord import app_commands
import stats_card

log = logging.getLogger("creepy.features")
try:
    from zoneinfo import ZoneInfo
    TZ = ZoneInfo("Europe/Berlin")
except Exception:
    TZ = None


def now():
    return dt.datetime.now(TZ) if TZ else dt.datetime.now()


def init_db(db):
    db.execute("CREATE TABLE IF NOT EXISTS recap_snap (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, taken INTEGER NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS recap_meta (k TEXT PRIMARY KEY, v TEXT)")
    db.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, channel_id INTEGER, title TEXT, ts INTEGER, descr TEXT, author INTEGER, reminded INTEGER NOT NULL DEFAULT 0)")
    db.execute("CREATE TABLE IF NOT EXISTS event_rsvp (event_id INTEGER, user_id INTEGER, PRIMARY KEY(event_id, user_id))")
    db.commit()


# ---------- Recap ----------
def names():
    try:
        return {e["uuid"].replace("-", ""): e["name"] for e in json.loads((stats_card.ROOT / "usercache.json").read_text(encoding="utf-8"))}
    except Exception:
        return {}


def totals():
    out, nm = {}, names()
    for d in (stats_card.ROOT / "world/players/stats", stats_card.ROOT / "world/stats"):
        if not d.is_dir():
            continue
        for p in d.glob("*.json"):
            try:
                s = json.loads(p.read_text(encoding="utf-8")).get("stats", {})
            except Exception:
                continue
            c = s.get("minecraft:custom", {})
            out[p.stem] = {
                "name": nm.get(p.stem.replace("-", ""), p.stem[:8]),
                "play": c.get("minecraft:play_time", 0),
                "kills": c.get("minecraft:mob_kills", 0),
                "deaths": c.get("minecraft:deaths", 0),
                "jumps": c.get("minecraft:jump", 0),
                "fish": c.get("minecraft:fish_caught", 0),
                "cm": sum(v for k, v in c.items() if k.endswith("_one_cm") and k != "minecraft:fall_one_cm"),
                "mined": s.get("minecraft:mined", {}),
            }
    return out


def snapshot(db, cur=None):
    cur = cur if cur is not None else totals()
    t = int(time.time())
    for u, v in cur.items():
        db.execute("INSERT OR REPLACE INTO recap_snap VALUES (?,?,?)", (u, json.dumps(v), t))
    db.commit()


def deltas(db, cur):
    old = {u: json.loads(d) for u, d in db.execute("SELECT uuid,data FROM recap_snap")}
    since = db.execute("SELECT MIN(taken) FROM recap_snap").fetchone()[0]
    res = {}
    for u, v in cur.items():
        o = old.get(u, {})
        d = {k: max(0, v[k] - o.get(k, 0)) for k in ("play", "kills", "deaths", "jumps", "fish", "cm")}
        om = o.get("mined", {})
        d["mined"] = {b: n - om.get(b, 0) for b, n in v["mined"].items() if n - om.get(b, 0) > 0}
        d["name"] = v["name"]
        res[u] = d
    return cur, res, since


def recap_embed(res, since):
    e = discord.Embed(color=0x57F287, title="\U0001F4C5 Wochen-Recap")
    active = [d for d in res.values() if d["play"] > 0]
    e.description = (f"-# Seit <t:{since}:D>\n" if since else "") + (f"**{len(active)}** Spieler waren diese Woche online." if active else "Diese Woche war niemand auf Survival \U0001F634")
    if not active:
        return e
    top = sorted(active, key=lambda d: -d["play"])[:3]
    medals = ["\U0001F947", "\U0001F948", "\U0001F949"]
    e.add_field(name="\u23F1\uFE0F Meiste Spielzeit", value="\n".join(f"{medals[i]} **{discord.utils.escape_markdown(d['name'])}** \u00b7 {stats_card.dur(d['play'])}" for i, d in enumerate(top)), inline=False)
    blocks = {}
    for d in active:
        for b, n in d["mined"].items():
            blocks[b] = blocks.get(b, 0) + n
    if blocks:
        b, n = max(blocks.items(), key=lambda kv: kv[1])
        e.add_field(name="\u26CF\uFE0F Meistabgebauter Block", value=f"{stats_card.pretty(b, 'block')} \u00b7 {n:,}".replace(",", "."), inline=True)
    kills = sum(d["kills"] for d in active)
    hunter = max(active, key=lambda d: d["kills"])
    e.add_field(name="\u2694\uFE0F Mob-Kills", value=f"{kills} gesamt" + (f"\nTop: **{discord.utils.escape_markdown(hunter['name'])}** ({hunter['kills']})" if hunter["kills"] else ""), inline=True)
    fun = []
    dead = max(active, key=lambda d: d["deaths"])
    if dead["deaths"]:
        fun.append(f"\U0001F480 Pechvogel: **{discord.utils.escape_markdown(dead['name'])}** ({dead['deaths']}\u00d7 gestorben)")
    walk = max(active, key=lambda d: d["cm"])
    if walk["cm"]:
        fun.append(f"\U0001F45F Weltenbummler: **{discord.utils.escape_markdown(walk['name'])}** ({walk['cm'] / 100000:.1f} km)")
    jump = max(active, key=lambda d: d["jumps"])
    if jump["jumps"]:
        fun.append(f"\U0001F407 Flummi: **{discord.utils.escape_markdown(jump['name'])}** ({jump['jumps']} Spr\u00fcnge)")
    fish = max(active, key=lambda d: d["fish"])
    if fish["fish"]:
        fun.append(f"\U0001F3A3 Angelk\u00f6nig: **{discord.utils.escape_markdown(fish['name'])}** ({fish['fish']} Fische)")
    if fun:
        e.add_field(name="\U0001F389 Fun Facts", value="\n".join(fun), inline=False)
    e.set_footer(text="Jeden Sonntag um 18 Uhr \u00b7 /stats f\u00fcr deine eigenen Zahlen")
    return e


# ---------- Events ----------
def parse_when(text):
    t = text.strip().lower().replace(" uhr", "")
    n = now()
    m = re.fullmatch(r"(heute|morgen)\s+(\d{1,2})[:.](\d{2})", t)
    if m:
        d = n.date() + dt.timedelta(days=1 if m[1] == "morgen" else 0)
        return dt.datetime(d.year, d.month, d.day, int(m[2]), int(m[3]), tzinfo=TZ)
    m = re.fullmatch(r"(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?\s+(\d{1,2})[:.](\d{2})", t)
    if m:
        y = int(m[3]) if m[3] else n.year
        y = y + 2000 if y < 100 else y
        r = dt.datetime(y, int(m[2]), int(m[1]), int(m[4]), int(m[5]), tzinfo=TZ)
        if not m[3] and r < n:
            r = r.replace(year=y + 1)
        return r
    raise ValueError


def event_embed(bot, ev_id):
    title, ts, descr = bot.db.execute("SELECT title,ts,descr FROM events WHERE id=?", (ev_id,)).fetchone()
    ppl = [r[0] for r in bot.db.execute("SELECT user_id FROM event_rsvp WHERE event_id=?", (ev_id,))]
    e = discord.Embed(color=0xF1C40F, title=f"\U0001F4E2 {title}", description=descr or None)
    e.add_field(name="\U0001F552 Wann", value=f"<t:{ts}:F>\n<t:{ts}:R>", inline=True)
    e.add_field(name=f"\u2705 Dabei ({len(ppl)})", value=", ".join(f"<@{u}>" for u in ppl[:30]) or "Noch niemand - sei der Erste!", inline=True)
    e.set_footer(text="Klick auf \u201eBin dabei\u201c - ich erinnere dich 15 Minuten vorher per DM. Nochmal klicken = absagen.")
    return e


class EventView(discord.ui.View):
    def __init__(self, bot):
        super().__init__(timeout=None)
        self.bot = bot

    @discord.ui.button(label="Bin dabei", emoji="\u2705", style=discord.ButtonStyle.success, custom_id="event:rsvp")
    async def rsvp(self, interaction: discord.Interaction, button):
        row = self.bot.db.execute("SELECT id,ts FROM events WHERE msg_id=?", (interaction.message.id,)).fetchone()
        if not row:
            return await interaction.response.send_message("Dieses Event kenne ich leider nicht mehr.", ephemeral=True)
        ev, ts = row
        if ts < time.time():
            return await interaction.response.send_message("Das Event ist schon vorbei \U0001F605", ephemeral=True)
        if self.bot.db.execute("SELECT 1 FROM event_rsvp WHERE event_id=? AND user_id=?", (ev, interaction.user.id)).fetchone():
            self.bot.save("DELETE FROM event_rsvp WHERE event_id=? AND user_id=?", (ev, interaction.user.id))
            msg = "Okay, du bist abgemeldet."
        else:
            self.bot.save("INSERT INTO event_rsvp VALUES (?,?)", (ev, interaction.user.id))
            msg = "Du bist dabei! \U0001F389 Ich schick dir 15 Minuten vorher eine DM."
        await interaction.response.edit_message(embed=event_embed(self.bot, ev), view=self)
        await interaction.followup.send(msg, ephemeral=True)


class EventModal(discord.ui.Modal, title="Neues Event"):
    titel = discord.ui.TextInput(label="Titel", placeholder="z. B. Bau-Wettbewerb", max_length=80)
    wann = discord.ui.TextInput(label="Wann?", placeholder="z. B. 02.11. 20:00 oder morgen 19:30", max_length=30)
    beschreibung = discord.ui.TextInput(label="Beschreibung (optional)", style=discord.TextStyle.paragraph, required=False, max_length=1000,
                                        placeholder="Worum geht's, wo trefft ihr euch, was braucht man?")

    def __init__(self, bot):
        super().__init__()
        self.bot = bot

    async def on_submit(self, interaction: discord.Interaction):
        try:
            when = parse_when(str(self.wann))
        except Exception:
            return await interaction.response.send_message(f"Die Zeit **{discord.utils.escape_markdown(str(self.wann))}** verstehe ich nicht \U0001F914\nSchreib sie so: `02.11. 20:00`, `02.11.2026 20:00` oder `morgen 19:30`.", ephemeral=True)
        ts = int(when.timestamp())
        if ts < time.time() + 60:
            return await interaction.response.send_message("Das liegt in der Vergangenheit - nimm eine Zeit in der Zukunft.", ephemeral=True)
        ch = discord.utils.get(interaction.guild.text_channels, name="announcements")
        if not ch:
            return await interaction.response.send_message("Ich finde #announcements nicht.", ephemeral=True)
        await interaction.response.defer(ephemeral=True)
        cur = self.bot.db.execute("INSERT INTO events (channel_id,title,ts,descr,author) VALUES (?,?,?,?,?)", (ch.id, str(self.titel), ts, str(self.beschreibung), interaction.user.id))
        ev = cur.lastrowid
        self.bot.db.commit()
        try:
            msg = await ch.send(content=f"<@&{self.bot.cfg['PLAYER_ROLE_ID']}> Neues Event!", embed=event_embed(self.bot, ev), view=EventView(self.bot),
                                allowed_mentions=discord.AllowedMentions(roles=True))
        except discord.HTTPException:
            self.bot.save("DELETE FROM events WHERE id=?", (ev,))
            return await interaction.followup.send("Ich konnte in #announcements nicht posten (Rechte?).", ephemeral=True)
        self.bot.save("UPDATE events SET msg_id=? WHERE id=?", (msg.id, ev))
        await interaction.followup.send(f"Event ist raus: {msg.jump_url}", ephemeral=True)


async def loop(bot):
    await bot.wait_until_ready()
    init_db(bot.db)
    if not bot.db.execute("SELECT 1 FROM recap_snap LIMIT 1").fetchone():
        snapshot(bot.db, await asyncio.to_thread(totals))  # Basis fuer die erste Woche
        log.info("Recap-Basis-Snapshot angelegt")
    while not bot.is_closed():
        try:
            n = now()
            week = n.strftime("%G-%V")
            last = bot.db.execute("SELECT v FROM recap_meta WHERE k='last_week'").fetchone()
            if n.weekday() == 6 and n.hour >= 18 and (not last or last[0] != week):
                cur, res, since = deltas(bot.db, await asyncio.to_thread(totals))
                ch = bot.get_channel(int(bot.cfg["CHAT_CHANNEL_ID"]))
                if ch:
                    await ch.send(embed=recap_embed(res, since))
                snapshot(bot.db, cur)
                bot.save("INSERT OR REPLACE INTO recap_meta VALUES ('last_week',?)", (week,))
                log.info("Wochen-Recap %s gepostet", week)
            t = int(time.time())
            for ev, title, ts, ch_id, msg_id in bot.db.execute("SELECT id,title,ts,channel_id,msg_id FROM events WHERE reminded=0 AND ts-900<=? AND msg_id IS NOT NULL", (t,)).fetchall():
                bot.save("UPDATE events SET reminded=1 WHERE id=?", (ev,))
                if ts < t - 600:
                    continue  # verpasst (Bot war aus), nicht nachtraeglich spammen
                link = f"https://discord.com/channels/{bot.cfg['GUILD_ID']}/{ch_id}/{msg_id}"
                for (uid,) in bot.db.execute("SELECT user_id FROM event_rsvp WHERE event_id=?", (ev,)).fetchall():
                    try:
                        u = bot.get_user(uid) or await bot.fetch_user(uid)
                        await u.send(f"\u23F0 **{discord.utils.escape_markdown(title)}** startet <t:{ts}:R>! Server: `mc.nwebnotfound.dev`\n{link}")
                    except discord.HTTPException:
                        pass
        except Exception:
            log.exception("Recap/Event-Loop fehlgeschlagen")
        await asyncio.sleep(60)


def register(bot, tree):
    init_db(bot.db)

    async def is_admin(interaction):
        if not bot.role(interaction.user, "ADMIN_ROLE_ID"):
            await interaction.response.send_message("Das d\u00fcrfen nur Admins \U0001F6E1\uFE0F", ephemeral=True)
            return False
        return True

    @tree.command(name="event", description="Event ank\u00fcndigen (Admin) - \u00f6ffnet ein Formular")
    async def event(interaction: discord.Interaction):
        if await is_admin(interaction):
            await interaction.response.send_modal(EventModal(bot))

    recap = app_commands.Group(name="recap", description="Wochen-Recap (jeden Sonntag 18 Uhr in #mc-chat)")

    @recap.command(name="preview", description="Vorschau des Wochen-Recaps, nur f\u00fcr dich sichtbar (Admin)")
    async def preview(interaction: discord.Interaction):
        if not await is_admin(interaction):
            return
        await interaction.response.defer(ephemeral=True)
        try:
            _, res, since = deltas(bot.db, await asyncio.to_thread(totals))
            await interaction.followup.send("-# Vorschau - so sieht der Recap am Sonntag aus (Stand jetzt):", embed=recap_embed(res, since), ephemeral=True)
        except Exception:
            log.exception("Recap-Vorschau fehlgeschlagen")
            await interaction.followup.send("Die Vorschau hat gerade nicht geklappt - Logs pr\u00fcfen.", ephemeral=True)

    tree.add_command(recap)

"""Gated Einladungen (/invite): persoenliche Einmal-Links, Tracking wer wen eingeladen hat,
Admin-Log und (optional) Eintrag in die CraftCore-Tabelle `invites` nach /link."""
import asyncio, logging, os, time
import discord

log = logging.getLogger("invites")

MAX_OPEN_INVITES = 2          # offene Einladungslinks pro Person (hier aendern)
INVITE_MAX_AGE = 24 * 3600    # Gueltigkeit in Sekunden
WELCOME_NAME = "welcome"
ADMIN_LOG_NAME = "admin-log"
SYNC_INTERVAL = 300


def init_db(db):
    db.execute("CREATE TABLE IF NOT EXISTS dc_invites (code TEXT PRIMARY KEY, inviter_id INTEGER NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL, used_by INTEGER, used_at INTEGER)")
    db.execute("CREATE TABLE IF NOT EXISTS dc_invite_joins (member_id INTEGER PRIMARY KEY, inviter_id INTEGER, code TEXT, joined INTEGER NOT NULL, synced_at INTEGER)")
    db.commit()


def open_invites(db, user_id, now=None):
    now = now or time.time()
    return db.execute("SELECT code, expires FROM dc_invites WHERE inviter_id=? AND used_by IS NULL AND expires>? ORDER BY created", (user_id, int(now))).fetchall()


def find_used(before, after, tracked_open=()):
    """before/after: {code: uses}. Gibt den benutzten Code zurueck (oder None).
    Einmal-Links verschwinden nach Benutzung, darum zaehlen auch verschwundene eigene Codes."""
    grown = [c for c, u in after.items() if u > before.get(c, 0)]
    if len(grown) == 1:
        return grown[0]
    if grown:
        return None
    gone = [c for c in tracked_open if c in before and c not in after]
    return gone[0] if len(gone) == 1 else None


def map_columns(cols):
    """Ordnet die echten Spalten der CraftCore-Tabelle `invites` unseren Werten zu."""
    cols = {c.lower(): c for c in cols}
    cand = {
        "inviter_uuid": ("inviter_uuid", "inviter", "referrer_uuid"),
        "invitee_uuid": ("invitee_uuid", "invited_uuid", "invitee", "referred_uuid"),
        "inviter_discord": ("inviter_discord", "inviter_discord_id"),
        "invitee_discord": ("invitee_discord", "invited_discord", "invitee_discord_id", "invited_discord_id"),
        "invitee_name": ("invitee_name", "invited_name"),
        "inviter_name": ("inviter_name",),
    }
    out = {}
    for key, names in cand.items():
        for n in names:
            if n in cols:
                out[key] = cols[n]
                break
    return out if "inviter_uuid" in out and "invitee_uuid" in out else None


def _dash(u):
    u = (u or "").replace("-", "")
    return f"{u[:8]}-{u[8:12]}-{u[12:16]}-{u[16:20]}-{u[20:]}" if len(u) == 32 else u


def craftcore_insert(rows):
    """rows: [(inviter_uuid, invitee_uuid, inviter_discord, invitee_discord, inviter_name, invitee_name)].
    Gibt die Menge erfolgreich geschriebener invitee_uuids zurueck; leer, wenn abgeschaltet/Tabelle fehlt."""
    pw = os.getenv("CREEPY_INV_DB_PASSWORD", "").strip()
    if not pw or os.getenv("INVITES_CRAFTCORE", "1") == "0" or not rows:
        return set()
    import pymysql
    try:
        con = pymysql.connect(host=os.getenv("CRAFTCORE_DB_HOST", "craftcore-db"), user=os.getenv("CREEPY_INV_DB_USER", "creepy_inv"),
                              password=pw, database="craftcore", connect_timeout=5, read_timeout=5, write_timeout=5, autocommit=True)
    except Exception as exc:
        log.warning("CraftCore (creepy_inv) nicht erreichbar: %s", type(exc).__name__)
        return set()
    done = set()
    try:
        with con.cursor() as cur:
            try:
                cur.execute("SELECT * FROM invites LIMIT 0")
            except pymysql.err.ProgrammingError:
                log.info("Tabelle invites fehlt noch - CraftCore-Eintrag wird spaeter nachgeholt")
                return set()
            m = map_columns([d[0] for d in cur.description])
            if not m:
                log.warning("Spalten von invites unbekannt - bitte map_columns anpassen")
                return set()
            keys = ["inviter_uuid", "invitee_uuid", "inviter_discord", "invitee_discord", "inviter_name", "invitee_name"]
            for r in rows:
                vals = dict(zip(keys, r))
                vals["inviter_uuid"], vals["invitee_uuid"] = _dash(vals["inviter_uuid"]), _dash(vals["invitee_uuid"])
                cur.execute(f"SELECT 1 FROM invites WHERE `{m['invitee_uuid']}`=%s LIMIT 1", (vals["invitee_uuid"],))
                if not cur.fetchone():
                    use = [k for k in keys if k in m]
                    cur.execute(f"INSERT INTO invites ({', '.join('`%s`' % m[k] for k in use)}) VALUES ({', '.join(['%s'] * len(use))})", [vals[k] for k in use])
                done.add(r[1])
    except Exception as exc:
        log.warning("CraftCore-Invite-Eintrag fehlgeschlagen: %s", type(exc).__name__)
    finally:
        con.close()
    return done


def channel_by(guild, env, name):
    cid = os.getenv(env, "").strip()
    ch = guild.get_channel(int(cid)) if cid.isdigit() else None
    return ch or discord.utils.find(lambda c: c.name.lower() == name, guild.text_channels)


async def fetch_uses(guild):
    """{code: uses} aller Invites; None, wenn die Berechtigung fehlt."""
    try:
        if guild.me.guild_permissions.manage_guild:
            return {i.code: i.uses or 0 for i in await guild.invites()}
        ch = channel_by(guild, "INVITE_CHANNEL_ID", WELCOME_NAME)
        if ch and ch.permissions_for(guild.me).manage_channels:
            return {i.code: i.uses or 0 for i in await ch.invites()}
    except discord.HTTPException as exc:
        log.warning("Invites nicht lesbar: %s", exc)
    return None


def perm_report(guild):
    p = guild.me.guild_permissions
    ch = channel_by(guild, "INVITE_CHANNEL_ID", WELCOME_NAME)
    cp = ch.permissions_for(guild.me) if ch else None
    return {"welcome": bool(ch), "create_invite": bool(cp and cp.create_instant_invite), "manage_guild": p.manage_guild,
            "manage_channels_welcome": bool(cp and cp.manage_channels)}


async def snapshot(bot):
    guild = bot.get_guild(int(bot.cfg["GUILD_ID"]))
    if guild:
        uses = await fetch_uses(guild)
        if uses is not None:
            bot._inv_uses = uses


async def on_ready(bot):
    guild = bot.get_guild(int(bot.cfg["GUILD_ID"]))
    if not guild:
        return
    rep = perm_report(guild)
    log.info("Invite-Rechte: %s", rep)
    await snapshot(bot)


async def on_join(bot, member):
    guild = member.guild
    before = getattr(bot, "_inv_uses", None)
    after = await fetch_uses(guild)
    code = None
    if before is not None and after is not None:
        tracked = [r[0] for r in bot.db.execute("SELECT code FROM dc_invites WHERE used_by IS NULL").fetchall()]
        code = find_used(before, after, tracked)
    if after is not None:
        bot._inv_uses = after
    row = bot.db.execute("SELECT inviter_id FROM dc_invites WHERE code=?", (code,)).fetchone() if code else None
    now = int(time.time())
    if row:
        bot.db.execute("UPDATE dc_invites SET used_by=?, used_at=? WHERE code=?", (member.id, now, code))
        bot.db.execute("INSERT OR IGNORE INTO dc_invite_joins (member_id, inviter_id, code, joined) VALUES (?,?,?,?)", (member.id, row[0], code, now))
        bot.db.commit()
    ch = channel_by(guild, "ADMIN_LOG_CHANNEL_ID", ADMIN_LOG_NAME)
    if ch and (row or code):
        e = discord.Embed(title="\u2709\ufe0f Beitritt \u00fcber Einladung", color=0x5865F2, timestamp=discord.utils.utcnow())
        e.add_field(name="Neu", value=f"{member.mention} (`{member}`)")
        e.add_field(name="Eingeladen von", value=f"<@{row[0]}>" if row else "fremder Link")
        e.add_field(name="Code", value=f"`{code}`")
        try:
            await ch.send(embed=e)
        except discord.HTTPException:
            log.warning("admin-log nicht beschreibbar")


async def sync_loop(bot):
    """Holt verknuepfte Eingeladene nach und schreibt sie in CraftCore (Retry alle 5 Minuten)."""
    await bot.wait_until_ready()
    try:
        await on_ready(bot)
    except Exception:
        log.exception("Invite-Snapshot fehlgeschlagen")
    while not bot.is_closed():
        try:
            rows = bot.db.execute(
                "SELECT j.member_id, j.inviter_id, a.uuid, a.name, b.uuid, b.name FROM dc_invite_joins j "
                "JOIN links b ON b.discord_id=j.member_id AND b.state='active' "
                "JOIN links a ON a.discord_id=j.inviter_id AND a.state='active' WHERE j.synced_at IS NULL").fetchall()
            if rows:
                payload = [(r[2], r[4], r[1], r[0], r[3], r[5]) for r in rows]
                done = await asyncio.to_thread(craftcore_insert, payload)
                for r in rows:
                    if r[4] in done:
                        bot.db.execute("UPDATE dc_invite_joins SET synced_at=? WHERE member_id=?", (int(time.time()), r[0]))
                bot.db.commit()
                if done:
                    log.info("%d Einladung(en) an CraftCore gemeldet", len(done))
        except Exception:
            log.exception("Invite-Sync fehlgeschlagen")
        await asyncio.sleep(SYNC_INTERVAL)


def allowed(bot, member):
    return bot.role(member, "ADMIN_ROLE_ID") or bot.role(member, "PLAYER_ROLE_ID")


def invite_embed(url, expires, open_count):
    e = discord.Embed(title="\u2709\ufe0f Deine Einladung", color=0x57F287,
                      description=f"Schick diesen Link an **eine** Person. Er gilt einmal und l\u00e4uft <t:{expires}:R> ab.\n```{url}```")
    e.set_footer(text=f"Offene Links: {open_count}/{MAX_OPEN_INVITES} \u00b7 Wer \u00fcber deinen Link kommt und sich verkn\u00fcpft, z\u00e4hlt als deine Einladung")
    return e


def list_embed(db, user_id):
    rows = db.execute("SELECT code, expires, used_by, used_at, created FROM dc_invites WHERE inviter_id=? ORDER BY created DESC LIMIT 15", (user_id,)).fetchall()
    now = time.time()
    lines = []
    for code, exp, used_by, used_at, _ in rows:
        if used_by:
            linked = db.execute("SELECT name FROM links WHERE discord_id=? AND state='active'", (used_by,)).fetchone()
            extra = f" \u00b7 MC: **{discord.utils.escape_markdown(linked[0])}**" if linked else " \u00b7 noch nicht verkn\u00fcpft"
            lines.append(f"\u2705 `{code}` eingel\u00f6st von <@{used_by}> <t:{used_at}:R>{extra}")
        elif exp > now:
            lines.append(f"\U0001F7E2 `{code}` offen, l\u00e4uft <t:{exp}:R> ab")
        else:
            lines.append(f"\u26AA `{code}` abgelaufen")
    e = discord.Embed(title="\U0001F4CB Meine Einladungen", color=0x5865F2, description="\n".join(lines) or "Du hast noch niemanden eingeladen.")
    joined = db.execute("SELECT COUNT(*) FROM dc_invite_joins WHERE inviter_id=?", (user_id,)).fetchone()[0]
    e.set_footer(text=f"Eingeladen insgesamt: {joined}")
    return e


class InviteView(discord.ui.View):
    def __init__(self, bot, url=None):
        super().__init__(timeout=600)
        self.bot = bot
        if url:
            self.add_item(discord.ui.Button(label="Link \u00f6ffnen", url=url, emoji="\U0001F517", row=0))

    @discord.ui.button(label="Neuer Link", emoji="\u2795", style=discord.ButtonStyle.success, row=1)
    async def new(self, interaction, _):
        await create(self.bot, interaction)

    @discord.ui.button(label="Meine Einladungen", emoji="\U0001F4CB", style=discord.ButtonStyle.secondary, row=1)
    async def mine(self, interaction, _):
        await interaction.response.send_message(embed=list_embed(self.bot.db, interaction.user.id), view=InviteView(self.bot), ephemeral=True)


async def create(bot, interaction):
    guild = interaction.guild
    if guild is None:
        return await interaction.response.send_message("Das geht nur auf dem Discord-Server.", ephemeral=True)
    if not allowed(bot, interaction.user):
        return await interaction.response.send_message("Einladen k\u00f6nnen nur Spieler mit der **Minecraft**-Rolle. Verkn\u00fcpf zuerst dein Konto mit `/link`.", ephemeral=True)
    opened = open_invites(bot.db, interaction.user.id)
    if len(opened) >= MAX_OPEN_INVITES and not bot.role(interaction.user, "ADMIN_ROLE_ID"):
        e = list_embed(bot.db, interaction.user.id)
        e.description = f"Du hast schon {len(opened)}/{MAX_OPEN_INVITES} offene Links. Warte, bis einer eingel\u00f6st wird oder abl\u00e4uft.\n\n" + e.description
        return await interaction.response.send_message(embed=e, view=InviteView(bot), ephemeral=True)
    ch = channel_by(guild, "INVITE_CHANNEL_ID", WELCOME_NAME)
    if not ch or not ch.permissions_for(guild.me).create_instant_invite:
        return await interaction.response.send_message("Ich kann gerade keinen Link erstellen (Kanal #welcome oder Recht 'Einladung erstellen' fehlt). Admins sind informiert.", ephemeral=True)
    await interaction.response.defer(ephemeral=True, thinking=True)
    try:
        inv = await ch.create_invite(max_uses=1, max_age=INVITE_MAX_AGE, unique=True, reason=f"/invite von {interaction.user} ({interaction.user.id})")
    except discord.HTTPException:
        log.exception("Invite erstellen fehlgeschlagen")
        return await interaction.followup.send("Link konnte nicht erstellt werden. Probier es gleich nochmal.", ephemeral=True)
    exp = int(time.time()) + INVITE_MAX_AGE
    bot.db.execute("INSERT OR REPLACE INTO dc_invites (code, inviter_id, created, expires) VALUES (?,?,?,?)", (inv.code, interaction.user.id, int(time.time()), exp))
    bot.db.commit()
    if hasattr(bot, "_inv_uses"):
        bot._inv_uses.setdefault(inv.code, 0)
    await interaction.followup.send(embed=invite_embed(inv.url, exp, len(opened) + 1), view=InviteView(bot, inv.url), ephemeral=True)


def register(bot, tree):
    init_db(bot.db)

    @tree.command(name="invite", description="Pers\u00f6nlichen Einladungslink f\u00fcr einen Freund erstellen")
    async def invite(interaction: discord.Interaction):
        await create(bot, interaction)

import os as _os, time as _time
_os.environ.setdefault("TZ", "Europe/Berlin")
_time.tzset()
import io
import socket
import asyncio
import json
import logging
import os
import re
import secrets
import shutil
import sqlite3
import tarfile
import time
import uuid as uuidlib
from pathlib import Path

import aiohttp
import discord
from discord import app_commands
from discord.ext import tasks
from mcstatus import JavaServer
from mcrcon import MCRcon

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("bot")
NAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")
DATA = Path("/app/data")
PENDING = Path("/link-pending")
LP_HOST = os.getenv("LP_HOST", "lobby")  # LuckPerms auf Lobby+Survival mit gemeinsamer MariaDB (luckperms); Sync schreibt ueber die Lobby
BACKENDS = (os.getenv("LOBBY_HOST", "lobby"), os.getenv("SURVIVAL_HOST", "survival"))
SERVER_HOSTS = {"lobby": BACKENDS[0], "survival": BACKENDS[1]}
SYNC_SERVERS = tuple(x.strip().lower() for x in os.getenv("SYNC_SERVERS", "survival").split(",") if x.strip().lower() in SERVER_HOSTS)  # Chat-/Presence-Sync
SETUP = ("**Minecraft Java einrichten**\n1. Installiere den offiziellen Minecraft Launcher und melde dich mit deinem Microsoft-Konto an. Du brauchst Minecraft: Java Edition.\n"
         "2. Starte die zum Server passende Minecraft-Version einmal. Für ein Modpack installiere einen passenden Launcher und importiere das bereitgestellte Fabric-Modpack.\n"
         "3. Alternativ installiere Fabric Loader für dieselbe Minecraft-Version und lege die passende Fabric-Version von Simple Voice Chat in den `mods`-Ordner. Starte das Fabric-Profil.\n"
         "4. Schick mir hier per DM deinen Minecraft-Namen (oder nutze `/link`). Verbinde dich anschließend mit `mc.nwebnotfound.dev` und schick mir den angezeigten Code hier per DM.\n"
         "5. Verbinde dich erneut. Mit `/server survival` wechselst du in die Spielwelt. Voice Chat benötigt zusätzlich einen öffentlich erreichbaren UDP-Endpunkt; frage die Administration, falls Sprache nicht funktioniert.")


def config():
    keys = ("DISCORD_TOKEN", "GUILD_ID", "PLAYER_ROLE_ID", "ADMIN_ROLE_ID", "CHAT_CHANNEL_ID", "STATUS_CHANNEL_ID", "RCON_PASSWORD")
    missing = [key for key in keys if not os.getenv(key, "").strip()]
    if missing:
        raise ValueError("Fehlende Konfiguration: " + ", ".join(missing))
    return {key: os.environ[key].strip() for key in keys}


socket.setdefaulttimeout(10)  # mcrcon timeout uses SIGALRM, fails outside main thread


def rcon(host, command):
    # Thread-safe RCON client (mcrcon uses SIGALRM, which fails in worker threads)
    import struct
    def packet(sock, rid, ptype, body):
        data = struct.pack("<ii", rid, ptype) + body.encode("utf-8") + b"\x00\x00"
        sock.sendall(struct.pack("<i", len(data)) + data)
    def recv_exact(sock, n):
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON-Verbindung geschlossen")
            buf += chunk
        return buf
    def read(sock):
        (length,) = struct.unpack("<i", recv_exact(sock, 4))
        data = recv_exact(sock, length)
        rid, _ = struct.unpack("<ii", data[:8])
        return rid, data[8:-2].decode("utf-8", "replace")
    with socket.create_connection((host, int(os.getenv("RCON_PORT", "25575"))), timeout=10) as sock:
        packet(sock, 1, 3, os.environ["RCON_PASSWORD"])
        rid, _ = read(sock)
        if rid == -1:
            raise PermissionError("RCON-Login fehlgeschlagen")
        packet(sock, 2, 2, command)
        return read(sock)[1]


def whitelist(action, uuid):
    if action not in ("add", "remove"):
        raise ValueError("Ungültige Aktion")
    uuid = str(uuidlib.UUID(uuid))
    for host in BACKENDS:
        result = rcon(host, f"linkwhitelist {action} {uuid}")
        if "LINK_OK" not in result:
            # RCON-Sender ist kein ConsoleCommandSender -> Plugin lehnt ab; Vanilla-Whitelist per Name als Fallback
            row = sqlite3.connect(DATA / "links.sqlite3").execute("SELECT name FROM links WHERE replace(uuid,'-','')=?", (uuid.replace("-", ""),)).fetchone()
            if row and re.fullmatch(r"[A-Za-z0-9_]{1,16}", row[0]):
                result = rcon(host, f"whitelist {action} {row[0]}")
                log.info("Whitelist-Fallback %s %s auf %s: %s", action, row[0], host, result)
                low = result.lower()
                if any(k in low for k in ("added", "removed", "already", "not whitelisted", "hinzugef", "entfernt")):
                    continue
            raise RuntimeError(f"Whitelist-Befehl auf {host} fehlgeschlagen: {result}")


def backup():
    output = DATA / "backups"
    output.mkdir(parents=True, exist_ok=True)
    target = output / (time.strftime("mc-%Y%m%d-%H%M%S") + ".tar.gz")
    paused = []
    restore_failed = False
    try:
        for host in BACKENDS:
            rcon(host, "save-off")
            paused.append(host)
            rcon(host, "save-all flush")
        with tarfile.open(target, "x:gz") as archive:
            for name in ("lobby", "survival"):
                archive.add(f"/minecraft/{name}", arcname=name, recursive=True)
    except Exception:
        target.unlink(missing_ok=True)
        raise
    finally:
        for host in paused:
            try:
                rcon(host, "save-on")
            except Exception:
                restore_failed = True
                log.exception("Speichern auf %s konnte nicht reaktiviert werden", host)
    if restore_failed:
        raise RuntimeError("Speichern konnte nicht wieder eingeschaltet werden")
    return target.name


class RoleView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=None)
        self.add_item(discord.ui.Button(label="Minecraft beitreten", emoji="\u26CF\uFE0F", style=discord.ButtonStyle.success, custom_id="role:minecraft"))


class StatsView(discord.ui.View):
    def __init__(self, uuid, name, page):
        super().__init__(timeout=None)
        import stats_card
        for key, label in stats_card.PAGES.items():
            self.add_item(discord.ui.Button(label=label, style=discord.ButtonStyle.primary if key == page else discord.ButtonStyle.secondary, custom_id=f"stats:{key}:{uuid}:{name}"[:100], disabled=key == page))


class Bot(discord.Client):
    def __init__(self, cfg):
        intents = discord.Intents.default()
        intents.members = True
        intents.message_content = True
        intents.voice_states = True
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.cfg = cfg
        self.tree = app_commands.CommandTree(self)
        self.lock = asyncio.Lock()
        DATA.mkdir(parents=True, exist_ok=True)
        PENDING.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(DATA / "links.sqlite3")
        self.db.execute("CREATE TABLE IF NOT EXISTS links (discord_id INTEGER PRIMARY KEY, uuid TEXT UNIQUE NOT NULL, name TEXT NOT NULL, code TEXT, expires INTEGER, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)")
        self.db.commit()
        self.offsets = {}
        self.dm_state = {}
        self.link_msg = {}
        self.last_count_rename = 0.0

    async def _post_changelog(self):
        await self.wait_until_ready()
        try:
            import changelog
            await changelog.post_new(self)
        except Exception:
            log.exception("Changelog-Post fehlgeschlagen")

    async def setup_hook(self):
        guild = discord.Object(id=int(self.cfg["GUILD_ID"]))
        dm_ok = {"link", "verify", "stats", "status", "setup", "leaderboard", "menu", "hilfe"}
        for cmd in self.tree.get_commands():
            cmd.guild_only = cmd.name not in dm_ok
        self.tree.clear_commands(guild=guild)
        await self.tree.sync(guild=guild)
        await self.tree.sync()
        self.add_view(LinkView(self))
        self.add_view(HelpView())
        self.maintenance.start()
        asyncio.create_task(self._post_changelog())
        import changelog as _cl
        asyncio.create_task(_cl.watch(self))
        import shops
        asyncio.create_task(shops.event_loop(self))
        import ah
        asyncio.create_task(ah.alarm_loop(self))
        import bid
        bid.init_db(self.db)
        asyncio.create_task(bid.notify_loop(self))
        import features
        self.add_view(features.EventView(self))
        import gui
        self.add_view(gui.HubView(self))
        import buy
        buy.init_db(self.db)
        self.add_dynamic_items(buy.BuyButton)
        asyncio.create_task(features.loop(self))
        import invites
        asyncio.create_task(invites.sync_loop(self))
        import ingest
        asyncio.create_task(ingest.loop(self))
        self.chat_tail.start()
        self.loop.create_task(self.cleanup_temp_voice())

    def role(self, member, key):
        return isinstance(member, discord.Member) and any(r.id == int(self.cfg[key]) for r in member.roles)

    def save(self, sql, params=()):
        self.db.execute(sql, params)
        self.db.commit()

    def remove_pending(self, uuid):
        (PENDING / uuid).unlink(missing_ok=True)

    async def reconcile(self):
        guild = self.get_guild(int(self.cfg["GUILD_ID"]))
        if guild is None:
            return
        for did, uuid, name, expires, state in self.db.execute("SELECT discord_id,uuid,name,expires,state FROM links").fetchall():
            try:
                try:
                    member = await guild.fetch_member(did)
                except discord.NotFound:
                    member = None
                if member is None and state == "pending":
                    self.remove_pending(uuid)
                    self.save("DELETE FROM links WHERE discord_id=?", (did,))
                    continue
                if state == "active" and not self.role(member, "PLAYER_ROLE_ID"):
                    self.save("UPDATE links SET state='removing' WHERE discord_id=?", (did,))
                    state = "removing"
                if state == "pending" and expires < time.time():
                    self.remove_pending(uuid)
                    self.save("DELETE FROM links WHERE discord_id=?", (did,))
                elif state == "adding":
                    await asyncio.to_thread(whitelist, "add", uuid)
                    self.save("UPDATE links SET state='active' WHERE discord_id=?", (did,))
                elif state == "removing":
                    await asyncio.to_thread(whitelist, "remove", uuid)
                    self.remove_pending(uuid)
                    self.save("DELETE FROM links WHERE discord_id=?", (did,))
            except Exception:
                log.exception("Verknüpfung %s wird später erneut verarbeitet", did)

    @tasks.loop(seconds=60)
    async def maintenance(self):
        async with self.lock:
            await self.reconcile()
        try:
            await self.update_status_card()
        except Exception:
            log.exception("Statuskanal konnte nicht aktualisiert werden")
        try:
            await self.update_leaderboard()
        except Exception:
            log.exception("Leaderboard fehlgeschlagen")
        try:
            hourly = time.time() - getattr(self, "lp_full", 0) > int(os.getenv("LP_FULL_SYNC_SEC", "600") or 600)
            if hourly:
                self.lp_full = time.time()
            await self.sync_lp(force=hourly)
        except Exception:
            log.exception("Rollen-Sync fehlgeschlagen")
        try:
            await self.ensure_roles_message()
        except Exception:
            log.exception("Rollen-Nachricht fehlgeschlagen")

    async def collect_status(self):
        servers, players, version = [], [], "?"
        for label, host in (("Lobby", BACKENDS[0]), ("Survival", BACKENDS[1])):
            try:
                out = await asyncio.to_thread(rcon, host, "list")
                names = [n.strip() for n in out.split(":", 1)[1].split(",") if n.strip()] if ":" in out else []
                servers.append((label, True, len(names)))
                players += names
            except Exception:
                servers.append((label, False, 0))
        try:
            st = await asyncio.to_thread(JavaServer.lookup(f"{BACKENDS[1]}:25565").status)
            version = re.sub(r"^\D+", "", st.version.name) or st.version.name
        except Exception:
            pass
        online = any(x[1] for x in servers)
        uptime = None
        try:
            path = Path("/minecraft/survival/logs/latest.log")
            first = path.open(encoding="utf-8", errors="replace").readline()
            hh, mm, ss = map(int, re.match(r"\[(\d+):(\d+):(\d+)\]", first).groups())
            now = time.localtime()
            start = time.mktime((now.tm_year, now.tm_mon, now.tm_mday, hh, mm, ss, 0, 0, -1))
            if start > time.time():
                start -= 86400
            secs = int(time.time() - start)
            d, r = divmod(secs, 86400)
            uptime = (f"{d}d " if d else "") + f"{r // 3600}h {r % 3600 // 60}m"
        except Exception:
            pass
        return online, servers, sorted(set(players), key=str.lower), version, uptime

    async def update_status_card(self, force=False):
        import stats_card
        channel = self.get_channel(int(self.cfg["STATUS_CHANNEL_ID"]))
        if not channel:
            return
        online, servers, players, version, uptime = await self.collect_status()
        mode = await asyncio.to_thread(self.server_mode)
        sig = repr((online, servers, players, version, mode))
        if not force and sig == getattr(self, "status_sig", None) and time.time() - getattr(self, "status_at", 0) < 600:
            return
        png = await asyncio.to_thread(stats_card.render_status, online, servers, players, version, uptime)
        e = discord.Embed(color=0x23A55A if online else 0xED4245, title=("\U0001F7E2 Server online" if online else "\U0001F534 Server offline"))
        if online and mode == "wartung":
            e.color, e.title = 0xE67E22, "\U0001F7E0 Wartung"
        elif online and mode == "dev":
            e.color, e.title = 0x5B7C99, "\U0001F535 Entwicklung"
        e.description = "**Adresse**\n```mc.nwebnotfound.dev```"
        e.add_field(name="Spieler", value=f"**{len(players)}/20**" + ("\n" + ", ".join(discord.utils.escape_markdown(p) for p in players[:20]) if players else "\nNiemand online"), inline=True)
        G, Rd = chr(0x1F7E2), chr(0x1F534)
        e.add_field(name="Server", value="\n".join(f"{G if on else Rd} {n} · {c if on else 'aus'}" for n, on, c in servers), inline=True)
        e.add_field(name="Version", value=f"{version}" + (f"\nUptime {uptime}" if uptime and online else ""), inline=True)
        e.add_field(name="\U0001F399\uFE0F Voice Chat", value="Simple Voice Chat ist installiert – Mod/Plugin im Client für Proximity-Chat.", inline=False)
        e.add_field(name="\u200b", value=f"-# Zuletzt aktualisiert <t:{int(time.time())}:R>", inline=False)
        e.set_image(url="attachment://status.png")
        file = discord.File(io.BytesIO(png), filename="status.png")
        marker = DATA / "status-card.txt"
        message = None
        if marker.exists():
            try:
                message = await channel.fetch_message(int(marker.read_text()))
            except discord.NotFound:
                pass
        if message:
            await message.edit(content=None, embed=e, attachments=[file])
        else:
            old = DATA / "status-message.txt"
            if old.exists():
                try:
                    await (await channel.fetch_message(int(old.read_text()))).delete()
                except discord.HTTPException:
                    pass
                old.rename(DATA / "status-message.txt.old")
            message = await channel.send(embed=e, file=file)
            marker.write_text(str(message.id))
        self.status_sig, self.status_at = sig, time.time()

    async def dm_prompt(self, user):
        e = link_embed(1)
        e.title = "\U0001F44B Willkommen bei HangoutNotFound Minecraft! \u00b7 Schritt 1/3"
        try:
            self.link_msg[user.id] = await user.send(embed=e, view=HelpView())
            self.dm_state[user.id] = "name"
            return True
        except discord.HTTPException:
            return False

    async def start_link(self, user, mc_name):
        """Gibt (ok, Text) zurück."""
        mc_name = mc_name.strip()
        if not re.fullmatch(r"[A-Za-z0-9_]{3,16}", mc_name):
            return False, "Das sieht nicht nach einem Minecraft-Namen aus 🤔 (3–16 Zeichen, nur Buchstaben, Zahlen und `_`). Versuch's nochmal."
        try:
            uuid, name = await mojang(mc_name)
        except ValueError as e:
            return False, f"Hmm, den Namen **{discord.utils.escape_markdown(mc_name)}** finde ich bei Mojang nicht. Tippfehler? Schick ihn nochmal."
        except Exception:
            log.exception("Mojang-Abfrage fehlgeschlagen")
            return False, "Mojang antwortet gerade nicht 😕 Versuch es in ein paar Minuten nochmal."
        async with self.lock:
            row = self.db.execute("SELECT discord_id,state FROM links WHERE discord_id=? OR uuid=?", (user.id, uuid)).fetchall()
            for did, state in row:
                if did == user.id and state == "pending":
                    self.remove_pending(self.db.execute("SELECT uuid FROM links WHERE discord_id=?", (did,)).fetchone()[0])
                    self.save("DELETE FROM links WHERE discord_id=?", (did,))
                elif did == user.id:
                    return False, "Dein Discord-Account ist schon mit einem Minecraft-Account verknüpft ✅ Falls du wechseln willst, frag kurz einen Admin."
                else:
                    return False, f"**{discord.utils.escape_markdown(name)}** ist schon mit einem anderen Discord-Account verknüpft. Wenn das nicht stimmt, melde dich bei einem Admin."
            code, expires = f"{secrets.randbelow(100000000):08d}", int(time.time()) + 600
            self.save("INSERT INTO links (discord_id,uuid,name,code,expires,state) VALUES (?,?,?,?,?,'pending')", (user.id, uuid, name, code, expires))
            temp = PENDING / (uuid + ".tmp")
            temp.write_text(f"{code}|{expires}", encoding="utf-8")
            temp.replace(PENDING / uuid)
        self.dm_state[user.id] = "code"
        return True, (f"Top, **{discord.utils.escape_markdown(name)}** gefunden! \U0001F389\n\n"
                      "**Letzter Schritt:** Verbinde dich in Minecraft mit `mc.nwebnotfound.dev`. Du wirst kurz mit einem **8-stelligen Code** rausgeworfen – "
                      "schick mir diesen Code einfach hier rein.\n-# Der Code ist 10 Minuten gültig.")

    async def show_progress(self, channel, user_id, embed, view=None):
        """Aktualisiert das Fortschritts-Embed an Ort und Stelle; sonst neu senden."""
        msg = self.link_msg.get(user_id)
        if msg is not None:
            try:
                await msg.edit(embed=embed, view=view)
                return msg
            except discord.HTTPException:
                pass
        msg = await channel.send(embed=embed, view=view)
        self.link_msg[user_id] = msg
        return msg

    async def verify_code(self, user, code):
        code = norm_code(code)
        async with self.lock:
            row = self.db.execute("SELECT uuid,code,expires,attempts,name FROM links WHERE discord_id=? AND state='pending'", (user.id,)).fetchone()
            if not row or row[2] < time.time() or row[3] >= 5:
                self.dm_state[user.id] = "name"
                return False, "Ich hab keinen offenen Code für dich (abgelaufen oder zu viele Versuche). Schick mir einfach nochmal deinen Minecraft-Namen."
            if not re.fullmatch(r"[0-9]{8}", code) or not secrets.compare_digest(row[1], code):
                self.save("UPDATE links SET attempts=attempts+1 WHERE discord_id=?", (user.id,))
                return False, f"Der Code passt leider nicht ❌ Noch {max(0, 4 - row[3])} Versuche."
            self.remove_pending(row[0])
            self.save("UPDATE links SET state='adding',code=NULL WHERE discord_id=?", (user.id,))
            guild = self.get_guild(int(self.cfg["GUILD_ID"]))
            try:
                member = await guild.fetch_member(user.id)
                prole = guild.get_role(int(self.cfg["PLAYER_ROLE_ID"]))
                if prole and prole not in member.roles:
                    await member.add_roles(prole, reason="Minecraft-Konto verknüpft")
            except discord.HTTPException:
                log.warning("Minecraft-Rolle nach Verify nicht vergeben")
            await self.reconcile()
            state = self.db.execute("SELECT state FROM links WHERE discord_id=?", (user.id,)).fetchone()
        self.dm_state.pop(user.id, None)
        return True, row[4] if state and state[0] == "active" else None

    def welcome_embed(self, name):
        e = discord.Embed(color=0x5865F2, title="\u2705 Du bist drin!", description=f"**{discord.utils.escape_markdown(name)}** ist jetzt verknüpft und auf der Whitelist.")
        e.add_field(name="Adresse", value="```mc.nwebnotfound.dev```", inline=False)
        e.add_field(name="So geht's", value="1. Minecraft **Java Edition** starten\n2. Mehrspieler → Server hinzufügen → `mc.nwebnotfound.dev`\n3. Du landest in der Lobby – mit `/server survival` geht's in die Welt", inline=False)
        e.add_field(name="\U0001F399\uFE0F Voice Chat", value="Wir nutzen **Simple Voice Chat** (Proximity-Chat). Installier die Mod für Fabric/Forge/NeoForge in deiner Version – dann hörst du Spieler in deiner Nähe. Taste `V` öffnet das Menü.", inline=False)
        e.set_thumbnail(url=f"https://mc-heads.net/avatar/{name}/64")
        e.set_footer(text="Viel Spaß! · /stats zeigt deine Statistiken")
        return e

    async def handle_dm(self, message):
        text = message.content.strip()
        if not text:
            return
        state = self.dm_state.get(message.author.id)
        if state is None:
            pending = self.db.execute("SELECT 1 FROM links WHERE discord_id=? AND state='pending'", (message.author.id,)).fetchone()
            state = "code" if pending else "name"
        uid = message.author.id
        async with message.channel.typing():
            if state == "code" and re.fullmatch(r"[0-9]{8}", norm_code(text)):
                ok, res = await self.verify_code(message.author, text)
                if ok:
                    name = res or self.db.execute("SELECT name FROM links WHERE discord_id=?", (uid,)).fetchone()[0]
                    await self.show_progress(message.channel, uid, link_embed(3, name, done=True))
                    self.link_msg.pop(uid, None)
                    await message.channel.send(embed=self.welcome_embed(name))
                    if not res:
                        await message.channel.send("-# Die Server sind gerade kurz nicht erreichbar - die Whitelist wird automatisch nachgetragen.")
                else:
                    pend = self.db.execute("SELECT name FROM links WHERE discord_id=? AND state='pending'", (uid,)).fetchone()
                    if pend and "Versuche" in res:
                        await self.show_progress(message.channel, uid, link_embed(3, pend[0], note="\u274C " + res), view=LinkView(self))
                        await message.channel.send("\u274C " + res + " (siehe oben)")
                    else:
                        await self.show_progress(message.channel, uid, link_embed(1, note=res), view=HelpView())
            else:
                linked = self.db.execute("SELECT name FROM links WHERE discord_id=? AND state='active'", (uid,)).fetchone()
                if linked and state != "code":
                    return await message.channel.send(f"Du bist schon als **{discord.utils.escape_markdown(linked[0])}** verkn\u00fcpft \u2705 Adresse: `{LINK_ADDR}`")
                ok, res = await self.start_link(message.author, text)
                if ok:
                    pend = self.db.execute("SELECT name FROM links WHERE discord_id=? AND state='pending'", (uid,)).fetchone()
                    await self.show_progress(message.channel, uid, link_embed(2, pend[0] if pend else text), view=LinkView(self))
                else:
                    await message.channel.send(res)

    # ---------- Join-to-create Voice ----------
    TEMP_FILE = DATA / "temp-voice.json"

    def temp_ids(self):
        try:
            return set(json.loads(self.TEMP_FILE.read_text()))
        except Exception:
            return set()

    def save_temp_ids(self, ids):
        self.TEMP_FILE.write_text(json.dumps(sorted(ids)))

    async def cleanup_temp_voice(self):
        await self.wait_until_ready()
        guild = self.get_guild(int(self.cfg["GUILD_ID"]))
        ids = self.temp_ids()
        for cid in list(ids):
            ch = guild.get_channel(cid) if guild else None
            if ch is None:
                ids.discard(cid)
            elif not ch.members:
                try:
                    await ch.delete(reason="Leerer Temp-Voice (Aufräumen)")
                    ids.discard(cid)
                except discord.HTTPException:
                    log.exception("Temp-Voice %s nicht löschbar", cid)
        self.save_temp_ids(ids)

    async def on_voice_state_update(self, member, before, after):
        if member.guild.id != int(self.cfg["GUILD_ID"]) or member.bot:
            return
        ids = self.temp_ids()
        if after.channel and after.channel.name.lower().endswith("join to create"):
            try:
                ow = {member: discord.PermissionOverwrite(manage_channels=True, move_members=True, connect=True)}
                try:
                    ch = await member.guild.create_voice_channel(f"\U0001F50A {member.display_name}"[:100], category=after.channel.category, overwrites={**after.channel.category.overwrites, **ow} if after.channel.category else ow, reason="Join to create")
                except discord.Forbidden:
                    ow = {member: discord.PermissionOverwrite(manage_channels=True, connect=True)}
                    ch = await member.guild.create_voice_channel(f"\U0001F50A {member.display_name}"[:100], category=after.channel.category, overwrites={**after.channel.category.overwrites, **ow} if after.channel.category else ow, reason="Join to create")
                    log.warning("Ersteller bekommt kein 'Mitglieder verschieben' (Bot fehlt das Recht)")
                ids.add(ch.id)
                self.save_temp_ids(ids)
                self.voice_owner = getattr(self, "voice_owner", {})
                self.voice_owner[ch.id] = member.id
                await member.move_to(ch, reason="Join to create")
            except discord.HTTPException:
                log.exception("Join-to-create fehlgeschlagen (Rechte: Kanäle verwalten + Mitglieder verschieben in VOICE)")
        if before.channel and before.channel.id in ids and not before.channel.members:
            try:
                await before.channel.delete(reason="Temp-Voice leer")
            except discord.NotFound:
                pass
            except discord.HTTPException:
                log.exception("Temp-Voice nicht löschbar")
                return
            ids.discard(before.channel.id)
            self.save_temp_ids(ids)

    def server_mode(self):
        """Modus aus v_server_status (dev|online|wartung); None wenn DB nicht erreichbar."""
        import pymysql
        import shops
        pw = shops.db_password()
        if not pw:
            return None
        try:
            con = pymysql.connect(host=os.getenv("CRAFTCORE_DB_HOST", "craftcore-db"), user=shops.db_user(), password=pw, database="craftcore", connect_timeout=3, read_timeout=3)
            try:
                with con.cursor() as cur:
                    cur.execute("SELECT mode FROM v_server_status LIMIT 1")
                    row = cur.fetchone()
            finally:
                con.close()
            m = str(row[0]).lower() if row else None
            return m if m in ("dev", "online", "wartung") else None
        except Exception:
            log.warning("Servermodus nicht lesbar - Statuskarte ohne Modus")
            return None

    # ---------- Parkour ----------
    def parkour_rows(self, course=None):
        import parkour
        return parkour.rank(self.parkour_raw(), course)

    def parkour_raw(self):
        import pymysql, parkour
        import shops
        pw = shops.db_password()
        if not pw:
            return []
        try:
            con = pymysql.connect(host=os.getenv("CRAFTCORE_DB_HOST", "craftcore-db"), user=shops.db_user(), password=pw, database="craftcore", connect_timeout=5, read_timeout=5)
        except Exception:
            log.warning("CraftCore-DB nicht erreichbar")
            return []
        try:
            with con.cursor() as cur:
                # Spalten zur Laufzeit erkennen: course, course_name, course_icon sind optional
                cur.execute("SELECT * FROM parkour_times WHERE best_ms > 0")
                cols = [d[0] for d in cur.description]
                rows = [dict(zip(cols, r)) for r in cur.fetchall()]
                raw = [(r.get("uuid"), r.get("name"), r.get("best_ms"), r.get("updated_at"),
                        r.get("course"), r.get("course_name"), r.get("course_icon")) for r in rows]
                try:  # Anzeigename/Icon von Craft (v_parkour_courses), optional
                    cur.execute("SELECT * FROM v_parkour_courses")
                    raw = parkour.apply_meta(raw, parkour.course_meta([d[0] for d in cur.description], cur.fetchall()))
                except (pymysql.err.OperationalError, pymysql.err.ProgrammingError):
                    pass  # View fehlt oder kein GRANT -> huebsch gemachter Slug
                return raw
        except pymysql.err.ProgrammingError:
            return []  # Tabelle existiert (noch) nicht
        finally:
            con.close()

    async def update_leaderboard(self):
        import parkour
        raw = await asyncio.to_thread(self.parkour_raw)
        for course in parkour.status_courses(os.getenv("PARKOUR_STATUS_COURSES")):
            try:
                await self._update_board(raw, course)
            except Exception:
                log.exception("Parkour-Board %s fehlgeschlagen", course)

    async def _update_board(self, raw, course):
        import stats_card, parkour
        rows = parkour.rank(raw, course)
        name = parkour.display(raw, course)
        top = [(r, n, ms) for r, n, ms, _ in rows[:10]]
        leaders = self.__dict__.setdefault("pk_leaders", {})
        leader = rows[0] if rows else None
        prev = leaders.get(course, "unset")
        if prev != "unset" and leader and (prev is None or prev[3] != leader[3] or prev[2] != leader[2]):
            chat = self.get_channel(int(self.cfg["CHAT_CHANNEL_ID"]))
            if chat and (prev is None or prev[3] != leader[3]):
                where = f" auf **{discord.utils.escape_markdown(name)}**" if name else ""
                try:
                    await chat.send(f"-# \U0001F3C1 Neuer Parkour-Rekord{where}: **{discord.utils.escape_markdown(leader[1])}** in **{stats_card.fmt_ms(leader[2])}**", allowed_mentions=discord.AllowedMentions.none())
                except discord.HTTPException:
                    log.exception("Rekord-Zeile fehlgeschlagen")
        leaders[course] = leader
        # Kein eigenes Bestenlisten-Board mehr in #server-status (nur /leaderboard parkour + Website).
        for stale in DATA.glob("leaderboard-message*.txt"):
            stale.unlink(missing_ok=True)

    # ---------- Rollen-Sync Discord -> LuckPerms (Discord fuehrt) ----------
    SYNC_GROUPS = {"Builder": "builder", "VIP": "vip", "MVP": "mvp"}  # Rollenname -> Gruppe (nur fuer ensure_sync_roles)

    async def ensure_sync_roles(self, guild):
        colors = {"Builder": 0x2ECC71, "VIP": 0xF1C40F, "MVP": 0xE91E63}
        ids = sync_role_ids()
        for rname, g in self.SYNC_GROUPS.items():
            if guild.get_role(ids.get(g, 0)) or self.find_role(guild, rname):
                continue
            try:
                await guild.create_role(name=rname, color=discord.Color(colors[rname]), reason="Rollen-Sync LuckPerms")
                log.info("Rolle %s angelegt", rname)
            except discord.HTTPException:
                log.exception("Rolle %s konnte nicht angelegt werden", rname)

    async def sync_lp(self, force=False):
        """Discord-Rollen (VIP/MVP/Builder) -> LuckPerms per `lp user <uuid> parent add/remove`.
        Nie parent set, nie /rank set, nie admin/default. force = voller Abgleich (Bot-Status wird ignoriert)."""
        guild = self.get_guild(int(self.cfg["GUILD_ID"]))
        if not guild:
            return
        await self.ensure_sync_roles(guild)
        state_file = DATA / "lp-sync.json"
        try:
            done = json.loads(state_file.read_text())
        except Exception:
            done = {}
        ids = sync_role_ids()
        links = {uuid: (did, name) for did, uuid, name in self.db.execute("SELECT discord_id, uuid, name FROM links WHERE state='active'").fetchall()}
        top = await asyncio.to_thread(player_top_ranks) if force else {}
        changed, lines = False, []
        for uuid in sorted(set(links) | set(done)):
            if uuid in links:
                did, name = links[uuid]
                member = guild.get_member(did)
                if member is None:
                    continue  # Austritt laeuft ueber on_member_remove -> reconcile -> Link weg -> naechster Lauf raeumt auf
                want = wanted_groups({r.id for r in member.roles}, ids)
            else:
                did, name, want = None, uuid, []  # entknuepft oder Server verlassen: nur Bot-gesetzte Gruppen entfernen
            prev = done.get(uuid)
            have = None if force and uuid in links else prev
            cmds = plan_lp(uuid, want, have, top.get(uuid.replace("-", "").lower()))
            if not cmds and prev == want:
                continue
            try:
                for cmd in cmds:
                    await asyncio.to_thread(rcon, LP_HOST, cmd)
                    await asyncio.sleep(0.3)
                if want:
                    done[uuid] = want
                else:
                    done.pop(uuid, None)
                changed = True
                ch = log_changes(cmds, prev)
                if ch:
                    who = f"<@{did}>" if did else "(nicht mehr verknüpft)"
                    lines.append(f"\U0001F3F7\uFE0F Rang-Sync: {who} **{discord.utils.escape_markdown(str(name))}** → " + ", ".join(f"`{c}`" for c in ch))
            except Exception:
                log.exception("LuckPerms-Sync für %s fehlgeschlagen", uuid)
        if changed or force:
            state_file.write_text(json.dumps(done))
        await self.rank_log(guild, lines)

    async def rank_log(self, guild, lines):
        if not lines:
            return
        try:
            import invites
            ch = invites.channel_by(guild, "ADMIN_LOG_CHANNEL_ID", invites.ADMIN_LOG_NAME)
            if ch:
                await ch.send("\n".join(lines)[:1900], allowed_mentions=discord.AllowedMentions.none())
        except Exception:
            log.warning("admin-log fuer Rang-Sync nicht beschreibbar")

    def find_role(self, guild, name):
        return discord.utils.find(lambda r: r.name.lower() == name.lower(), guild.roles)

    async def ensure_roles_message(self):
        guild = self.get_guild(int(self.cfg["GUILD_ID"]))
        channel = discord.utils.get(guild.text_channels, name="rollen") if guild else None
        if not channel:
            return
        marker = DATA / "roles-message.txt"
        if marker.exists():
            if getattr(self, "roles_checked", False):
                return
            try:
                await channel.fetch_message(int(marker.read_text()))
                self.roles_checked = True
                return
            except discord.NotFound:
                pass
        e = discord.Embed(color=0x57F287, title="\u26CF\uFE0F Minecraft", description=(
            "Lust auf unseren Survival-Server? Klick auf den Button, dann bekommst du die **Minecraft**-Rolle "
            "und siehst alle Minecraft-Kanäle.\n\nIch schreib dir danach eine DM – dort schickst du mir einfach deinen Minecraft-Namen "
            "und bist in einer Minute auf der Whitelist.\n\n-# Nochmal klicken nimmt die Rolle wieder weg."))
        msg = await channel.send(embed=e, view=RoleView())
        marker.write_text(str(msg.id))
        self.roles_checked = True

    async def stats_payload(self, uuid, name, page):
        import stats_card
        data, mtime = await asyncio.to_thread(stats_card.load_stats, str(uuidlib.UUID(uuid)))
        if data is None:
            return {"content": f"Für **{discord.utils.escape_markdown(name)}** gibt es noch keine Survival-Statistiken."}
        online = False
        for host in BACKENDS:
            try:
                if re.search(rf"\b{re.escape(name)}\b", (await asyncio.to_thread(rcon, host, "list")).split(":", 1)[-1]):
                    online = True
            except Exception:
                pass
        body = getattr(self, "body_cache", {}).get(uuid)
        if body is None:
            try:
                async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as session:
                    async with session.get(f"https://mc-heads.net/body/{uuid}/300", headers={"User-Agent": "Creepy-Bot"}) as r:
                        if r.status == 200:
                            body = await r.read()
                            self.body_cache = getattr(self, "body_cache", {})
                            self.body_cache[uuid] = body
            except Exception:
                pass
        view = StatsView(uuid, name, page)
        try:
            png = await asyncio.to_thread(stats_card.render, name, data, stats_card.status_line(mtime, online), online, body, page)
            return {"file": discord.File(io.BytesIO(png), filename=f"stats-{name}-{page}.png"), "view": view}
        except Exception:
            log.exception("Stats-Karte fehlgeschlagen, sende Embed")
            e = discord.Embed(title=f"Stats · {name}", color=0x5865F2, description=stats_card.status_line(mtime, online))
            e.set_thumbnail(url=f"https://mc-heads.net/avatar/{uuid}/64")
            for label, value in stats_card.summarize(data):
                e.add_field(name=label, value=stats_card.fmt(value))
            return {"embed": e, "view": view}

    async def on_interaction(self, interaction):
        cid = (interaction.data or {}).get("custom_id", "") if interaction.type == discord.InteractionType.component else ""
        if cid.startswith("stats:"):
            _, page, uuid, name = cid.split(":", 3)
            await interaction.response.defer()
            payload = await self.stats_payload(uuid, name, page)
            f = payload.pop("file", None)
            await interaction.edit_original_response(attachments=[f] if f else [], **payload)
        elif cid == "role:minecraft":
            role = interaction.guild.get_role(int(self.cfg["PLAYER_ROLE_ID"])) if interaction.guild else None
            if not role:
                return await interaction.response.send_message("Rolle nicht gefunden.", ephemeral=True)
            try:
                if role in interaction.user.roles:
                    await interaction.user.remove_roles(role, reason="Rollen-Button")
                    msg = "Minecraft-Rolle entfernt. \U0001F44B"
                else:
                    await interaction.user.add_roles(role, reason="Rollen-Button")
                    if await self.dm_prompt(interaction.user):
                        msg = "Du hast jetzt die **Minecraft**-Rolle! \U0001F4EC Schau in deine DMs – dort geht's weiter."
                    else:
                        msg = ("Du hast jetzt die **Minecraft**-Rolle! Ich konnte dir aber keine DM schicken. "
                               "Erlaube DMs von Servermitgliedern (Server-Name → Privatsphäre-Einstellungen) und nutze dann `/link <MC-Name>`.")
                await interaction.response.send_message(msg, ephemeral=True)
            except discord.Forbidden:
                log.warning("Rolle kann nicht vergeben werden (Manage Roles / Rollenhierarchie)")
                await interaction.response.send_message("Ich darf die Rolle gerade nicht vergeben – Admins sind informiert.", ephemeral=True)

    async def update_player_count(self, count):
        channel_id = os.getenv("PLAYER_COUNT_CHANNEL_ID", "").strip()
        if not channel_id or not channel_id.isdigit() or time.monotonic() - self.last_count_rename < 600:
            return
        channel = self.get_channel(int(channel_id))
        guild = self.get_guild(int(self.cfg["GUILD_ID"]))
        if not channel or channel.guild != guild:
            return
        desired = f"Minecraft online: {count}"
        if channel.name != desired:
            # Limit attempts too, including failed edits, to avoid repeated API calls.
            self.last_count_rename = time.monotonic()
            try:
                await channel.edit(name=desired, reason="Minecraft-Spielerzahl")
            except discord.HTTPException:
                log.exception("Spielerzahlkanal konnte nicht umbenannt werden")

    async def on_invite_create(self, invite):
        if invite.guild and invite.guild.id == int(self.cfg["GUILD_ID"]) and hasattr(self, "_inv_uses"):
            self._inv_uses[invite.code] = invite.uses or 0

    async def on_invite_delete(self, invite):
        pass  # Einmal-Links verschwinden nach Nutzung; on_join erkennt das ueber den alten Stand

    async def on_member_join(self, member):
        if member.guild.id != int(self.cfg["GUILD_ID"]) or member.bot:
            return
        try:
            import invites
            await invites.on_join(self, member)
        except Exception:
            log.exception("Invite-Tracking fehlgeschlagen")
        role = self.find_role(member.guild, "Member")
        if role:
            try:
                await member.add_roles(role, reason="Neues Mitglied")
            except discord.HTTPException:
                log.warning("Member-Rolle konnte nicht vergeben werden")
        try:
            await member.send(SETUP)
        except discord.HTTPException:
            log.warning("Willkommens-DM konnte nicht zugestellt werden")

    @maintenance.before_loop
    async def before_maintenance(self):
        await self.wait_until_ready()

    async def get_webhook(self, channel):
        if getattr(self, "webhook", None) is not None or time.monotonic() < getattr(self, "webhook_retry", 0):
            return getattr(self, "webhook", None)
        try:
            for hook in await channel.webhooks():
                if hook.name == "Creepy MC" and hook.token:
                    self.webhook = hook
                    return hook
            self.webhook = await channel.create_webhook(name="Creepy MC", reason="Minecraft-Chat-Sync")
            log.info("Webhook 'Creepy MC' angelegt")
        except discord.Forbidden:
            self.webhook_retry = time.monotonic() + 120
            log.warning("Webhook nicht möglich: Recht 'Webhooks verwalten' fehlt in #mc-chat, nutze normale Nachrichten")
            return None
        return self.webhook

    async def post_chat(self, channel, name, server, text):
        hook = await self.get_webhook(channel)
        text = text[:1900]
        if hook:
            try:
                await hook.send(text, username=name, avatar_url=f"https://mc-heads.net/avatar/{name}/64", allowed_mentions=discord.AllowedMentions.none())
                return
            except discord.NotFound:
                self.webhook = None
            except discord.HTTPException:
                log.exception("Webhook-Senden fehlgeschlagen")
        await channel.send(f"**{discord.utils.escape_markdown(name)}** ({server}): {discord.utils.escape_markdown(text)}", allowed_mentions=discord.AllowedMentions.none())

    def event_line(self, kind, name, detail=""):
        n = f"**{discord.utils.escape_markdown(name)}**"
        if kind == "join":
            return f"-# \U0001F7E2 {n} ist online"
        if kind == "leave":
            return f"-# \U0001F534 {n} ist offline"
        if kind == "death":
            rest = detail[len(name):].strip() if detail.startswith(name) else detail
            return f"-# \U0001F480 {n} {discord.utils.escape_markdown(rest)}"
        kind_word, adv = detail
        icon = {"challenge": "\U0001F48E", "goal": "\U0001F3AF"}.get(kind_word, "\U0001F3C6")
        return f"-# {icon} {n} hat **{discord.utils.escape_markdown(adv)}** erreicht"

    async def post_lines(self, channel, lines):
        if not lines:
            return
        try:
            await channel.send("\n".join(lines)[:1990], allowed_mentions=discord.AllowedMentions.none())
        except discord.HTTPException:
            log.exception("Event-Zeile fehlgeschlagen")

    LINE_RE = re.compile(r"^\[[^\]]*\] \[([^\]]*)/INFO\]: (?:System chat: )?(.*)$")
    CHAT_RE = re.compile(r"^(?:\[Not Secure\] )?<([A-Za-z0-9_]{3,16})> (.+)")
    JOIN_RE = re.compile(r"^([A-Za-z0-9_]{3,16}) joined the game$")
    LEAVE_RE = re.compile(r"^([A-Za-z0-9_]{3,16}) left the game$")
    ADV_RE = re.compile(r"^([A-Za-z0-9_]{3,16}) has (made the advancement|completed the challenge|reached the goal) \[(.+)\]$")
    DEATH_RE = re.compile(r"^([A-Za-z0-9_]{3,16}) (?:was |were |died|drowned|fell |blew up|burned|went up in flames|went off|hit the ground|experienced kinetic|froze|starved|suffocated|walked into|tried to swim|withered|discovered the floor|left the confines|didn't want to live|walked into fire|was squashed|was impaled|was skewered|was stung|was poked|was killed|was slain|was shot|was blown|was fireballed|was pummeled|was struck|was pricked)")

    def parse_line(self, line):
        m = self.LINE_RE.match(line.rstrip("\n"))
        if not m:
            return None
        thread, body = m.groups()
        c = self.CHAT_RE.match(body)
        if c:
            return ("chat", c[1], c[2])
        if thread != "Server thread":
            return None
        for kind, rx in (("join", self.JOIN_RE), ("leave", self.LEAVE_RE)):
            e = rx.match(body)
            if e:
                return (kind, e[1], "")
        e = self.ADV_RE.match(body)
        if e:
            return ("adv", e[1], (e[2].split()[-1], e[3]))
        d = self.DEATH_RE.match(body)
        if d and d[1] in getattr(self, "online", set()):
            return ("death", d[1], body)
        return None

    @tasks.loop(seconds=0.75)
    async def chat_tail(self):
        channel = self.get_channel(int(self.cfg["CHAT_CHANNEL_ID"]))
        if not channel:
            return
        if not hasattr(self, "pending_presence"):
            self.pending_presence = []
            self.online = set()
        lines = []
        for server in SYNC_SERVERS:
            path = Path(f"/minecraft/{server}/logs/latest.log")
            try:
                stat = path.stat()
                old = self.offsets.get(server)
                if old is None:
                    self.offsets[server] = (stat.st_ino, stat.st_size)
                    continue
                inode, offset = old
                if inode != stat.st_ino or stat.st_size < offset:
                    offset = 0
                with path.open(encoding="utf-8", errors="replace") as stream:
                    stream.seek(offset)
                    while line := stream.readline():
                        ev = self.parse_line(line)
                        if not ev:
                            continue
                        kind, name, detail = ev
                        if kind == "chat":
                            await self.post_chat(channel, name, server, detail)
                        elif kind in ("join", "leave"):
                            (self.online.add if kind == "join" else self.online.discard)(name)
                            self.pending_presence.append((time.monotonic(), kind, name, server))
                        else:
                            lines.append(self.event_line(kind, name, detail))
                    self.offsets[server] = (stat.st_ino, stream.tell())
            except (FileNotFoundError, PermissionError):
                continue
            except Exception:
                log.exception("Chat-Log konnte nicht gelesen werden")
        # Serverwechsel: leave+join desselben Spielers innerhalb 5s = kein Post
        now = time.monotonic()
        keep, used = [], set()
        for i, item in enumerate(self.pending_presence):
            if i in used:
                continue
            t, kind, name, server = item
            pair = next((j for j, x in enumerate(self.pending_presence) if j != i and j not in used and x[2] == name and x[1] != kind), None)
            if pair is not None:
                used.update((i, pair))
            elif now - t >= (5 if kind == "leave" else 0.5):  # Leave 5s puffern (Rejoin = kein Post)
                lines.append(self.event_line(kind, name))
            else:
                keep.append(item)
        self.pending_presence = keep
        await self.post_lines(channel, lines)

    @chat_tail.before_loop
    async def before_chat(self):
        await self.wait_until_ready()

    @staticmethod
    def build_tellraw(message):
        member = message.author
        color = getattr(member, "color", None)
        name_color = str(color) if color and color.value else "#FFFFFF"
        text = message.clean_content
        text = re.sub(r"<a?:(\w+):\d+>", r":\1:", text)
        text = re.sub(r"\s+", " ", text).strip()
        if len(text) > 256:
            text = text[:253] + "..."
        parts = [{"text": "\u2726 ", "color": "#5865F2", "hover_event": {"action": "show_text", "value": "Discord \u00b7 #mc-chat"}}]
        ref = getattr(message, "reference", None)
        resolved = getattr(ref, "resolved", None) if ref else None
        if resolved is not None and getattr(resolved, "author", None):
            preview = (getattr(resolved, "clean_content", "") or "")[:80]
            parts.append({"text": "\u21b3 ", "color": "gray", "hover_event": {"action": "show_text", "value": f"Antwort an @{resolved.author.display_name}: {preview}"}})
        parts += [{"text": "<", "color": "white"}, {"text": member.display_name[:32], "color": name_color}, {"text": "> ", "color": "white"}]
        if text:
            parts.append({"text": text, "color": "white", "bold": False})
        for a in message.attachments[:3]:
            label = "[Bild]" if (a.content_type or "").startswith("image/") else "[Datei]"
            parts.append({"text": " " + label, "color": "aqua", "underlined": True, "bold": False, "click_event": {"action": "open_url", "url": a.url}, "hover_event": {"action": "show_text", "value": a.filename}})
        return "tellraw @a " + json.dumps(["", *parts], ensure_ascii=True)

    async def on_message(self, message):
        if message.author.bot or message.webhook_id:
            return
        if message.guild is None:
            log.info("DM von %s (%d Zeichen)", message.author.id, len(message.content or ""))
            try:
                await self.handle_dm(message)
            except Exception:
                log.exception("DM-Verarbeitung fehlgeschlagen")
                await message.channel.send("Da ist bei mir was schiefgelaufen 😵 Versuch's gleich nochmal.")
            return
        if message.type not in (discord.MessageType.default, discord.MessageType.reply) or getattr(message, "interaction_metadata", None) or message.content.lstrip().startswith(("/", "!", ".")):
            return
        if not message.guild or message.guild.id != int(self.cfg["GUILD_ID"]) or message.channel.id != int(self.cfg["CHAT_CHANNEL_ID"]):
            return
        if not message.clean_content.strip() and not message.attachments:
            return
        command = self.build_tellraw(message)
        for host in (SERVER_HOSTS[x] for x in SYNC_SERVERS):
            try:
                await asyncio.to_thread(rcon, host, command)
            except Exception:
                log.exception("Chat an %s fehlgeschlagen", host)

    async def on_member_remove(self, member):
        if member.guild.id == int(self.cfg["GUILD_ID"]):
            async with self.lock:
                self.save("UPDATE links SET state='removing' WHERE discord_id=? AND state!='pending'", (member.id,))
                await self.reconcile()

    async def on_member_update(self, before, after):
        if {r.id for r in before.roles} != {r.id for r in after.roles}:
            try:
                await self.sync_lp()
            except Exception:
                log.exception("Rollen-Sync fehlgeschlagen")
        await self._on_member_update_orig(before, after)

    async def _on_member_update_orig(self, before, after):
        if before.guild.id == int(self.cfg["GUILD_ID"]) and self.role(before, "PLAYER_ROLE_ID") and not self.role(after, "PLAYER_ROLE_ID"):
            async with self.lock:
                await self.reconcile()



# ---------- Rang-Sync: reine Helfer (testbar ohne Discord) ----------
SYNCABLE = ("vip", "mvp", "builder")  # admin/default fasst der Sync nie an


def sync_role_ids():
    """{gruppe: discord_role_id} aus ENV, Defaults = Rollen auf Nils' Server."""
    defaults = {"vip": ("VIP_ROLE_ID", 1565044626736292021), "mvp": ("MVP_ROLE_ID", 1566125881540476948),
                "builder": ("BUILDER_ROLE_ID", 1565044625511809127)}
    out = {}
    for g, (env, d) in defaults.items():
        v = os.getenv(env, "").strip()
        out[g] = int(v) if v.isdigit() else d
    return out


def wanted_groups(role_ids, ids):
    return sorted(g for g, rid in ids.items() if g in SYNCABLE and rid in role_ids)


def plan_lp(uuid, want, have, top=None):
    """RCON-Befehle fuer einen Spieler. have=None -> unbekannt (voller Abgleich): alle gewollten adden,
    alle nicht gewollten removen. Sonst nur Differenz. top = hoechster Rang aus v_player_rank (spart add, wenn schon da)."""
    out = []
    for g in SYNCABLE:
        if g in want and (have is None or g not in have):
            if have is None and top == g:
                continue
            out.append(f"lp user {uuid} parent add {g}")
        elif g not in want and (have is None or g in have):
            out.append(f"lp user {uuid} parent remove {g}")
    assert all(" parent add " in c or " parent remove " in c for c in out)
    return out


def log_changes(cmds, prev):
    """Nur echte Aenderungen gegenueber dem letzten Bot-Stand fuers admin-log (voller Abgleich spamt sonst)."""
    out = []
    for c in cmds:
        op, g = c.split(" parent ", 1)[1].split()
        if (op == "add" and (prev is None or g not in prev)) or (op == "remove" and prev and g in prev):
            out.append(f"{op} {g}")
    return out


def player_top_ranks():
    """{uuid ohne Bindestriche: rang} aus v_player_rank; {} wenn View fehlt."""
    try:
        import ingest
        res = ingest.fetch_rows("v_player_rank")
    except Exception:
        return {}
    if not res:
        return {}
    cols, rows = res
    if "uuid" not in cols or "rank" not in cols:
        return {}
    iu, ir = cols.index("uuid"), cols.index("rank")
    return {str(r[iu]).replace("-", "").lower(): str(r[ir]) for r in rows if r[iu]}

LINK_ADDR = "mc.nwebnotfound.dev"


def norm_code(text):
    """Toleriert Leerzeichen, Bindestriche, Punkte und Gross/Klein."""
    return re.sub(r"[\s\-_.]", "", text or "").upper()


def link_embed(step, name=None, note=None, done=False):
    bar = ["\u2B1C"] * 3
    for i in range(min(step, 3) if not done else 3):
        bar[i] = "\U0001F7E9"
    def mark(n):
        if done or n < step:
            return "\u2705"
        return "\U0001F449" if n == step else "\u25AB\uFE0F"
    e = discord.Embed(color=0x57F287 if done else 0x5865F2,
                      title="\u2705 Fertig!" if done else f"\U0001F517 Minecraft verkn\u00fcpfen \u00b7 Schritt {step}/3")
    if done:
        e.description = f"{''.join(bar)}\n\n**Fertig, du bist auf der Whitelist, join einfach nochmal** \U0001F389"
        return e
    n = f" (**{discord.utils.escape_markdown(name)}**)" if name else ""
    e.description = (
        f"{''.join(bar)}  {step}/3\n\n"
        f"{mark(1)} **1/3 \u00b7 Minecraft-Namen schicken**{n if step > 1 else ''}\n"
        + ("-# Schick mir deinen Java-Namen einfach als Nachricht hier rein.\n" if step == 1 else "")
        + f"{mark(2)} **2/3 \u00b7 Auf den Server joinen**\n"
        + (f"-# Minecraft \u2192 **Multiplayer > Server hinzuf\u00fcgen** \u2192 Adresse einf\u00fcgen:\n```{LINK_ADDR}```\n"
           "\u26A0\uFE0F **Du wirst absichtlich rausgeworfen, das ist normal** - auf dem Kick-Bildschirm steht dein Code.\n" if step >= 2 else "")
        + f"{mark(3)} **3/3 \u00b7 Code hier reinschicken**\n"
        + ("-# Einfach die 8 Ziffern abtippen, Leerzeichen sind egal. Code gilt 10 Minuten.\n" if step >= 2 else ""))
    if step == 1:
        e.add_field(name="Gut zu wissen", value=f"\u26A0\uFE0F Beim ersten Join wirst du **absichtlich rausgeworfen, das ist normal** - da kriegst du deinen Code.\nAdresse: `{LINK_ADDR}`", inline=False)
    if note:
        e.add_field(name="\u200b", value=note, inline=False)
    return e


HELP_TEXT = ("**So klappt das Verkn\u00fcpfen**\n"
    "1. Schick mir deinen **Minecraft-Java-Namen** (z. B. `NwebNotFound`).\n"
    "2. Join in Minecraft auf `mc.nwebnotfound.dev` (Multiplayer > Server hinzuf\u00fcgen). Du wirst **absichtlich rausgeworfen** - auf dem Bildschirm steht ein 8-stelliger Code.\n"
    "3. Schick mir den Code hier rein. Fertig!\n\n"
    "-# Nur Java Edition. Der Code gilt 10 Minuten, mit \u201eNeuen Code anfordern\u201c bekommst du einen frischen. Klappt gar nichts? Schreib einem Admin.")


class HelpView(discord.ui.View):
    """Schritt 1: nur Hilfe-Button (persistent)."""
    def __init__(self, bot=None):
        super().__init__(timeout=None)

    @discord.ui.button(label="Hilfe", emoji="\u2753", style=discord.ButtonStyle.secondary, custom_id="link:help")
    async def help(self, interaction: discord.Interaction, button):
        await interaction.response.send_message(HELP_TEXT, ephemeral=True)


class LinkView(discord.ui.View):
    """Persistente Buttons (custom_id) - funktionieren auch nach Bot-Neustart."""
    def __init__(self, bot):
        super().__init__(timeout=None)
        self.bot = bot

    @discord.ui.button(label="Neuen Code anfordern", emoji="\U0001F504", style=discord.ButtonStyle.primary, custom_id="link:newcode")
    async def newcode(self, interaction: discord.Interaction, button):
        row = self.bot.db.execute("SELECT name FROM links WHERE discord_id=? AND state='pending'", (interaction.user.id,)).fetchone()
        if not row:
            self.bot.dm_state[interaction.user.id] = "name"
            return await interaction.response.edit_message(embed=link_embed(1, note="Ich hab keinen offenen Link f\u00fcr dich - schick mir einfach deinen Minecraft-Namen."), view=HelpView())
        await interaction.response.defer()
        ok, res = await self.bot.start_link(interaction.user, row[0])
        if ok:
            await interaction.edit_original_response(embed=link_embed(2, row[0], note="\U0001F504 Neuer Code ist bereit - join nochmal, der alte gilt nicht mehr."), view=self)
        else:
            await interaction.edit_original_response(embed=link_embed(1, note=res), view=HelpView())

    @discord.ui.button(label="Hilfe", emoji="\u2753", style=discord.ButtonStyle.secondary, custom_id="link:help2")
    async def help(self, interaction: discord.Interaction, button):
        await interaction.response.send_message(HELP_TEXT, ephemeral=True)

    @discord.ui.button(label="Falscher Name", emoji="\u21A9\uFE0F", style=discord.ButtonStyle.secondary, custom_id="link:wrongname")
    async def wrongname(self, interaction: discord.Interaction, button):
        async with self.bot.lock:
            row = self.bot.db.execute("SELECT uuid FROM links WHERE discord_id=? AND state='pending'", (interaction.user.id,)).fetchone()
            if row:
                self.bot.remove_pending(row[0])
                self.bot.save("DELETE FROM links WHERE discord_id=? AND state='pending'", (interaction.user.id,))
        self.bot.dm_state[interaction.user.id] = "name"
        await interaction.response.edit_message(embed=link_embed(1, note="Kein Problem - schick mir einfach den richtigen Minecraft-Namen."), view=HelpView())


async def mojang(name):
    if not NAME.fullmatch(name):
        raise ValueError("Minecraft-Namen bestehen aus 3–16 Buchstaben, Ziffern oder Unterstrichen.")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as session:
        async with session.get(f"https://api.mojang.com/users/profiles/minecraft/{name}") as response:
            if response.status in (204, 404):
                raise ValueError("Minecraft-Konto nicht gefunden.")
            response.raise_for_status()
            profile = await response.json()
    if not re.fullmatch(r"[0-9a-f]{32}", profile.get("id", "")) or not NAME.fullmatch(profile.get("name", "")):
        raise ValueError("Ungültige Antwort von Mojang.")
    return profile["id"], profile["name"]


def register(bot):
    tree = bot.tree

    @tree.command(name="setup", description="Anleitung per Direktnachricht erhalten")
    async def setup(interaction: discord.Interaction):
        try:
            await interaction.user.send(SETUP)
            await interaction.response.send_message("Die Anleitung ist per DM unterwegs.", ephemeral=True)
        except discord.Forbidden:
            await interaction.response.send_message("DMs sind geschlossen. Bitte erlaube Direktnachrichten von Servermitgliedern und versuche es erneut.", ephemeral=True)

    @tree.command(name="link", description="Minecraft-Konto verknüpfen und auf die Whitelist kommen - Schritt für Schritt per DM")
    async def link(interaction: discord.Interaction, mc_name: str = None):
        await interaction.response.defer(ephemeral=interaction.guild is not None)
        if not mc_name:
            if interaction.guild is None:
                bot.dm_state[interaction.user.id] = "name"
                return await interaction.followup.send("Schick mir einfach deinen Minecraft-Namen als Nachricht hier rein 🙂")
            ok = await bot.dm_prompt(interaction.user)
            return await interaction.followup.send("📬 Schau in deine DMs!" if ok else "Ich kann dir keine DM schicken – erlaube DMs von Servermitgliedern oder nutze `/link mc_name:<Name>`.", ephemeral=True)
        ok, res = await bot.start_link(interaction.user, mc_name)
        pend = bot.db.execute("SELECT name FROM links WHERE discord_id=? AND state='pending'", (interaction.user.id,)).fetchone() if ok else None
        if ok and interaction.guild is None:
            return await interaction.followup.send(embed=link_embed(2, pend[0] if pend else mc_name), view=LinkView(bot))
        if ok and interaction.guild is not None:
            try:
                bot.link_msg[interaction.user.id] = await interaction.user.send(embed=link_embed(2, pend[0] if pend else mc_name), view=LinkView(bot))
                res = "📬 Ich hab dir die nächsten Schritte per DM geschickt!"
            except discord.HTTPException:
                res += "\n\nDen Code kannst du mit `/verify` eingeben."
        await interaction.followup.send(res)

    @tree.command(name="verify", description="Den Code vom Kick-Bildschirm eingeben (geht auch einfach per DM)")
    async def verify(interaction: discord.Interaction, code: str):
        await interaction.response.defer(ephemeral=interaction.guild is not None)
        ok, res = await bot.verify_code(interaction.user, code)
        if ok:
            name = res or bot.db.execute("SELECT name FROM links WHERE discord_id=?", (interaction.user.id,)).fetchone()[0]
            await interaction.followup.send(embed=bot.welcome_embed(name))
        else:
            await interaction.followup.send(res)

    voice = app_commands.Group(name="voice", description="Deinen Temp-Voice-Kanal verwalten", guild_only=True)

    async def own_channel(interaction):
        ch = interaction.user.voice.channel if isinstance(interaction.user, discord.Member) and interaction.user.voice else None
        if not ch or ch.id not in bot.temp_ids():
            await interaction.response.send_message("Du musst in deinem eigenen Temp-Voice sein (➕ Join to create).", ephemeral=True)
            return None
        owner = getattr(bot, "voice_owner", {}).get(ch.id)
        if owner != interaction.user.id and not ch.permissions_for(interaction.user).manage_channels:
            await interaction.response.send_message("Das ist nicht dein Kanal.", ephemeral=True)
            return None
        return ch

    @voice.command(name="name", description="Deinen Kanal umbenennen")
    async def v_name(interaction: discord.Interaction, name: app_commands.Range[str, 1, 90] = None):
        if not name:
            import gui
            return await interaction.response.send_modal(gui.FormModal("Kanal umbenennen", [("n", "Neuer Name", "z.B. Bauprojekt", True)], lambda i, d: v_name.callback(i, name=d["n"][:90])))
        if ch := await own_channel(interaction):
            await ch.edit(name=f"\U0001F50A {name}")
            await interaction.response.send_message("Umbenannt ✅", ephemeral=True)

    @voice.command(name="limit", description="Wie viele in deinen Kanal d\u00fcrfen - zum Ausw\u00e4hlen")
    async def v_limit(interaction: discord.Interaction, anzahl: app_commands.Range[int, 0, 99] = None):
        if anzahl is None:
            import gui
            opts = [("Kein Limit", 0)] + [(f"{n} Personen", n) for n in (2, 3, 4, 5, 6, 8, 10, 15, 20)]
            return await interaction.response.send_message("Wie viele d\u00fcrfen in deinen Kanal?", view=gui.ChoiceView("Limit ausw\u00e4hlen", opts, lambda i, v: v_limit.callback(i, anzahl=int(v)), interaction.user.id), ephemeral=True)
        if ch := await own_channel(interaction):
            await ch.edit(user_limit=anzahl)
            await interaction.response.send_message(f"Limit: {anzahl or 'keins'} ✅", ephemeral=True)

    @voice.command(name="lock", description="Kanal für neue Leute sperren")
    async def v_lock(interaction: discord.Interaction):
        if ch := await own_channel(interaction):
            await ch.set_permissions(interaction.guild.default_role, connect=False)
            await interaction.response.send_message("\U0001F512 Gesperrt", ephemeral=True)

    @voice.command(name="unlock", description="Kanal wieder öffnen")
    async def v_unlock(interaction: discord.Interaction):
        if ch := await own_channel(interaction):
            await ch.set_permissions(interaction.guild.default_role, connect=None)
            await interaction.response.send_message("\U0001F513 Offen", ephemeral=True)

    tree.add_command(voice)

    lb = app_commands.Group(name="leaderboard", description="Bestenlisten")

    @lb.command(name="parkour", description="Parkour-Bestenliste zum Durchbl\u00e4ttern, mit deinem Platz")
    async def _pk_courses(interaction: discord.Interaction, current: str):
        import parkour
        raw = await asyncio.to_thread(bot.parkour_raw)
        cur = (current or "").strip().lower()
        return [app_commands.Choice(name=n[:100], value=c[:100]) for c, n in parkour.courses(raw) if cur in n.lower() or parkour.slug(cur) in c][:25]

    @app_commands.describe(kurs="Nur diesen Kurs zeigen (leer = beste Zeit je Spieler über alle Kurse)")
    @app_commands.autocomplete(kurs=_pk_courses)
    async def lb_parkour(interaction: discord.Interaction, kurs: str | None = None):
        import stats_card, parkour
        await interaction.response.defer()
        raw = await asyncio.to_thread(bot.parkour_raw)
        rows = parkour.rank(raw, kurs)
        top = [(r, n, ms) for r, n, ms, _ in rows[:10]]
        link = bot.db.execute("SELECT uuid FROM links WHERE discord_id=? AND state='active'", (interaction.user.id,)).fetchone()
        me = None
        if link:
            mine = next((x for x in rows if x[3].replace("-", "") == link[0].replace("-", "")), None)
            if mine and mine[0] > 10:
                me = mine[:3]
        note = "" if not link else ("" if any(x[3].replace("-", "") == link[0].replace("-", "") for x in rows) else "-# Du hast noch keine Zeit – ab in die Lobby!")
        import gui
        cs = parkour.courses(raw)
        name = parkour.display(raw, kurs)
        pager = gui.LbPager(f"Parkour \u2013 {name}" if name else "Parkour", rows, me, courses=cs, raw=raw, selected=kurs)
        show_view = pager.pages > 1 or bool(cs)
        await interaction.followup.send(note or None, file=await pager.file(), view=pager if show_view else discord.utils.MISSING)

    @lb.command(name="pvp", description="PvP-Bestenliste: Siege, Niederlagen, Unentschieden und Winrate")
    async def lb_pvp(interaction: discord.Interaction):
        import pvp, ingest, gui
        await interaction.response.defer()
        res = await asyncio.to_thread(ingest.fetch_rows, "v_pvp_stats")
        if not res:
            return await interaction.followup.send("PvP-Stats sind gerade nicht erreichbar, versuch es gleich nochmal.")
        rows = pvp.rank(pvp.build_stats(*res))
        link = bot.db.execute("SELECT uuid FROM links WHERE discord_id=? AND state='active'", (interaction.user.id,)).fetchone()
        me, note = None, ""
        if link:
            u = link[0].replace("-", "")
            mine = next((x for x in rows if x[3].replace("-", "") == u), None)
            if mine and mine[0] > 10:
                me = mine[:3]
            if not mine:
                note = "-# Du hast noch kein Match \u2013 `/pvp queue` in der Lobby!"
        pager = gui.LbPager("PvP \u2013 Siege", rows, me, fmt=str, empty="Noch keine Matches \u2013 ab in die PvP-Queue!")
        await interaction.followup.send(note or None, file=await pager.file(), view=pager if pager.pages > 1 else discord.utils.MISSING)

    tree.add_command(lb)
    import shops
    shops.register(bot, tree)
    import ah
    ah.register(bot, tree)
    import features
    features.register(bot, tree)
    import gui
    gui.register(bot, tree)
    import invites
    invites.register(bot, tree)
    import markt
    markt.register(bot, tree)
    import buy
    buy.RCON, buy.HOST = rcon, BACKENDS[1]

    @tree.command(name="stats", description="Deine Survival-Statistiken als Karte (oder die von jemand anderem)")
    @app_commands.describe(spieler="Minecraft-Name (optional)", user="Discord-User mit verkn\u00fcpftem Konto (optional)")
    async def stats(interaction: discord.Interaction, spieler: str = None, user: discord.User = None):
        await interaction.response.defer()
        import stats_card
        try:
            name, uuid = spieler, None
            if not name:
                target = user or interaction.user
                row = bot.db.execute("SELECT uuid,name FROM links WHERE discord_id=? AND state='active'", (target.id,)).fetchone()
                if not row:
                    return await interaction.followup.send(f"{target.display_name} hat kein verkn\u00fcpftes Minecraft-Konto. Nutze `/stats spieler:<Name>` oder `/link`.")
                uuid, name = row
            if not uuid:
                found = stats_card.find_uuid(name)
                if found:
                    uuid, name = found
                else:
                    uuid, name = await mojang(name)
            await interaction.followup.send(**await bot.stats_payload(str(uuidlib.UUID(uuid)), name, "overview"))
        except ValueError as e:
            await interaction.followup.send(str(e))
        except Exception:
            log.exception("/stats fehlgeschlagen")
            await interaction.followup.send("Statistiken konnten gerade nicht geladen werden.")

    @tree.command(name="status", description="Ist der Server online und wer spielt gerade?")
    async def status(interaction: discord.Interaction):
        await interaction.response.defer()
        try:
            result = await asyncio.to_thread(JavaServer.lookup(f"{os.getenv('MC_HOST', 'velocity')}:{os.getenv('MC_PORT', '25565')}").status)
            await interaction.followup.send(f"🟢 Online · {result.players.online}/{result.players.max} Spieler · {result.version.name}")
        except Exception:
            await interaction.followup.send("🔴 Server nicht erreichbar.")

    group = app_commands.Group(name="whitelist", description="Whitelist verwalten")
    restart_group = app_commands.Group(name="restart", description="Server neu starten")

    async def admin(interaction):
        if not bot.role(interaction.user, "ADMIN_ROLE_ID"):
            await interaction.response.send_message("Dafür brauchst du die Admin-Rolle.", ephemeral=True)
            return False
        return True

    async def manual(interaction, mc_name, action):
        if not await admin(interaction):
            return
        if not mc_name:
            import gui
            return await interaction.response.send_modal(gui.FormModal("Whitelist: Spieler " + ("hinzuf\u00fcgen" if action == "add" else "entfernen"), [("n", "Minecraft-Name", "z.B. NwebNotFound", True)], lambda i, d: manual(i, d["n"], action)))
        await interaction.response.defer(ephemeral=True)
        try:
            uuid, name = await mojang(mc_name)
            async with bot.lock:
                if action == "remove":
                    bot.save("UPDATE links SET state='removing' WHERE uuid=? AND state!='pending'", (uuid,))
                await asyncio.to_thread(whitelist, action, uuid)
                if action == "remove":
                    bot.remove_pending(uuid)
                    bot.save("DELETE FROM links WHERE uuid=?", (uuid,))
            await interaction.followup.send(f"Whitelist {action}: {name} auf beiden Backends ausgeführt.", ephemeral=True)
        except Exception:
            log.exception("Whitelist-Adminbefehl fehlgeschlagen")
            await interaction.followup.send("Whitelist konnte nicht vollständig aktualisiert werden; Serverlogs prüfen.", ephemeral=True)

    @group.command(name="add", description="Spieler auf die Whitelist setzen (Admin) - ohne Name kommt ein Formular")
    async def add(interaction: discord.Interaction, mc_name: str = None):
        await manual(interaction, mc_name, "add")

    @group.command(name="remove", description="Spieler von der Whitelist nehmen (Admin)")
    async def remove(interaction: discord.Interaction, mc_name: str = None):
        await manual(interaction, mc_name, "remove")

    @restart_group.command(name="survival", description="Survival neu starten (Admin)")
    async def restart(interaction: discord.Interaction):
        if not await admin(interaction):
            return
        await interaction.response.send_message("Survival wird gestoppt; Docker startet den Dienst erneut.", ephemeral=True)
        try:
            await asyncio.to_thread(rcon, BACKENDS[1], "stop")
        except Exception:
            log.warning("RCON-Verbindung beim Stoppen geschlossen oder fehlgeschlagen")

    @tree.command(name="backup", description="Backup von Lobby und Survival machen (Admin)")
    async def create_backup(interaction: discord.Interaction):
        if not await admin(interaction):
            return
        await interaction.response.defer(ephemeral=True, thinking=True)
        try:
            async with bot.lock:
                name = await asyncio.to_thread(backup)
            await interaction.followup.send(f"Backup erstellt: `{name}` in `bot/data/backups`.", ephemeral=True)
        except Exception:
            log.exception("Backup fehlgeschlagen")
            await interaction.followup.send("Backup fehlgeschlagen. Logs prüfen; kontrolliere, dass Speichern wieder aktiviert ist.", ephemeral=True)

    tree.add_command(group)
    tree.add_command(restart_group)


def main():
    try:
        cfg = config()
    except ValueError as exc:
        log.error("%s", exc)
        return 2
    shutil.copyfile("/app/link.jar", "/plugin-output/DiscordLink.jar")
    bot = Bot(cfg)
    register(bot)
    bot.run(cfg["DISCORD_TOKEN"], log_handler=None)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

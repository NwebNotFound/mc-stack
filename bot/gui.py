"""GUI-Hub fuer Creepy: /menu und /hilfe mit Buttons zu allen Funktionen (persistent)."""
import asyncio, logging
import discord
from discord import app_commands

log = logging.getLogger("gui")

# (custom_id, Label, Emoji, Befehlspfad, Zeile)
BUTTONS = [
    ("hub:link", "Konto verkn\u00fcpfen", "\U0001F517", "link", 0),
    ("hub:stats", "Meine Stats", "\U0001F4CA", "stats", 0),
    ("hub:stats_other", "Stats von ...", "\U0001F50E", None, 0),
    ("hub:status", "Serverstatus", "\U0001F7E2", "status", 0),
    ("hub:shops", "Alle Shops", "\U0001F6D2", "shops", 1),
    ("hub:shop_of", "Shop von ...", "\U0001F3EA", None, 1),
    ("hub:ah", "Auktionen", "\U0001F528", "ah", 1),
    ("hub:alarms", "Meine Preisalarme", "\U0001F514", "alarm list", 1),
    ("hub:parkour", "Parkour-Bestenliste", "\U0001F3C6", "leaderboard parkour", 2),
    ("hub:setup", "Anleitung per DM", "\U0001F4D6", "setup", 2),
    ("hub:invite", "Freunde einladen", "\u2709\ufe0f", "invite", 2),
]

HUB_TEXT = (
    "Klick einfach auf einen Button - du musst nichts eintippen.\n\n"
    "\U0001F517 **Konto verkn\u00fcpfen** - Minecraft-Konto verbinden und auf die Whitelist\n"
    "\U0001F4CA **Stats** - deine Statistik-Karte oder die von anderen\n"
    "\U0001F6D2 **Shops & Auktionen** - Angebote durchbl\u00e4ttern mit \u25C0 \u25B6\n"
    "\U0001F514 **Preisalarme** - DM, wenn ein Item billig angeboten wird\n"
    "\U0001F3C6 **Bestenliste** - Parkour-Top 10\n"
    "\u2709\ufe0f **Freunde einladen** - pers\u00f6nlicher Einmal-Link (/invite)\n\n"
    "\U0001F6D2 **Kaufen per Discord** - in /shop, /shops und /ah \u00fcber das Men\u00fc *Kaufen ...*, abholen ingame mit /abholen\n\n"
    "-# Alle alten Befehle wie /stats oder /shop gehen weiter."
)


def find_command(tree, path):
    parts = path.split()
    cmd = tree.get_command(parts[0])
    for p in parts[1:]:
        cmd = cmd.get_command(p) if cmd is not None and hasattr(cmd, "get_command") else None
    return cmd


def hub_embed():
    e = discord.Embed(title="\U0001F7E9 Creepy-Men\u00fc", description=HUB_TEXT, color=0x57F287)
    e.set_footer(text="mc.nwebnotfound.dev")
    return e


class StatsModal(discord.ui.Modal, title="Stats von einem Spieler"):
    name = discord.ui.TextInput(label="Minecraft-Name", placeholder="z.B. NwebNotFound", min_length=3, max_length=16)

    def __init__(self, bot):
        super().__init__()
        self.bot = bot

    async def on_submit(self, interaction):
        cmd = find_command(self.bot.tree, "stats")
        await cmd.callback(interaction, spieler=str(self.name.value).strip())


class SellerSelect(discord.ui.Select):
    def __init__(self, bot, names):
        opts = [discord.SelectOption(label=n[:100], value=n[:100]) for n in names[:25]]
        super().__init__(placeholder="Verk\u00e4ufer ausw\u00e4hlen", options=opts)
        self.bot = bot

    async def callback(self, interaction):
        cmd = find_command(self.bot.tree, "shop")
        await cmd.callback(interaction, spieler=self.values[0])


class HubButton(discord.ui.Button):
    def __init__(self, bot, cid, label, emoji, path, row):
        super().__init__(label=label, emoji=emoji, custom_id=cid, row=row,
                         style=discord.ButtonStyle.success if cid == "hub:link" else discord.ButtonStyle.secondary)
        self.bot, self.path = bot, path

    async def callback(self, interaction):
        try:
            if self.custom_id == "hub:stats_other":
                return await interaction.response.send_modal(StatsModal(self.bot))
            if self.custom_id == "hub:shop_of":
                import shops
                try:
                    names = await asyncio.to_thread(shops.sellers)
                except Exception:
                    names = []
                if not names:
                    return await interaction.response.send_message("Gerade hat niemand einen Shop offen.", ephemeral=True)
                v = discord.ui.View(timeout=300)
                v.add_item(SellerSelect(self.bot, names))
                return await interaction.response.send_message("Wessen Shop willst du sehen?", view=v, ephemeral=True)
            cmd = find_command(self.bot.tree, self.path)
            if cmd is None:
                return await interaction.response.send_message("Diese Funktion gibt es gerade nicht.", ephemeral=True)
            if getattr(cmd, "guild_only", False) and interaction.guild is None:
                return await interaction.response.send_message("Das geht nur auf dem Discord-Server, nicht per DM.", ephemeral=True)
            await cmd.callback(interaction)
        except Exception:
            log.exception("Hub-Button %s fehlgeschlagen", self.custom_id)
            msg = "Da ist etwas schiefgelaufen. Probier es gleich nochmal."
            if interaction.response.is_done():
                await interaction.followup.send(msg, ephemeral=True)
            else:
                await interaction.response.send_message(msg, ephemeral=True)


class HubView(discord.ui.View):
    def __init__(self, bot):
        super().__init__(timeout=None)
        for b in BUTTONS:
            self.add_item(HubButton(bot, *b))


def register(bot, tree):
    async def show(interaction):
        await interaction.response.send_message(embed=hub_embed(), view=HubView(bot), ephemeral=interaction.guild is not None)

    @tree.command(name="menu", description="Alle Funktionen von Creepy mit Buttons - ganz ohne Tippen")
    async def menu(interaction: discord.Interaction):
        await show(interaction)

    @tree.command(name="hilfe", description="Hilfe und Men\u00fc mit Buttons zu allen Funktionen")
    async def hilfe(interaction: discord.Interaction):
        await show(interaction)

    # Autocomplete fuer /stats spieler: bekannte, verknuepfte Namen
    stats = tree.get_command("stats")
    if stats is not None:
        @stats.autocomplete("spieler")
        async def stats_ac(interaction, current):
            try:
                rows = bot.db.execute("SELECT DISTINCT name FROM links WHERE state='active' AND name IS NOT NULL ORDER BY name").fetchall()
            except Exception:
                rows = []
            return [app_commands.Choice(name=r[0], value=r[0]) for r in rows if current.lower() in r[0].lower()][:25]

class FormModal(discord.ui.Modal):
    """Generisches Formular: fields = [(key, label, placeholder, required)]; done(interaction, dict)."""
    def __init__(self, title, fields, done):
        super().__init__(title=title[:45], timeout=600)
        self.done, self.inputs = done, {}
        for key, label, ph, req in fields:
            ti = discord.ui.TextInput(label=label[:45], placeholder=(ph or "")[:100], required=req, max_length=100)
            self.inputs[key] = ti
            self.add_item(ti)

    async def on_submit(self, interaction):
        await self.done(interaction, {k: str(v.value).strip() for k, v in self.inputs.items()})


class ChoiceView(discord.ui.View):
    """Auswahlmenue statt Tippen: options = [(label, value)]; done(interaction, value)."""
    def __init__(self, placeholder, options, done, owner_id=None):
        super().__init__(timeout=300)
        self.done, self.owner_id = done, owner_id
        sel = discord.ui.Select(placeholder=placeholder[:150], options=[discord.SelectOption(label=str(l)[:100], value=str(v)[:100]) for l, v in options[:25]])
        sel.callback = self._pick
        self.sel = sel
        self.add_item(sel)

    async def _pick(self, interaction):
        if self.owner_id and interaction.user.id != self.owner_id:
            return await interaction.response.send_message("Das Men\u00fc geh\u00f6rt jemand anderem.", ephemeral=True)
        await self.done(interaction, self.sel.values[0])


def to_number(text, kind=float, default=None):
    t = (text or "").replace(",", ".").replace("CP", "").strip()
    if not t:
        return default
    try:
        return kind(float(t)) if kind is int else kind(t)
    except ValueError:
        return None


class LbPager(discord.ui.View):
    """Bestenliste mit Blaettern: rows = [(rang, name, ms, uuid)], 10 pro Seite."""
    PER = 10

    def __init__(self, title, rows, me=None, courses=None, raw=None, selected=None):
        super().__init__(timeout=600)
        self.title, self.rows, self.me, self.page = title, rows, me, 0
        self.raw = raw or []
        self.pages = max(1, (len(rows) + self.PER - 1) // self.PER)
        if courses:
            import parkour
            sel = parkour.slug(selected) if selected and selected != "*" else ""
            opts = [discord.SelectOption(label="Alle Kurse (beste Zeit je Spieler)", value="*", default=not sel)]
            opts += [discord.SelectOption(label=n[:100], value=c[:100], default=c == sel) for c, n in courses[:24]]
            menu = discord.ui.Select(placeholder="Kurs w\u00e4hlen", options=opts, row=1)
            menu.callback = self._pick
            self.course_menu = menu
            self.add_item(menu)
        self._sync()

    async def _pick(self, interaction):
        import parkour
        v = self.course_menu.values[0]
        course = None if v == "*" else v
        self.rows = parkour.rank(self.raw, course)
        name = parkour.display(self.raw, course)
        self.title = f"Parkour \u2013 {name}" if name else "Parkour"
        self.me, self.page = None, 0
        self.pages = max(1, (len(self.rows) + self.PER - 1) // self.PER)
        for o in self.course_menu.options:
            o.default = o.value == v
        self._sync()
        await interaction.response.edit_message(attachments=[await self.file()], view=self)

    def _sync(self):
        self.prev.disabled = self.page == 0
        self.next.disabled = self.page >= self.pages - 1

    async def file(self):
        import io, stats_card
        part = [(r, n, ms) for r, n, ms, _ in self.rows[self.page * self.PER:(self.page + 1) * self.PER]]
        png = await asyncio.to_thread(stats_card.render_leaderboard, f"{self.title} \u2013 Seite {self.page + 1}/{self.pages}" if self.pages > 1 else self.title, part, self.me if self.page == 0 else None)
        return discord.File(io.BytesIO(png), filename="leaderboard.png")

    async def _flip(self, interaction, d):
        self.page += d
        self._sync()
        await interaction.response.edit_message(attachments=[await self.file()], view=self)

    @discord.ui.button(label="\u25C0", style=discord.ButtonStyle.secondary)
    async def prev(self, interaction, _):
        await self._flip(interaction, -1)

    @discord.ui.button(label="\u25B6", style=discord.ButtonStyle.secondary)
    async def next(self, interaction, _):
        await self._flip(interaction, 1)
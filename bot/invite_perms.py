"""Einmalig: CREATE_INSTANT_INVITE fuer @everyone/Member/Minecraft entziehen, Kanal-Overrides pruefen.
Nutzung: python invite_perms.py [--apply]   (ohne --apply nur Anzeige). Backup nach /app/data/invite-perms-backup.json"""
import asyncio, json, os, sys, time
import discord

APPLY = "--apply" in sys.argv
TARGET = {"@everyone", "member", "minecraft"}

async def main():
    c = discord.Client(intents=discord.Intents.default())
    async with c:
        await c.login(os.environ["DISCORD_TOKEN"])
        g = await c.fetch_guild(int(os.environ["GUILD_ID"]))
        roles = await g.fetch_roles()
        chans = await g.fetch_channels()
        me = await g.fetch_member(c.user.id)
        top = max(r.position for r in me.roles)
        bk = {"time": int(time.time()), "roles": {}, "overrides": []}
        for r in roles:
            if r.name.lower() in TARGET:
                bk["roles"][r.name] = {"id": r.id, "permissions": r.permissions.value, "create_instant_invite": r.permissions.create_instant_invite}
        tids = {v["id"] for v in bk["roles"].values()}
        for ch in chans:
            for t, ow in ch.overwrites.items():
                if getattr(t, "id", None) in tids and not isinstance(t, discord.Member) and ow.create_instant_invite is True:
                    bk["overrides"].append({"channel": ch.name, "channel_id": ch.id, "role_id": t.id})
        print("Bot-Rechte:", {"create_instant_invite": me.guild_permissions.create_instant_invite, "manage_guild": me.guild_permissions.manage_guild, "manage_roles": me.guild_permissions.manage_roles, "manage_channels": me.guild_permissions.manage_channels})
        print(json.dumps(bk, ensure_ascii=False))
        if not APPLY:
            return
        path = "/app/data/invite-perms-backup.json"
        if not os.path.exists(path):
            open(path, "w").write(json.dumps(bk, ensure_ascii=False, indent=2))
        for r in roles:
            if r.name.lower() in TARGET and r.permissions.create_instant_invite:
                if r.position >= top and not r.is_default():
                    print("NICHT AENDERBAR (Hierarchie):", r.name); continue
                p = r.permissions; p.update(create_instant_invite=False)
                try:
                    await r.edit(permissions=p, reason="Einladen nur noch ueber /invite")
                    print("entzogen:", r.name)
                except discord.HTTPException as e:
                    print("FEHLER", r.name, e.status)
        for o in bk["overrides"]:
            ch = next(x for x in chans if x.id == o["channel_id"])
            role = discord.Object(id=o["role_id"])
            ow = next(v for k, v in ch.overwrites.items() if k.id == role.id); ow.update(create_instant_invite=None)
            try:
                await ch.set_permissions(role, overwrite=ow, reason="Einladen nur noch ueber /invite")
                print("Override neutralisiert:", o["channel"], o["role_id"])
            except discord.HTTPException as e:
                print("FEHLER Override", o["channel"], e.status)

asyncio.run(main())

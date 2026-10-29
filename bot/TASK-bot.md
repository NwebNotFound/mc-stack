Auftrag von Luna (auf Nils' Wunsch): Baue in diesem Ordner (C:\Users\nilsw\mc-stack\bot) einen Discord-Bot fuer Nils' Minecraft-Server. Lies zuerst ..\STATUS.md und ..\compose.yaml (Velocity + Paper lobby/survival, RCON intern). Eine andere Codex-Instanz arbeitet parallel im Hauptordner an install-Skripten: aendere ausserhalb von bot/ nur compose.yaml (Service "bot" hinzufuegen) und das minimal noetige.
Anforderungen (Python, discord.py, Slash-Commands, deutsch):
- /setup: DM-Anleitung fuer Laien (Java + Fabric/Modpack bzw. Simple Voice Chat Mod, Serveradresse mc.nwebnotfound.dev, Schritte).
- Whitelist mit Account-Verknuepfung: /link <MC-Name> -> Mojang-UUID pruefen, Rolle pruefen (konfigurierbare Rolle "Spieler"), einmaligen Code erzeugen; Spieler bestaetigt im Spiel (z. B. Join-Versuch zeigt Code in Kick-Nachricht oder /verify im Spiel via Plugin/Log-Parsing - waehle die robusteste einfache Loesung) -> whitelist add per RCON auf allen Backends. Beim Verlassen des Discord-Servers oder Rollenverlust automatisch whitelist remove. SQLite fuer Verknuepfungen.
- /status und ein automatisch aktualisierter Status-Kanal (online, Spieler, Version) via mcstatus.
- Chat-Sync Discord <-> Minecraft in einem konfigurierbaren Kanal: Minecraft->Discord per Log-Tail oder Plugin, Discord->Minecraft per RCON tellraw. Waehle robuste Loesung (gern ein fertiges Paper-Plugin wie DiscordSRV, falls sauberer, aber Whitelist-Logik im eigenen Bot).
- Admin-Befehle (nur Admin-Rolle): /whitelist add/remove, /restart survival, /backup.
- Konfiguration nur ueber .env (DISCORD_TOKEN, GUILD_ID, Kanal-/Rollen-IDs, RCON-Daten) mit .env.example; keine Secrets im Code.
- Dockerfile + Service "bot" in compose.yaml (Profil "bot", startet erst, wenn Token gesetzt).
- README-bot.md (deutsch): wie man den Bot im Discord Developer Portal anlegt (Intents: Server Members, Message Content), Einladungs-URL mit noetigen Rechten, welche Kanaele/Rollen der Server braucht.
- Tests/Lint wo sinnvoll; Bot ohne Token sauber nicht starten.
Nichts kaufen, keine Secrets ausgeben. Am Ende kurze Zusammenfassung in bot/STATUS-bot.md.

Projekt: C:\Users\nilsw\mc-stack (Docker: Velocity, Paper lobby + survival, playit, Discord-Bot in bot\). Lies STATUS.md, STATUS-bot.md, README zuerst. Regeln: keine kostenpflichtigen Plugins, keine Portfreigaben, keine Secrets ausgeben, keine Welten ohne Backup löschen. Schreibe Plan+Fortschritt in TASK4.md, Ergebnis in STATUS.md / STATUS-bot.md.

0) Voice: in config/survival-plugins/voicechat/voicechat-server.properties voice_host=<playit voice endpoint, see .env VOICE_HOST> setzen, survival neu erstellen, pruefen. NwebNotFound muss auf Whitelist + OP auf lobby und survival sein (pruefen/fixen).

1) Lobby: Superflat-Welt (Backup der alten Lobby vorher), Spawnplattform, Nether-Portal-artiges Portal (oder Druckplatte/Portalblock-Region) das Spieler per Velocity auf survival schickt. Keine Mobs, kein Schaden, kein Abbauen in Lobby fuer Nicht-OPs.

2) Eigenes Paper-Plugin (Java, Gradle, in plugins-src\craftcore, gemeinsame Datenbank fuer lobby+survival, z.B. SQLite in gemeinsamem Volume oder MySQL/MariaDB-Container lokal ohne Port nach aussen):
- Waehrung CP (currency points), Startguthaben 0. /balance, /pay. Vault-API bereitstellen.
- Sidebar-Scoreboard: aktueller Server (Lobby/Survival), CP-Kontostand, Online-Spieler, Team.
- Bank: /bank GUI, Items verkaufen gegen CP nach konfigurierbarer Preisliste (config yml).
- Spielershop: jeder Spieler eigener Shop; /shop (Shops durchsuchen), /shop sell <preis> (Item in Hand anbieten), /shop <spieler>; Kauf per GUI, Geld an Verkaeufer.
- /trade <spieler> sicheres Tauschfenster mit beidseitiger Bestaetigung.
- /spawn, /home, /sethome (Limit konfigurierbar, z.B. 3), Teleport erst nach 10 s Stillstehen, Abbruch bei Bewegung/Schaden mit Countdown-Nachricht.
- /claim: Chunk-Claims gegen Griefing (Kosten in CP konfigurierbar), /unclaim, /claim list; Teams: /team create|invite|join|leave|kick|info, alle Teammitglieder duerfen Team-Claims bauen und verwalten (Owner/Admin-Rollen). Alternative: falls stabiler, ein kostenloses Claim-Plugin (z.B. GriefPrevention/Lands free nicht) nur wenn Teams sauber abbildbar – Entscheidung in TASK4.md begruenden.
- Grabsteine: bei Tod Truhe/Grab mit Items, gespeicherte XP halbiert, nur Besitzer (+Team) oeffnet, Despawn nach konfigurierbarer Zeit.
- Rang/Team-Daten per Plugin-Messaging oder DB fuer den Discord-Bot abrufbar.
3) Simple Voice Chat: Sprechen soll Sculk-Sensoren aktivieren (eigenes Addon ueber Voicechat-API oder vorhandenes kostenloses Addon).
4) Discord-Bot (bot\): Rollen-Sync Ränge + Teams MC->Discord; Onboarding: bei Server-Join DM, Nutzer schickt MC-Namen, Bot whitelisted per RCON und schickt per DM die Adresse mc.nwebnotfound.dev; Kanal fuer Chat-Sync mit survival; Sprachkanal/Kanal dessen Name die Online-Spielerzahl zeigt (Rate-Limit beachten, max alle 5-10 min umbenennen).
5) Bauen, Tests, docker compose up, Logs pruefen, check-local.py. Am Ende kurze deutsche Zusammenfassung: was fertig, was getestet, was offen.

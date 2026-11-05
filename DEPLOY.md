# Deploy-Hinweise

## PvP-Server (`pvp`, Compose-Profil `pvp`)
1. Plugins (nicht im Repo, `*.jar` ist ignoriert): `craftcore.jar` (Build mit `gradle:9-jdk`), `Vault-1.7.3.jar` `LuckPerms-Bukkit-5.5.71.jar` und `worldedit-bukkit-7.4.6.jar` nach `config/pvp-plugins/` kopieren (gleiche Jars wie in `config/lobby-plugins/`). Beim Start kopiert itzg sie per `COPY_PLUGINS_SRC` nach `data/pvp/plugins`.
   - WorldEdit 7.4.6 (Paper, unterstuetzt 26.3; gleiche Version wie Lobby): https://cdn.modrinth.com/data/1u6JkXh5/versions/YFnqiUlL/worldedit-bukkit-7.4.6.jar (Modrinth-Projekt `worldedit`, EngineHub). SHA-512 `1fe3975132857093f971fed9efc601299bfa76fa7885f52a542fee41e7a0165ca9cb3cbcf5ae244474887d774d650b0db6f8f79aa80f98b245dea44f1c1b4152` vor dem Kopieren pruefen (`Get-FileHash -Algorithm SHA512`); identisch mit `config/lobby-plugins/worldedit-bukkit-7.4.6.jar`.
   - Rechte: `lp group builder permission set worldedit.* true server=pvp` (admin erbt von builder), siehe `scripts/luckperms-ranks.txt`.
2. DB-User: `powershell -ExecutionPolicy Bypass -File tools\pvp-db-user.ps1`. Vorher `CRAFTCORE_PVP_DB_PASSWORD` selbst erzeugen und in `.env` eintragen, z. B. `python -c "import secrets; print(secrets.token_hex(24))"` (der Helper erzeugt es nicht mehr und bricht ab, wenn es fehlt). Er fuehrt dann `scripts/pvp-db-user.sql` als `craft_admin` aus: `craft_pvp` bekommt nur DML auf `pvp_matches`/`pvp_rounds` und `SELECT` auf `v_player_rank`, keinen Zugriff auf Economy-/Inventartabellen.
3. CraftCore auf pvp (`CRAFTCORE_SERVER=pvp`) laedt nur das PvP-Modul, keine Economy-/Markt-/Inventar-Module (`ServerProfile.economyEnabled`). DB-Login `CRAFTCORE_DB_USER=craft_pvp`; das craftcore-Passwort wird im pvp-Container ueberschrieben.
4. LuckPerms auf pvp nutzt wie Lobby/Survival/Velocity die gemeinsame LuckPerms-DB (`luckperms`, User `LUCKPERMS_DB_USER`, SQL-Messaging), damit Raenge/Permissions (`craftcore.build.pvp` fuer builder/admin) identisch sind. Dieser User hat nur Rechte auf die `luckperms`-DB.
5. Host-Dienst (Start/Stop/Status nur fuer `mc-stack-pvp-1`):
   - `.env`: `PVP_HOST_TOKEN` (zufaellig, 32 Byte hex), `PVP_HOST_PORT=25599`; `PVP_HOST_BIND` leer lassen (= 127.0.0.1). Velocity erreicht ihn ueber `host.docker.internal:25599` (Docker Desktop leitet auf Host-Loopback); keine Firewallregel, nie 0.0.0.0.
   - Autostart nach PC-Neustart: einmalig `powershell -ExecutionPolicy Bypass -File tools\pvp-host-service\install-task.ps1`. Registriert die Aufgabe "mc-stack PvP host service" (bei Anmeldung, Neustart bei Fehler 3x/1 min, ohne Zeitlimit), die `tools\pvp-host-service\run.ps1` startet.
   - Container einmal anlegen: `docker compose --profile pvp create pvp` (der Dienst macht nur `docker start/stop`).
   - Test aus Velocity: `GET /status` mit Header `X-PvP-Token` -> `sleeping`/`online`.

### PvP Schritt 3 (Match-Ablauf)
- Vor dem Deploy: `scripts/pvp-matchflow.sql` als craft_admin ausfuehren (nach `pvp-db-user.sql`).
- Lobby braucht `PVP_HOST_TOKEN` aus `.env` (compose reicht ihn durch) -> Lobby neu erstellen (`docker compose up -d --no-deps lobby`).
- pvp neu anlegen: `docker compose --profile pvp up --no-start --force-recreate --no-deps pvp`.
- NPC: `/cc npc create PvP` mit Aktion `command pvp queue`.

### PvP-Join-Pfad ohne Minecraft-Account testen (Konsole)
Velocity/Paper laufen online-mode, ein echter Join braucht einen Minecraft-Account (macht Nils in-game). Den Plugin-Pfad (Zuweisung anlegen -> Lookup -> Join-Entscheidung) testet man so:
1. Lobby-Konsole: `docker exec mc-stack-lobby-1 rcon-cli "pvp debug-assign <uuidA> <uuidB> <kitA> <kitB>"` -> legt ueber `PvpStore.createAssignment` (derselbe Code wie die Queue) eine pending-Zuweisung an und gibt die Match-ID aus.
2. pvp starten (Host-Service `/start`), dann pvp-Konsole: `rcon-cli "pvpdebug join <uuidA>"` -> derselbe `store.pendingFor` + `PvpAccess.decide` wie beim PlayerJoin; erwartet `MATCH matchId=<id>`. Mit einer fremden UUID: `KICK`.
3. Aufraeumen: `rcon-cli "pvpdebug cancel <matchId>"` (pending -> failed). Danach stoppt pvp 120 s nach Start ohne Spieler, auch ohne DB.
Hinweis: Die Antworten kommen asynchron und gehen ueber RCON verloren; sie stehen im Server-Log (`docker logs mc-stack-<server>-1 | Select-String 'pvpdebug|debug-assign'`).
Nicht abgedeckt: Velocity-Handshake/Forwarding und das Teleportieren in die Arena - nur mit echtem Account.

### Website presence deployment

Apply `scripts/v-player-stats.sql` as a MariaDB administrator after the existing CraftCore stats and team migrations. Then run `tools/pvp-db-user.ps1` and `tools/vip-db-user.ps1` before deploying or restarting PvP and VIP backends. This creates the shared presence table and grants each restricted backend access to it. Each backend needs a unique `CRAFTCORE_SERVER` value.

The database user helpers require `MARIADB_ADMIN_USER`, `MARIADB_ADMIN_PASSWORD`, and the respective `CRAFTCORE_PVP_DB_PASSWORD` or `CRAFTCORE_VIP_DB_PASSWORD` in `.env`. They send credentials and SQL over stdin, create a private temporary option file inside the database container, and remove it afterwards. Run `python tools/check-db-credentials.py` to check scripts, compose files and CI workflows for common command-line credential patterns; `python tools/test_check_db_credentials.py` runs the scanner's regression tests.

### creepy_ro: Admin-Setup und Recovery

Nach dem ersten CraftCore-Start (Tabellen/Views muessen existieren) und bei fehlenden Leserechten explizit im Stack-Checkout ausfuehren:

```powershell
powershell -ExecutionPolicy Bypass -File tools\creepy-ro-grants.ps1
```

Voraussetzungen: vorhandenes Konto `'creepy_ro'@'%'`, laufender `craftcore-db` und `MARIADB_ADMIN_USER` / `MARIADB_ADMIN_PASSWORD` in `.env`. Der Admin braucht SELECT mit GRANT OPTION auf den acht Objekten in `scripts/creepy-ro-grants.sql`; das CraftCore-Laufzeitkonto bekommt keine zusaetzlichen Rechte. Der Helper nutzt denselben privaten Optionsdatei-/stdin-Pfad wie die DB-User-Helper, benoetigt aber kein Passwort des Lesekontos.

Der Schritt ist manuell und idempotent; er laeuft weder beim Plugin-Start noch automatisch beim Deploy. Er fuegt ausschliesslich die acht SELECT-Grants hinzu, legt keinen User an, aendert keine Passwoerter, Daten oder bestehenden weiteren Rechte. Fehlende Konten/Objekte oder unzureichende Admin-Rechte fuehren zu einem Fehler. GRANT ist nicht transaktional: nach einem Teilfehler Voraussetzung beheben und denselben Schritt erneut ausfuehren. Kein FLUSH PRIVILEGES, Neustart oder Jar-/Image-/Konfigurationswechsel erforderlich.

Danach als `creepy_ro` mit sicherer Optionsdatei fuer jedes der acht Objekte `SELECT * FROM craftcore.<Objekt> LIMIT 0;` pruefen (keine Nutzdaten werden ausgegeben). Weitere Leserechte aus anderen Migrationen bleiben unveraendert.

Helper ohne Docker-/DB-Zugriff testen: `powershell -NoProfile -File tools\test-creepy-ro-grants.ps1` (isolierte Fixtures, gemocktes Docker).

### One-off presence backfill

After the presence and player stats tables exist, an administrator may run `scripts/backfill-player-presence.sql` once using a secure option file or the existing database helper. It inserts missing lobby and survival rows from `player_playtime_world`; existing presence rows, including online state and last seen, are preserved on repeat runs. New rows are offline. Their `last_seen` is NULL (unknown). The script first permits NULL in the existing `last_seen` column. This script is manual and is not run at startup or deploy.


### #136: Velocity switch fallback

Build both `plugins-src/craftcore` and `plugins-src/server-status-velocity` with Java 25.
After the required backup, deploy CraftCore to lobby/survival and the usual templates.
CraftCore creates `proxy_switch_requests` using the existing craftcore database account.
Also copy the rebuilt server-status-velocity jar to the Velocity plugin directory (replace the previous MCStack Status jar),
then restart Velocity in the same maintenance window. A proxy restart disconnects players.
The existing `CRAFTCORE_DB_PASSWORD` environment variable is sufficient; no proxy configuration change is required.
Do not deploy only CraftCore: the fallback needs the new proxy consumer.

The proxy polls every 500 ms and executes a pending request after a four-second grace period via its own connection API.
It rejects requests older than 15 seconds, requests from earlier proxy logins, and requests whose source backend no longer matches.
Rows are claimed under a database lock; backend writes use one ordered worker and supersede older pending choices.
Backends without database write access retain the plugin-message path and clickable `/server` fallback.
Test the Survival portal, NPC/menu joins, a newer destination replacing an older one, and disconnect/reconnect within five seconds.
Successful fallback switches log `Wechsel per Queue` in Velocity. Check both backend and proxy logs for queue permission errors.

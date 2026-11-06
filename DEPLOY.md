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
Also copy the rebuilt server-status-velocity jar to the Velocity plugin directory (replace the previous MCStack Status jar),
then restart Velocity in the same maintenance window. A proxy restart disconnects players.
Deploy both jars: switches use the dedicated `craftcore:switch` listener registered by the proxy plugin.
The backend registers its outgoing channel and ensures it is present on the player connection before sending.

Only messages from the player's current backend are accepted. Client-origin messages are consumed and rejected.
Revision acceptance and nonblocking connection submission share one critical section; there is no delayed database queue.
Portal, switch and quit handlers perform no queue database operations. Any old queue table is left untouched and no longer used.
The existing asynchronous status refresh still uses the database independently of switching.
Test portal, NPC/menu joins, latest-choice ordering and disconnect/reconnect within five seconds.
Test switching with the database unavailable; existing database-dependent VIP features still need the database.
Chat verification and the clickable `/server` fallback remain available if a switch fails.

## Wartungs-Tasks: Backup und Dev-Reset (fuer Homelab Deployer)

**Online-Backup (Standard):** `backup.ps1` stoppt nichts mehr. Pro Server: optional Chat-Hinweis, RCON `save-off`, `save-all flush`, Welten kopieren (ohne `session.lock`), `save-on`; `save-on` laeuft auch bei einem Fehler (finally). Der DB-Dump bleibt `--single-transaction`. `-StopServers` ist das alte Verhalten (Stop, Kopie, Start), `-LeaveStopped` (Deploys) setzt es automatisch, `-NoWarning` laesst den Chat-Hinweis weg. **Nach dem Deploy den Task `MCStack-Backup` auf 00:00 stellen** (Homelab Deployer).

Nach dem Vorfall vom 05.11. (beide Nachhol-Laeufe um 11:36 gleichzeitig, `craftcore-db` noch aus) gilt fuer `backup.ps1` und `dev-reset.ps1`:

- **Log:** jeder Lauf schreibt JSON-Zeilen (Start, Schritte, Exit-Code, Fehler) nach `logs/maintenance/<backup|dev-reset>-<yyyy-MM>.log`, eine Datei pro Monat. Es wird nichts automatisch geloescht; alte Monate nur mit Nils' OK von Hand entfernen.
- **Bereitschaft:** vor jeder Aktion prueft das Skript Docker, `craftcore-db` healthy und `SELECT 1` (Standard 12 Versuche × 10 s, `-ReadyAttempts`). Danach klarer Fehler im Log, Exit 1, es wird nichts gestoppt.
- **Unvollstaendige Backups:** gueltig ist nur ein Ordner mit `COMPLETE.txt`. Ein abgebrochener Lauf bekommt `FAILED.txt` mit dem Grund und wird zu `daily-…-FAILED` umbenannt (nichts geloescht; die 7-Tage-Aufbewahrung fasst `-FAILED` nicht an).
- **Aufbewahrung:** `backup.ps1` loescht keine alten Backups mehr (PROJECT.md Regel 5). Vollstaendige Backups aelter als 7 Tage stehen als `retention`-Warnung im Log; entfernt werden sie nur von Hand mit Nils' OK. `-PreserveBackups` bleibt als Schalter erhalten, hat aber keine Wirkung mehr.
- **Sperre:** beide nutzen `backups/.maintenance.lock` (exklusiver Datei-Handle, auch ueber Sitzungen hinweg). `dev-reset.ps1` wartet bis zu 30 min (`-LockWaitSeconds`), bis ein laufendes Backup fertig ist; `backup.ps1` bricht sofort ab, wenn die Sperre belegt ist.

Empfohlene Task-Einstellungen (Task Scheduler, Benutzer `nilsw`, "Unabhaengig von der Benutzeranmeldung ausfuehren"):

| Task | Zeit | Aktion | Einstellungen |
|---|---|---|---|
| `MCStack-Backup` | **00:00** (online, ohne Downtime) | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Users\nilsw\mc-stack\backup.ps1` | "Task so schnell wie moeglich nach verpasstem Start ausfuehren" an; "Verzoegerung" 5 min; "Wenn bereits ausgefuehrt: neue Instanz nicht starten"; Abbruch nach 2 h |
| `MCStack-Dev-Reset` | 05:10 (20 min Abstand) | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Users\nilsw\mc-stack\dev-reset.ps1` | verpasster Start an, Verzoegerung 10 min; neue Instanz nicht starten; Abbruch nach 1 h |

Zusaetzlich den Verlauf der Aufgabenplanung einschalten ("Verlauf aller Aufgaben aktivieren"). Kein AtStartup-Task. Die Scheduler-Aenderungen gehoeren nicht in diesen PR und werden vom Homelab Deployer umgesetzt.

## VIP-Web-Queue einschalten

`compose.yaml` reicht `MC_INGEST_TOKEN` und `VIP_WEB_QUEUE` (Standard `0` = aus) an `lobby` und `survival` durch. Aktivieren: in `.env` `VIP_WEB_QUEUE=1` setzen (Token steht dort schon), dann Backup (`backup.ps1`) und `docker compose up -d --no-deps lobby survival`; im Lobby-Log nach `VIP-Queue` pruefen. Abschalten: `VIP_WEB_QUEUE=0` und die beiden Services neu erzeugen. Keine Secrets ins Repo.

Welt-Sync zur Website (gleicher Schalter `VIP_WEB_QUEUE`): die Lobby schickt alle VIP-Welten (vollstaendige Liste, hoechstens 500; nie gekuerzt) per `POST /api/mc-vip-worlds` an die Website, sobald sich etwas aendert (Pruefung alle 5 s, auch fuer `/vw` im Spiel), sonst alle 5 Minuten. Ohne diesen Sync zeigt `/vip` keine Welten und keine Aktionen. Pruefen: im Lobby-Log keine Zeile `VIP-Sync:`; in D1 (nur lesen) `SELECT kind, updated_at FROM mc_blobs WHERE kind='vip_worlds'` hat eine Zeile. Fehler blockieren nichts und werden in der naechsten Runde wiederholt. Liegt die Liste ueber 1,9 MB (UTF-8), lehnt die Website sie ganz ab (HTTP 400, Log `VIP-Sync: … HTTP 400`) und behaelt ebenfalls ihre letzte Liste. Bei mehr als 500 Welten sendet die Lobby nichts und loggt eine WARNUNG `VIP-Sync: … <Anzahl> live VIP worlds, more than the limit of 500; sync skipped`; die Website behaelt ihre letzte vollstaendige Liste. Reihenfolge: zuerst die Website mit Limit 500 (NwebNotFound #93) deployen, dann dieses Plugin.

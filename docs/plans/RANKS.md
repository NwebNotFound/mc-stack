# Plan: Ränge (mit MVP)

Status: Plan, kein Code. Reihenfolge danach: NPCs → PvP → VIP-Welten → Mario Party.

## Ist-Stand
- LuckPerms 5.5.71 auf Lobby, Survival und Velocity, gemeinsame MariaDB-DB `luckperms`, Messaging `sql`.
- Gruppen vorhanden: `default`, `builder`, `vip`. NwebNotFound ist in `builder` und noch OP (Level 4).
- Kein Tab/Nametag-Plugin. CraftCore baut pro Spieler ein eigenes Scoreboard (Sidebar) und nutzt dort nur die Teams `line0..6` für die Zeilen. CraftCore-Teams (`/team`) haben bisher keine Farbe.

## Gruppen
| Gruppe | Anzeige | Farbe | Weight | Erbt von |
|---|---|---|---|---|
| `admin` | Admin | `&c` | 100 | builder |
| `builder` | Builder | `&a` | 80 | mvp |
| `mvp` | MVP | `&d` | 60 | vip |
| `vip` | VIP | `&6` | 40 | default |
| `default` | Spieler | `&7` | 0 | – |

- Track `ranks`: default → vip → mvp → builder → admin.
- Prefix/Weight per LuckPerms-Meta (`prefix.<weight>.<text>`), damit jede Ausgabe dieselbe Quelle nutzt.
- Admin ersetzt mittelfristig OP; OP bleibt nur für Nils als Notfall.

## Anzeige
- **Lobby:** Rangfarbe und Prefix in Chat, Tab und Nametag. CraftCore liest den Prefix über die LuckPerms-API und setzt ihn in Chat-Format, `playerListName` und in jedem Spieler-Scoreboard ein Team pro Rang (`r<weight>_<name>`, Prefix + Farbe). Die Teams werden in alle Scoreboards eingetragen, weil jeder Spieler sein eigenes hat.
- **Survival:** Teamfarbe statt Rangfarbe. Neue Spalte `teams.color` (Standard `&f`, `/team color <farbe>` für Owner). Nametag/Tab über dieselben Scoreboard-Teams (`t<id>`). Ohne Team: weiß. Rang erscheint in Survival nur klein im Chat (z. B. `[MVP]` grau).
- Bei Gruppen-/Teamwechsel: LuckPerms-`UserDataRecalculateEvent` bzw. Team-Events lösen ein Update aus, kein Polling.
- AFK-Markierung von VipWorldsAFK schreibt ebenfalls den Tab-Namen: dort auf Suffix umstellen, sonst überschreiben sie sich.

## Perks
- **VIP:** 1 VIP-Welt-Slot.
- **MVP:** 3 Slots + Vorrang in der Warteschlange.
- Meta statt Hartcodierung: `meta.vipworld-slots.<n>` und `meta.queue-priority.<n>`. So lassen sich Limits ohne Code ändern.
- **Laufende VIP-Welten:** max. 3 gleichzeitig, konfigurierbar (`vipworlds.max-running: 3`), ca. 1,5 GB pro Welt (`vipworlds.memory: 1536M`).
- **RAM-Budget (32 GB):** ca. 4 GB System + Docker; Rest 28 GB für Velocity, Lobby, Survival, DB, Bot, Website-Dienste und VIP-Welten. Alle Werte in `.env`/Config, nicht im Code.
- **Queue:** Velocity-Plugin (Teil des VIP-Controllers). Start-Anfragen über dem Limit landen in einer Warteschlange, sortiert nach `queue-priority` (MVP vor VIP), danach nach Zeitpunkt. Velocity liest die Meta über LuckPerms-Velocity. Wird eine Welt gestoppt, startet die erste in der Queue. Status und Position bekommt auch die API für Cloud.

## Rechte pro Gruppe
- `default`: normales Spielen, `/team`, `/claim`, `/home`, Markt.
- `vip`/`mvp`: zusätzlich `craftcore.vipworld.create|start|stop|addon` (nur eigene Welten).
- `builder`: WorldGuard-Bau in Lobby, **PvP-Arena bauen**, **Party-Map bauen** (`craftcore.build.pvp`, `craftcore.build.party`).
- `admin`: alles von builder + `luckperms.*`, `craftcore.admin.*`, Ränge vergeben.
- PvP- und Party-Bau ist ausschließlich admin/builder.

## Vergabe
- Jetzt: `/lp user <name> parent set <gruppe>` (nur admin). Optional Kurzbefehl `/rank set <name> <rang>` in CraftCore mit Journal-Eintrag.
- **Offen:** Sync mit Discord-Rollen über Creepy (Dex). Vorschlag: Discord-Rolle → LuckPerms-Gruppe nur in eine Richtung, Creepy schreibt über RCON `lp user … parent set`.

## Daten für die Website (Cloud)
- View `craftcore.v_player_rank` (Name, UUID, Gruppe, Anzeige, Farbe, Weight), gebaut aus `luckperms.luckperms_players` + `luckperms_user_permissions` (`group.*`, höchste Weight).
- Nur `SELECT` auf die View für `creepy_ro`, keine Rechte auf die LuckPerms-Tabellen selbst. Grant läuft über `craft_admin`.
- Optional im Ingest: `rank` pro Spieler im Online-Payload.

## Schritte
1. LuckPerms-Export als Backup, dann Gruppen `admin`, `mvp` anlegen, Weights/Prefixe/Meta setzen, Track `ranks`.
2. CraftCore: Rang-Anzeige Lobby (Chat, Tab, Nametag) + Tests.
3. CraftCore: `teams.color` + Survival-Anzeige + Tests.
4. VipWorldsAFK auf Suffix umstellen.
5. View + Grant für `creepy_ro`, Cloud informieren.
6. Deploy mit Backup und Online-Check.

## Testplan
- Unit: Prefix-Auswahl nach Weight, Farb-Parsing, Team-Namen < 16 Zeichen.
- Ingame (2 Accounts): Rang ändern → Chat/Tab/Nametag sofort aktualisiert auf beiden Servern; Survival zeigt Teamfarbe; AFK-Tag bleibt erhalten.
- Rechte: Spieler/VIP kann nicht in PvP- oder Party-Map bauen, Builder schon.
- DB: `creepy_ro` liest die View, aber keine LuckPerms-Tabelle.

## Offene Fragen
- Farben für Admin/Builder/VIP/Spieler so okay (`&c`, `&a`, `&6`, `&7`)?
- Wird MVP vergeben oder später gekauft? (Kauf = Nils' OK, keine Kosten-Tools.)
- Soll OP für Nils ganz weg, sobald `admin` läuft?
- Discord-Sync: welche Seite ist führend?
- Team-Farbe frei wählbar oder aus fester Liste (keine Rangfarben wie `&d`)?
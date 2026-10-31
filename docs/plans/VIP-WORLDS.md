# Plan: VIP-Welten

Status: Plan, kein Code. Reihenfolge laut RANKS.md: NPCs → PvP → **VIP-Welten** → Mario Party.
Vorbild ist PvP (on-demand Server über Velocity + Host-Service, Auto-Stop). Alle offenen Fragen stehen unten unter „Offene Fragen an Nils“.

## 1. Ziel und Slots

- Spieler mit Rang `vip` bekommen **1** eigene Welt, `mvp` (und höher) **3**.
- Slots kommen nur aus LuckPerms-Meta, nicht aus Gruppennamen im Code:
  - `vipworlds.slots` (vip = 1, mvp = 3)
  - `vipworlds.queue-priority` (vip = 10, mvp = 20)
  - optional `vipworlds.max-addons` und `vipworlds.idle-minutes`
- CraftCore liest die Meta über die LuckPerms-API (wie Prefix/Weight in RANKS.md). Default ohne Meta: 0 Slots.
- Rangverlust: Welten bleiben erhalten, aber nur so viele sind startbar wie Slots vorhanden (der Spieler wählt, welche). Nichts wird automatisch gelöscht.

## 2. Was Spieler können

In der Lobby per `/vw` (Befehl) und GUI-Menü (NPC „VIP-Welten“):

| Aktion | Befehl | Wer |
|---|---|---|
| Welt erstellen | `/vw create <name> [typ]` (normal, flach, void, Seed optional) | Owner mit freiem Slot |
| Umbenennen | `/vw rename <welt> <neuer-name>` | Owner |
| Starten / Beitreten | `/vw start <welt>`, `/vw join <welt>` | Owner, Mitglieder |
| Stoppen | `/vw stop <welt>` | Owner |
| Addons | `/vw addon list|add|remove <welt> <addon>` | Owner, nur gestoppte Welt |
| Mitglieder | `/vw invite|kick <welt> <spieler>`, Rollen `member`/`builder` | Owner |
| Sichtbarkeit | `/vw public|private <welt>` (öffentlich = jeder darf rein) | Owner |
| Einstellungen | Spielmodus, Schwierigkeit, Gamerules aus Allowlist | Owner |
| Löschen | `/vw delete <welt>` + Bestätigung (siehe 7.) | Owner |

- Namen: 3–24 Zeichen, `[a-zA-Z0-9_-]`, pro Owner eindeutig, Filter gegen Beleidigungen.
- Interne ID ist eine UUID; Ordner und Container heißen nach der ID, nie nach dem Namen.

## 3. Laufzeit-Modell

- **Ein Container pro laufender Welt** (`vipworld-1..N`, Slots im Pool, nicht pro Welt). Image wie pvp (itzg Paper 26.3), `MEMORY` ~1.25G Heap bei `mem_limit` 1.5g (konfigurierbar).
- Beim Start mountet der Host-Service das Welt-Verzeichnis `data/vipworlds/<id>/` und die Addons der Welt in einen freien Slot.
- In Velocity sind die Slot-Server fest eingetragen (`vipworld-1..3`), damit keine Velocity-Neustarts nötig sind. CraftCore-Lobby weiß, welche Welt in welchem Slot läuft.
- **Maximal 3 VIP-Welten gleichzeitig** (`vipworlds.max-running`, konfigurierbar), zusätzlich begrenzt durch das RAM-Budget (5.).

### Start / Stop (wie PvP)

1. Spieler klickt „Starten“ → Lobby prüft Rechte, Slots, Addons, freien Laufzeit-Slot.
2. Lobby → Host-Service `POST /vip/start {worldId, slot}` mit `X-PvP-Token`-ähnlichem eigenem Token (`VIP_HOST_TOKEN` aus `.env`, nur 127.0.0.1).
3. Host-Service prüft selbst nochmal: Slot frei, RAM-Budget, Welt existiert, Addons nur aus Allowlist → `docker compose up` für den Slot.
4. Lobby pollt `/vip/status`, bis healthy, dann Transfer über Velocity.
5. **Auto-Stop:** Welt-Server stoppt sich selbst X Minuten (Default 10, konfigurierbar) nach dem letzten Spieler bzw. nach dem Start ohne Spieler (DB-unabhängig, wie #48). Vor dem Stop `save-all`.
6. Owner-Stop: `/vw stop` → Spieler zurück in die Lobby, Save, Container stoppen.

### Serverseitige Prüfungen (auf dem Welt-Server)

- Join nur, wenn Spieler Owner/Mitglied ist oder die Welt öffentlich (Lookup per DB, Fail-fast wie #48; bei DB-Fehler Kick).
- Modern Forwarding Pflicht, Secret-Check wie pvp; kein direkter Port nach außen.
- Builder/Admin-Bypass wie bei PvP.
- Owner bekommt in seiner Welt Rechte über LuckPerms-Kontext `server=vipworld-N` nur für die Laufzeit (kein OP).
- Welt-Server läuft mit `online-mode=false` nur hinter Velocity, Slot-Ports nur im Docker-Netz.

## 4. Warteschlange

- Sind alle Laufzeit-Slots belegt oder das RAM-Budget voll, kommt der Start in eine Queue (`vip_world_queue`).
- Reihenfolge: `queue-priority` (MVP vor VIP), dann Wartezeit.
- Anzeige: Position in Actionbar/Chat, Abbruch mit `/vw queue leave`.
- Wird ein Slot frei, startet die erste Welt; der Spieler hat 2 Minuten zum Beitreten, sonst Auto-Stop.
- Optional (offene Frage): Fairness-Grenze, z. B. maximale Laufzeit pro Welt, wenn jemand wartet.

## 5. RAM-Budget (PC-Nils)

Gemessen am 31.10.2026, 19:53 CET mit `docker stats` (ohne Spieler):

| Dienst | Konfig | Gemessen |
|---|---|---|
| survival | `MEMORY: 4G` | 3,75 GiB |
| lobby | `MEMORY: 1G` | 1,75 GiB |
| pvp (on-demand) | `MEMORY: 768M`, `mem_limit: 1g` | 1015 MiB (am Limit!) |
| velocity | `MEMORY: 512m` | 563 MiB |
| craftcore-db | – | 618 MiB |
| bot, playit | – | ~70 MiB |
| **Summe** | | **~8,3 GiB** |

- PC: 32 GB, davon ~4 GB für Windows/Docker reserviert → ~28 GB nutzbar.
- **Wichtig:** Die Docker-VM (WSL2) sieht aktuell nur **15,57 GiB** (keine `.wslconfig`, WSL-Standard = 50 %). Ohne Anpassung bleiben ~7 GiB für VIP-Welten + PvP.
- Mit 3 × 1,5 GB = 4,5 GB passt es knapp in die aktuelle VM; für mehr Puffer `.wslconfig` mit `memory=24GB` (offene Frage).
- Konfigurierbar in `config/vipworlds.yml` (nicht `.env`, keine Geheimnisse):
  - `ram.total-mb`, `ram.reserved-mb`, `ram.per-world-mb` (Default 1536), `max-running` (Default 3).
  - Host-Service rechnet vor jedem Start: Summe `mem_limit` laufender Container + neue Welt ≤ `total - reserved`. Sonst Queue.

## 6. Addons

- „Addon“ = Eintrag aus einer **Allowlist** kostenloser Plugins oder Datapacks (z. B. WorldEdit, Chunky, ausgewählte Datapacks). Keine Bezahl-Plugins, keine Uploads von Spielern.
- Allowlist in `config/vipworlds-addons.yml`: `id`, Name, Typ (`plugin`/`datapack`), Version, Download-URL, SHA-256, Lizenz, Paper-Version, RAM-Hinweis.
- Dateien liegen einmal in `data/vipworlds-addons/` (geprüft per Hash) und werden read-only in den Container gemountet bzw. Datapacks in die Welt kopiert.
- Addons nur bei gestoppter Welt änderbar; Plugin-Datenordner pro Welt.
- Max. Addons pro Welt über Meta `vipworlds.max-addons`.
- Admin pflegt die Liste; neue Einträge nur per PR.

## 7. Speicher, Backups, Löschen

- Pfad: `data/vipworlds/<world-id>/` (Welt, Nether, End, Plugin-Daten). Größenlimit pro Welt konfigurierbar (Default 2 GB, Warnung bei 80 %); Welt-Border Default 5000 Blöcke.
- Backups: nach jedem Stop ein inkrementelles Backup (oder nächtlich für Welten, die seit dem letzten Backup liefen), Aufbewahrung z. B. 7 Tage + 4 Wochen; im bestehenden Backup-Ordner unter `backups/vipworlds/<id>/`.
- **Löschen nur mit Bestätigung:** `/vw delete <welt>` → Bestätigung durch Eintippen des Weltnamens innerhalb von 60 s. Dann nur **Soft-Delete**: Status `deleted`, Ordner nach `data/vipworlds-trash/` verschoben, endgültig erst nach 30 Tagen per Admin-Befehl (kein automatisches Hard-Delete ohne Freigabe).
- Restore: `/vwadmin restore <welt> [backup]`.

## 8. DB-Tabellen (craftcore)

```sql
vip_worlds(id UUID PK, owner UUID, name, type, seed, status ENUM('stopped','starting','running','stopping','deleted'),
           visibility ENUM('private','public'), size_bytes, created_at, last_started_at, deleted_at,
           UNIQUE(owner, name))
vip_world_members(world_id, player UUID, role ENUM('member','builder'), added_at, PK(world_id, player))
vip_world_addons(world_id, addon_id, added_at, PK(world_id, addon_id))
vip_world_runs(id, world_id, slot, started_at, stopped_at, stop_reason)
vip_world_queue(id, world_id, player UUID, priority, queued_at, status)
vip_world_settings(world_id, key, value)
```

- Rechte: `craft_lobby` schreibt, Welt-Server nur lesend (Mitglieder-Check) + `vip_world_runs`; `creepy_ro` lesend auf eine View `v_vip_worlds` für den Bot.
- Migration als `vip-worlds.sql`, Test-SQL wie bei PvP.

## 9. Admin-Befehle (`/vwadmin`, nur admin, auch Konsole)

- `list [running|queue|player]`, `info <id>`
- `start|stop <id>`, `stopall`
- `slots <spieler>` (zeigt effektive Meta)
- `addon allowlist reload`
- `restore <id> [backup]`, `purge <id>` (endgültig löschen, Bestätigung)
- `ram` (Budget-Anzeige), `queue clear`
- Debug wie PvP (`vwdebug`), Ergebnisse auch ins Log.

## 10. Reihenfolge

1. Diese Plan-PR + Antworten von Nils.
2. LuckPerms-Meta auf `vip`/`mvp` setzen, `.wslconfig` (falls gewünscht).
3. DB-Schema + Views + SQL-Tests.
4. Host-Service: `/vip/start|stop|status`, RAM-Prüfung, Slot-Container, Token in `.env`.
5. Velocity: Slot-Server `vipworld-1..3`; Welt-Server-Image mit CraftCore (Join-Check, Auto-Stop, Forwarding-Check).
6. Lobby: `/vw` + GUI, Queue mit Priorität.
7. Addons: Allowlist + Mount.
8. Backups, Soft-Delete, Restore, Admin-Befehle.
9. Test mit Konsolen-Debug, danach echter Test durch Nils (wie PvP).
10. STATUS.md.

## 11. Risiken

- **RAM:** Docker-VM hat nur 15,57 GiB; pvp steht schon jetzt am `mem_limit`. Zu knappe Limits → OOM-Kills, Weltdaten-Verlust ohne Save.
- **Disk:** viele große Welten; Größenlimit + Monitoring nötig.
- **Addons:** inkompatible Plugin-Versionen nach Paper-Update; Sicherheit fremder Plugins (nur Allowlist mit Hash).
- **Missbrauch:** Lag-Maschinen, Chunk-Generierung → Border, Chunky nur Admin, Entity-Limits.
- **Host-Service** wird zentraler: Bugs blockieren pvp und VIP-Welten; Token nur lokal.
- **Docker-Socket**-Zugriff des Host-Service bleibt hochprivilegiert.
- **Datenverlust** bei Löschen → nur Soft-Delete + Backups.
- **Lizenz/Monetarisierung:** VIP/MVP-Perks müssen zur Minecraft-EULA passen (keine Gameplay-Vorteile gegen Geld, falls Ränge verkauft werden).

## 12. Offene Fragen an Nils

1. Sollen die Docker-VM/WSL mehr RAM bekommen (`.wslconfig`, z. B. `memory=24GB`)? Aktuell sieht Docker nur 15,57 GiB.
2. Bleibt es bei max. 3 gleichzeitig laufenden Welten à ~1,5 GB, oder anderes Limit?
3. Wie lange bis Auto-Stop ohne Spieler (Vorschlag 10 min)?
4. Welche Addons kommen auf die erste Allowlist (Plugins und Datapacks)?
5. Welttypen beim Erstellen: normal, flach, void, eigener Seed – alle erlaubt?
6. Dürfen Owner andere einladen bzw. Welten öffentlich machen? Wie viele Mitglieder max.?
7. Welche Rechte hat der Owner in seiner Welt (Creative, Gamemode-Wechsel, WorldEdit)?
8. Größenlimit pro Welt (Vorschlag 2 GB) und Welt-Border (Vorschlag 5000)?
9. Backup-Rhythmus und Aufbewahrung (Vorschlag: nach Stop, 7 Tage + 4 Wochen)?
10. Löschen: Soft-Delete 30 Tage ok? Wer darf endgültig löschen?
11. Was passiert bei Rangverlust (VIP läuft aus): Welten nur gesperrt oder irgendwann gelöscht?
12. Queue: maximale Laufzeit pro Welt, wenn andere warten? Vorrang nur MVP > VIP oder auch Builder/Admin?
13. Werden VIP/MVP verkauft? Dann EULA-Regeln prüfen.
14. Sollen Spieler Welten herunterladen können (Export)?
15. Soll pvp vorher mehr RAM bekommen (aktuell am `mem_limit`)?

# Plan: PvP-Server (on demand)

Status: Infrastruktur und Build-Modul in Umsetzung. Reihenfolge: Ränge ✓ → NPCs ✓ → **PvP** → VIP-Welten → Mario Party.

## Entscheidungen (31.10.2026)
- Nur 1v1, Best of 3: Runde 1 Kit von Spieler A, Runde 2 Kit von Spieler B, Runde 3 zuf?llig das Kit von Spieler A oder Spieler B; beide Spieler erhalten pro Runde dasselbe Kit.
- PvP ist vollst?ndig von Survival getrennt: eigenes Inventar, keine CP, Coins oder Belohnungen. Elo und Zuschauen kommen sp?ter.
- Nur builder/admin bauen Arenen und Kits. Pro Runde gelten f?nf Minuten; danach ist die Runde unentschieden und ohne Wertung.
- Stopp 120 Sekunden nach leerem Server (keine Builder und keine Queue). Start/Stopp erfolgen ?ber den Host-Dienst ausschlie?lich f?r `mc-stack-pvp-1`; dieser bindet standardm??ig an `127.0.0.1`.
- Ein Bo3-Match z?hlt als ein Sieg bzw. eine Niederlage. `pvp_matches` bleibt eine Zeile pro Match mit Match-Sieger und -Verlierer; `kit` enth?lt das Kit von Runde 1. Optional speichert `pvp_rounds` die Felder `match_id`, `round_no`, `kit`, `winner_uuid` (NULL bei Unentschieden) und `duration_ms`. `v_pvp_stats` z?hlt nur Matches.
- Plugin-Jars stammen beim Start aus `config/<server>-plugins` (`COPY_PLUGINS_SRC`) und ?berschreiben Dateien in `data/<server>/plugins`.

## Ist-Stand (31.10.2026)
- Velocity + Paper 26.3 (Lobby, Survival) in Docker (`compose.yaml`, itzg/minecraft-server), MariaDB 11.8, LuckPerms auf MariaDB mit SQL-Messaging.
- Citizens 2.0.44-b4268 + CraftCore-NPCs (`/cc npc`) auf der Lobby.
- RAM gemessen (docker stats, Leerlauf, 0 Spieler): Survival 3,55 GiB, Lobby 1,77 GiB, Velocity 0,67 GiB, DB 0,39 GiB, Bot 0,06 GiB. Docker/WSL-Limit 15,57 GiB.

## Server `pvp`
- Eigener Paper-Server (gleiche Version wie Lobby) hinter Velocity, nicht in `try`, nur über die Lobby erreichbar.
- JVM: `-Xmx768M -Xms768M` + Aikar-Flags (itzg: `USE_AIKAR_FLAGS=true`, `MEMORY=768M`). Container-Limit ca. 1 GB.
- Welten: nur Void (`level-type=flat`, Void-Generator-Settings), keine Overworld-Generierung, Nether/End aus (`allow-nether=false`, End in bukkit.yml aus).
- `view-distance=4`, `simulation-distance=3`, `spawn-monsters=false`, `spawn-animals=false`, `spawn-npcs=false`, Mob-Spawn-Limits 0, `difficulty=peaceful`-ähnlich für Mobs, PvP an.
- Plugins: CraftCore (PvP-Modul, `CRAFTCORE_SERVER=pvp`), LuckPerms, Vault. Kein Citizens.
- Komplett abschaltbar: `compose.yaml` mit `profiles: [pvp]` bzw. `restart: "no"`; ohne laufenden Container fehlt der PvP-Modus einfach (NPC zeigt „PvP gerade aus“).

## Lebenszyklus (on demand)
- Zustände: `offline` (deaktiviert per Config) · `sleeping` (aus, startbar) · `starting` · `online`.
- Start: Sobald in der Lobby-Queue **2 Spieler gematcht** sind, startet Velocity den Server über einen kleinen **Host-Dienst** (lauscht nur auf `127.0.0.1`), der ausschließlich `start`, `stop` und `status` für den Container `mc-stack-pvp-1` kann. **Kein** allgemeiner Docker-Socket-Proxy und kein Docker-Socket in einem Container (Entscheidung Luna). Kaltstart erwartet **15–30 s**; Spieler sehen „PvP-Server startet …“ in der Actionbar, Queue bleibt erhalten.
- Wenn online: Velocity verschiebt beide Spieler, Match startet.
- Stopp: Server leer **und** niemand baut **und** keine Queue → nach Grace (Vorschlag 120 s) `stop` → `sleeping`.
- Fehlschlag (Start > 60 s): Queue informieren, Spieler bleiben in der Lobby, Log + State `offline`.

## Lobby: Queue + NPC
- Citizens-NPC „PvP“ (vorhandenes `/cc npc`, Aktion `menu pvp`).
- Menü: Kit-Auswahl (Icons aus `/pvp kit`), Button „Queue beitreten/verlassen“, Anzeige Queue-Länge + Server-State.
- Matching: FIFO, 1v1, Kit-Reihenfolge wie oben entschieden.
- Queue liegt in der Lobby (CraftCore); Match-Zuweisung per Plugin-Message/DB-Tabelle `pvp_queue` an den PvP-Server.

## Matches
- Arena zufällig aus den gespeicherten Arenen.
- Pro Match eine **Instanzkopie** der Arena-Welt (`pvp_m_<match_id>`, Kopie des Templates aus `arenas/<name>`), nach Matchende entladen und **gelöscht**.
- `max_parallel_matches` konfigurierbar (erwartet 4–6, bei 1 GB messen). Darüber: Spieler bleiben in der Queue.
- Matchende: Tod/Disconnect = Niederlage, Zeitlimit pro Runde (5 min, dann Unentschieden ohne Wertung). Danach zurück in die Lobby.

## Bauen: Welt `pvp_build`
- Creative-Welt `pvp_build` auf dem PvP-Server, Zugang nur über `/pvp build` (Permission `craftcore.build.pvp`, LuckPerms für `builder` und `admin`).
- Server bleibt an, solange jemand in `pvp_build` ist.
- Befehle:
  - `/pvp arena create <name>`, Region markieren (Pos1/Pos2 per Wand oder `/pvp arena pos1|pos2`), `/pvp arena setspawn <name> <n>` (n = 1, 2, …), `/pvp arena save <name>` (kopiert Region als Template).
  - `/pvp kit create <name>` (aktuelles Inventar + Rüstung), `/pvp kit icon <name>` (Item in Hand).
  - `/pvp arena list|remove`, `/pvp kit list|remove`.

## Datenbank
```sql
CREATE TABLE pvp_matches (
  match_id CHAR(36) PRIMARY KEY,
  arena VARCHAR(64) NOT NULL,
  kit VARCHAR(64) NOT NULL,
  winner_uuid CHAR(36) NULL,
  loser_uuid CHAR(36) NULL,
  started_at BIGINT NOT NULL,
  ended_at BIGINT NOT NULL,
  duration_ms BIGINT NOT NULL,
  KEY (winner_uuid), KEY (loser_uuid), KEY (ended_at)
);
```
- `v_pvp_stats`: `uuid, name, wins, losses, matches, winrate_pct` (`NULL` bei < 5 Matches, sonst gerundet auf 1 Nachkommastelle), `favorite_kit` (häufigstes Kit, bei Gleichstand das zuletzt gespielte).
- `v_pvp_matches`: Matches mit Namen statt nur UUIDs.
- `GRANT SELECT` auf beide Views für `creepy_ro` in `scripts/pvp-grants.sql`, als craft_admin (kein GRANT aus CraftCore, siehe #38).

## Ingest (Dex/Cloud)
- Neue Keys `pvp_stats` und `pvp_matches`; `pvp_matches` dedupliziert über `match_id`.
- Zusätzlich pro Server: `on_demand` (bool) und `state` (`online`/`sleeping`/`starting`/`offline`).

## RAM-Budget (Host 32 GB, Docker-Limit aktuell 15,57 GiB)
| Dienst | heute | mit PvP |
|---|---|---|
| Survival | 3,6 GiB | 3,6 GiB |
| Lobby (+Citizens) | 1,8 GiB | ~2,0 GiB |
| Velocity | 0,7 GiB | 0,7 GiB |
| MariaDB | 0,4 GiB | 0,4 GiB |
| Bot, playit | 0,1 GiB | 0,1 GiB |
| PvP (768M Heap + Overhead) | – | ~1,0 GiB (nur wenn an) |
| **Summe** | ~6,6 GiB | **~7,8 GiB** |
- Reserve im Docker-Limit: ~7,7 GiB; Windows behält ~16 GB. Kein Upgrade nötig.

## Umsetzung (eigene PRs)
1. Compose-Dienst `pvp` + Velocity-Eintrag, Start/Stopp-Hook, States.
2. CraftCore PvP-Modul: Arenen, Kits, Build-Welt.
3. Queue + NPC-Menü in der Lobby, Match-Ablauf, Instanzkopien.
4. DB-Tabelle, Views, Grants-Script, Ingest-Keys.
5. Lasttest: parallele Matches bei 1 GB messen, `max_parallel_matches` setzen.

## Beantwortete Fragen an Nils
- Nur 1v1 oder später auch 2v2/FFA?
- Beantwortet: Bo3-Kit-Reihenfolge wie oben.
- Beantwortet: f?nf Minuten pro Runde, danach Unentschieden.
- Belohnungen (Coins/Vault) für Siege? Wenn ja, wie viel, und Schutz gegen Absprachen (Win-Trading)?
- Ranked/Elo jetzt oder später?
- Beantwortet: 120 Sekunden.
- Zuschauer-Modus gewünscht?
- Beantwortet: nur builder/admin.

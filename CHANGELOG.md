# Changelog

## Für Spieler

### 01.11.2026

- Auf der Website siehst du jetzt überall echte Spielerköpfe: in der Spielerliste, im Profil und oben in deiner Konto-Anzeige.
- Im Profil heißen Kills jetzt „Spieler-Kills“, gezählt werden nur Kills an anderen Spielern.
- Im Inventar auf der Website haben Rüstung und Offhand jetzt eigene, richtige Slots.
- Deine Bestellungen zeigen jetzt deutsche Itemnamen, zum Beispiel „Fass“ statt „barrel“.
- Deine Tode stehen jetzt in zwei Listen: „Getötet von“ für Mobs und „Todesursachen“ für alles andere.

### 31.10.2026

- Neu im Discord: `/leaderboard pvp` zeigt die PvP-Bestenliste mit Siegen, Niederlagen, Unentschieden, Winrate (ab 5 Matches) und Lieblings-Kit.
- In der Lobby stehen jetzt NPCs. Schau sie aus der Nähe an, dann siehst du, was sie machen; per Klick bringen sie dich zum Beispiel auf einen anderen Server oder öffnen ein Menü.
- Es gibt jetzt Ränge: Spieler, VIP, MVP, Builder und Admin. In der Lobby siehst du den Rang farbig im Chat, in der Tabliste und über dem Kopf.
- In Survival zeigt dein Name die Farbe deines Teams. Team-Owner stellen sie mit `/team color <farbe>` ein (z. B. blau, gelb, lila).
- Die AFK-Markierung steht jetzt hinter deinem Namen und überschreibt den Rang nicht mehr.
- Du verkaufst jetzt Stack für Stack statt alles auf einmal.
- Wenn du während eines Verkaufs offline gehst, bekommst du deine Items beim nächsten Einloggen zurück.
- Im Verkaufsmenü siehst du vorher, wie viel du für deine Items bekommst.
- Verkaufen ist vorerst ausgeschaltet. Wir sagen Bescheid, sobald es wieder geht.

## Admin

### Website PR #23 (NwebNotFound/NwebNotFound, live 01.11.2026)

- Köpfe serverseitig gerendert (Face + Hat-Layer, gecacht), Mojang-UUID-Auflösung 6 h gecacht, bei 429 Steve-Fallback. `deaths_entity`/`deaths_by` getrennt angezeigt.

### PR #46

- Creepy: `/leaderboard pvp` liest `v_pvp_stats` über `creepy_ro` (Sortierung: Siege, dann Winrate), neues Modul `bot/pvp.py`.
- Ingest: Feld `pvp_stats` im 5-Minuten-Push (`uuid`, `name`, `wins`, `losses`, `draws`, `matches`, `winrate_pct`, `favorite_kit`); fehlt die View oder ist sie leer, wird das Feld weggelassen (nie `[]`). Fehlt die Spalte `favorite_kit`, wird `null` gesendet.

### PR #37, #38

- Citizens 2.0.44-b4268 (CitizensDev Jenkins, SHA256 `908E5C548AEBE22CC2D8CA88E0068CCC781CE90509A28758AB6F8DC450256D67`) nur auf der Lobby.
- `/cc npc create <name> | setskin <name> <skin> | setmessage <name> <text> | setaction <name> <server|menu|command|none> [arg] | move <name> | remove <name> | list`, Permission `craftcore.admin.npc`. NPCs in `plugins/CraftCore/npcs.yml`, Namen exakt, keine IDs sichtbar.
- CraftCore führt beim Start keine GRANTs mehr aus, wenn das Recht fehlt; Grants für `creepy_ro` stehen in `scripts/creepy-ro-grants.sql` (als craft_admin ausführen).

### PR #34

- LuckPerms-Gruppen admin/builder/mvp/vip/default mit Weights, Prefixen, Track `ranks`; Perks nur per Meta (`vipworld-slots`, `queue-priority`), Setup in `scripts/luckperms-ranks.txt`.
- CraftCore: Rang-Anzeige über die LuckPerms-API, Spalte `teams.color`, `/team color`, `/rank set` (admin nur über die Konsole).
- VipWorldsAFK: AFK nur noch als Suffix.
- View `v_player_rank` (DEFINER craft_admin) mit SELECT für `creepy_ro`.

### PR #30

- Pin CI actions, restrict workflow permissions, and verify the vendored VaultAPI jar.
- Batch sell-menu price previews and prevent repeat refunds for delivered sales.

### PR #28

- Backfill death stats path; add the `v_economy_daily` view and `creepy_ro` grant.
- **Breaking Change:** the Docker Compose profile `cloudflare` was renamed to `tunnel`. `--profile cloudflare` no longer starts anything; use `--profile tunnel`.
- Add sell transaction idempotency and commit-status checks, with per-stack asynchronous selling.
- Add `REFUND_OWED` recovery and `sell_pending`.
- Add the `market.sell.enabled` flag, defaulting to false.
- Add CI.

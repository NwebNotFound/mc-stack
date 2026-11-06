# Changelog

## Für Spieler

<!-- Eintrag: `- **Kurztitel** – ein Satz als Unterzeile.` unter einer Überschrift `### TT.MM.JJJJ`, neueste zuerst. Optional Englisch am Zeilenende als HTML-Kommentar mit dem Präfix "en:" und derselben Form (Titel – Unterzeile), siehe die Einträge unten. -->
### 06.11.2026

- **Neue Startseite** – Die Hauptseite stellt Nils und seine Projekte vor, mit Steckbrief, Projektkarten und den letzten Neuigkeiten. <!-- en: **New home page** – The main page introduces Nils and his projects, with a profile card, project cards and the latest news. -->
- **VIP-Welten über die Website** – Auf der Website legst du deine VIP-Welten an, startest, benennst und stellst sie ein und lädst Freunde ein; Erfolg meldet die Seite erst, wenn der Server die Aktion bestätigt hat. <!-- en: **VIP worlds from the website** – On the website you create, start, rename and configure your VIP worlds and invite friends; the page only reports success once the server has confirmed the action. -->
- **Schnellere Website** – Seiten und Bilder werden besser zwischengespeichert, deshalb laden sie beim nächsten Besuch deutlich schneller. <!-- en: **Faster website** – Pages and images are cached better, so they load much faster on your next visit. -->
- **Eine Navigation für alles** – Hauptseite und Server-Seite teilen sich jetzt dieselbe Navigationsleiste, auch auf dem Handy. <!-- en: **One navigation for everything** – The main site and the server site now share the same navigation bar, on phones too. -->
- **Backup ohne Unterbrechung** – Das tägliche Backup um 00:00 läuft jetzt im laufenden Betrieb: Der Server wird nicht mehr gestoppt und du fliegst nicht raus. <!-- en: **Backup without downtime** – The daily backup at 00:00 now runs while the server is up: it is no longer stopped and you are not kicked. -->

### 05.11.2026

- **Portale und VIP-Welten wieder zuverlässig** – Das Wechseln zwischen den Servern über Portale und NPCs funktioniert wieder, und Starten und Betreten von VIP-Welten wurde repariert; wenn etwas nicht klappt, bekommst du eine Meldung im Chat. <!-- en: **Portals and VIP worlds work again** – Switching between servers through portals and NPCs works again, starting and entering VIP worlds was repaired, and you get a chat message if something does not work. -->

### 01.11.2026

- **Echte Spielerköpfe auf der Website** – Auf der Website siehst du jetzt überall echte Spielerköpfe: in der Spielerliste, im Profil und oben in deiner Konto-Anzeige. <!-- en: **Real player heads on the website** – On the website you now see real player heads everywhere: in the player list, in the profile and in your account display at the top. -->
- **Kills heißen „Spieler-Kills“** – Im Profil heißen Kills jetzt „Spieler-Kills“, gezählt werden nur Kills an anderen Spielern. <!-- en: **Kills are called “Player kills”** – In the profile, kills are now called “Player kills”; only kills on other players are counted. -->
- **Rüstung und Offhand im Inventar** – Im Inventar auf der Website haben Rüstung und Offhand jetzt eigene, richtige Slots. <!-- en: **Armour and offhand in the inventory** – In the website inventory, armour and offhand now have their own proper slots. -->
- **Deutsche Itemnamen in Bestellungen** – Deine Bestellungen zeigen jetzt deutsche Itemnamen, zum Beispiel „Fass“ statt „barrel“. <!-- en: **German item names in orders** – Your orders now show German item names, for example “Fass” instead of “barrel”. -->
- **Tode in zwei Listen** – Deine Tode stehen jetzt in zwei Listen: „Getötet von“ für Mobs und „Todesursachen“ für alles andere. <!-- en: **Deaths in two lists** – Your deaths now appear in two lists: “Killed by” for mobs and “Causes of death” for everything else. -->

### 31.10.2026

- **PvP-Bestenliste im Discord** – Neu im Discord: `/leaderboard pvp` zeigt die PvP-Bestenliste mit Siegen, Niederlagen, Unentschieden, Winrate (ab 5 Matches) und Lieblings-Kit. <!-- en: **PvP leaderboard on Discord** – New on Discord: `/leaderboard pvp` shows the PvP leaderboard with wins, losses, draws, win rate (from 5 matches) and favourite kit. -->
- **NPCs in der Lobby** – In der Lobby stehen jetzt NPCs. Schau sie aus der Nähe an, dann siehst du, was sie machen; per Klick bringen sie dich zum Beispiel auf einen anderen Server oder öffnen ein Menü. <!-- en: **NPCs in the lobby** – There are NPCs in the lobby now. Look at one up close to see what it does; clicking one takes you to another server or opens a menu, for example. -->
- **Neue Ränge** – Es gibt jetzt Ränge: Spieler, VIP, MVP, Builder und Admin. In der Lobby siehst du den Rang farbig im Chat, in der Tabliste und über dem Kopf. <!-- en: **New ranks** – There are ranks now: Player, VIP, MVP, Builder and Admin. In the lobby you see your rank in colour in chat, in the tab list and above your head. -->
- **Teamfarben in Survival** – In Survival zeigt dein Name die Farbe deines Teams. Team-Owner stellen sie mit `/team color <farbe>` ein (z. B. blau, gelb, lila). <!-- en: **Team colours in Survival** – In Survival your name shows your team's colour. Team owners set it with `/team color <colour>` (e.g. blue, yellow, purple). -->
- **AFK-Markierung hinter dem Namen** – Die AFK-Markierung steht jetzt hinter deinem Namen und überschreibt den Rang nicht mehr. <!-- en: **AFK marker behind the name** – The AFK marker now sits behind your name and no longer overrides the rank. -->
- **Verkaufen Stack für Stack** – Du verkaufst jetzt Stack für Stack statt alles auf einmal. <!-- en: **Selling stack by stack** – You now sell stack by stack instead of everything at once. -->
- **Items zurück nach Offline-Verkauf** – Wenn du während eines Verkaufs offline gehst, bekommst du deine Items beim nächsten Einloggen zurück. <!-- en: **Items back after an offline sale** – If you go offline during a sale, you get your items back the next time you log in. -->
- **Preisvorschau im Verkaufsmenü** – Im Verkaufsmenü siehst du vorher, wie viel du für deine Items bekommst. <!-- en: **Price preview in the sell menu** – In the sell menu you now see beforehand how much you get for your items. -->
- **Verkaufen vorerst aus** – Verkaufen ist vorerst ausgeschaltet. Wir sagen Bescheid, sobald es wieder geht. <!-- en: **Selling is off for now** – Selling is switched off for now. We'll let you know as soon as it works again. -->

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

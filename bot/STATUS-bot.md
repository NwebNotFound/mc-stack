# Bot-Status

Stand: 28.10.2026. Implementiert: Discord-Slash-Commands, UUID-Verknüpfung mit Paper-Bestätigung, SQLite und Rollenabgleich, RCON-Whitelist, Status, Chatbrücke, Adminbefehle, Docker-Build und deutsche Einrichtung. Konfiguration und Aktivierung stehen in `README-bot.md`.

Der Bot wird nicht ohne Token gestartet. Für den produktiven Einsatz sind Discord-Konfiguration, Plugin-Installation durch Neustart beider Backends und ein vollständiger Test mit echtem Minecraft- und Discord-Konto erforderlich. Voice benötigt zusätzlich einen erreichbaren UDP-Endpunkt.

Prüfungen: Docker-Image inklusive Paper-Plugin gebaut, Compose-Konfiguration validiert, vier Unit-Tests erfolgreich; Start ohne Konfiguration endet mit einer Fehlermeldung. Ein End-to-End-Test mit Discord, Minecraft-Login, RCON und Backup-Restore steht noch aus.

## TASK4: Zwischenstand

Bei Serverbeitritt wird eine Willkommens-DM mit der vorhandenen `/link`- und `/verify`-Anleitung versucht. Der vorhandene best?tigte Verkn?pfungsablauf bleibt bestehen; die gew?nschte direkte Verarbeitung eines Minecraft-Namens per DM ist noch offen. Die Chatbr?cke ist auf Survival beschr?nkt. Optional kann `PLAYER_COUNT_CHANNEL_ID` einen Kanal f?r die Onlinezahl festlegen; Umbenennungsversuche sind auf h?chstens einen pro zehn Minuten begrenzt. Der Bot ben?tigt hierf?r ?Kan?le verwalten?. Rang- und Teamrollen-Synchronisation fehlen weiterhin. Das Docker-Image wurde gebaut und f?nf Tests bestanden; ein Discord-End-to-End-Test und ein aktivierter Bot stehen aus.


## Changelog (Pflicht)
Jede Bot-Aenderung braucht einen neuen Eintrag in `changelog.json` (version, date, changes[type: neu|verbessert|fix, text: kurz, deutsch, spielerverstaendlich]). Creepy postet neue Versionen beim Start in `CHANGELOG_CHANNEL_ID`; gepostete Versionen stehen in `data/changelog-posted.json` (kein Doppelpost nach Rebuild).



## Verknüpfungs-UX (v1.5, 28.10.2026)
- DM-Flow: ein Fortschritts-Embed (Schritt 1/3 Name, 2/3 Joinen, 3/3 Code), wird per Edit aktualisiert; Adresse `mc.nwebnotfound.dev` als Codeblock + "Multiplayer > Server hinzufügen", Vorwarnung zum absichtlichen Kick.
- Persistente Buttons `link:newcode` (Neuen Code anfordern) und `link:wrongname` (Falscher Name) via `add_view`, funktionieren nach Neustart.
- Code-Eingabe: Leerzeichen/Bindestriche/Punkte werden entfernt, case-insensitive (`norm_code`). Sicherheit unverändert (Mojang-UUID, Rolle, Einmalcode, 10 min, 5 Versuche).
- Kick-Text kommt aus dem Bot-eigenen Plugin `bot/plugin/.../LinkPlugin.java` (AsyncPlayerPreLoginEvent), neu: Code groß/farbig in 4+4 + "Schick diesen Code Creepy auf Discord". Das JAR wird im Bot-Image gebaut und ueber Volume `bot-plugin` beim Start von Lobby/Survival kopiert -> wirkt erst nach Neustart von Lobby/Survival.
- Backups: `*.bak-linkux`.


## Recap & Events (v1.7, 28.10.2026)
- `features.py`: Wochen-Recap So 18:00 Berlin in #mc-chat aus Survival-Statsdateien; Wochen-Deltas ueber SQLite `recap_snap` (Basis-Snapshot beim ersten Start), `recap_meta.last_week` verhindert Doppelpost. `/recap preview` (Admin, ephemeral).
- `/event` (Admin): Modal (Titel, Wann, Beschreibung), Post in #announcements mit Ping der Minecraft-Rolle und persistentem Button `event:rsvp` (an/abmelden). Tabellen `events`, `event_rsvp`; DM-Erinnerung 15 min vorher, verpasste Erinnerungen werden nach Neustart nicht nachgeschickt.
- Command-Beschreibungen verstaendlicher. Backups `*.bak-recap`.


## Roadmap (nach dem Launch)
> Launch hat Vorrang: Probelauf So 1.11., Go-live ca. Mo 2.11. Erst danach angehen.

1. **Bonuszeit-Hinweise:** Post in #announcements, wenn ein Bonus-Zeitfenster startet (Daten liefert Craft).
2. **Einladungen:** 1-2 persoenliche Invite-Links pro verknuepftem Spieler; tracken, wer ueber wen gejoint ist; CP fuer beide, sobald der Eingeladene X Stunden gespielt hat (CP bucht Craft). Anti-Abuse: nur neue Accounts, Whitelist bleibt Pflicht.
3. **Woechentliche Wettbewerbe:** eigener Kanal #events mit Ankuendigung und Ergebnissen (nicht im normalen Chat). Command `/wettbewerb` (`/event` existiert schon).


## Polish (v1.8, 29.10.2026)
- Name bei offenem Code: vorhandenes Fortschritts-Embed wird editiert (kein neues). Hilfe-Button (`link:help` in Schritt 1, `link:help2` in Schritt 2/3, persistent).
- Tests im Image: `docker compose exec -T -w /app bot python -m unittest test_bot` (inkl. norm_code, parse_when, recap_embed). Backups `*.bak-polish`.

## v1.9 (29.10.2026) Discord-Kauf
- Texte in /shop, /ah, Shop-Bild und Alarm-DMs: 'Kaufen per Discord kommt bald, bis dahin ingame'.
- BLOCKER fuer Kaufen-Button: CraftCore hat noch keinen Konsolen/RCON-Befehl. Shop.command(Player,...) nimmt nur Spieler.
- Bedarf an Craft: RCON 'shop buy <uuid> <listing_id> <menge> <max_preis_cent> <idem_key>' (auch fuer 'ah buy'), eine Zeile Antwort, z.B. 'OK <menge> <gesamt_cent> <rest_bestand>' oder 'ERR <NO_FUNDS|SOLD_OUT|PRICE_CHANGED|NOT_FOUND|SELF_BUY|DUPLICATE|INVALID>'. Gleicher idem_key = gleiche Antwort ohne zweiten Kauf. Items in Abhol-Lager (/abholen), wenn Kaeufer offline. Atomar in einer DB-Transaktion, Listing-IDs wie in shop_journal.
- GUI: gui.py mit /menu + /hilfe (persistente Hub-Buttons hub:*, Modal fuer Stats-Name, Select fuer Verkaeufer), Autocomplete /stats spieler. Alte Befehle unveraendert.

## v1.10 (29.10.2026) GUI Teil 2
- /alarm add ohne Args -> Formular; /alarm list mit Loesch-Menue + Neu-Button; /voice limit Auswahl, /voice name Formular; /whitelist add/remove ohne Name -> Formular (Admin-Check zuerst); Parkour-Leaderboard mit Blaettern (gui.LbPager); Beschreibungen ueberarbeitet. /stats hatte schon Tab-Buttons (StatsView).

## Minecraft-Changelog (fuer Craft)
- Datei: C:\Users\nilsw\mc-stack\changelog-mc.json (read-only im Bot unter /app/mc/changelog-mc.json). Creepy prueft jede Minute und postet neue Eintraege einmal in #changelog als "Was ist neu auf dem Server" mit Abschnitt Minecraft (optional Discord).
- Format: JSON-Liste, neuer Eintrag hinten anhaengen: {"version": "2026-10-29" (eindeutig, z.B. Datum oder Datum-2), "date": "29.10.2026", "changes": [{"type": "neu"|"verbessert"|"fix", "text": "spielerfreundlich"}], "discord": ["optional, Zeilen fuer den Discord-Abschnitt"]}.
- Wichtig: Datei IN PLACE bearbeiten (nicht loeschen/neu anlegen), sonst sieht der Container den Single-File-Mount nicht mehr; UTF-8 ohne BOM; gueltiges JSON (sonst wird uebersprungen). Bereits gepostete versions nie aendern.
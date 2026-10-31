# Night-Report — 31.10.2026

## A — craft/sell-all: nur Launchpad-Teil umgesetzt

- Branch `craft/sell-all`, Commit `e10a005d1bd195a6d4d0eff7b97054a6cf251d03`, nach origin gepusht. Basis `craft/launchpad`.
- Gold-Druckplatte auf dem konfigurierten Block aktiviert das Launchpad auch ueber das Physical-Event. Plugin-Default und Lobby-Laufzeitkonfiguration setzen das Pad auf `world 112,-31,89`; die Laufzeitdatei enthielt zuvor noch keinen Launchpad-Abschnitt. Backup: `data/lobby/plugins/CraftCore/config.yml.bak-20261031-020101`.
- Gezielter Docker-Gradle-Test: 4 Tests, 0 Fehler, 0 uebersprungen. Kein vollstaendiger CraftCore-Build, kein ArbitrageTest, keine MariaDB-Tests in diesem Lauf. Ingame nicht geprueft.
- **Offen:** allgemeiner Bankverkauf, Haltbarkeitsbewertung, sicherer niedriger Fallback und erweiterter Arbitrage-Nachweis. Der bestehende Katalog schliesst einzelne Items ganz aus; Cent-Rundung und Rezeptketten muessen gemeinsam behandelt werden. Der bestehende Status dokumentiert bereits eine nicht vollstaendig ausgeraeumte Arbitrage-Luecke. Dieser Branch ist deshalb kein fertiger Sell-all-Stand.

## B — craft/dynamic-trade-prices: offen

Nicht implementiert; kein Branch, Commit, Push oder Test. Website-Push nicht geprueft. Die Preisarbeit baut auf dem noch unvollstaendigen A auf und braucht einen belastbaren Arbitrage-Nachweis. Bestehende Faktoren wurden nicht geaendert.

## C — craft/sell-gui: offen

Nicht implementiert; kein Branch, Commit, Push oder Test. Insbesondere sind transaktionale Inventaruebergabe und Wiederherstellung bei Disconnect und Server-Stopp nicht nachgewiesen. Keine CraftCore-JAR fuer C gebaut oder gestagt.

## D — parkour/multi-course-boards

- Branch `parkour/multi-course-boards` auf `origin/parkour/course-floor`, Commit `8ca0b87f35fe0ae82cac3bade3f5cf88af7815b1`, nach origin gepusht. Worktree `C:\Users\nilsw\mc-stack-night-pk`.
- `/parkour setboard Cave Rush` und `/parkour setboard "Cave Rush"` akzeptieren Kursnamen mit Leerzeichen und suchen den echten Namen aus der Lobby-Parkour-Datenbank; Titelzeile der Kurstafel ist der echte Name. Punkte im Kursnamen sind wegen Bukkit-Konfigurationspfaden momentan nicht erlaubt. Bestehende allgemeine Tafel bleibt per `/parkour setboard` moeglich. Der Course-Floor-Fix ist aus dem Basisbranch enthalten.
- Lobby Parkour unterstuetzt kursbezogene Leaderboards: die JAR enthaelt `LeaderboardManager.spawnLeaderboard(Location, String)` und DB-Abfragen fuer Kurs-/Leaderboard-Zuordnung. Die laufende SQLite-Datenbank kennt `Cave Rush`, aber keine Eintraege in `leaderboards` oder `leaderboard_lines`.
- Das falsche Board war `boards.'1'` in `data/lobby/plugins/ParkourBridge/config.yml`, Entity `32c9142e-0e22-4316-9246-1429541122ab`. Diese Konfiguration wurde mit Backup `config.yml.bak-20261031-020414` umgebunden: das andere bestehende Display zeigt `Cave Rush`; die alte Entity ist unter `retired-boards` zum Entfernen beim Start vorgemerkt. Solange der Server laeuft, sind die sichtbaren Hologramme unveraendert. Die Loeschung und Beschriftung muessen nach Neustart ingame verifiziert werden. Ein Speichern durch das aktuell geladene Plugin vor dem Neustart kann die Dateiaenderung ueberschreiben; vorher Datei erneut pruefen.
- Docker `gradle clean build`: 13 Tests, 0 Fehler, 0 uebersprungen. Ein Zwischenlauf schlug wegen gleichzeitig geaenderter Quelldateien fehl; anschliessender sauberer Build erfolgreich. Kein neuer Migrationscode, keine MariaDB-Integration in diesem Lauf, kein Ingame-Test.
- `data/lobby/plugins/update/parkour-bridge.jar`: SHA256 `55107525FCCE70D1E390D089C8E1E9251BEE01D4F8B95F322D4DB675E2299709`.

## E — Lobby Mob-Spawning

In `data/lobby/plugins/WorldGuard/worlds/world/regions.yml` war die globale Flag bereits `mob-spawning: deny`. Fuer `world_nether` und `world_the_end` globale Regionen mit derselben Flag ergaenzt und vorher jeweils `regions.yml.bak-<Datum>` angelegt. Alle drei Dateien wurden als YAML gelesen und die Flag geprueft. Wirksamkeit beim naechsten regulaeren Neustart; ein Speichern des laufenden WorldGuard kann die Offline-Aenderung zuvor ueberschreiben. Kein Restart oder Reload.

## Update-Verzeichnis und weitere Grenzen

Die vorhandene Lobby-`update/craftcore.jar` wurde in diesem Lauf nicht ersetzt; ihr SHA256 ist `4EECCF415BC3BA7807294AE56738DB0C0399873C92B58DA41E9CA0BDF6B4A5D6`. Sie ist **nicht** der verlangte finale C-Stand und enthaelt die hier gebaute Launchpad-Aenderung nicht nachgewiesen. In Survival lag bei der Pruefung keine `update/craftcore.jar`. Keine fertige CraftCore-JAR bereitgestellt, weil A bis C nicht fertig und nicht vollstaendig getestet sind. Es wurden keine Server oder bestehenden Container neu gestartet, kein Deploy-Skript und kein RCON-Reload ausgefuehrt, nicht gemergt und kein Test-DB-Container gestartet. `changelog-mc.json`, `bot/` und git stash blieben unangetastet.

## Ingame fuer Nils nach regulaerem Neustart

- `/parkour setboard Cave Rush` oder `/parkour setboard "Cave Rush"` als OP setzt eine Kurstafel am eigenen Standort; `/parkour setboard` setzt eine allgemeine Tafel; `/parkour leave` verlaesst den Parkour.
- Pruefen, dass nur die Cave-Rush-Tafel nahe dem Start sichtbar ist, dass ihre Titelzeile `Cave Rush` lautet und Bestzeiten kursbezogen angezeigt werden.
- Launchpad-Golddruckplatte erst nach Bereitstellung einer fertigen CraftCore-JAR und regulaerem Neustart testen; `/cclaunch list` zeigt konfigurierte Pads. `/sell` ist noch nicht vorhanden.
- Vor Neustart die drei WorldGuard-Regiondateien und die ParkourBridge-Konfiguration auf die oben genannten Werte pruefen.

## Folgeauftrag 31.10.2026 — Zwischenstand, nicht fertig

Dieser Abschnitt korrigiert die frueheren Aussagen zum Launchpad: `112,-31,89` ist die Cave-Rush-Startplatte. `origin` wurde gefetcht; `craft/sell-all` wurde auf `origin/craft/launchpad` gesetzt und mit `--force-with-lease` gepusht. HEAD und Remote: `8c5faa9b88b7fe95b67b512e78780e9da12ae461`. Der gesamte Vorlauf-Commit `e10a005` (Goldplatten-Trigger, falsche Defaultposition und Test) ist nicht mehr im Branch; `/cclaunch` aus dem Upstream bleibt. Die aktuelle Lobby-CraftCore-Konfiguration enthaelt bereits `launchpads.pads: []` und keinen Eintrag fuer die Cave-Rush-Platte; sie wurde in diesem Lauf nicht geschrieben, deshalb kein neues Backup.

`parkour/multi-course-boards`: Commit `066a4f3` gepusht. Neue Bestzeiten speichern Kurs-Slug (Leerzeichen/Bindestriche als Unterstrich) und `course_name`; idempotenter Spaltenzusatz, bestehende Zeilen bleiben erhalten. Die Tafel fasst alte und neue Schreibweisen je Spieler zusammen. `kurs1` bleibt `kurs1`. Docker `gradle clean build`: 14 Tests, 0 Fehler, 0 uebersprungen. Build-JAR SHA256 `20DFC0941F8DADF38290F9142D6184F65C077581A167BB57DA9A2CF1FA953F4A`, NICHT gestagt. Eine echte MariaDB-Migration, die Website-Ende-zu-Ende und Ingame wurden nicht getestet; kollidierende Slugs und sehr lange Kursnamen sind nicht abschliessend behandelt. Der parallel vorhandene Bot-Leser erwartet die neue Spalte, aber der Bot wurde nicht veraendert oder deployt.

**Offen und Pflicht:** A Bankverkauf aller Items inkl. Haltbarkeit, Fallback und Arbitrage; B dynamische Preise mit Dump-Schutz und Website-Push; C transaktionssichere Sell-GUI; vollstaendige Commands und Menues nach Luna-Spec; MariaDB-, GUI-, Arbitrage- und Gesamttests; finale CraftCore- und Parkour-JARs bauen/stagen. `craft/dynamic-trade-prices` und `craft/sell-gui` wurden nicht erstellt. Der Parkour-Zweig ist fuer die neue UX ebenfalls unvollstaendig.

Lobby-Mobs: WorldGuard `mob-spawning: deny` weiterhin in allen drei globalen Regionen, `deny-spawn` wurde dort nicht gefunden. CraftCore enthaelt keinen gefundenen CreatureSpawnEvent-Listener. Bukkit hat normale Spawn-Limits; `server.properties` steht auf `difficulty=peaceful`, was hostile Mobs beeinflusst. Keine Konfigurationsdatei geaendert: Flags duerfen erst nach Umsetzung und Pruefung eines geeigneten Ersatzes entfernt werden. Spawn-Eier und `/summon` sind nicht freigeschaltet oder ingame geprueft.

Staging unveraendert: Lobby `craftcore.jar` SHA256 `4EECCF415BC3BA7807294AE56738DB0C0399873C92B58DA41E9CA0BDF6B4A5D6`; Lobby `parkour-bridge.jar` SHA256 `55107525FCCE70D1E390D089C8E1E9251BEE01D4F8B95F322D4DB675E2299709`; Survival `update/craftcore.jar` fehlt. Diese Dateien sind nicht die angeforderte finale Lieferung. Kein Neustart, Reload oder Merge. `changelog-mc.json`, `bot/` und stash nicht geaendert.

### Commands & Menues

Nur bestehende Funktionen, keine Aussage ueber die angeforderte neue UX: `/cclaunch list` verwaltet/listet Launchpads gemaess Upstream; `/parkour leave` verlaesst einen Lauf; `/parkour setboard [Kursname]` setzt als OP eine Tafel. Die verlangten neuen Aliase, Menues und `/cc`-Sammelcommands sind nicht implementiert und daher hier nicht als vorhanden aufgefuehrt. Vor einem regulaeren Neustart WorldGuard- und ParkourBridge-Konfiguration erneut pruefen; die in diesem Report genannten Update-JARs sind nicht fertig.

## Folgeauftrag night3 — Zwischenstand, nicht fertig

- Branch `craft/my-listings` auf Stack-Spitze `8c5faa9b88b7fe95b67b512e78780e9da12ae461`; Teil-Commit `38b0233f0509c171826e34e3d6533da25bec8427` nach origin gepusht. Kein Merge.
- Ursachenbefund im Quellcode: `/market` oeffnet den Bankmarkt, `/markt` ist nicht registriert; es gibt dort keinen Einstieg fuer eigene Anzeigen. AH hat `/ah my` und einen Klick zum Rueckzug, aber keinen Einstieg im normalen AH-Menue und keinen Rueckzug-Command. Shop bietet `/shop show` und `/shop remove <Nr>`; Rueckzug erstellt eine Abholfach-Lieferung, `deliver` ist leer. AH ebenso. Das erklaert fehlende Auffindbarkeit und fehlende direkte Inventarrueckgabe; ein konkreter historischer Fehlversuch ist damit nicht abschliessend bewiesen. Der bestehende Kaufauftrag-Pfad erlaubte auch nach Teilverkaeufen den Rueckzug.
- Datenbank nur lesend geprueft: `craftcore@%` hat ALL PRIVILEGES auf `craftcore`.*. Zum Pruefzeitpunkt keine Zeilen in `ah_journal` und `buy_orders`; `shop_journal` hatte DELIVERY 1, DONE 2, SOLD 1; `auctions` EXPIRED 1. Abholfach: offen 3, geliefert 14, storniert 1. Keine Rechte-Aenderung. `v_shop_events` bildet vorhandene Shop-Rueckzuege als CANCELLED ab; `v_ah_listings` zeigt nur offene Anzeigen, keinen Cancel-Status. Survival-Logs enthalten `/shop`, `/shop show` und Verkaufsbefehle; die Suche ergab keinen konkreten passenden CraftCore-Rueckzugfehler. Kein Beleg fuer einen DB-Rechtefehler.
- Sicherheitsgrenze: `Deliveries.collect` markiert eine Lieferung in der DB als geliefert, bevor das Inventar gespeichert wird. Ein Absturz zwischen diesen Schritten kann Items verlieren; die gewuenschte verlustfreie transaktionale Inventaruebergabe ist nicht nachgewiesen. Vorhandene Abholbox und direkte Ausgabe deshalb nicht als crashsicher ausgeben.
- Einziger implementierter Teil: Kaufauftrag nach erstem Verkauf nicht mehr stornierbar; eine noch nicht bediente Order wird einmalig ohne Gebuehr zurueckerstattet. Der Order-Row-Lock serialisiert Verkauf und Storno; MariaDB-Race-Test fehlt. GUI-Fehlermeldung angepasst. Docker-Gradle-Test `BuyOrderStoreTest`: 3 Tests, 0 Fehler, 0 uebersprungen (H2). Der erste Versuch scheiterte an fehlender Vault-JAR im Worktree; mit read-only Mount der installierten Vault-JAR erfolgreich. Befehl: `docker run --rm -v "${PWD}:/workspace" -v "C:\Users\nilsw\mc-stack\data\survival\plugins\Vault-1.7.3.jar:/workspace/config/lobby-plugins/Vault-1.7.3.jar:ro" -w /workspace/plugins-src/craftcore gradle:9-jdk gradle test --tests de.mcstack.craftcore.BuyOrderStoreTest --no-daemon`. Kein vollstaendiger Build, keine temporaere MariaDB und kein Ingame-Test.
- Offen: gemeinsames Menue, `/markt meine`, `/markt entfernen <id>`, `/ah zurueckziehen <id>` und Alias, eigene ID-Completion, Gebotsauktionen, sichere Rueckgabe und Ablauf, Website-Push fuer cancelled, `/cc holo remove <id>` inkl. Permission und Completion, alle zugehoerigen Tests und finale JAR. Bestehende Commands/Menues: `/ah my`, `/shop show`, `/shop remove <Nr>`, Kaufauftraege ueber Spielershops, `/abholen`. Die angeforderten neuen Commands/Menues existieren noch nicht.
- Keine JAR gebaut oder gestagt. Lobby-Update-JAR unveraendert SHA256 `4EECCF415BC3BA7807294AE56738DB0C0399873C92B58DA41E9CA0BDF6B4A5D6`, Survival-Update-JAR fehlt. Kein Restart, Reload oder Merge; `changelog-mc.json`, `bot/` und stash nicht angefasst.

## Folgeauftrag night4 — unvollstaendig

- ParkourBridge Branch `parkour/multi-course-boards`, Commits `18c72b0` und `cfbd5d0f18a4b7b70f5ff25e49e090033edae395`, gepusht. `/cc holo list`, `remove [id]`, `move <id>` fuer in ParkourBridge konfigurierte TextDisplay-Tafeln, Klickbestaetigung, Ablauf und Abbruch bei zwischenzeitlich veraenderter Tafel. IDs und Distanz werden angezeigt. Der bestehende Startcode entfernt weiterhin die unter `retired-boards` vorgemerkte alte Tafel. Kein Ingame-Test; neue Befehle haben keine eigenen Tests. `saveConfig()` meldet Schreibfehler nicht an den Aufrufer; dauerhafte Loeschung bei Dateifehler ist nicht nachgewiesen. Verwaiste, nicht referenzierte Entities und Lobby-Parkour-Tafeln werden nicht verwaltet. `/cc` muss noch mit der verlangten gemeinsamen Admin-UX abgestimmt werden. Nicht als fertig betrachten.
- Lobby-Parkour SQLite: `leaderboards` und `leaderboard_lines` waren beim lesenden Check leer. Die installierte Lobby-Parkour-JAR besitzt `LeaderboardManager.deleteLeaderboard(int)` fuer eigene Tafeln; die Integration fehlt. Keine produktive DB oder Serverkonfiguration veraendert.
- Docker-Build `docker run --rm -v "${PWD}:/workspace" -v "C:\Users\nilsw\mc-stack\data\lobby:/workspace/data/lobby:ro" -w /workspace/plugins-src/parkour-bridge gradle:9-jdk gradle build --no-daemon`: erfolgreich, 14 bestehende Tests, 0 Fehler. Ein frueherer clean build scheiterte am UTF-8-BOM und wurde nach Korrektur erfolgreich wiederholt. Build-JAR SHA256 `39F7F7B7DD92944F5F1F153E8EAE0DF03DF280658CD3AA178C21A497311F66B5`, nicht gestagt.
- Sell-all, dynamische Preise, Sell-GUI, Luna-UX, Mob-Listener, Listings, persistente Sidebar und Restart-Countdown wurden in night4 nicht implementiert oder getestet. Lobby bleibt `difficulty=peaceful`: feindliche Mobs verschwinden auch bei manueller Erzeugung. Velocity hatte bereits `try = ["lobby"]` und `failover-on-unexpected-server-disconnect = true`; Crash-Verhalten und Countdown sind nicht getestet.
- Keine finale JAR gestagt. Bestehende Lobby-Update-Dateien: CraftCore SHA256 `4EECCF415BC3BA7807294AE56738DB0C0399873C92B58DA41E9CA0BDF6B4A5D6`; ParkourBridge `55107525FCCE70D1E390D089C8E1E9251BEE01D4F8B95F322D4DB675E2299709`; Survival-Update-CraftCore fehlt. Keine MariaDB-Tests, kein Restart, Reload oder Merge, keine Server-Config geaendert, `changelog-mc.json`, `bot/` und stash nicht angefasst.

### Commands & Menues (night4, nur Quellstand)

`/cc holo list`, `/cc holo remove [id]`, `/cc holo move <id>` betreffen nur ParkourBridge und sind noch nicht auf dem Server bereitgestellt. Die uebrigen geforderten Commands und Menues bleiben offen; vorherige Abschnitte enthalten den bestehenden Stand.

## Sell-all Folgeauftrag 31.10.2026 — Teilstand, nicht fertig
- Branch `craft/sell-all`: Commit `ea18351` gepusht; `craft/my-listings` darauf rebased und als `b649888` mit `--force-with-lease` gepusht.
- Bankverkauf beruecksichtigt Resthaltbarkeit und rundet auf Cent ab; Preise unter 0,01 CP bleiben unverkaeuflich. Verkaufssperren, DIRT und Fallback sind weiterhin offen; kein vollstaendiges Sell-all.
- Tests: `ArbitrageTest` und `MarketDurabilityTest` erfolgreich; keine Ingame- oder Datenbanktests. 1500-CP-Limit unveraendert.
- Befehl: `docker run --rm -v "${PWD}:/workspace" -v "C:\Users\nilsw\mc-stack\data\survival\plugins\Vault-1.7.3.jar:/workspace/config/lobby-plugins/Vault-1.7.3.jar:ro" -w /workspace/plugins-src/craftcore gradle:9-jdk gradle test --tests de.mcstack.craftcore.ArbitrageTest --tests de.mcstack.craftcore.MarketDurabilityTest --no-daemon`.

## Dynamische Bankpreise 31.10.2026 — Teilstand
- Branch `craft/dynamic-trade-prices` auf `craft/my-listings`, Commit `7c8834c` nach origin gepusht; kein Merge oder Restart.
- Pro Stack 0,3 %, Grenzen 0,8 bis 1,2, 72 Stunden Halbwertszeit; Preis vor Bewegung, Website-Katalog nach Bewegung. Dump/Rueckkauf und Katalog/Event-Rollback mit H2 getestet. Keine aktive taegliche Kursanpassung im Ausgangsstand gefunden oder ergaenzt; Website-Ende-zu-Ende und MariaDB-Migration nicht getestet.
- Docker `clean build`: 194 Tests, 2 Fehler, 12 uebersprungen; `BuyOrderTransactionTest` und `EconomyEndToEndTest` erwarten Storno nach Teilverkauf, das die Stack-Spitze bereits verbietet. Neue `DynamicPriceTest`: 3 erfolgreich.
- Befehl: `docker run --rm -v "${PWD}:/workspace" -v "C:\Users\nilsw\mc-stack\data\survival\plugins\Vault-1.7.3.jar:/workspace/config/lobby-plugins/Vault-1.7.3.jar:ro" -w /workspace/plugins-src/craftcore gradle:9-jdk gradle clean build --no-daemon`.

## Verkaufs-GUI 31.10.2026 — nicht umgesetzt
- Branch `craft/sell-gui` auf `7c8834c`; keine GUI und kein Code-Commit.
- Bestehende Banktransaktion und Spielerinventar werden getrennt gespeichert; sichere Wiederherstellung und Dupe-Freiheit sind damit nicht nachgewiesen. Beim abgelehnten Umsatzlimit kann zudem ein Artikelkontingent verbraucht werden.
- Tests/Build: nicht ausgefuehrt; GUI-Logik, Rueckgabe und Tageslimit bleiben offen.
- Befehle: nur lesende Quellcode-/Git-Pruefung und `git switch -c craft/sell-gui`; kein Restart, Merge oder Staging nach `data/`.

## Command-UX 31.10.2026 ? Teilstand
- Branch `craft/command-ux`, Code-Commit `d5188fc`; kein Merge, Restart oder Staging.
- Commands & Menues: `/markt|/shop` (Uebersicht, `meine`, `entfernen <uuid>`), `/geld|/bal`, `/sidebar|/sb an|aus`, `/parkour`, Admin `/cc portal|launch`, `/ccportal`, `/cclaunch`; Vanilla `/scoreboard` bleibt frei. Sidebar-Einstellung in MariaDB pro Spieler.
- Tests: CraftCore gesamt 200, davon 2 bekannte Kaufauftrag-Fehler und 12 uebersprungen; gezielt Descriptor/Sidebar/Portals 6 erfolgreich. ParkourBridge `test` erfolgreich. Kein Ingame-Test; vollstaendige Argument-Completion und Parkour-Kursnamen mit Leerzeichen nicht nachgewiesen, `/cc`-Abstimmung mit separatem Parkour-Branch offen.
- CraftCore: `docker run --rm -v "${PWD}:/workspace" -v "C:\Users\nilsw\mc-stack\data\survival\plugins\Vault-1.7.3.jar:/workspace/config/lobby-plugins/Vault-1.7.3.jar:ro" -w /workspace/plugins-src/craftcore gradle:9-jdk gradle test --no-daemon` (gezielt zusaetzlich `--tests de.mcstack.craftcore.CommandDescriptorTest --tests de.mcstack.craftcore.SidebarPreferenceTest --tests de.mcstack.craftcore.PortalsTest`).
- Parkour: `docker run --rm -v "${PWD}:/workspace" -v "C:\Users\nilsw\mc-stack\data\lobby:/workspace/data/lobby:ro" -w /workspace/plugins-src/parkour-bridge gradle:9-jdk gradle test --no-daemon`.

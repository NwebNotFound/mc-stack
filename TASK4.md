# TASK4 – Plan und Fortschritt

Stand: 28.10.2026

## Plan
1. Voice-Tunneladresse setzen und Survival neu erstellen; Whitelist und OP auf beiden Backends prüfen.
2. Lobby bei gestopptem Server sichern, dann Superflat und geschützte Spawnplattform mit Survival-Portal einrichten.
3. CraftCore als eigenes Paper-Plugin mit gemeinsamer Datenhaltung, Wirtschaft, Inventartransaktionen, Teleports, Teams, Claims und Gräbern entwickeln und testen. Für Teams und ihre Claims ist eine eigene Umsetzung vorgesehen, da eine saubere gemeinsame Rechteverwaltung explizit gefordert ist; die Entscheidung muss bei der Implementierung anhand der tatsächlich verfügbaren kostenlosen Alternativen geprüft werden.
4. Voice-API-Anbindung für Sculk-Sensoren prüfen und implementieren.
5. Discord-Bot für Onboarding, Chat, Rang-/Teamsynchronisation und Spielerzahl ergänzen.
6. Bauen, Compose starten, Logs und lokalen Check prüfen und verbleibende End-to-End-Tests dokumentieren.

## Fortschritt
- `STATUS.md`, `README.md` und `bot/STATUS-bot.md` gelesen; eine `STATUS-bot.md` im Projektwurzelverzeichnis existiert nicht.
- NwebNotFound ist auf beiden Backends bereits mit derselben UUID auf der Whitelist und OP-Level 4; RCON und persistierte Dateien geprüft.
- Voice-Host in Vorlage und laufendem Survival gepr?ft; Plugin meldet den gew?nschten Host und UDP 24454. Externer Clienttest offen.
- Lobby angehalten, `backups/lobby-world-20261028-155128.zip` erstellt und alle Eintr?ge gelesen; `world/level.dat` ist enthalten. Lobby wieder gestartet. Keine Welt gel?scht oder umgestellt.
- Bot: Willkommens-DM, auf Survival begrenzte Chatbr?cke und optionale Spielerzahl-Kanalumbenennung mit zehn Minuten Abstand implementiert. Die bisherige Verkn?pfung mit `/link` und `/verify` bleibt erhalten; direkte DM-Namensverarbeitung und Rang-/Teamrollen fehlen.
- Bot-Image gebaut; f?nf Tests im Container erfolgreich. Lokaler Python-Test scheiterte zun?chst an fehlendem `discord`-Modul, im Image sind die Abh?ngigkeiten vorhanden.
- Offen: Superflat-Lobby, Plattform, Portal und vollst?ndiger Schutz; CraftCore einschlie?lich Datenbank, Vault, Wirtschaft, Shop, Trade, Teleports, Teams, Claims und Gr?bern; Voice-Sculk-Addon; direkte DM-Registrierung und Rollen-Sync; vollst?ndiger Ingame-/Discord-/Voice-Test. Die Claim-Architektur ist noch nicht implementiert und die Alternativenpr?fung steht aus.

## Nachtrag Nils und Umsetzung 28.10.2026

- Lobby: Alte Welt zus?tzlich gesichert und aufbewahrt; neue flache Welt mit Gras, ohne Strukturen, 7?7-Plattform. Keine weiteren Bauten erzeugt. WorldEdit, WorldGuard und LuckPerms installiert; globale Schutzflags und Builder-Gruppe eingerichtet. NwebNotFound ist ?ber seine UUID Mitglied; Export gepr?ft. Kreativmodus per Region f?r Mitglieder eingestellt, tats?chlichen Login noch testen.
- Advanced Portals auf Paper und Velocity installiert. Nils setzt die Region und Portalbl?cke selbst: `/portal wand`, Ecken markieren, `/portal create name:survival proxy:survival triggerblock:NETHER_PORTAL`. WorldEdit-Integration ist laut Hersteller in der aktuellen Neufassung nicht vorhanden. Funktionstest mit Client offen.
- Lobby-Parkour 1.4.1 installiert, keine Strecke gebaut. Verwaltungsmen? `/lpk`; Start, Checkpoints, Ziel, Timer, Hologramm-Bestenlisten laut Plugin. Automatischer Fall-R?ckteleport ist nicht belegt, manueller Checkpoint-Gegenstand vorhanden. Ingame-Test und optionale CP-Belohnung offen.
- Economy-Nachtrag: Marktpreise sollen bei Verk?ufen sinken und bei K?ufen steigen, sich zeitabh?ngig zum Basispreis erholen und zwischen konfigurierbaren Grenzen bleiben; Bank kauft und verkauft, `/market` zeigt Kurse und Trend. Noch nicht implementiert, da CraftCore insgesamt noch fehlt.
- Pr?fungen: Plugin-JARs anhand Modrinth-SHA-512 gepr?ft; Paper meldet alle f?nf Lobby-Plugins geladen und Velocity Advanced Portals aktiviert; Regionsdatei, LuckPerms-Export, Weltgenerator, Healthchecks und lokaler Status-Ping gepr?ft. Keine echten Ingame-Funktionstests. Die Generatorwarnung beim ersten Start bezog sich auf leere `generator-settings`; Paper nutzte dabei den flachen Standardgenerator. Compose enth?lt inzwischen explizite Einstellungen f?r zuk?nftige Welten.
- Weiterhin offen: die ?brigen urspr?nglichen TASK4-Funktionen einschlie?lich CraftCore, Voice-Sculk und Discord-Synchronisation; siehe STATUS.md.

## Voice-Sculk / CraftCore, 28.10.2026

- Paper Survival ist 26.3 Build 227, Java 25. VoiceSculk unter `plugins-src/voice-sculk` erstellt: Voice-API-Mikrofonpakete lösen gedrosselt ein Vanilla-Vibrationsereignis auf dem Serverthread aus. Cooldown konfigurierbar, kein Dekodieren der Audiodaten. Gradle-Build und ein Unit-Test erfolgreich; JAR in `config/survival-plugins` bereitgestellt und Survival neu gestartet. Ein realer Voice-Client-/Sculk-Sensor-Test steht aus.
- CraftCore ist weiterhin nicht implementiert. CP/Vault, Datenbank, Bank/Markt, Shops, Trade, Teleports, Teams/Claims und Grabsteine bleiben offen. Die Entscheidung für Claims und Tests der gemeinsamen Datenhaltung stehen ebenfalls aus. Keine CraftCore-JAR auf den Backends installiert.
- Portal und Lobby-Parkour sind bereits über Advanced Portals und Lobby-Parkour vorhanden; die im früheren Nachtrag genannten Ingame-Tests und der automatische Fall-Rückteleport bleiben offen. Bot nicht geändert.
- Neustartprüfung: Survival ist healthy; Paper aktiviert VoiceSculk und Simple Voice Chat meldet ein geladenes und initialisiertes Addon. Im Startausschnitt keine ERROR-/Exception-Meldung. `scripts/check-local.py` meldet eine Velocity-Statusantwort; kein Ingame-Test.


## CraftCore Teil 1 ? 28.10.2026 (aktueller Stand)

CraftCore 0.1.0 wurde unter `plugins-src/craftcore` f?r Paper 26.3 Build 227 und Java 25 gebaut und zusammen mit Vault 1.7.3 auf Lobby und Survival installiert. Beide verwenden MariaDB 11.8 im Compose-Netz ohne Host-Port; das persistente Docker-Volume `craftcore-db` enth?lt die gemeinsamen Konten, Teams und Einladungen. MariaDB wurde statt SQLite im gemeinsam genutzten Dateisystem gew?hlt, weil zwei Server gleichzeitig schreiben und Transaktionen ben?tigen. Das zuf?llige DB-Passwort liegt in der privaten `.env`; Volume und Passwort zusammen sichern. ?nderungen an Zugangsdaten erfordern eine DB-Migration.

Implementiert sind 0 CP Startguthaben, `/balance`, `/pay`, der Vault-Economy-Provider, Sidebar (lokaler Server, CP, lokale Onlinezahl, Team), `/team create|invite|join|leave|kick|info` sowie `/team promote|demote`. Owner k?nnen Rollen ?ndern, Admins einladen und Mitglieder entfernen; der Owner l?st mit `/team leave` das Team auf. Zielspieler f?r `/pay`, Einladen und Rollen-/Kickbefehle m?ssen derzeit auf demselben Backend online sein. Die Sidebar ersetzt andere Scoreboards und aktualisiert alle f?nf Sekunden. DB-Zugriffe laufen synchron auf dem Serverthread; bei langsamer oder ausgefallener DB sind Verz?gerungen m?glich. Vault-Bankkonten sind nicht implementiert.

Gradle `clean build` und zwei Unit-Tests f?r Betragspr?zision und ung?ltige Werte erfolgreich. Beide Backends neu erstellt und gesund; `vault-info` zeigt auf beiden `Economy: CraftCore`, die vier Tabellen sind in der gemeinsamen DB vorhanden, lokaler Velocity-Statusping erfolgreich. Im untersuchten Neustartlog von Lobby, Survival und MariaDB keine ERROR-, SEVERE- oder Exception-Meldungen; bestehende Paper-Konfigurationswarnung bleibt. Keine authentifizierten Ingame-Tests, Transaktionstests ?ber zwei Clients oder Vault-Fremdplugin-Tests durchgef?hrt. Die Tests decken die Team- und Geldtransaktionen noch nicht ab.

Weiter offen sind Bank/Markt, Shops, Trade, Teleports, Claims, Gr?ber und Discord-Synchronisation sowie die bereits dokumentierten Clienttests. `bot/` wurde nicht ge?ndert. Die ?lteren Aussagen in diesem Dokument, CraftCore sei noch nicht installiert, sind durch diesen Nachtrag ?berholt.


## CraftCore Teil 2 - 28.10.2026

- Eigene Claims auf gemeinsamer MariaDB statt eines Fremdplugins: Team-Zuordnung verwendet bestehende Team-IDs und Mitgliedschaft. /claim, /unclaim und /claim list; Kosten claim-cost (CP, Standard 100), Teammitglieder verwalten Team-Claims. Beim Aufloesen eines Teams bleiben dessen Claims als persoenliche Claims des jeweiligen Erstellers bestehen. Claims und Homes sind pro Backend und Welt getrennt. Beim Claim erscheinen Partikel an der Chunkgrenze.
- /spawn, /sethome [Name], /home [Name]: home-limit 3, teleport-seconds 10; Bewegung oder Schaden bricht ab. Spawn ist der Spawn der aktuellen Welt.
- Grab als Fass mit in MariaDB gespeicherten Items und halbierter aktueller XP; Zugriff fuer Besitzer und Mitglieder des Teams zum Todeszeitpunkt, partielle Entnahme bei vollem Inventar, Ablauf grave-despawn-minutes (Standard 1440). Bei fehlendem freien Block oder DB-Fehler bleiben normale Drops bestehen.
- Schutz fuer Blockabbau/-platzierung, Blockinteraktion, Eimer, Explosionen, Feuer, Kolben und Hopper. Hopper in Claims sind pauschal gesperrt; Schutz erfasst nicht alle moeglichen Minecraft-Mechaniken (insbesondere Entitaeten wie Truhen-Loren). Nicht als vollstaendigen Griefingschutz betrachten.
- Wichtige Grenzen: DB-Transaktionen und Inventar-/Weltaenderungen sind nicht atomar; bei Fehlern oder Absturz beim Leeren eines Grabes koennen Items dupliziert werden oder verloren gehen. Vor produktiver Nutzung mit wertvollen Items sind Integrationstests und eine crashsichere Transaktion noetig. Datenbankzugriffe erfolgen weiterhin synchron und koennen den Serverthread verzoegern. Ohne authentifizierte Clients wurden weder Mehrspielerrechte noch Schaden/Bewegung, Tod, Ablauf und Inventarrueckgabe ingame verifiziert.
- Gradle clean build mit Unit-Tests fuer Geldbetraege, Team-/Besitzer-Zugriff und XP-Berechnung erfolgreich. Der erste Neustart zeigte einen SQL-Indexnamenfehler und deaktivierte CraftCore; Indexname korrigiert und neu gebaut. Zweiter Neustart und Logs siehe aktuelles STATUS.md. bot/ nicht bearbeitet.


## CraftCore Teil 3 - 28.10.2026 (Teilstand)

- /bank oeffnet eine GUI mit Einzelkauf per Linksklick und Einzelverkauf per Rechtsklick; nur unveraenderte Standarditems werden angenommen. /market zeigt den aktuellen Preis je Item und die Richtung der letzten Transaktion. Die gemeinsamen Kurse liegen in MariaDB; Kauf erhoeht und Verkauf senkt den Preis, Grenzen und exponentielle Erholung zum Basispreis sind konfigurierbar. Standardartikel sind Cobblestone, Eisenbarren und Diamant. Einstellungen stehen unter market in beiden data/*/plugins/CraftCore/config.yml; beide Backends muessen dieselben Einstellungen verwenden. Nach Aenderungen beide Backends neu starten.
- Risiko: Minecraft-Inventar und Datenbank lassen sich nicht atomar gemeinsam speichern. Zwischen Buchung und Inventaraenderung oder bei Serverabsturz koennen CP bzw. Items verloren gehen oder dupliziert werden; daher noch nicht fuer wertvolle Waren produktionsreif. DB-Zugriffe laufen weiterhin synchron. Keine authentifizierten GUI-, Mehrspieler-, Crash- oder serveruebergreifenden Transaktionstests. /market zeigt die letzte Transaktionsrichtung, nicht die Richtung der laufenden Erholung.
- Nicht umgesetzt: /shop, /shop sell, Spielershop-GUI, /trade und Parkour-Fallrueckkehr bzw. einmalige Checkpoint-Belohnung. Die installierte Lobby-Parkour-JAR zeigte bei der Klassenpruefung keine erkennbare oeffentliche Event-API; eine belastbare Integration ist noch zu untersuchen.
- Gradle clean build und sechs Unit-Tests erfolgreich; Preisrichtung, Begrenzung und Erholung sind unitgetestet. JAR auf Lobby und Survival kopiert, beide Container neu erstellt und healthy. CraftCore aktiviert, im untersuchten Neustartlog keine ERROR-, SEVERE- oder Exception-Eintraege; lokaler Velocity-Statusping erfolgreich. bot/ nicht angefasst.


## CraftCore: Sicherung und Teilstand 28.10.2026

Bank-Kauf und -Verkauf sind voruebergehend gesperrt; /bank und /market zeigen weiterhin Preise. Die bisherige Implementierung konnte bei Absturz oder Datenbankfehler Items oder CP verlieren oder duplizieren. Neue Todesfaelle verwenden normale Drops statt Graeber. Die Entnahme aus vorhandenen Graebern und deren Ablauf sind ausgesetzt; vorhandene Daten bleiben in MariaDB. Diese Sperren sind keine Umsetzung der verlangten crashsicheren Transaktionen. Der Claim-Schutz prueft nun auch Spielerinteraktionen mit Entitaeten und direkte bzw. von Spielern ausgeloeste Projektilangriffe auf Nichtspieler-Entitaeten, darunter Truhenloren. Weitere Mechaniken sind nicht vollstaendig geprueft.

Nicht umgesetzt: wiederherstellbare Banktransaktionen, Spielershops, beidseitig bestaetigter Trade, Parkour-Fallrueckkehr und einmalige CP-Belohnung sowie sichere Grabentnahme. Claims, Teleports und Graeber sind weiterhin nicht als produktionsreif bestaetigt; authentifizierte Mehrspieler-, Crash- und Ingame-Tests fehlen. Datenbank- und Minecraft-Spielerdateien haben keine gemeinsame atomare Transaktion; ein SQL-Rollback allein loest dies nicht.

Gradle clean build einschliesslich bestehender Unit-Tests erfolgreich. JAR auf Lobby und Survival bereitgestellt, beide Container neu erstellt und healthy; CraftCore auf beiden aktiviert, im geprueften Neustartlog keine ERROR-, SEVERE- oder Exception-Meldungen, lokaler Velocity-Statusping erfolgreich. Keine neuen automatisierten Tests fuer die geaenderten Event-Handler oder Ingame-Tests. bot/ nicht bearbeitet.

Projekt C:\Users\nilsw\mc-stack\plugins-src\craftcore (Paper-Plugin CraftCore, Gradle). Lies STATUS.md Abschnitte L13d-L13f fuer den bestehenden Teilkauf-Code. bot\ NUR LESEN, nie bearbeiten. Keinen Server neu starten, nichts loeschen.

Aufgabe (nur diese, direkt implementieren, nicht nur analysieren):
Konsolen-/RCON-Befehle (nur ConsoleCommandSender/RemoteConsoleCommandSender, Spieler abgelehnt), nur auf dem Survival-Backend aktiv (gleiche Erkennung wie bestehender Code):
  shop buy <uuid> <listing_id> <menge> <max_preis_cent> <idem_key>
  ah buy <uuid> <listing_id> <menge> <max_preis_cent> <idem_key>
Antwort an den Sender GENAU eine Zeile: "OK <menge> <gesamt_cent> <rest_bestand>" oder "ERR <NO_FUNDS|SOLD_OUT|PRICE_CHANGED|NOT_FOUND|SELF_BUY|DUPLICATE|INVALID>".
- Wiederverwende die bestehende transaktionale Teilkauf-Logik (Shop und AH) aus L13d inkl. Sperren, Kontobuchung, Restbestand, sale_notices, Abholfach. Items gehen IMMER ins Abholfach (/abholen) des Kaeufers.
- Listing-IDs wie in shop_journal bzw. AH-Tabelle.
- gesamt > max_preis_cent -> PRICE_CHANGED. Ungueltige Argumente/UUID/menge<=0 -> INVALID. Kaeufer = Verkaeufer -> SELF_BUY. Restbestand < menge -> SOLD_OUT (kein Teilkauf ueber Bestand).
- Idempotenz: neue Tabelle (CREATE IF NOT EXISTS beim Start) z.B. rcon_purchases(idem_key PK, kind, buyer, listing_id, qty, response, created_at). In DERSELBEN Transaktion wie der Kauf schreiben. Gleicher idem_key mit gleichen Parametern -> exakt die gespeicherte Antwort zurueck, ohne zweiten Kauf; gleicher Key mit anderen Parametern -> ERR DUPLICATE. idem_key max 64 Zeichen [A-Za-z0-9_-], sonst INVALID.
- DB-Arbeit asynchron ausfuehren, Antwort danach an den Sender (RCON wartet ggf. nicht: gib die Zeile trotzdem aus; falls bestehender Code synchron ist, synchron ok).
Tests: Unit/H2 und MariaDB-Integrationstest (wie bestehende Tests) fuer OK, Teilkauf mit Rest, NO_FUNDS, SOLD_OUT, PRICE_CHANGED, NOT_FOUND, SELF_BUY, INVALID, gleicher Key zweimal (nur ein Kauf, gleiche Antwort), Key mit anderen Parametern (DUPLICATE); Kontosummen vorher=nachher (keine CP entstehen).
Dann: gradle clean build mit echter MariaDB wie in L13f (alle Tests gruen, keine uebersprungen), danach powershell -File C:\Users\nilsw\mc-stack\deploy-craftcore.ps1 ausfuehren.
Ergaenze STATUS.md um Abschnitt "RCON shop buy / ah buy (29.10.2026)" mit Befehlsformat, Fehlercodes, Testanzahl, Jar-Hash. Am Ende eine Zeile: RESULT tests=<n> failed=<n> jar=<sha256>.

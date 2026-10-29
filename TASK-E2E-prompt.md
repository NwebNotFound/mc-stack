Projekt C:\Users\nilsw\mc-stack\plugins-src\craftcore (Paper, Gradle, bestehende H2- und MariaDB-Tests, siehe STATUS.md L9-L13f, RCON shop/ah buy, invites). bot\ NUR LESEN. Kein Server-/Containerneustart, nichts loeschen ausser eigenen temporaeren Testtabellen/Testdaten. Keine Secrets ausgeben.

Aufgabe (direkt implementieren, nur Tests, Produktivcode nur bei gefundenem Bug minimal fixen und melden):
Neuer Integrationstest EconomyEndToEndTest (H2 und echte MariaDB mit temporaeren/isolierten Tabellen wie bestehende Tests), ein zusammenhaengendes Szenario mit 3-4 Spielern, nutzt die ECHTEN Store-/Service-Klassen:
1. Shop-Kauf komplett, Shop-Teilkauf mit Rest, AH-Teilkauf (auch ueber RconPurchase).
2. Kaufauftrag: erstellen (Reservierung), Teilverkauf durch anderen Spieler, Storno des Rests (Rueckerstattung).
3. Offline-Verkauf: Verkaeufer offline -> sale_notices-Eintrag, Zusammenfassung beim Join genau einmal.
4. /pay zwischen Spielern (inkl. zu wenig Guthaben abgelehnt).
5. Bank-Verkauf bis Tageslimit 1500 CP: Verkauf ueber Limit abgelehnt, Zaehler korrekt.
Nach jedem Schritt: Kontostaende jedes Spielers exakt erwartet; Invariante: Summe(Konten)+Summe(Reservierungen) aendert sich nur um Bank-Gutschriften (CP aus der Bank) bzw. bleibt bei Spieler-zu-Spieler gleich; keine CP entstehen/verschwinden. Abholfach-Eintraege korrekt.
Dann gradle clean build mit echter MariaDB (alle gruen, keine skipped). Wenn sich Produktivcode aenderte: powershell -File C:\Users\nilsw\mc-stack\deploy-craftcore.ps1, sonst nicht.
STATUS.md Abschnitt "Economy-E2E-Test (29.10.2026)". Ende: RESULT tests=<n> failed=<n> prodcode_changed=<yes/no> jar=<sha256>.

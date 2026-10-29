# Economy-Audit: Bestandsaufnahme, nicht abgeschlossen

Stand 29.10.2026. Dies ist keine Freigabe der Wirtschaft und dokumentiert keine vollstaendige Arbitragepruefung.

- `PriceCatalog` liest die Bukkit-Rezeptliste, leitet viele nicht explizit festgelegte Preise durch wiederholte Relaxation ab und benutzt sonst Kategorie-Fallbacks. Der bestehende `ArbitrageTest` prueft nur 32 explizite Rezepte; ein vollstaendiger mehrstufiger Nachweis und ein isolierter Weizen-Brot-Test fehlen.
- `Market` nutzt einen gemeinsamen Kurs je Material mit Erholung und Bewegung bei Bankkauf bzw. -verkauf. Ein Verkauf wird einzeln verarbeitet. Die bestehende Material-Tagesgrenze und Mengenstaffel gelten pro Spieler; das Umsatzlimit von 1.500 CP wird ebenfalls pro Spieler gespeichert. Eine gemeinsame Discord-Zuordnung und die verlangte serverweite und verknuepfungsweite Volumenabhaengigkeit fehlen. Die Berechnung ist noch nicht als geforderte Boersenlogik nachgewiesen.
- Das Inventar und die Datenbanktransaktion sind nicht atomar. `bank_journal` enthaelt bisher keinen Zeitpunkt; vollstaendige Bankkaeufe und Marktpreissnapshots fehlen als Historienquelle.
- Im untersuchten CraftCore-Quellcode wurde keine Verwendung von IP-Adressen als Handelslimit oder Sperrkriterium gefunden. Die Suche beweist nichts ueber andere Plugins oder externe Dienste. Im untersuchten Shop-Code wurden keine Regeln gefunden, die Spieler-Shoppreise an den Bankankauf koppeln oder Artikel nach Herkunft sperren.
- Der lokale Quellstand enthaelt fuer AH-Kaeufe und Teilkaeufe eine konfigurierbare Gebuehr (Standard 500 Basispunkte), auch beim RCON-Kauf. Das widerspricht dem Ziel gebuehrenfreien Spielerhandels. Wegen der ausdruecklichen Vorgabe, in diesem Lauf keine Auktionsaenderungen vorzunehmen, wurde dies nicht geaendert; der aktuell laufende Pluginstand kann von diesem Quellstand abweichen.
- Die angeforderten Tests fuer Escrow, Erstattung, Gleichstand, Verlaengerung, Mindesterhoehung, Eigengebot, Offline-Abholung und request_id-Idempotenz sind nicht neu implementiert. Das vorhandene AH ist ein Festpreis-Angebot; es gibt in den gelesenen AH-Klassen keine Gebotslogik fuer Gleichstand oder Verlaengerung.

Keine Aenderung an AH- oder Banklogik, keine Preisfreigabe und kein Deployment in diesem Teilstand.

## Folgepruefung 29.10.2026

Ein neuer Test prueft Weizen zu Brot gesondert an den unabhaengigen Preisgrenzen der Plugin-Standardkonfiguration; dieser Grenzfall ist dort durch die bereits vorhandenen Grenzen abgedeckt. Ein weiterer diagnostischer Test entspannt die Kosten ueber die vorhandenen 32 Ein-Zutaten-Rezepte mehrfach. Er findet unter anderem fuer Goldnuggets eine Untergrenze von 16,67 Cent je Nugget gegenueber maximal 50 Cent Bankankauf, ausgehend von einem Goldblock und dessen Zerlegung in Barren und Nuggets. Dies verwendet gleichzeitig angenommene Extremkurse und erlaubt rechnerisch gebrochene Rezeptmengen; daraus folgt noch kein Nachweis, dass der Gewinn bei realem Preisdruck und Mengenstaffel ausfuehrbar ist. Der diagnostische Test bestaetigt ausdruecklich eine offene Luecke, nicht die Sicherheit des Marktes. Der vollstaendige Bukkit-Rezeptbestand, Alternativzutaten und reale Transaktionsablaeufe sind weiterhin nicht abgedeckt.

Die Bot-Verknuepfungen liegen in `bot/data/links.sqlite3`, Tabelle `links` mit primaerem Discord-Schluessel und eindeutiger UUID. Dieses Schema bildet pro Discord-ID nur eine UUID ab; in der CraftCore-MariaDB gibt es keine Discord-Spalte oder entsprechende Tabelle. Ohne eine verbindliche Quelle fuer mehrere Accounts und deren zeitnahe Uebertragung ist das angeforderte gemeinsame Limit nicht umgesetzt. `bot/` wurde nicht veraendert.

## Lokaler Teilstand 29.10.2026

Nicht deployt: Der Quellcode berechnet konservative fraktionale Kosten ueber die zur Laufzeit registrierten, von PriceCatalog unterstuetzten Rezepte und begrenzt damit den Bankankauf. Ein neuer isolierter Test erfasst die Goldblock-Barren-Nugget-Kette und Weizen-Brot. Das ist noch kein vollstaendiger Arbitragenachweis: echte Server-Rezeptdaten, weitere Rezeptmechaniken, Rundung und Transaktionsablaeufe sind nicht vollstaendig getestet. Preise koennen auf null fallen. Das Modell fuer Discord-Verknuepfungen, getrennte serverweite Kauf-/Verkaufsvolumen und stuendliche Kursaufnahmen fehlt weiterhin; das bestehende Materiallimit besteht weiterhin. Keine Preisfreigabe.


## Teil B1: Luecken und Fixes (29.10.2026)

- Die Bank verwendet getrennte serverweite Faktoren je Material. Verkaeufe druecken den Ankauf bis Faktor 0,25; Kaeufe heben den Verkauf bis Faktor 2. Beide Faktoren erholen sich stetig und werden in `bank_price_factors` gespeichert. Der Verkaufspreis liegt mindestens 10 % ueber dem Ankaufspreis. Die konservative Rezeptkostengrenze bleibt fuer den Bankankauf aktiv. Banktransaktionen schreiben den bezahlten Stueckpreis transaktional nach `market_events`; die vorhandene Stundenaggregation bildet Handelsstunden ab.
- Die fruehere Materialgrenze und Mengenstaffel werden im Bankpfad nicht mehr angewendet. Das Umsatzlimit betraegt 1500 CP je Markttag und Discord-ID, ohne Zuordnung je UUID. `cclink set <uuid> <discord_id>` ist fuer Konsole/RCON verfuegbar; kein IP-Abgleich. Bestehende UUID-Umsaetze werden in den UUID-Zaehler uebernommen. Spielerhandel greift nicht auf den Bankzaehler zu.
- Offene Luecke: Wird eine UUID waehrend des Markttags nach Verkaeufen erstmalig oder erneut verknuepft, wird ihr bisheriger Umsatz nicht in den neuen Discord-Zaehler uebertragen. Auch bereits bestehende UUID-Umsaetze werden beim spaeteren Verknuepfen nicht automatisch zusammengefuehrt. Verknuepfungen daher vor Handelsbeginn setzen; die geforderte durchgaengige gemeinsame Tagesgrenze ist bei Aenderungen am selben Tag noch nicht sichergestellt.
- Der neue mehrstufige Test umfasst die 32 bekannten Rezepte einschliesslich Goldkette und Weizen/Brot, nicht alle auf dem Server registrierten Rezepte. Rezeptarten ausserhalb der von PriceCatalog unterstuetzten Shaped-, Shapeless-, Cooking- und Stonecutting-Rezepte, echte Server-Registry und Inventar/DB-Grenze bleiben unvollstaendig geprueft. Ein vollstaendiger Arbitragenachweis steht aus. Die AH-Gebuehr bleibt unveraendert.

### Top 10 beobachtete Bankankaufsaenderungen

CP pro Stueck; sortiert nach absoluter Differenz. Die Preisdatei `prices-base.yml` wurde nicht geaendert. Die Werte stammen aus den vor/nach dem Neustart gespeicherten Katalog-Snapshots, die aus der Preisdatei, Konfiguration, Rezepten und Kursen berechnet werden; alte Katalogwerte koennen veraltet gewesen sein. Das sind keine zehn Aenderungen der Preisdatei. Grund ist die neue Berechnung bzw. die Rezeptkostengrenze.

| Item | Alt | Neu | Grund |
|---|---:|---:|---|
| EMERALD_BLOCK | 37.12 | 4.05 | Neuer Startkurs und Rezeptkostengrenze |
| DIAMOND | 100.00 | 125.00 | Neuer Startkurs und Rezeptkostengrenze |
| GOLD_BLOCK | 14.85 | 0.45 | Neuer Startkurs und Rezeptkostengrenze |
| GOLDEN_APPLE | 13.88 | 1.15 | Neuer Startkurs und Rezeptkostengrenze |
| IRON_BLOCK | 9.90 | 0.56 | Neuer Startkurs und Rezeptkostengrenze |
| IRON_TRAPDOOR | 4.40 | 0.24 | Neuer Startkurs und Rezeptkostengrenze |
| WAXED_COPPER_BLOCK | 5.45 | 1.95 | Neuer Startkurs und Rezeptkostengrenze |
| EMERALD | 3.75 | 0.45 | Neuer Startkurs und Rezeptkostengrenze |
| COPPER_BLOCK | 3.71 | 0.45 | Neuer Startkurs und Rezeptkostengrenze |
| GOLDEN_SWORD | 3.32 | 0.11 | Neuer Startkurs und Rezeptkostengrenze |

## B2: Untersuchung und lokaler Teilstand (29.10.2026)

Nicht deployt und keine Preisfreigabe. Die Rezeptkostengrenze wird aus theoretischen Mindestkaufpreisen und allen unterstuetzten registrierten Rezepten berechnet, einschliesslich der Zerlegung von Bloecken und Barren. Niedrige Eingangs- oder Rundungspreise propagieren dadurch durch die Rezeptkette; anschliessend wird der Ankauf auf diesen rechnerischen Grenzwert begrenzt und nochmals mit dem dynamischen Faktor multipliziert. Dies erklaert die stark abgesenkten Ankaufswerte; ein Anheben ohne gleichzeitige Pruefung aller Kauf- und Rezeptpfade koennte Arbitrage ermoeglichen.

In `market-before-econB2.tsv` liegen 53 Items mit vorhandenem Kaufpreis und Ankauf unter 5 % dieses Kaufpreises (CP pro Stueck):

| Item | Kauf alt | Ankauf alt |
|---|---:|---:|
| GOLD_BLOCK | 89.10 | 0.45 |
| GOLDEN_APPLE | 83.32 | 1.15 |
| IRON_BLOCK | 59.40 | 0.56 |
| IRON_TRAPDOOR | 26.40 | 0.24 |
| COPPER_BLOCK | 22.27 | 0.45 |
| GOLDEN_SWORD | 19.92 | 0.11 |
| LIGHT_WEIGHTED_PRESSURE_PLATE | 19.80 | 0.10 |
| SMITHING_TABLE | 14.10 | 0.27 |
| IRON_SWORD | 13.32 | 0.14 |
| HEAVY_WEIGHTED_PRESSURE_PLATE | 13.20 | 0.12 |
| IRON_DOOR | 13.20 | 0.12 |
| GOLDEN_DANDELION | 12.15 | 0.49 |
| STICKY_PISTON | 11.25 | 0.42 |
| GOLDEN_SHOVEL | 10.14 | 0.08 |
| COPPER_TRAPDOOR | 9.90 | 0.20 |
| GOLD_INGOT | 9.00 | 0.05 |
| PISTON | 8.43 | 0.37 |
| IRON_CHAIN | 8.21 | 0.07 |
| LODESTONE | 8.05 | 0.14 |
| RAW_COPPER | 7.50 | 0.05 |
| RAW_GOLD | 7.50 | 0.05 |
| RAW_IRON | 7.50 | 0.06 |
| RESIN_BRICK | 7.50 | 0.05 |
| SLIME_BALL | 7.50 | 0.05 |
| LIGHTNING_ROD | 7.42 | 0.15 |
| SOUL_LANTERN | 7.39 | 0.20 |
| IRON_SHOVEL | 6.84 | 0.09 |
| ACTIVATOR_RAIL | 6.81 | 0.09 |
| LANTERN | 6.71 | 0.09 |
| CHISELED_COPPER | 6.12 | 0.11 |
| COPPER_GRATE | 6.12 | 0.11 |
| CUT_COPPER | 6.12 | 0.11 |
| CUT_COPPER_STAIRS | 6.12 | 0.11 |
| IRON_INGOT | 6.00 | 0.06 |
| COPPER_SWORD | 5.07 | 0.11 |
| COPPER_DOOR | 4.95 | 0.10 |
| TRIPWIRE_HOOK | 3.47 | 0.05 |
| COPPER_CHAIN | 3.08 | 0.06 |
| CUT_COPPER_SLAB | 3.06 | 0.05 |
| COPPER_LANTERN | 2.76 | 0.08 |
| COPPER_SHOVEL | 2.72 | 0.08 |
| IRON_BARS | 2.47 | 0.02 |
| COPPER_INGOT | 2.25 | 0.05 |
| RESIN_BRICK_SLAB | 2.25 | 0.10 |
| RESIN_CLUMP | 2.25 | 0.05 |
| GOLD_NUGGET | 1.10 | 0.00 |
| COPPER_BARS | 0.92 | 0.01 |
| IRON_NUGGET | 0.73 | 0.00 |
| COPPER_NUGGET | 0.27 | 0.00 |
| SMOOTH_STONE_SLAB | 0.09 | 0.00 |
| STONE_BRICK_SLAB | 0.08 | 0.00 |
| STONE_SLAB | 0.08 | 0.00 |
| COBBLESTONE_SLAB | 0.04 | 0.00 |

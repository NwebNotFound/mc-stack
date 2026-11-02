# Marktpreise: Kurve, Rezepte, Snapshots

> **Für die Umsetzung:** Required sub-skill: `superpowers:subagent-driven-development` (empfohlen) oder `superpowers:executing-plans`, Aufgabe für Aufgabe. Dieses Dokument ist der Plan, kein Code-Change. Checkboxen (`- [ ]`) gehören zur späteren Umsetzung.

**Ziel:** Der Bankkurs jedes Items hängt an den Rezepten, bewegt sich bei jedem einzelnen Kauf und Verkauf (auch im Stapel) entlang einer Exponentialkurve, bleibt zwischen Item-Unter- und Obergrenze und läuft langsam zum Basispreis zurück. Kauf/Verkauf und jedes Rezept bleiben ohne Gewinn. Die stündlichen Preissnapshots hören auf zu springen.

**Architektur:** Eine Zeile `market_curve` pro Item hält `net` (Käufe minus Verkäufe) und `updated`. Der Mid-Kurs ist `Basis * exp(net / Tiefe)`, geklemmt auf `0.25b` bis `1.2b`. Die Tiefe ist kurz für Massenblöcke und länger für seltene Items. Kauf- und Verkaufscent kommen aus den heutigen Faktoren (`buy-factor` 1.5, `sell-factor` 0.75, `max-sell-ratio` 0.5). Der Rezeptdeckel begrenzt den Verkauf auf die aktuellen Kaufpreise der Zutaten. Lesende Kursabfragen schreiben nichts. Handel, Kontostand, `net` und das Handel-Event liegen in einer Transaktion. Die Historie trennt Angebots-Kurs und Abschluss-Durchschnitt.

**Technik:** CraftCore, MariaDB, JUnit, `prices-base.yml` (Rohwerte in CP), `config.yml` Abschnitt `market`, Rezeptliste `plugins-src/craftcore/src/test/resources/recipes-26.1.tsv` (1741 Zeilen, minecraft-data 26.1).

## Globale Vorgaben

- Rohpreise in `plugins-src/craftcore/src/main/resources/prices-base.yml` bleiben die Quelle. Diese Plan-PR ändert sie nicht.
- `market.base-factor` = 0.5, `market.buy-factor` = 1.5, `market.sell-factor` = 0.75, `market.max-sell-ratio` = 0.5.
- Verkaufsuntergrenze der Kurve ist `lo = 0.25 * b`, Obergrenze `hi = 1.2 * b`, für jedes Item. `market.min-price` / `market.max-price` klemmen die Kurve nicht mehr: Bruchstein und Stein stehen dort auf einem Punkt (`0.05` bzw. `0.10`) und würden sonst fest bleiben.
- Halbwertszeit ist konfigurierbar und hängt an derselben Grenze wie `D`: `b < 1` nutzt `market.curve.half-life-cheap-hours` (Default 12), alles andere `market.curve.half-life-hours` (Default 72). Beide Schlüssel sind Stunden, beide müssen endlich und positiv sein.
- Tiefe hängt an der Quote-Basis, nicht an einem globalen `D = 64000/3`. Das alte `D` bleibt nur als Vergleich in den Tabellen. Normativ: `b < 1` → `D = 128` (zwei Stacks), `1 ≤ b < 50` → `D = 250`, `b ≥ 50` → `D = 400`. Ein Trade bewegt den Mid um `exp(1/D) - 1`: 0.778 % bei Massenblöcken, 0.400 % in der Mitte, 0.250 % bei Diamant und Netherit.
- Ein Trade nimmt höchstens 2304 Stück an (`36 * 64`, ein Inventar ohne Shulker-Inhalt). Die Schleife läuft im Speicher, nicht als SQL pro Stück.
- 1 CP = 100 Cent. Auszahlung und Abbuchung in ganzen Cent, Summe über die Stückkurve, nicht ein Kurs mal Menge.
- `market.unsellable` (darunter Bruchstein, Erde, Sand) bleibt. Die Szenarien rechnen den Ankauf trotzdem, damit die Kurve auch ohne diese Sperre keine Diamant- oder Netheritausrüstung finanziert.
- Kein Gewinn heißt Erlös ≤ Kosten. Gleichstand ist kein Gewinn.

---

## 1. Ist-Zustand

Quote-Basis in CP, `Market.quote`:

```text
b = max(Rohwert * 0.5, market.valuable-base.<ITEM> oder 0)
```

Beispiele aus `prices-base.yml` und `config.yml`:

| Item | Rohwert CP | Quote-Basis b | min-price | max-price |
| --- | ---: | ---: | ---: | ---: |
| COBBLESTONE | 0.10 | 0.05 | 0.05 | 0.05 |
| DIRT | 0.10 | 0.05 | — (0.04) | — (0.06) |
| SAND | 0.20 | 0.10 | 0.08 | 0.12 |
| DIAMOND | 400 | 200 | 160 | 240 |
| DIAMOND_CHESTPLATE | 3200 | 1600 | — (1280) | — (1920) |
| NETHERITE_INGOT | 8000 | 4000 | 3200 | 4800 |
| NETHERITE_CHESTPLATE | 14000.10 | 7000.05 | — (5600.04) | — (8400.06) |
| NETHERITE_UPGRADE_SMITHING_TEMPLATE | 2800.10 | 1400.05 | — (1120.04) | — (1680.06) |

`valuable-base` hebt Diamant (200) und Netheritbarren (4000) auf den Wert, den `Rohwert * 0.5` ohnehin ergibt. Netheritbrust hat kein `valuable-base`.

Bankcent bei aktuellem Mid `m` (CP), `Market.cents` plus `PriceModel.cappedSell` mit Ratio 0.5:

```text
Kauf(m) = max(1, floor(m * 100 * 1.5))
Rohverkauf(m) = min( floor(m * 100 * 0.75), cappedSell(Kauf(m)) )
cappedSell: bp = 5000; min(Verkauf, (Kauf / 10000) * bp + (Kauf % 10000) * bp / 10000)
```

Bei diesen Faktoren ist `0.5 * Kauf = floor(m * 75)` für die Beispiele unten, der normative Weg bleibt `cappedSell`.

Heute multipliziert `DynamicPrice` nicht den Mid, sondern die **festen** Basiscent mit einem Faktor in [0.8, 1.2]. Der Faktor ist `1 + pressure`. Pro Aufruf von `DynamicPrice.trade` ändert sich `pressure` um `0.003 * sold / 64`. `Market.price` übergibt `sold = +1` beim Ankauf und `sold = -1` beim Bankkauf, unabhängig von der Stückzahl. `DynamicPrice.move` ignoriert `stackSize`. Ein Stapel und ein Einzelstück schieben den Kurs gleich weit. `WebsiteStats.event` speichert dagegen die volle Menge zum **einen** Preis vor der Bewegung.

`RecipeCost.derive` legt auf den Ankauf einen Deckel: Startwert je kaufbarem Item ist der Kauf an der dynamischen Untergrenze, Rezepte relaxieren `Summe / Menge`, `Market.safeSell` nimmt `min(Kurscents, floor(Rezeptkosten))`. Items in `market.unbuyable` (Diamant, Netherit, Ancient Debris, Vorlage, …) haben keine endlichen Rezeptkosten. Bruchstein, Erde und Sand bleiben unter ihrem Deckel (Deckel 6, 6 und 12 Cent, Kurvenverkauf höchstens 3, 4 und 9 Cent).

`prices-base.yml` erfüllt schon `RecipeArbitrageTest.noProductBelowItsIngredients`: das Produkt liegt mindestens auf der Summe des günstigsten normalen Rezepts (Schmelzen mit Faktor 1.3 auf den Rohwerten). Netheritbrust ist exakt die Summe:

```text
1600 + 4000 + 1400.05 = 7000.05 CP
DIAMOND_CHESTPLATE + NETHERITE_INGOT + NETHERITE_UPGRADE_SMITHING_TEMPLATE
```

## 2. Ursache der oszillierenden Snapshots

`market_price_history.(item, hour_ts).avg_price` hat zwei Schreiber, und die vergleichen unterschiedliche Preise.

1. Nur Survival, `Market.start`, Delay 1 Tick, Periode 72000 Ticks: für jedes Item `price(connection, item, false, false)`, danach `WebsiteStats.snapshot`. Lobby macht denselben `price`-Lauf ohne Snapshot (`Market.java`, Zweig `else`).
2. `price(..., trade=false)` ruft `DynamicPrice.current` und **immer** `WebsiteStats.catalog`. `current` gibt `factor(decay(gespeicherter Druck, now - updated))` zurück und schreibt den abgeklungenen Druck nicht zurück. `updated` bleibt auf dem letzten Handel. Jeder Lauf legt deshalb einen neuen, näher am Basispreis liegenden Verkauf in `market_catalog`.
3. `WebsiteStats.snapshot` schreibt diesen `sell_price` in die aktuelle Stunde `floor(epochSeconds / 3600) * 3600`. Das `ON DUPLICATE KEY UPDATE` überschreibt `avg_price`, `min_price` und `max_price`, solange `volume_qty = 0`. Eine Stunde ohne Abschlüsse wird bei jedem Lauf (Neustart, zweiter Lauf in derselben Stunde) mit einem weiter abgeklungenen Katalogpreis ersetzt. `min` und `max` fallen dabei auf den einen Kurs zusammen.
4. `WebsiteStats.start` läuft auf **jedem** Backend, Delay 20 Ticks, Periode 72000: `maintain` setzt für alle Stunden mit `market_events` der letzten 90 Tage **bedingungslos** `avg_price = SUM(total_cp) / SUM(qty)`, dazu `min`, `max` und `volume_qty`. Lobby und Survival tun das auf eigenen Zeitplänen.
5. Der Abschluss speichert den Preis **vor** der Bewegung. `sellUnit` und `sellStack` lesen `price(..., false)`, buchen danach `price(..., true)` und rufen erst dann `WebsiteStats.event` mit den vorher gelesenen Cent auf. Der Katalog danach ist der Preis nach der Bewegung plus späterer Abkling-Lesung. Snapshot kopiert den Nachher-Kurs, `maintain` den Vorher-Durchschnitt, beide in `avg_price`.

`v_market.change_24h_pct` (`CHANGE_24H_SQL`) rechnet `(catalog.sell_price - history.avg_price) / history.avg_price`. Die Referenzstunde ist die jüngste Zeile mit `hour_ts <= now - 86400`. Stille Stunden tragen den abgeklungenen Angebotskurs, Stunden mit Handel den Abschlussdurchschnitt. Rutscht das 24-Stunden-Fenster von einer stillen Stunde auf eine Handelsstunde, springt die Prozentzahl. In einer offenen stillen Stunde wandert der Punkt zusätzlich, weil `volume_qty` 0 bleibt und der Snapshot den Katalog neu überschreibt.

Das `WHERE 1=1` in `SNAPSHOT_SQL` ist ein liegen gebliebener Filter und nicht die Ursache. Die Ursache ist der eine Spaltenname für zwei Messungen, plus Katalogschreiben aus einer Lesung, deren Abklingzeit nicht persistiert wird.

## 3. Formel

Zustand pro Item, Tabelle `market_curve`:

```text
net      BIGINT   -- Bankverkäufe an Spieler minus Ankäufe von Spielern
updated  BIGINT   -- Millisekunden der letzten Persistenz
```

`net` steigt, wenn ein Spieler von der Bank kauft, und fällt, wenn er an die Bank verkauft.

Abklingzeit einmal pro Transaktion, nicht pro Stück, und nur schreibend in der Handels- oder Snapshot-Transaktion:

```text
Δt_ms = max(0, now - updated)
H_h   = half-life-cheap-hours    wenn b < 1, sonst half-life-hours
net   = net * 2^(-Δt_ms / (H_h * 3600 * 1000))
```

Defaults: 12 Stunden unter 1 CP, 72 Stunden sonst. Ein Dump und die anschließende Erholung stehen weiter unten; die Kurve nach N Trades ohne Zeitverzug hängt nicht von `H_h` ab.

Mid in CP. `D` nach Quote-Basis:

```text
D(b) = 128   wenn b < 1          -- Massenblöcke, zwei Stacks bis zur e-Faltung
D(b) = 250   wenn 1 ≤ b < 50
D(b) = 400   wenn b ≥ 50         -- Diamant, Netherit
lo   = 0.25 * b
hi   = 1.2 * b
m(net) = min(hi, max(lo, b * exp(net / D(b))))
```

`market.min-price` und `market.max-price` gelten für diese Kurve nicht. Bruchstein (`min = max = 0.05`) und Stein (`0.10`) wären sonst fest; Sand wäre bei `0.08`–`0.12` eingeklemmt und käme nie auf `0.25b`.

Stückpreise in Cent. Liegen Kauf und Verkauf nach dem Runden gleichauf, ist der Kauf mindestens ein Cent darüber (`BankPrice.minimumBuy` verlangt heute schon `sell + 1`):

```text
Kauf0(m) = max(1, floor(m * 100 * 1.5))
Rohverkauf(m) = max(1, min(floor(m * 100 * 0.75), cappedSell(Kauf0(m), 0.5)))
Kauf(m) = max(Kauf0(m), Rohverkauf(m) + 1)
Verkauf(m) = min(Rohverkauf(m), Rezeptdeckel)
```

`cappedSell` bleibt `bp = 5000`. `Rezeptdeckel` ist das Minimum über die ausführbaren Rezepte von `floor(Summe der aktuellen Kaufpreise der günstigsten kaufbaren Zutat je Slot / Ausgabemenge)`. Zutatenzeilen werden in derselben Transaktion gesperrt, Reihenfolge der Item-Namen. Ein statischer Deckel auf `Kauf(lo)` würde den Basisverkauf von 779 Rezeptprodukten unter den heutigen Kurs drücken (Stein von 7 auf 2 Cent, weil Bruchstein an `lo` nur noch 2 Cent kostet), obwohl am Basis-`net` kein Gewinn entsteht. Deshalb liest der Deckel den **aktuellen** Kauf. An der Ecke (jede Zutat bei `lo`, Produkt bei `hi`) fällt er auf den Floor-Kauf zurück; dort hält die Ungleichung.

Massenverkauf von `q` Stück, `1 ≤ q ≤ 2304`, Start-`net` nach der Abklingzeit, Summe im Speicher, ein `UPDATE`:

```text
Erlös = Σ_{i=0}^{q-1} Verkauf( m(net - i) )
net  := net - q
```

Massenkauf analog mit `Kauf` und `net := net + q`. `q > 2304` lehnt der Trade ab, ohne `net` zu ändern. 2304 ist ein Spielerinventar (36 Slots à 64). Shulker-Inhalte verkauft das Verkaufsmenü nicht mit. Die 10.000er-Tabellen sind aufeinanderfolgende Trades ohne Zeitverzug, nicht eine Transaktion: `10000 = 4 * 2304 + 784`, die Summe ist dieselbe.

Der Spieler bekommt oder zahlt den Kurs vor dem Schritt dieses Stücks. Die Summe ist die Summe der Einzeltrades.

### Warum `D = 64000/3` zu träge ist

Das ist `64 / 0.003` und trifft die heutige Zeile „0,3 % je Stack“: ein Stück bewegt den Mid um `exp(3/64000) - 1 = 0.004687 %`, 64 Stück um 0.300 %. Bruchstein ist zusätzlich fest (`lo = hi`), der Mid bewegt sich gar nicht. Ein Diamant-Verkauf ändert den Ankauf von 15000 auf 14999 Cent. Das ist ein Cent auf 150 CP, kein sichtbarer Schritt. `D = 64` (ein Stack) läge bei 1.55 % pro Stück und wäre für Massenblöcke nur eine Rampe von etwa 89 Verkäufen bis zum Floor `0.25b`; danach klebt der Kurs. `D = 128` ist das langsame Ende von „ein bis zwei Stacks“: ein Stück 0.778 % (knapp über dem Beispiel 0.5 %, der Mid ist bei 3-Cent-Items das sichtbare Signal), 64 Stück 39.35 %, der Floor kommt erst nach `128 * ln(4) = 177.4` Verkäufen. Seltene Items mit `D = 400` liegen bei 0.250 % pro Stück, also in der Spanne 0.1–0.5 %, und 64 Stück sind 14.79 % abwärts bzw. 17.35 % aufwärts.

„Kurs nach N“ ist der Quote bei `net = ±N`, also der Preis des nächsten Stücks. Start ist immer `net = 0`.

### Vergleich: altes `D = 64000/3`, altes Band

Bruchstein fest bei `b`. Erde `0.8b`–`1.2b` ohne Config-Pin wäre `0.04`–`0.06`, Sand `0.08`–`0.12`, Diamant `160`–`240`, Netheritbarren `3200`–`4800`. Kein Rezeptdeckel in diesen Vergleichszahlen (die fünf Items haben keinen, der den Basisverkauf senkt).

Massenblöcke, altes Modell:

| Item | Seite | nach 1 | nach 64 | nach 640 | nach 10.000 |
| --- | --- | --- | --- | --- | --- |
| Bruchstein | Verkauf | 0.050000 CP, Kauf 7 / Verkauf 3, ±0 % | unverändert | unverändert | unverändert |
| Bruchstein | Kauf | unverändert | unverändert | unverändert | unverändert |
| Erde | Verkauf | 0.049998 CP, 7 / 3, −0.0047 % | 0.049850, 7 / 3, −0.300 % | 0.048522, 7 / 3, −2.96 % | 0.040000, 6 / 3, −20 % |
| Erde | Kauf | 0.050002, 7 / 3, +0.0047 % | 0.050150, 7 / 3, +0.300 % | 0.051523, 7 / 3, +3.05 % | 0.060000, 9 / 4, +20 % |
| Sand | Verkauf | 0.099995, 14 / 7, −0.0047 % | 0.099700, 14 / 7, −0.300 % | 0.097045, 14 / 7, −2.96 % | 0.080000, 12 / 6, −20 % |
| Sand | Kauf | 0.100005, 15 / 7, +0.0047 % | 0.100300, 15 / 7, +0.300 % | 0.103045, 15 / 7, +3.05 % | 0.120000, 18 / 9, +20 % |

Teure Items, altes Modell:

| Item | Seite | nach 1 | nach 64 | nach 640 | nach 10.000 |
| --- | --- | --- | --- | --- | --- |
| Diamant | Verkauf | 199.990625 CP, 29998 / 14999, −0.0047 % | 199.400899, 29910 / 14955, −0.300 % | 194.089107, 29113 / 14556, −2.96 % | 160, 24000 / 12000, −20 % |
| Diamant | Kauf | 200.009375, 30001 / 15000, +0.0047 % | 200.600901, 30090 / 15045, +0.300 % | 206.090907, 30913 / 15456, +3.05 % | 240, 36000 / 18000, +20 % |
| Netheritbarren | Verkauf | 3999.812504, 599971 / 299985, −0.0047 % | 3988.017982, 598202 / 299101, −0.300 % | 3881.782134, 582267 / 291133, −2.96 % | 3200, 480000 / 240000, −20 % |
| Netheritbarren | Kauf | 4000.187504, 600028 / 300014, +0.0047 % | 4012.018018, 601802 / 300901, +0.300 % | 4121.818136, 618272 / 309136, +3.05 % | 4800, 720000 / 360000, +20 % |

Kauf und Verkauf in der Zelle sind Cent. Der erste Diamant-Verkauf zahlt noch 15000 Cent; danach steht der Quote auf 14999.

### Neue Kurve

Bruchstein, `b = 0.05`, `D = 128`, `lo = 0.0125`, `hi = 0.06`. Basisquote Kauf 7 / Verkauf 3 Cent. Ein Verkauf setzt den Mid auf `0.05 * exp(-1/128) = 0.049611` (−0.778 %). Der Centpreis bleibt 7 / 3, weil das Centgitter gröber ist als der Schritt. Der Verkauf fällt bei Stück 29 (Index 29, Mid 0.039863) auf 2 Cent und bei Stück 81 auf 1 Cent. An `lo` ist `floor(0.0125 * 75) = 0`, der Mindestverkauf ist 1 Cent, der Kauf wird 2 Cent.

Diamant, `b = 200`, `D = 400`, `lo = 50`, `hi = 240`:

```text
m(0) = 200, Kauf 30000, Verkauf 15000
m(-1) = 200 * exp(-1/400) = 199.500624, Kauf 29925, Verkauf 14962
m(+1) = 200.500626, Kauf 30075, Verkauf 15037
```

Der erste Verkauf zahlt 15000 Cent und hinterlässt 14962. Das sind 38 Cent auf 150 CP, nicht ein Cent.

Netheritbarren, `b = 4000`, `D = 400`, `lo = 1000`, `hi = 4800`. Erster Verkauf 300000 Cent, Quote danach 299250 Cent (Mid 3990.012490). Floor nach `400 * ln(4) = 554.52` Verkäufen, Verkauf dann 75000 Cent = 750 CP.

Erholung vom Floor, nur für Items mit 72 Stunden: `net` halbiert sich in einer Halbwertszeit, der Mid ist dann `b * sqrt(0.25) = 0.5 b`. Diamant: nach 72 Stunden Mid 100 CP, Verkauf 7500 Cent, Kauf 15000 Cent. Dieselbe Halbierung bei Bruchstein dauert 12 Stunden und landet auf Mid `0.025` CP (Kauf 3 / Verkauf 1), nicht auf dem Basiscent.

### Zwei Stacks verkauft, dann Erholung

Zwei Stacks sind 128 Stück, bei Massenblöcken genau ein `D`. Unmittelbar danach ist `net = -128` und `m = b / e` (−63.21 %), noch über `lo`. „Wieder da“ heißt hier: der Verkaufscent ist wieder der Basiscent. Der Mid liegt dann noch spürbar unter `b`; 1 % Abstand ist die zweite Spalte.

Bruchstein und Erde, `b = 0.05`, Halbwertszeit 12 h. Basisquote 7 / 3 Cent. Direkt nach dem Dump: Mid 0.018394 CP, Kauf 2 / Verkauf 1.

| Zeit | net | Mid CP | Kauf / Verkauf Cent |
| --- | ---: | ---: | --- |
| 0 | −128 | 0.018394 | 2 / 1 |
| 6 h | −90.510 | 0.024653 | 3 / 1 |
| 12 h | −64 | 0.030327 | 4 / 2 |
| 24 h | −32 | 0.038940 | 5 / 2 |
| 26.0 h | −28.562 | 0.040000 | 6 / 3 |
| 46.3 h | −8.831 | 0.046667 | 7 / 3 |
| 79.6 h | −1.286 | 0.049500 | 7 / 3 |

26.0 Stunden, bis der Verkauf wieder 3 Cent ist (bei der alten Halbwertszeit 72 h wären das 155.8 Stunden). 46.3 Stunden, bis auch der Kauf wieder 7 Cent ist. 79.6 Stunden, bis der Mid höchstens 1 % unter `b` liegt (bei 72 h: 477.8 Stunden).

Sand, gleiche `net`-Kurve und dieselbe 12-Stunden-Halbwertszeit, `b = 0.10`, Basis 15 / 7. Direkt nach dem Dump: Mid 0.036788 CP, Kauf 5 / Verkauf 2. Nach 12 h: Mid 0.060653, Kauf 9 / Verkauf 4. Der Verkaufscent ist nach 46.3 Stunden wieder 7, der Mid nach 79.6 Stunden innerhalb 1 %. Die Zeiten stimmen mit Bruchstein überein, sobald der Ziel-Mid derselbe Bruchteil von `b` ist; die Centgrenzen liegen bei Sand höher, deshalb dauert der Weg zurück auf den Basis-Verkaufscent länger als die 26.0 Stunden des Bruchsteins.

Dieselben 128 Stück Diamant (`D = 400`, Halbwertszeit 72 h) setzen den Mid auf 145.230 CP (−27.39 %, Verkauf 10892 Cent). Nach 72 h: Mid 170.429 CP, Verkauf 12782 Cent. Bis auf 1 % an `b`: 359.5 Stunden. Der letzte Cent vor 15000 CP Verkauf ist kein sinnvolles Ziel, `floor(m * 75) = 15000` gilt erst bei `m = 200`.

### Massenblöcke, neue Kurve

`D = 128`, `lo = 0.25b`, `hi = 1.2b`. Zellen: Mid in CP, Kaufcent / Verkaufscent, Änderung des Mids gegen `b`.

| Item | Seite | nach 1 | nach 64 | nach 640 | nach 10.000 |
| --- | --- | --- | --- | --- | --- |
| Bruchstein | Verkauf | 0.049611, 7 / 3, −0.778 % | 0.030327, 4 / 2, −39.35 % | 0.0125, 2 / 1, −75 % | 0.0125, 2 / 1, −75 % |
| Bruchstein | Kauf | 0.050392, 7 / 3, +0.784 % | 0.060000, 9 / 4, +20 % | 0.060000, 9 / 4, +20 % | 0.060000, 9 / 4, +20 % |
| Erde | Verkauf | 0.049611, 7 / 3, −0.778 % | 0.030327, 4 / 2, −39.35 % | 0.0125, 2 / 1, −75 % | 0.0125, 2 / 1, −75 % |
| Erde | Kauf | 0.050392, 7 / 3, +0.784 % | 0.060000, 9 / 4, +20 % | 0.060000, 9 / 4, +20 % | 0.060000, 9 / 4, +20 % |
| Sand | Verkauf | 0.099222, 14 / 7, −0.778 % | 0.060653, 9 / 4, −39.35 % | 0.025, 3 / 1, −75 % | 0.025, 3 / 1, −75 % |
| Sand | Kauf | 0.100784, 15 / 7, +0.784 % | 0.120000, 18 / 9, +20 % | 0.120000, 18 / 9, +20 % | 0.120000, 18 / 9, +20 % |

64 Käufe von Bruchstein oder Erde liegen schon an der Decke (`128 * ln(1.2) = 23.35` Käufe). Der Floor stoppt den Verkauf ab Stück 178. Sand verliert beim ersten Verkauf einen Kaufcent (15 auf 14), der Verkaufscent bleibt 7 bis der Mid die nächste Grenze kreuzt.

### Diamant und Netheritbarren, neue Kurve

`D = 400`, `lo = 0.25b`, `hi = 1.2b`.

| Item | Seite | nach 1 | nach 64 | nach 640 | nach 10.000 |
| --- | --- | --- | --- | --- | --- |
| Diamant | Verkauf | 199.500624, 29925 / 14962, −0.250 % | 170.428758, 25564 / 12782, −14.79 % | 50, 7500 / 3750, −75 % | 50, 7500 / 3750, −75 % |
| Diamant | Kauf | 200.500626, 30075 / 15037, +0.250 % | 234.702174, 35205 / 17602, +17.35 % | 240, 36000 / 18000, +20 % | 240, 36000 / 18000, +20 % |
| Netheritbarren | Verkauf | 3990.012490, 598501 / 299250, −0.250 % | 3408.575156, 511286 / 255643, −14.79 % | 1000, 150000 / 75000, −75 % | 1000, 150000 / 75000, −75 % |
| Netheritbarren | Kauf | 4010.012510, 601501 / 300750, +0.250 % | 4694.043484, 704106 / 352053, +17.35 % | 4800, 720000 / 360000, +20 % | 4800, 720000 / 360000, +20 % |

64 Diamant-Käufe bleiben unter der Decke (234.70 < 240). 640 Verkäufe liegen auf dem Floor.


## 4. Kein Gewinn über Rezepte und über Kauf/Verkauf

**Basiswerte.** Ein normales Rezept erfüllt weiter `Rezeptmenge * Rohwert(Produkt) >= Summe der günstigsten Zutaten` (Schmelzen `* 1.3` auf den Rohwerten). Das ist der bestehende `RecipeArbitrageTest.noProductBelowItsIngredients`. Netheritbrust ist der Gleichstand 7000.05 CP.

**Bankschleife am selben Item.** Verkauf von `q` Stück und anschließender Rückkauf derselben `q` Stück, ohne Zeitverzug:

```text
Σ Verkauf(m(n - i))  <  Σ Kauf(m(n - q + i))     für i = 0..q-1
```

Der Spread und die Gegenbewegung tragen das. Gerechnete Fälle mit dem neuen `D` und `lo = 0.25b`, Start `net = 0`:

| Trade | Erlös Cent | Rückkauf Cent | Differenz |
| --- | ---: | ---: | ---: |
| 1 Diamant | 15000 | 29925 | −14925 |
| 64 Diamanten | 888212 | 1772024 | −883812 |
| 64 Netheritbarren | 17764901 | 35441120 | −17676219 |
| 10000 Erde | 10110 | 20284 | −10174 |
| 10000 Sand | 10459 | 30812 | −20353 |
| 10000 Bruchstein | 10110 | 20284 | −10174 |

**Rezeptschleife an den Ecken.** Der ausführbare Fall: Produkt an `hi` verkaufen, jede Zutat an `lo` kaufen, jeder Slot hat eine kaufbare Zutat, das Produkt ist nicht `unsellable`, keine Selbstkopie. Das Band `0.25` gegen `1.2` ist `4.8` fach; mal dem Spread `0.5` bleibt ein Faktor `2.4` über einer knappen Rezeptmarge. Ohne Deckel ist die Kontrolle nicht leer. Konkrete Zeile: `RAW_GOLD` x9 aus `RAW_GOLD_BLOCK`, Erlös 7281 Cent gegen Kauf des Blocks an `lo` 3037 Cent. Mit Deckel `floor(Summe Kauf(lo) / Menge)` hat dieselbe Schleife 1652 Rezepte und 0 Verletzungen. Der Test ruft `Verkauf` auf, nicht eine abgeschriebenen Ungleichung.

Der Deckel im Betrieb liest die **aktuellen** Kaufpreise, nicht einen eingefrorenen Floor. Bei `net = 0` liegt er damit auf dem Basis-Kauf. Der heutige `safeSell`-Saatwert `Kauf` an `0.8b` (Bambustür 27 Cent bei Rohverkauf 135) ist nicht der neue Vertrag: der würde am Start den Basisverkauf von Hunderten Items senken, obwohl Basis gegen Basis schon ohne diesen Saatwert hält. Sobald eine Zutat Richtung `lo` läuft, sinkt der Deckel mit.

Selbstkopien bleiben außen vor. Umfärben und Zerlegen laufen mit, sobald jeder Slot eine kaufbare Option hat.

Stein aus Bruchstein, am Start: Verkauf Stein 7 Cent, Kauf Bruchstein 7 Cent, kein Gewinn. An der Ecke kostet Bruchstein 2 Cent, der Deckel zieht den Steinverkauf auf 2 Cent. Der Rohwert-Faktor 1.3 überlebt das nicht als Bankmarge; die Kette bleibt ohne Gewinn.

Netheritbrust, wenn die Bank die Teile verkaufte. Am Basis-Mid unverändert:

```text
1600 + 4000 + 1400.05 = 7000.05
Kauf der Teile = 240000 + 600000 + 210007 = 1050007 Cent
Verkauf der Brust = 525003 Cent
Marge 525004 Cent
```

An der neuen Ecke (`lo = 0.25b`, Brust an `hi`) wäre der Kauf der Teile `60000 + 150000 + 52501 = 262501` Cent und der Verkauf der Brust 630004 Cent. Das ist ein Gewinn von 367503 Cent. Die drei Zutaten stehen in `unbuyable`, die Schleife ist nicht ausführbar. Der Test stellt genau das fest: Diamantbrust, Netheritbarren und Vorlage bleiben unkaufbar. Wer eine davon freigibt, muss den Deckel für die Schmiederezepte mitziehen, bevor der Floor `0.25b` gilt.

## 5. Atomare Updates

`CraftCore.tx` hält bereits `GET_LOCK('craftcore-write', 5)`. Das bleibt die äußere Serialisierung von Kontostand und Markt.

In derselben Transaktion, für einen Handel von `q` Stück. `q` außerhalb `1..2304` bricht vor dem ersten Schreiben ab.

1. `INSERT IGNORE INTO market_curve(item, net, updated) VALUES(?, 0, ?)`, danach `SELECT net, updated FROM market_curve WHERE item = ? FOR UPDATE`. Zwei gleichzeitige Erst-Trades treffen so den Primary Key nicht: einer legt die Zeile an, der andere ignoriert den Insert und wartet auf der Sperre. Ein vorheriges `SELECT` plus `INSERT` ohne `IGNORE` wirft die zweite Transaktion auf den PK.
2. `net` um die Halbwertszeit falten, `updated = now`.
3. Cent-Summe in Speicher über die `q` Schritte rechnen. Kein SQL pro Stück. 2304 Schritte bleiben in derselben Transaktion; 10.000 Stück sind mehrere Trades.
4. Kontostand, Tageslimit, `bank_journal` wie heute.
5. `UPDATE market_curve SET net = ?, updated = ?`.
6. `market_catalog` mit Kauf und Verkauf **am neuen** Mid überschreiben.
7. Ein `market_events`-Satz: `qty = q`, `total_cp` = Summe / 100, `unit_price` = Summe / Menge / 100, plus die Min- und Max-Stückcent des Pfads (neue Spalten `min_unit_cp`, `max_unit_cp`, damit `maintain` nicht wieder einen einzelnen Vorher-Preis als Spanne missversteht).

Rollback verwirft `net`, Kontostand, Journal und Event zusammen. Eine Kurslesung für die GUI ruft dieselbe Mid-Rechnung **ohne** Schritte 2, 5, 6 und 7 auf. Sie darf den Katalog nicht anfassen. Das ist der Unterschied zu `Market.price(..., false)`, das heute bei jedem GUI-Aufruf `catalog` schreibt.

## 6. Snapshot-Reparatur

Neue Spalte `quote_price DECIMAL(20,4) NULL` auf `market_price_history`. `avg_price`, `min_price`, `max_price`, `volume_qty` bleiben die Abschlussaggregation.

- Snapshot schreibt nur `quote_price`. `INSERT` der Stunde, `ON DUPLICATE KEY UPDATE quote_price = quote_price` (erster Wert der Stunde bleibt). Kein Überschreiben, auch nicht bei `volume_qty = 0`.
- `maintain` schreibt nur `avg_price`, `min_price`, `max_price`, `volume_qty` aus `market_events`. Es setzt `quote_price` nicht an.
- `change_24h_pct` vergleicht `market_catalog.sell_price` mit `quote_price` der Referenzstunde, nicht mit `avg_price`. Stunden ohne Quote bleiben `NULL` (wie der Stichtag heute fehlende Kurse ausblendet).
- Nur ein Backend persistiert die stündliche Abklingzeit und den Quote. `GET_LOCK('craftcore-market-snapshot', 0)`; wer den Lock nicht bekommt, lässt den Lauf aus. Damit falten Lobby und Survival `net` nicht zweimal und schreiben nicht zwei Katalogstände.
- Vor dem Quote-Insert faltet der Lock-Inhaber `net`, setzt `updated` und schreibt den Katalog aus dem **persistierten** Mid. Anzeige-Abklingzeit ohne Schreiben gibt es weiterhin für die GUI, sie landet nicht in der Historie.

Bestehende Zeilen: wo `volume_qty = 0`, `quote_price = avg_price` einmalig setzen. Wo `volume_qty > 0`, `quote_price` leer lassen, bis der nächste Quote-Lauf eine Stunde füllt. `avg_price` von Handelsstunden nicht in den Quote kopieren.

## 7. Szenario: 10.000 Bruchstein, Erde, Sand

Aufeinanderfolgende Verkäufe ohne Zeitverzug, nicht ein SQL-Lauf. „Von der Basis“ startet bei `net = 0`. „Von der Decke“ startet bei `net = D * ln(1.2)`, also `m = hi`. Vergleich gegen den Bankkauf der Ausrüstung an `lo = 0.25b`. Diesen Kauf bietet die Bank für Diamant und Netherit nicht an (`unbuyable`); es ist die billigste Formelgrenze.

Ausrüstung, Kauf an `lo`, neue Untergrenze. In Klammern der alte Kauf an `0.8b` bzw. am Config-Floor:

| Set | Cent neu | CP neu | CP alt |
| --- | ---: | ---: | ---: |
| Diamantrüstung | 180000 | 1800.00 | 5760.00 |
| Diamantausrüstung | 247517 | 2475.17 | 7920.63 |
| Netheritrüstung | 990004 | 9900.04 | 31680.24 |
| Netheritausrüstung | 1867529 | 18675.29 | 59761.11 |

Einzelteile an `lo`, Kauf neu: Diamanthelm 37500, Brust 60000, Hose 52500, Stiefel 30000 Cent. Netherithelm 240001, Brust 262501, Hose 255001, Stiefel 232501 Cent.

Erlös der 10.000 Stück, neue Kurve, und der alte Erlös daneben:

| Start | Bruchstein neu | Erde neu | Sand neu | alt (Bruchstein / Erde / Sand) |
| --- | ---: | ---: | ---: | --- |
| Basis, Kurve | 10110 Cent = 101.10 CP | 10110 Cent = 101.10 CP | 10459 Cent = 104.59 CP | 300.00 / 300.00 / 614.72 CP |
| Decke, Kurve | 10172 Cent = 101.72 CP | 10172 Cent = 101.72 CP | 10616 Cent = 106.16 CP | 300.00 / 325.13 / 678.76 CP |
| Jedes Stück an der Decke, Kurve ignoriert | 400.00 CP (4 Cent) | 400.00 CP (4 Cent) | 900.00 CP (9 Cent) | 300 / 400 / 900 CP |

Der Decken-Cent von Bruchstein ist jetzt 4, nicht 3, weil `hi = 1.2 * 0.05 = 0.06` und `floor(0.06 * 75) = 4`. Die Kurve von der Decke zahlt trotzdem nur 101.72 CP: nach `128 * ln(1.2) = 23.35` Verkäufen ist der +20-%-Rücken weg, nach 177 Verkäufen klebt der Verkauf auf 1 Cent.

101.72 CP sind 5.65 % der Diamantrüstung an `lo` (1800 CP) und 1.03 % der Netheritrüstung (9900.04 CP). Sand von der Decke, 106.16 CP, sind 5.90 % bzw. 1.07 %. Selbst jedes Sandstück eingefroren zum Decken-Cent, 900 CP, ist die Hälfte der Diamantrüstung: `180000 / 9 = 20000` Sand, nicht 10000. Bruchstein an der Decke bräuchte `180000 / 4 = 45000` Stück. 10000 reichen nicht.

Der Rezeptdeckel senkt Bruchstein, Erde und Sand nicht (kein billigeres ausführbares Rezept). `unsellable` setzt den realen Erlös heute auf 0. Die Tabelle ist die Kurve ohne diese Sperre.

## 8. Netherit-Simulation

Barren, `D = 400`, `lo = 1000`, `hi = 4800`, Start `net = 0`. Kein Rezeptdeckel. Die Zeile ist das Stück mit dieser Nummer (Preis vor dem Schritt) und die Summe bis dahin. Daneben die alte Summe bei `D = 64000/3` und Floor `0.8b` (Verkauf am Ende 2400 CP, Summe nach 10.000 Stück 25375326.60 CP).

| Stück Nr. | Mid CP | Verkauf dieses Stücks | Summe neu | Summe alt |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 4000.000000 | 300000 Cent = 3000 CP | 3000.00 CP | 3000.00 CP |
| 2 | 3990.012490 | 299250 Cent | 5992.50 CP | 5999.85 CP |
| 64 | 3417.107254 | 256283 Cent | 177649.01 CP | 191716.44 CP |
| 640 | 1000 | 75000 Cent = 750 CP | 965234.67 CP | — |
| 1000 | 1000 | 75000 Cent | 1235234.67 CP | 2930837.25 CP |
| 10000 | 1000 | 75000 Cent | 7985234.67 CP | 25375326.60 CP |

Ab Stück 555 klebt der Mid auf 1000 CP und der Verkauf auf 750 CP. Der alte Floor 3200 CP / 2400 CP Verkauf kam erst bei Stück 4761; deshalb war die Summe 25,4 Millionen CP. Der neue Floor schneidet den Schwanz auf 750 CP. 7,99 Millionen CP sind der Integralwert der Kurve, nicht eine Auszahlung.

`Market.revenueLimit` gibt heute fest 150000 Cent zurück. Das sind 1500 CP und trifft den Wert von `market.daily-revenue-limit: 1500.0`, die Methode liest den Schlüssel aber nicht. Die Umsetzungs-PR liest diesen Schlüssel (CP, `Money.cents`, Default 1500, wenn der Eintrag fehlt oder nicht endlich positiv ist). Die folgende Grenze ist der heutige Config-Wert, nicht eine zweite Konstante im Code. Ein Barren an der Basis kostet die Bank 300000 Cent. `affordable` lässt den Trade nicht zu, auch nicht als Einzelstück. Der erste Verkauf, dessen Stückpreis auf höchstens 1500 CP fällt, ist Index 278 (das 279. Stück, Mid 1996.30 CP, Verkauf 149722 Cent). Die 278 Stücke davor liegen einzeln über dem Tageslimit. Ein Spieler kommt die Kurve damit nicht hinunter, und 10.000 Barren zu 7,99 Millionen CP werden nicht gutgeschrieben. Mehrere Spieler teilen sich das Tageslimit nicht; jeder scheitert am ersten Barren für sich. Der Floor `0.25b` bleibt trotzdem, damit ein später angehobenes Tageslimit nicht wieder bei 2400 CP pro Barren ausläuft.

64 Barren verkaufen und zurückkaufen, ohne Tageslimit gerechnet: Erlös 17764901 Cent, Rückkauf 35441120 Cent.

Schmieden, Basis-Mids, unverändert 7000.05 CP und Marge 525004 Cent auf der Bank. Die Ecke mit `lo = 0.25b` steht in Abschnitt 4: sie würde gewinnen, wenn die Teile kaufbar wären. Sie sind es nicht.

10.000 Sand von der Decke (106.16 CP) sind 1.07 % der Netheritrüstung an `lo` (9900.04 CP) und 4.04 % einer Netheritbrust an `lo` (2625.01 CP).

## 9. Geplante Tests

Alle unter `plugins-src/craftcore/src/test/java/de/mcstack/craftcore/`. Zahlen sind Cent, sofern nicht CP dazu steht. Die Tests instanziieren die Kurve mit `D(b)` aus Abschnitt 3, `lo = 0.25b`, `hi = 1.2b`, Halbwertszeit 12 h bei `b < 1` und 72 h sonst. Sie lesen `prices-base.yml` und `config.yml` aus den Plugin-Ressourcen, analog `RecipeArbitrageTest`. Die Config-Pins für Bruchstein und Stein dürfen den Test-Mid nicht festsetzen.

- [ ] `CurvePriceTest.diamondMovesAQuarterPercent`
  - Verkauf bei `net = 0`: 15000, Mid 200.
  - Nach einem Verkauf: `net = -1`, Mid `200 * exp(-1/400) = 199.500624`, Verkaufsquote 14962, Kaufquote 29925.
  - Nach einem Kauf ab `net = 0`: Mid `200.500626`, Kauf 30075.

- [ ] `CurvePriceTest.quoteAfterVolume`
  - Die vier neuen Tabellen aus Abschnitt 3: Bruchstein, Erde, Sand, Diamant, Netheritbarren, jeweils Kauf und Verkauf, nach 1, 64, 640 und 10.000. Mid auf 1e-6 CP, Cent exakt.
  - Bruchstein nach einem Verkauf: Verkaufscent bleibt 3. Nach 64 Verkäufen: Verkauf 2 Cent, Mid 0.030327.

- [ ] `CurvePriceTest.netheriteIngotSecondUnit`
  - Erster Verkauf 300000 Cent, zweiter 299250 Cent, Summe 599250.

- [ ] `CurvePriceTest.stackMatchesSingles`
  - 64 Netheritbarren in einem Aufruf und 64 einzelne Verkäufe ergeben beide 17764901 Cent und dasselbe `net`.

- [ ] `CurvePriceTest.quantityCap`
  - `q = 2304` wird gerechnet, `q = 2305` ändert `net` nicht und wirft die erwartete Ablehnung.

- [ ] `CurvePriceTest.twoStacksThenRecovery`
  - 128 Bruchstein verkauft: Mid `0.05 / e = 0.018394`, Kauf 2, Verkauf 1.
  - Nach 12 h (billige Halbwertszeit): Mid 0.030327, Kauf 4, Verkauf 2.
  - Nach 25.967 h: Verkauf wieder 3 Cent, Mid 0.040000.
  - Dieselbe Rechnung mit 72 h statt 12 h lässt den Verkaufscent erst nach 155.805 h wieder auf 3.
  - 128 Sand: direkt Mid 0.036788, Kauf 5, Verkauf 2; Verkauf wieder 7 Cent nach 46.289 h.
  - 128 Diamant, 72 h: direkt Mid 145.229807, Verkauf 10892; nach 72 h Mid 170.428758, Verkauf 12782.

- [ ] `CurvePriceTest.floorCeilingAndRecovery`
  - Nach 10000 Verkäufen Netheritbarren: Mid 1000, Verkauf 75000, `net = -10000`, Summe 798523467 Cent.
  - Diamant an `lo` (`net = -400 * ln(4)`), 72 h später Mid 100, Verkauf 7500, Kauf 15000.
  - Bruchstein an `lo` (`net = -128 * ln(4)`), 12 h später Mid 0.025, Kauf 3, Verkauf 1.
  - Negative vergangene Zeit ändert `net` nicht.
  - `half-life-cheap-hours = 24` und `half-life-hours = 48` verschieben nur die jeweils gemeinte Klasse.

- [ ] `CurvePriceTest.farmDumpCannotBuyGear`
  - 10000 Bruchstein von der Basis 10110 Cent, von der Decke 10172.
  - 10000 Erde: 10110 und 10172. 10000 Sand: 10459 und 10616.
  - Jeder dieser Werte `< 180000` und `< 990004`.
  - `10000 * Verkauf(hi)` ist 40000, 40000 und 90000, jeweils `< 180000`.

- [ ] `CurvePriceTest.roundTripIsALoss`
  - Die sechs Zeilen aus der Tabelle in Abschnitt 4, Erlös < Kosten, `net` am Ende wieder 0.

- [ ] `CurvePriceTest.quoteDoesNotPersist`
  - Zwei Lesungen mit unterschiedlicher `now` ändern `net`, `updated`, `market_catalog` und `market_price_history` nicht.
  - Ein Verkauf danach faltet die Zeit genau einmal.

- [ ] `CurvePriceTest.tradeIsAtomic`
  - H2-Transaktion wie `DynamicPriceTest.websiteCatalogAndEventAreUpdatedAndRolledBackTogether`: nach Rollback stehen `net`, Katalog, Event und Kontostand auf dem Vorwert. Die Event-Menge ist die Stapelmenge, `total_cp` ist die Summe der Stückcent.

- [ ] `CurvePriceTest.firstRowUsesInsertIgnore`
  - Zwei Transaktionen, die beide mit `INSERT IGNORE` die noch fehlende Zeile anlegen und dann `SELECT … FOR UPDATE` lesen, enden mit genau einer Zeile. Kein Primary-Key-Fehler.

- [ ] `RecipeCurveArbitrageTest.everyExecutableRecipe`
  - Für jede Zeile in `recipes-26.1.tsv` außer Selbstkopien: wenn das Produkt nicht in `unsellable` steht und jeder Slot mindestens eine kaufbare Zutat hat, dann `count * Verkauf(hi)` ≤ Summe der günstigsten `Kauf(lo)`. `Verkauf` enthält den Deckel aus diesen Floor-Käufen.
  - Erwartet 0 Verletzungen bei 1652 Rezepten. Dieselbe Schleife ohne Deckel verletzt die Ungleichung; Kontrollzeile `RAW_GOLD` x9, 7281 > 3037.
  - `DIAMOND_CHESTPLATE`, `NETHERITE_INGOT` und `NETHERITE_UPGRADE_SMITHING_TEMPLATE` sind unkaufbar. Die hypothetische Ecke ohne diese Sperre ist 630004 > 262501 und darf nicht als „grün“ durchgewunken werden, solange die Sperre fehlt. Eigener Test in der Umsetzungs-PR: `NetheriteChestIngredientsStayUnbuyable` (siehe Abschnitt 10).

- [ ] `MarketSnapshotOscillationTest.writersDoNotShareColumns`
  - H2, Stunde 7200: Snapshot setzt `quote_price`, lässt eine vorhandene Fill-Zeile (`avg_price` 2, `volume_qty` 10) in den Fill-Spalten unverändert.
  - Zweiter Snapshot in derselben Stunde ändert `quote_price` nicht.
  - `maintain` mit einem Event setzt `avg_price` und `volume_qty` und lässt `quote_price` stehen.
  - Zehn abwechselnde Aufrufe von Snapshot und `maintain` ändern keine der beiden Zahlen mehr.
  - `CHANGE_24H_SQL` bezieht `quote_price`, nicht `avg_price`.

- [ ] Bestehend `MarketSnapshotTest` und `RecipeArbitrageTest` bleiben grün. `DynamicPriceTest` wandert auf die neue Klasse oder prüft nur noch die Hülle, die `CurvePrice` aufruft. Die alte lineare `pressure`-Formel ist nicht mehr der Vertrag.

## 10. Umsetzung, Dateien

Keine dieser Dateien wird in der Plan-PR angefasst.

- Neu: `plugins-src/craftcore/src/main/java/de/mcstack/craftcore/CurvePrice.java` mit `mid`, `buyCents`, `sellCents`, `fold`, `quote`, `fillSell`, `fillBuy`. `fold` nimmt die Halbwertszeit in Stunden. Reine Funktionen plus die eine SQL-Zeile `market_curve`.
- `DynamicPrice`: Handel und Lesung delegieren. `move` mit festem `±1` entfällt.
- `Market.price`: Lesung schreibt keinen Katalog. Handel ruft `fillSell` / `fillBuy` mit der echten Menge. `safeSell` bleibt auf dem Kurvenergebnis.
- `config.yml`: `market.curve.half-life-cheap-hours: 12` und `market.curve.half-life-hours: 72`. Grenze bleibt `b < 1`, dieselbe wie bei `D = 128`.

### Für die Umsetzungs-PR, nicht diese Plan-PR

- [ ] **Rex, Netheritbrust.** `NetheriteChestIngredientsStayUnbuyable`: `Market.unbuyable` ist für `DIAMOND_CHESTPLATE`, `NETHERITE_INGOT` und `NETHERITE_UPGRADE_SMITHING_TEMPLATE` wahr, gelesen aus der Plugin-`config.yml` (`market.unbuyable`). Die drei Namen fehlen in keiner der drei Listen. Ohne diese Sperre wäre die Ecke aus Abschnitt 4 ein Gewinn (630004 Cent Verkauf gegen 262501 Cent Kauf); der Test ist die Sperre, nicht die Preisformel.
- [ ] **Rex, Tageslimit.** `Market.revenueLimit` liest `market.daily-revenue-limit` in CP und rechnet mit `Money.cents`. Default 1500, wenn der Schlüssel fehlt oder der Wert nicht endlich und positiv ist. Heute steht in der Methode fest `150000L`. Ein Test setzt den Config-Wert auf 20 CP und erwartet 2000 Cent, und mit der ausgelieferten 1500 bleibt es 150000 Cent. Die Netherit-Rechnung in Abschnitt 8 benutzt diesen Config-Wert, keine zweite Literale.
- `WebsiteStats`: Spalte `quote_price`, Snapshot und `maintain` wie Abschnitt 6, `CHANGE_24H_SQL` auf `quote_price`. Event nimmt Min- und Max-Stückpreis.
- `Market.start`: stündlicher Lauf nur mit `GET_LOCK('craftcore-market-snapshot', 0)`, und nur dort wird die Abklingzeit persistiert.
- Tests aus Abschnitt 9.

- [ ] **Schritt 1.** `CurvePrice` und `CurvePriceTest` mit den Cent-Erwartungen aus Abschnitt 3, 4, 7 und 8, inklusive der 12-Stunden-Erholung nach zwei Stacks Bruchstein. Test zuerst, er muss an der fehlenden Klasse scheitern, dann die Klasse, dann grün.
- [ ] **Schritt 1b.** `NetheriteChestIngredientsStayUnbuyable` und `revenueLimit` aus `market.daily-revenue-limit`, wie im Abschnitt „Für die Umsetzungs-PR“.
- [ ] **Schritt 2.** `RecipeCurveArbitrageTest` gegen `CurvePrice.sellCents` / `buyCents` und den Deckel aus `RecipeCost`.
- [ ] **Schritt 3.** `market_curve` per `INSERT IGNORE` und `SELECT … FOR UPDATE` in `CraftCore.tx` hängen. `q > 2304` ablehnen. GUI-Lesung persistiert nicht. Rollback-Test.
- [ ] **Schritt 4.** Historie trennen, Oszillationstest, `CHANGE_24H_SQL`.
- [ ] **Schritt 5.** Ein Commit pro Schritt, Message mit dem Verhalten (`market: ein Stück bewegt den Mid`, `market: Quote und Fill getrennt`).

Akzeptanz: die Tests aus Abschnitt 9 sind grün, 10000 Bruchstein, Erde oder Sand bleiben unter 180000 Cent, und ein wiederholter Snapshot plus `maintain` bewegt weder `quote_price` noch `avg_price` einer abgeschlossenen Stunde.

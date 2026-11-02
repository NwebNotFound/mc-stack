# Marktpreise: Kurve, Rezepte, Snapshots

> **Für die Umsetzung:** Required sub-skill: `superpowers:subagent-driven-development` (empfohlen) oder `superpowers:executing-plans`, Aufgabe für Aufgabe. Dieses Dokument ist der Plan, kein Code-Change. Checkboxen (`- [ ]`) gehören zur späteren Umsetzung.

**Ziel:** Der Bankkurs jedes Items hängt an den Rezepten, bewegt sich bei jedem einzelnen Kauf und Verkauf (auch im Stapel) entlang einer Exponentialkurve, bleibt zwischen Item-Unter- und Obergrenze und läuft langsam zum Basispreis zurück. Kauf/Verkauf und jedes Rezept bleiben ohne Gewinn. Die stündlichen Preissnapshots hören auf zu springen.

**Architektur:** Eine Zeile `market_curve` pro Item hält `net` (Käufe minus Verkäufe) und `updated`. Der Mid-Kurs ist `Basis * exp(net / Tiefe)`, geklemmt auf die Item-Grenzen. Kauf- und Verkaufscent kommen aus den heutigen Faktoren (`buy-factor` 1.5, `sell-factor` 0.75, `max-sell-ratio` 0.5). Ein Rezeptdeckel, wie ihn `Market.safeSell` mit `RecipeCost` schon heute auf den Ankauf legt, bleibt verbindlich und wird gegen die Untergrenze der Zutaten gerechnet. Lesende Kursabfragen schreiben nichts. Handel, Kontostand, `net` und das Handel-Event liegen in einer Transaktion. Die Historie trennt Angebots-Kurs und Abschluss-Durchschnitt.

**Technik:** CraftCore, MariaDB, JUnit, `prices-base.yml` (Rohwerte in CP), `config.yml` Abschnitt `market`, Rezeptliste `plugins-src/craftcore/src/test/resources/recipes-26.1.tsv` (1741 Zeilen, minecraft-data 26.1).

## Globale Vorgaben

- Rohpreise in `plugins-src/craftcore/src/main/resources/prices-base.yml` bleiben die Quelle. Diese Plan-PR ändert sie nicht.
- `market.base-factor` = 0.5, `market.buy-factor` = 1.5, `market.sell-factor` = 0.75, `market.max-sell-ratio` = 0.5.
- Dynamische Standardbandbreite bleibt 0.8 bis 1.2 um die Quote-Basis, Halbwertszeit 72 Stunden. Engere Werte in `market.min-price` / `market.max-price` gelten (Bruchstein und Stein sind fest).
- Tiefe `D = 64000/3` (21333.333… Stück). Das ist `volume-scale / strength` = 64 / 0.003 und trifft die heutige Kommentarzeile „je Stack 0,3 %“: `exp(0.003) - 1 = 0.30045 %` pro 64 Stück.
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
net   = net * 2^(-Δt_ms / (72 * 3600 * 1000))
```

Mid in CP:

```text
D = 64000 / 3
m(net) = min(hi, max(lo, b * exp(net / D)))
```

`lo` und `hi` sind `market.min-price.<ITEM>` und `market.max-price.<ITEM>`, sonst `0.8 * b` und `1.2 * b`. Es gilt `lo <= b <= hi`. Bruchstein und Stein haben `lo = hi = b`, der Mid klebt, `net` zählt trotzdem jedes Stück.

Stückpreise in Cent:

```text
Kauf(m) = max(1, floor(m * 100 * 1.5))
Rohverkauf(m) = min(floor(m * 100 * 0.75), cappedSell(Kauf(m), 0.5))
Verkauf(m) = min(Rohverkauf(m), floor(Rezeptdeckel))    -- Deckel nur wenn endlich
```

`Rezeptdeckel` ist der heutige `RecipeCost`-Wert, Saat aber `Kauf(lo)` je kaufbarem Item (Cent als Double), dann Relaxation `Summe der günstigsten Zutat je Slot / Ausgabemenge` über alle registrierten Rezepte. Unkaufbare Zutaten bleiben `+∞` und sperren diese Kette. `Market.safeSell` bleibt die Anwendung des Deckels.

Massenverkauf von `q` Stück, Start-`net` nach der Abklingzeit, in einer Transaktion:

```text
Erlös = Σ_{i=0}^{q-1} Verkauf( m(net - i) )
net  := net - q
```

Massenkauf:

```text
Kosten = Σ_{i=0}^{q-1} Kauf( m(net + i) )
net   := net + q
```

Der Spieler bekommt oder zahlt den Kurs **vor** dem Schritt dieses Stücks. Danach gilt der neue `net`. Die Summe ist in Cent exakt die Summe der Einzeltrades. Stapel dürfen nicht mit `sold = ±1` abgekürzt werden.

Ein Stück ändert `net` immer um 1. Der Centpreis ändert sich, sobald der Mid eine Centgrenze kreuzt. Bei Diamant ist das jedes Stück, bei Bruchstein nie, weil das Band die Breite 0 hat.

### Beispielwerte

Diamant, `b = 200`, `lo = 160`, `hi = 240`, Start `net = 0`:

```text
m(0)  = 200
Kauf  = floor(200 * 150) = 30000 Cent = 300 CP
Verkauf = floor(200 * 75) = 15000 Cent = 150 CP

m(-1) = 200 * exp(-3/64000) = 199.99062522
Verkauf des nächsten Stücks = floor(199.99062522 * 75) = 14999 Cent

m(+1) = 200 * exp(+3/64000) = 200.00937522
Kauf des nächsten Stücks = floor(200.00937522 * 150) = 30001 Cent
```

Netheritbarren, `b = 4000`, `lo = 3200`, `hi = 4800`:

```text
Verkauf bei net 0     = 300000 Cent = 3000 CP
Verkauf bei net -1    = 299985 Cent = 2999.85 CP
Mid nach 64 Verkäufen = 3988.204925 CP, dieses Stück 299115 Cent
Untergrenze erreicht nach D * ln(4000/3200) = 4760.40 Verkäufen, Mid 3200, Verkauf 240000 Cent = 2400 CP
```

Erholung, Diamant an der Untergrenze (`net = -4760.39576137`, Mid 160, Verkauf 12000 Cent). Nach 72 Stunden ist `net` halbiert, der Mid ist das geometrische Mittel:

```text
m = 200 * sqrt(160/200) = 200 * sqrt(0.8) = 178.88543820 CP
Verkauf = floor(178.88543820 * 75) = 13416 Cent = 134.16 CP
Kauf    = floor(178.88543820 * 150) = 26832 Cent
```

Erde an der Untergrenze (Mid 0.04 nach 4761 Verkäufen) läuft nach 72 Stunden auf Mid `0.044721`. Verkauf bleibt 3 Cent, weil das Centgitter gröber ist als das Band 0.04–0.06. Der gespeicherte Mid hat sich bewegt. Das ist der Nachweis für billige Blöcke: `net` und Mid ändern sich pro Stück, der ausgezahlte Centpreis erst an der Grenze.

Bruchstein: `lo = hi = 0.05`, Mid bleibt 0.05, Kauf 7 Cent, Verkauf 3 Cent, für jedes `net`.

## 4. Kein Gewinn über Rezepte und über Kauf/Verkauf

**Basiswerte.** Ein normales Rezept erfüllt weiter `Rezeptmenge * Rohwert(Produkt) >= Summe der günstigsten Zutaten` (Schmelzen `* 1.3` auf den Rohwerten). Das ist der bestehende `RecipeArbitrageTest.noProductBelowItsIngredients`. Netheritbrust ist der Gleichstand 7000.05 CP.

**Bankschleife am selben Item.** Verkauf von `q` Stück und anschließender Rückkauf derselben `q` Stück, ohne Zeitverzug:

```text
Σ Verkauf(m(n - i))  <  Σ Kauf(m(n - q + i))     für i = 0..q-1
```

Der Spread `Verkauf <= Kauf / 2` und die Gegenbewegung der Kurve tragen das. Gerechnete Fälle, Start `net = 0`:

| Trade | Erlös Cent | Rückkauf Cent | Differenz |
| --- | ---: | ---: | ---: |
| 1 Diamant | 15000 | 29998 | −14998 |
| 64 Diamanten | 958552 | 1917045 | −958493 |
| 64 Netheritbarren | 19171644 | 38341526 | −19169882 |
| 10000 Erde | 30000 | 61471 | −31471 |
| 10000 Sand | 61472 | 124523 | −63051 |
| 10000 Bruchstein | 30000 | 70000 | −40000 |

**Rezeptschleife an den Ecken.** Der teuerste ausführbare Fall ist: Produkt an `hi` verkaufen, jede Zutat an `lo` kaufen, und die Bank verkauft jede Zutat (`unbuyable` ist leer für diesen Slot). Ohne Deckel scheitert das an 96 ausführbaren Rezepten (87 normal, 8 Zerlegen, 1 Umfärben), weil `1.2 / 0.8 = 1.5` die knappe statische Marge auffrisst. Größtes Beispiel: Endstab, 4 Stück, Ecken-Erlös 2340 Cent gegen Ecken-Kosten 1945 Cent. Am Basis-Mid (`lo = hi = b`) bleibt die heutige Prüfung mit Toleranz `Slots + Menge` Cent grün; die Ecke ist das neue Loch.

Der Deckel schließt es. Saat `Kauf(lo)`, Relaxation wie `RecipeCost` über alle Rezeptklassen. Danach ist `Menge * Verkauf(hi) <= Summe der Floor-Käufe` für jede ausführbare Kette, inklusive der 96. Der Test ruft `Verkauf` und `Kauf` auf, nicht eine Kopie der Ungleichung. Ohne Deckel ist die Ungleichung falsch; der Deckel ist Teil der Formel, nicht ein optionaler Schutz.

Der Deckel bindet am Basis-Verkauf schon heute, sobald `floor(Rezeptkosten) < Rohverkauf(b)`. Die Relaxation über Ketten trifft 119 Items in der TSV-Liste. Beispiele, die der neue Code nicht „freigeben“ darf:

| Item | Rohverkauf am Basis-Mid | Rezeptdeckel (floor) |
| --- | ---: | ---: |
| BAMBOO_DOOR | 135 Cent | 27 Cent |
| BAMBOO_RAFT | 337 Cent | 67 Cent |
| END_ROD | 487 Cent | 426 Cent |
| CHISELED_RED_SANDSTONE | 240 Cent | 48 Cent |

Bruchstein (Deckel 6, Verkauf 3), Erde (6 und 3) und Sand (12, Verkauf am Hoch 9) bleiben auf der Kurve. Diamant- und Netheritausrüstung haben keinen endlichen Deckel, weil die Zutaten unkaufbar sind.

Selbstkopien (das Produkt steht selbst in einem Slot) bleiben außen vor, wie in `RecipeArbitrageTest`. Umfärben und Zerlegen (`class` `recolor` und `uncraft`) laufen mit, sofern kein Slot nur unkaufbare Optionen hat.

Netheritbrust, Schmiedevorlage, wenn die Bank die Teile verkaufte (tut sie nicht, alle drei Zutaten sind `unbuyable`):

```text
Basis: Kauf der Teile = 240000 + 600000 + 210007 = 1050007 Cent
       Verkauf der Brust = 525003 Cent
       Marge 525004 Cent

Ecke:  Kauf der Teile an lo = 192000 + 480000 + 168006 = 840006 Cent
       Verkauf der Brust an hi = 630004 Cent
       Marge 210002 Cent
```

Schmelzen Ancient Debris → Schrott: Verkauf Schrott an `hi` = 87750 Cent, Kauf Debris an `lo` = 90000 Cent, Marge 2250 Cent. Debris ist unkaufbar, die Zahlen sind die Kurvengrenze.

Stein aus Bruchstein: beide Mids fest, Verkauf Stein 7 Cent, Kauf Bruchstein 7 Cent. Erlös = Kosten, kein Gewinn. Der Rohwert-Faktor 1.3 (Stein 0.20 gegen Bruchstein 0.10) ist durch Spread und festes Band auf 7 Cent zusammengedrückt.

## 5. Atomare Updates

`CraftCore.tx` hält bereits `GET_LOCK('craftcore-write', 5)`. Das bleibt die äußere Serialisierung von Kontostand und Markt.

In derselben Transaktion, für einen Handel von `q` Stück:

1. `SELECT net, updated FROM market_curve WHERE item = ? FOR UPDATE`. Fehlende Zeile: `INSERT` mit `net = 0`, `updated = now`, danach noch einmal sperren.
2. `net` um die Halbwertszeit falten, `updated = now`.
3. Cent-Summe in Speicher über die `q` Schritte rechnen. Kein SQL pro Stück.
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

Verkauf von 10.000 Stück in **einem** Trade. „Von der Basis“ startet bei `net = 0`. „Von der Decke“ startet bei dem `net`, an dem `m = hi` (Bruchstein: Decke = Basis). Vergleich gegen den **günstigsten** Bankkauf der Ausrüstung, also jedes Teil an seinem `lo`. Diesen Kauf bietet die Bank für Diamant und Netherit heute nicht an (`unbuyable`); die Cent sind die Untergrenze der Formel, der leichteste Fall, den die Erlöse trotzdem nicht erreichen.

Ausrüstung, Kauf an `lo`:

| Set | Teile | Cent | CP |
| --- | --- | ---: | ---: |
| Diamantrüstung | Helm, Brust, Hose, Stiefel | 576000 | 5760.00 |
| Diamantausrüstung | Rüstung + Schwert, Spitzhacke, Axt, Schaufel | 792063 | 7920.63 |
| Netheritrüstung | Helm, Brust, Hose, Stiefel | 3168024 | 31680.24 |
| Netheritausrüstung | Rüstung + Schwert, Spitzhacke, Axt, Schaufel | 5976111 | 59761.11 |

Einzelne Rüstungsteile an `lo`, Kauf: Diamanthelm 120000, Brust 192000, Hose 168000, Stiefel 96000 Cent. Netherithelm 768006, Brust 840006, Hose 816006, Stiefel 744006 Cent.

Erlös der 10.000 Stück:

| Start | Bruchstein | Erde | Sand |
| --- | ---: | ---: | ---: |
| Basis, Kurve | 30000 Cent = 300.00 CP | 30000 Cent = 300.00 CP | 61472 Cent = 614.72 CP |
| Decke, Kurve | 300.00 CP | 32513 Cent = 325.13 CP | 67876 Cent = 678.76 CP |
| Jedes Stück an der Decke, Kurve ignoriert | 300.00 CP (3 Cent) | 400.00 CP (4 Cent) | 900.00 CP (9 Cent) |

Anteile am günstigsten Set (Diamantrüstung 5760 CP), Kurve von der Decke: Bruchstein 5.21 %, Erde 5.64 %, Sand 11.78 %. Gegen Netheritrüstung 31680.24 CP: 0.95 %, 1.03 %, 2.14 %.

Selbst die ignorierte Kurve (jedes Stück zum Decken-Cent) bleibt darunter: Sand 900 CP sind 15.63 % der Diamantrüstung. Stückzahl an der Decke, bis der Erlös die Diamantrüstung an `lo` träfe: Bruchstein 192000, Erde 144000, Sand 64000. 10000 reichen nicht.

Sand von der Basis: die ersten 1472 Stück zu 7 Cent, danach 6 Cent, ab Stück 4761 liegt der Mid auf 0.08 und der Verkauf bleibt 6 Cent. Erde von der Basis bleibt durchgehend 3 Cent (Untergrenze `floor(0.04 * 75) = 3`). Erde von der Decke: 2513 Stück zu 4 Cent, Rest zu 3 Cent, Summe 32513 Cent. Bruchstein ist fest bei 3 Cent.

Der Rezeptdeckel senkt diese drei Erlöse nicht (Deckel 6, 6, 12 Cent). `unsellable` setzt den realen Erlös heute auf 0. Die Tabelle ist die Kurve ohne diese Sperre.

## 8. Netherit-Simulation

Barren, ein Trade, Start Basis `net = 0`. Deckel endlich? Nein.

| Stück Nr. | Mid CP | Verkauf dieses Stücks | Summe Cent | Summe CP |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 4000.000000 | 300000 Cent | 300000 | 3000.00 |
| 2 | 3999.812504 | 299985 Cent | 599985 | 5999.85 |
| 64 | 3988.204925 | 299115 Cent | 19171644 | 191716.44 |
| 1000 | 3817.005582 | 286275 Cent | 293083725 | 2930837.25 |
| 3890 | 3333.415607 | 250006 Cent | 1066808109 | 10668081.09 |
| 4761 | 3200.059365 | 240004 Cent | 1280172660 | 12801726.60 |
| 10000 | 3200.000000 | 240000 Cent | 2537532660 | 25375326.60 |

Ab Stück 4761 klebt der Mid auf 3200 CP, der Verkauf auf 2400 CP. 64 Barren verkaufen und zurückkaufen kostet 38341526 Cent und bringt 19171644 Cent ein.

Schmieden einer Netheritbrust aus den Basis-Mids, ohne dass die Kurven der Teile laufen (die Bank verkauft die Teile nicht; das ist die Preisidentität):

```text
Diamantbrust 1600 + Barren 4000 + Vorlage 1400.05 = Brust 7000.05
Kauf der drei Teile am Basis-Mid = 1050007 Cent = 10500.07 CP
Verkauf der Brust am Basis-Mid    = 525003 Cent = 5250.03 CP
```

Ecke, Teile an `lo`, Brust an `hi`: Kosten 840006 Cent, Erlös 630004 Cent, Marge 210002 Cent. Ein Spieler, der die Teile zum Floor-Kauf bekäme, verliert beim Ankauf der Brust.

10.000 Sand von der Decke (678.76 CP) sind 2.14 % der Netheritrüstung an `lo` (31680.24 CP) und 8.08 % einer einzelnen Netheritbrust an `lo` (8400.06 CP).

## 9. Geplante Tests

Alle unter `plugins-src/craftcore/src/test/java/de/mcstack/craftcore/`. Zahlen sind Cent, sofern nicht CP dazu steht. Die Tests instanziieren die neue Kurvenklasse mit `D = 64000/3`, Halbwertszeit 72 h und den Faktoren aus Abschnitt 3. Sie lesen `prices-base.yml` und `config.yml` aus den Plugin-Ressourcen, analog `RecipeArbitrageTest`.

- [ ] `CurvePriceTest.diamondMovesOneCentPerItem`
  - Verkauf bei `net = 0`: 15000, Mid 200.
  - Nach einem Verkauf: `net = -1`, Mid `200 * exp(-3/64000)`, Verkauf 14999.
  - Kauf bei `net = 0`: 30000. Nach einem Kauf: `net = +1`, Kauf 30001.

- [ ] `CurvePriceTest.netheriteIngotSecondUnit`
  - Erster Verkauf 300000 Cent, zweiter 299985 Cent, Summe 599985.

- [ ] `CurvePriceTest.stackMatchesSingles`
  - 64 Netheritbarren in einem Aufruf und 64 einzelne Verkäufe ergeben beide 19171644 Cent und dasselbe `net`.

- [ ] `CurvePriceTest.floorCeilingAndRecovery`
  - Nach 10000 Verkäufen Netheritbarren: Mid 3200, Verkauf 240000, `net = -10000`.
  - Diamant auf die Untergrenze setzen (`net = -D * ln(1.25)`), 72 h vergehen lassen, Mid = `200 * sqrt(0.8)`, Verkauf 13416, Kauf 26832.
  - Negative vergangene Zeit ändert `net` nicht.

- [ ] `CurvePriceTest.farmDumpCannotBuyGear`
  - 10000 Bruchstein, Basis und Decke: 30000 Cent.
  - 10000 Erde, Basis: 30000; Decke: 32513.
  - 10000 Sand, Basis: 61472; Decke: 67876.
  - Jeder dieser Werte `< 576000` und `< 3168024`.
  - Zusätzlich `10000 * Verkauf(hi) < 576000` für die drei Items (30000, 40000, 90000).

- [ ] `CurvePriceTest.roundTripIsALoss`
  - Die sechs Zeilen aus der Tabelle in Abschnitt 4, Erlös < Kosten, `net` am Ende wieder 0.

- [ ] `CurvePriceTest.quoteDoesNotPersist`
  - Zwei Lesungen mit unterschiedlicher `now` ändern `net`, `updated`, `market_catalog` und `market_price_history` nicht.
  - Ein Verkauf danach faltet die Zeit genau einmal.

- [ ] `CurvePriceTest.tradeIsAtomic`
  - H2-Transaktion wie `DynamicPriceTest.websiteCatalogAndEventAreUpdatedAndRolledBackTogether`: nach Rollback stehen `net`, Katalog, Event und Kontostand auf dem Vorwert. Die Event-Menge ist die Stapelmenge, `total_cp` ist die Summe der Stückcent.

- [ ] `RecipeCurveArbitrageTest.everyExecutableRecipe`
  - Für jede Zeile in `recipes-26.1.tsv` außer Selbstkopien: wenn das Produkt nicht in `unsellable` steht und jeder Slot mindestens eine kaufbare Zutat hat (`unbuyable` und fehlender Basispreis zählen als nicht kaufbar), dann `count * Verkauf(hi(Produkt)) <= Summe der günstigsten Kauf(lo(Zutat))`.
  - Erwartet 0 Verletzungen. Dieselbe Schleife **ohne** Rezeptdeckel hat 96 Verletzungen (87 `normal`, 8 `uncraft`, 1 `recolor`; Kontrollassertion, damit niemand den Deckel entfernt). Größte Kontrollzeile: `END_ROD` x4, 2340 > 1945.
  - Netheritbrust, Zutaten trotz `unbuyable` einzeln geprüft: Basis-Summe der Quote-Basen 7000.05, Ecken-Marge `840006 - 630004 = 210002`.

- [ ] `MarketSnapshotOscillationTest.writersDoNotShareColumns`
  - H2, Stunde 7200: Snapshot setzt `quote_price`, lässt eine vorhandene Fill-Zeile (`avg_price` 2, `volume_qty` 10) in den Fill-Spalten unverändert.
  - Zweiter Snapshot in derselben Stunde ändert `quote_price` nicht.
  - `maintain` mit einem Event setzt `avg_price` und `volume_qty` und lässt `quote_price` stehen.
  - Zehn abwechselnde Aufrufe von Snapshot und `maintain` ändern keine der beiden Zahlen mehr.
  - `CHANGE_24H_SQL` bezieht `quote_price`, nicht `avg_price`.

- [ ] Bestehend `MarketSnapshotTest` und `RecipeArbitrageTest` bleiben grün. `DynamicPriceTest` wandert auf die neue Klasse oder prüft nur noch die Hülle, die `CurvePrice` aufruft. Die alte lineare `pressure`-Formel ist nicht mehr der Vertrag.

## 10. Umsetzung, Dateien

Keine dieser Dateien wird in der Plan-PR angefasst.

- Neu: `plugins-src/craftcore/src/main/java/de/mcstack/craftcore/CurvePrice.java` mit `mid`, `buyCents`, `sellCents`, `fold`, `quote`, `fillSell`, `fillBuy`. Reine Funktionen plus die eine SQL-Zeile `market_curve`.
- `DynamicPrice`: Handel und Lesung delegieren. `move` mit festem `±1` entfällt.
- `Market.price`: Lesung schreibt keinen Katalog. Handel ruft `fillSell` / `fillBuy` mit der echten Menge. `safeSell` bleibt auf dem Kurvenergebnis.
- `WebsiteStats`: Spalte `quote_price`, Snapshot und `maintain` wie Abschnitt 6, `CHANGE_24H_SQL` auf `quote_price`. Event nimmt Min- und Max-Stückpreis.
- `Market.start`: stündlicher Lauf nur mit `GET_LOCK('craftcore-market-snapshot', 0)`, und nur dort wird die Abklingzeit persistiert.
- Tests aus Abschnitt 9.

- [ ] **Schritt 1.** `CurvePrice` und `CurvePriceTest` mit den Cent-Erwartungen aus Abschnitt 3, 4, 7 und 8. Test zuerst, er muss an der fehlenden Klasse scheitern, dann die Klasse, dann grün.
- [ ] **Schritt 2.** `RecipeCurveArbitrageTest` gegen `CurvePrice.sellCents` / `buyCents` und den Deckel aus `RecipeCost`.
- [ ] **Schritt 3.** `market_curve` in der bestehenden `CraftCore.tx`-Transaktion an `Market.price` hängen. GUI-Lesung persistiert nicht. Rollback-Test.
- [ ] **Schritt 4.** Historie trennen, Oszillationstest, `CHANGE_24H_SQL`.
- [ ] **Schritt 5.** Ein Commit pro Schritt, Message mit dem Verhalten (`market: ein Stück bewegt den Mid`, `market: Quote und Fill getrennt`).

Akzeptanz: die Tests aus Abschnitt 9 sind grün, 10000 Bruchstein, Erde oder Sand bleiben unter 576000 Cent, und ein wiederholter Snapshot plus `maintain` bewegt weder `quote_price` noch `avg_price` einer abgeschlossenen Stunde.

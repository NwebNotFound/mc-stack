# Markt-GUIs

## Angebot bearbeiten (27 Slots)

```text
 0 Glas |  1 Glas |  2 Glas |  3 Glas |  4 Angebot |  5 Glas |  6 Glas |  7 Glas |  8 Glas
 9 -10  | 10 -1  | 11 -0,10 | 12 Preis | 13 +0,10 | 14 +1 | 15 +10 | 16 Nachfuellen | 17 Glas
18 Glas | 19 Speichern | 20 Glas | 21 Abbrechen | 22 Glas | 23 Zurueckziehen | 24 Glas | 25 Zurueck | 26 Glas
```

Preisaenderungen werden erst mit Speichern uebernommen. Nachfuellen aus der Haupthand erfolgt sofort; Abbrechen verwirft nur den vorgemerkten Preis. Zurueckziehen erfordert zwei Klicks innerhalb von 10 Sekunden und legt das Angebot ins Abholfach.

## Bank: Verkaufen (54 Slots)

```text
 0-35  Spiegel des Spielerinventars (einschliesslich Hotbar)
36-44  graues Glas
45-48  graues Glas | 49 Alles Verkaufbare verkaufen | 50-52 graues Glas | 53 Schliessen
```

Ein Klick verkauft ein Stueck, Umschaltklick den ganzen Stack aus dem echten Inventar. Die Schaltflaeche fuer alle verkaufbaren Items oeffnet eine Bestaetigung mit Gesamtpreis und Anzahl. Das Menue nimmt keine Items auf. Vorschau, Preise und Limits werden beim Verkauf erneut serverseitig geprueft; fehlgeschlagene Verkaeufe werden zurueckgegeben. Items mit Wert 0 Cent sind nicht verkaufbar.

## Bank-Verkaufspreis

Pro Stueck gilt: `Gesamt = Basis * Faktor + min(min(roher Bonus, Bonus-Deckel) * Faktor, Basis)`, auf ganze Cent abgerundet. Der Faktor ist `(maximale Haltbarkeit - Schaden) / maximale Haltbarkeit`, bei Items ohne Haltbarkeit 1, mindestens aber der konfigurierte Mindestfaktor. Der rohe Bonus ist die Summe aus Stufe mal Bonus pro Stufe; Flueche zaehlen nicht. Der Tooltip zeigt Basis, Haltbarkeitsabzug, rohen Bonus, Deckel, skalierten Bonus, gegebenenfalls die Grenze von 100% der Basis und den Gesamtpreis.

Konfiguration unter `market.sell` (Geldwerte in CP; 1 CP = 100 Cent):

- `enchant.bonus-per-level`: Standardbonus pro Stufe (Standard 1 CP).
- `enchant.overrides`: Bonus pro Stufe fuer einzelne Verzauberungen, etwa `sharpness` oder `minecraft:sharpness`.
- `enchant.max-bonus`: Hoechstbonus pro Item (Standard 5 CP bzw. 500 Cent).
- `durability.min-factor`: Mindestfaktor fuer Basis und Bonus (Standard 0).
- `tipped-arrow-price`: optionaler Basispreis fuer Traenkenpfeile; sonst gilt der normale Marktkurs.

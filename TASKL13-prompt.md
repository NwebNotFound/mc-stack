Lies STATUS.md. VOLLSTAENDIG umsetzen, bauen, testen (Unit- und Integrationstests gegen echte MariaDB). bot\ nicht anfassen. KEIN Neustart: Jar nach data\<server>\plugins\update\, STATUS.md 'wartet auf Neustart'.
AUFTRAG L13 Shop/AH korrekt und offline-faehig (pruefen, was L1c schon kann, Rest fertigstellen):
1) Kaufen: Kaeufer sieht Stueckpreis, waehlt Menge (1/8/16/32/64/Alles/eigene), sieht Gesamtpreis und bestaetigt; Kauf sofort, genau diese Menge zu genau diesem Stueckpreis. Wenn sich Preis oder Bestand inzwischen geaendert hat: abbrechen mit klarer Meldung, nie teurer kaufen.
2) Verkaeufer offline: Kauf funktioniert trotzdem; CP werden sofort auf sein Konto gebucht, beim naechsten Join EINE Zusammenfassung ('Waehrend du weg warst: 32x Eisen verkauft, +X CP').
3) Kaufauftraege (Buy-Orders): Spieler kann 'Ich kaufe Item X, Menge N, zu Stueckpreis P' einstellen; CP werden dabei reserviert. Andere Spieler verkaufen per GUI beliebige Teilmenge in den Auftrag, auch wenn der Auftraggeber offline ist; Verkaeufer bekommt sofort CP, Auftraggeber bekommt Items ins Abholfach. Auftrag stornierbar, Rest-CP zurueck.
4) Alles in DB-Transaktionen mit Zeilensperre, Items ueber Abholfach, Idempotenz gegen Doppelklick.
5) Tests: Teilkauf, Kauf bei offline Verkaeufer, Verkauf in Auftrag bei offline Auftraggeber, Preisaenderung waehrend Kauf, Doppelklick, zu wenig CP.

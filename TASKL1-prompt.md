ALLGEMEIN: Lies STATUS.md und COMMANDS.md zuerst. Setze DIESEN kleinen Auftrag VOLLSTAENDIG um, so dass er ingame nutzbar ist. Nicht bei vorbereiteten Regeln oder Teilstaenden aufhoeren; lieber weniger Extras, aber fertig, eingebunden, gebaut, getestet und ausgerollt. bot\ nicht anfassen. Keine Kosten, keine Portfreigaben, keine Secrets ausgeben. Vor Neustart: Backup der betroffenen Daten. Ergebnis kurz in STATUS.md dokumentieren (was ingame testen).
AUFTRAG L1: SHOP Stueckpreis + Teilkauf: /shop sell nutzt Preis pro Stueck; bestehende Angebote auf Stueckpreis migrieren (Backup vorher). Kaeufer waehlen im Shop und in /ah per GUI eine beliebige Teilmenge (1, 8, 16, 64, Rest, eigene Menge); der Rest bleibt gelistet. Kauf atomar in einer DB-Transaktion mit Idempotenz-Key, kein Dupe, kein Doppelabzug.

VORAUSSETZUNG: L5 hat das Abholfach (pending_deliveries) gebaut. Nutze es fuer alle Kaeufe.

WICHTIG (zweiter Versuch): L5b ist fertig, das Abholfach existiert. Diesmal NICHT nur pruefen: Code aendern, Migration mit Backup ausfuehren, bauen, ausrollen, neu starten. Das in STATUS.md dokumentierte Restrisiko ist akzeptiert und kein Grund abzubrechen.

MIGRATION ENTSCHIEDEN: Stueckpreis = Gesamtpreis / Menge, auf 0,01 CP AUFgerundet (Mindestens 0,01). Kleine Abweichung zugunsten des Verkaeufers ist ok. Kein weiterer Grund zum Abbruch.

NEUSTART-REGEL (gilt ab sofort, ueberschreibt alle anderen Angaben): KEIN Neustart von Lobby, Survival oder Velocity. Nur bauen, testen und die neue Jar nach data\<server>\plugins\update\ legen (Paper uebernimmt sie beim naechsten Neustart). In STATUS.md notieren 'wartet auf Neustart'.

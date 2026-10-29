Arbeitsverzeichnis C:\Users\nilsw\mc-stack, Plugin plugins-src\craftcore. bot\ NICHT anfassen. NUR Bank-Verkauf/Kauf.
Design (Journal/Outbox):
- Verkauf: Main-Thread prueft und entfernt Items aus dem Inventar, schreibt sofort synchron (kurze Tx) eine Zeile bank_journal(id uuid, player, item, amount, cp, status=PENDING). Danach async: in EINER Tx Spieler-CP gutschreiben + Marktpreis anpassen + journal status=DONE. Idempotent ueber journal-id (kein doppeltes Gutschreiben).
- Kauf: erst CP abbuchen + journal PENDING_DELIVERY in einer Tx, dann Items auf Main-Thread geben, dann DONE. Wenn Spieler offline/Inventar voll: beim naechsten Join nachliefern.
- Beim Start und Join: alle PENDING abarbeiten. Fehlschlag des ersten Journal-Inserts: Items zurueckgeben.
- Tests: Crash nach Item-Abzug, nach Gutschrift, Doppelausfuehrung. Sperre aufheben, bauen, Lobby+Survival neu starten, STATUS.md.

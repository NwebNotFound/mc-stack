Arbeitsverzeichnis C:\Users\nilsw\mc-stack, Plugin plugins-src\craftcore. bot\ NICHT anfassen. NUR Graeber, nichts anderes.
Design (genau so umsetzen):
- Bei Tod: Items + halbe gespeicherte XP serialisieren, in EINER DB-Transaktion als Grab (status=OPEN) speichern; erst nach erfolgreichem Commit Drops leeren (event.getDrops().clear(), setDroppedExp(0)). Schlaegt der Insert fehl: normale Drops behalten.
- Grab-Block (z.B. Spielerkopf) an Todesposition, nur Besitzer + Teammitglieder.
- Entnahme: Rechtsklick -> UPDATE graves SET status='CLAIMING' WHERE id=? AND status='OPEN' (atomarer Lock, nur 1 Zeile betroffen = weiter). Items ins Inventar, Ueberschuss droppen, XP geben, dann status='CLAIMED'. Main-Thread fuer Inventar, Async fuer DB, Cooldown gegen Doppelklick.
- Beim Serverstart: CLAIMING-Graeber aelter als 1 Min auf OPEN zuruecksetzen NUR wenn nicht ausgegeben (Flag delivered in derselben Tx wie CLAIMED setzen).
- Unit-Tests fuer Doppelklick, zwei Spieler gleichzeitig, DB-Fehler. Sperre aufheben, bauen, Survival neu starten, STATUS.md.

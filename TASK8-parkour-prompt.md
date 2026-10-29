Arbeitsverzeichnis C:\Users\nilsw\mc-stack. bot\ NICHT anfassen. Lies STATUS.md.
Parkour (Lobby): KEINE Server-Waehrung/CP-Belohnung (entfernen, falls vorhanden). Parkour ist nur Zeitvertreib mit Zeitmessung:
- Bestzeit pro Spieler in MariaDB-Tabelle parkour_times (uuid, name, best_ms, updated_at) speichern; nur verbessern, nicht ueberschreiben mit schlechter.
- Respawn am letzten Checkpoint bei Fall/Void, /parkour leave.
- In-World-Leaderboard Top 10 als Hologramm (TextDisplay-Entities, ohne Zusatzplugin) an konfigurierbarer Position in der Lobby, Befehl /parkour setboard fuer OPs, Update bei neuer Bestzeit.
- Tabelle so dokumentieren (README in STATUS.md), dass der Discord-Bot sie lesen kann (Dex baut das Discord-Leaderboard).
Bauen, Tests, nur Lobby neu starten, Logs pruefen, STATUS.md aktualisieren.

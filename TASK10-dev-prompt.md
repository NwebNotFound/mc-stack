Arbeitsverzeichnis C:\Users\nilsw\mc-stack. bot\ NICHT anfassen. Lies STATUS.md.
1. Lobby: keine Advancements. Gamerule announceAdvancements false, Advancement-Fortschritt in der Lobby blockieren/zuruecksetzen (z.B. kleines Listener-Plugin oder Paper/Spigot-Config, Spigot advancements.disable-saving, und PlayerAdvancementDone-Kriterien revoken). Kein Chat-Spam.
2. DEV-Modus (Schalter DEV_MODE=true in .env, Standard true, spaeter fuer Live auf false): taeglicher Reset um 05:00 Europe/Berlin per geplanter Aufgabe (Windows Task Scheduler oder Container-Cron, robust):
   - Vorher Backup der Survival-Welt als zip nach backups\ (nur letzte 7 behalten).
   - Survival stoppen, Weltordner (world, world_nether, world_the_end) loeschen, Advancements/Stats/Playerdata der Spieler in Survival zuruecksetzen, Survival starten (neuer Seed).
   - CraftCore-Daten, die sich auf die Survival-Welt beziehen (Claims, Homes, Graeber), ebenfalls zuruecksetzen; CP und Teams behalten.
   - Chat: per RCON vor dem Reset Hinweis 5 Min vorher; danach Chat fuer alle leeren (Leerzeilen). Zusaetzlich alle 6 Stunden Chat leeren (nur DEV).
   - Lobby-Welt und Parkour NIE zuruecksetzen.
   - Manuell ausloesbar per Skript dev-reset.ps1.
Testen (Skript einmal im Trockenlauf), STATUS.md aktualisieren.

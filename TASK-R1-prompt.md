Projekt C:\Users\nilsw\mc-stack\plugins-src\craftcore (Paper-Plugin CraftCore, Gradle). bot\ nur lesen. Kein Server-/Containerneustart, nichts loeschen. Keine Secrets ausgeben.
Aufgabe R1 (direkt implementieren): End in Survival sperren, nur auf dem Survival-Backend (gleiche Erkennung wie bestehender Code).
- Config end-lock.enabled (Default true) und end-lock.message (Default "&cDas End ist noch gesperrt - es wird spaeter als Event freigeschaltet!") in Plugin-Default-Config und beiden Vorlagen-Configs unter config\ (lobby + survival). Bei false: Vanilla-Verhalten.
- Gesperrt: Enderauge in Endportalrahmen einsetzen (PlayerInteractEvent, Rahmen ohne Auge -> abbrechen, Item bleibt), Betreten Endportal (PlayerPortalEvent/EntityPortalEvent Cause END_PORTAL und Teleport in Welt mit Environment THE_END abbrechen), auch Teleports von Nicht-Ops in THE_END-Welten; Endermen-Perlen fliegen weiter normal. Meldung an Spieler, max 1x pro 3 Sekunden. Kreaturen/Items nicht durch Endportale lassen. Ops mit Permission craftcore.end.bypass duerfen.
- Unit-Tests fuer Entscheidungslogik (enabled/disabled, Bypass, Cooldown der Meldung).
gradle clean build mit echter MariaDB wie in STATUS.md (alle gruen, keine skipped), dann powershell -File C:\Users\nilsw\mc-stack\deploy-craftcore.ps1.
STATUS.md Abschnitt "R1 End-Sperre (29.10.2026)". Ende: RESULT tests=<n> failed=<n> jar=<sha256>.

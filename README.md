# MC Stack

Minecraft Java mit Lobby, Survival und Simple Voice Chat in Docker.

## Installation

1. Docker Desktop für Windows/macOS bzw. Docker Engine mit Compose-Plugin für Linux installieren und starten. Ausreichend Arbeitsspeicher und Internet für den ersten Download bereitstellen.
2. Projektordner auf das Gerät kopieren und ein Terminal in diesem Ordner öffnen. Mit dem Start akzeptierst du die [Minecraft-EULA](https://www.minecraft.net/eula); Compose setzt `EULA=TRUE`.
3. Windows PowerShell: `powershell -ExecutionPolicy Bypass -File .\install.ps1`. Linux/macOS: `sh ./install.sh`.
4. Der erste Start kann mehrere Minuten dauern. Mit `docker compose ps` prüfen, ob lobby, survival und velocity `healthy` sind. Logs: `docker compose logs --tail 100 lobby survival velocity`.
5. Den eigenen Java-Spielernamen auf beiden Servern freischalten: `docker compose exec lobby rcon-cli whitelist add SPIELERNAME` und `docker compose exec survival rcon-cli whitelist add SPIELERNAME`. Dann auf demselben Gerät in Minecraft Java `127.0.0.1:25565` verbinden und mit `/server survival` wechseln.

Für Voice braucht jeder Spieler die zur Minecraft-Version passende Simple-Voice-Chat-Client-Mod. Lokal nutzt Survival UDP 24454. Für andere Geräte ist ein UDP-Tunnel nötig; die erforderliche Einrichtung steht in [STATUS.md](STATUS.md). Die Lobby hat keinen Voice-Chat. Minecraft und Voice sind momentan nur auf dem Servergerät erreichbar.

Die Installer erzeugen `.env` und `secrets/forwarding.secret` nur, wenn sie fehlen; bestehende Dateien bleiben erhalten. `.env`, `secrets/`, `data/` und Logs privat halten. `MINECRAFT_VERSION` in `.env` kann auf eine getestete Version gesetzt werden; leer bedeutet neueste Version und kann bei einem Neustart ein Update auslösen. Vor Updates Welten sichern.

## Stoppen und Umzug

`docker compose stop` fährt die Server sauber herunter; `docker compose up -d` startet sie wieder. Für einen Umzug zuerst stoppen, dann den gesamten Projektordner einschließlich `data/`, `.env` und `secrets/` privat auf das neue Gerät kopieren. Die Installer dort starten. Diese Kopie enthält Welten und Geheimnisse und gehört nicht in ein öffentliches Repository. Vorher eine zusätzliche Sicherung anlegen. `docker compose down` entfernt Container und Netzwerk, nicht die Daten im Projektordner.

For PvP and VIP database account setup, use `tools/pvp-db-user.ps1` and `tools/vip-db-user.ps1`; see [DEPLOY.md](DEPLOY.md) for required credentials and migration order.

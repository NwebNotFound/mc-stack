# Discord-Bot für den Minecraft-Server

Der Bot ist optional. Er benötigt einen Discord-Server und ein Java-Konto zum vollständigen Test. Velocity authentifiziert Spieler; RCON und Paper bleiben intern. Das mitgebaute Paper-Plugin zeigt beim ersten Verbindungsversuch den Bestätigungscode und verwaltet die Whitelist anhand der Mojang-UUID. Beide Paper-Backends benötigen das Plugin.

## Discord einrichten

1. Im [Discord Developer Portal](https://discord.com/developers/applications) eine Anwendung erstellen, unter **Bot** einen Bot anlegen und das Token vertraulich kopieren. Unter **Privileged Gateway Intents** sowohl **Server Members Intent** als auch **Message Content Intent** aktivieren.
2. Unter **OAuth2 → URL Generator** die Scopes `bot` und `applications.commands` wählen. Bot-Rechte: **View Channels**, **Send Messages**, **Read Message History**; für Slash-Commands müssen Mitglieder den Kanal sehen und Anwendungsbefehle verwenden dürfen. Die erzeugte URL öffnen und den Bot zum eigenen Server hinzufügen. Die generische Einladungsadresse lautet `https://discord.com/oauth2/authorize?client_id=ANWENDUNGS_ID&scope=bot%20applications.commands&permissions=68608` (Anwendungs-ID einsetzen).
3. Eine Spielerrolle und eine getrennte Adminrolle anlegen. Einen Textkanal für den Chat und einen für den Status anlegen. Der Bot muss beide sehen und dort schreiben dürfen. Im Statuskanal darf er seine eigenen Nachrichten bearbeiten. Vertraue die Adminrolle nur Personen an, die Server stoppen und Backups auslösen dürfen.
4. In Discord unter Erweitert den Entwicklermodus aktivieren und per Rechtsklick IDs von Server, Rollen und Kanälen kopieren.

## Konfiguration und Start

Im Hauptordner PowerShell öffnen:

```powershell
Copy-Item .\bot\.env.example .\bot\.env
notepad .\bot\.env
```

`DISCORD_TOKEN`, `GUILD_ID`, `PLAYER_ROLE_ID`, `ADMIN_ROLE_ID`, `CHAT_CHANNEL_ID` und `STATUS_CHANNEL_ID` eintragen. Das RCON-Passwort wird beim Compose-Start aus der vorhandenen **Hauptordner**-`.env` übernommen; der Eintrag in `bot/.env` ist nur für eigenständige Starts gedacht. Keine Secrets veröffentlichen. Ohne Token beendet sich der Bot mit einer Fehlermeldung; er startet nicht automatisch mit den übrigen Diensten.

```powershell
docker compose --profile bot up -d --build bot
# Der Bot kopiert sein Plugin in ein gemeinsames Volume. Danach Backends neu erstellen:
docker compose up -d --force-recreate lobby survival
docker compose --profile bot logs --tail 100 bot
```

Die Neuerstellung unterbricht Spieler. Nach Plugin-Updates beide Backends erneut erstellen. Im jeweiligen Paper-Log muss `DiscordLink` geladen sein. Bot und Plugin verwenden eigene Docker-Volumes; `docker compose down -v` würde diese Daten löschen. `bot/data` enthält SQLite, Statusnachrichten-ID und Backups. Backups getrennt sichern. Der Bot startet nach einem Docker-Neustart nicht automatisch; bei Bedarf `docker compose --profile bot up -d bot` ausführen.

## Verwendung und Betrieb

`/setup` sendet eine Anleitung per DM. `/link mc_name` prüft den Namen bei Mojang; nur mit Spielerrolle möglich. Anschließend mit `mc.nwebnotfound.dev` verbinden und den im Kick angezeigten Code innerhalb von zehn Minuten per `/verify code` in Discord eingeben. Der Code ist einmalig und nach fünf Fehlversuchen gesperrt. Nach Ablauf kann `/link` erneut verwendet werden. Die Adresse muss extern tatsächlich erreichbar sein; Voice braucht zusätzlich den in `../STATUS.md` beschriebenen UDP-Zugang.

Der Bot entfernt eine Verknüpfung und den Whitelist-Zugang bei Rollenverlust oder Serveraustritt; ausgefallene Backends werden regelmäßig erneut versucht. Änderungen während eines Bot-Ausfalls werden nach Start abgeglichen. Aktuell eingeloggte Spieler werden bei Entzug entfernt. Administrierende können `/whitelist add` und `/whitelist remove` nutzen; manuell hinzugefügte Einträge sind nicht an Discord-Konten gebunden und werden nicht automatisch entfernt. `/restart survival` stoppt den Container, dessen Restart-Policy ihn wieder startet. `/backup` pausiert die Weltspeicherung, fordert einen Flush an, archiviert beide Backend-Daten in `bot/data/backups` und aktiviert die Speicherung anschließend wieder. Ein Abbruch des Bot-Prozesses während des Backups kann das Reaktivieren verhindern: dann auf beiden Backends `save-on` ausführen. Für Versionsupdates und besonders verlässliche Sicherungen Server stoppen; Proxy-Konfiguration, Secrets und Bot-Daten sind nicht Bestandteil dieses Backups.

`/status` und der Statuskanal zeigen den Status des Velocity-Pings; dieser ersetzt keinen Spieltest. Nachrichten im konfigurierten Chatkanal werden per RCON an Survival gesendet. Der Rückweg liest die üblichen Paper-Chatzeilen aus `latest.log`; andere Chat-Plugins oder veränderte Logformate können den Rückweg beeinflussen. Beim Bot-Neustart werden alte Logzeilen nicht nachgesendet. Keine vertraulichen Daten im Chat teilen.

Die Verknüpfung setzt funktionierendes Velocity Modern Forwarding und ein geladenes Lobby-Plugin voraus. Nach der Einrichtung mit einem echten Java-Konto Verknüpfung, Login, Backendwechsel, Chat in beide Richtungen, Rollenentzug und Wiederherstellung sowie Backup-Restore prüfen. Die Serverseite nutzt eine aktuelle Paper-Version; das Plugin wurde gegen Paper 1.21.4 gebaut, bei Versionswechseln die Kompatibilität erneut prüfen.

Paper kann bei UUID-basiert hinzugefügten Spielern einen leeren Namen in `whitelist.json` speichern. Die Zuordnung erfolgt über die UUID; für eine Übersicht der verknüpften Namen dient die Bot-Datenbank. Normale namensbasierte Whitelist-Befehle sollten für diese Einträge nicht als Ersatz für die Bot-Befehle verwendet werden.

Optional `PLAYER_COUNT_CHANNEL_ID` in `bot/.env` auf einen Sprach- oder Textkanal setzen. Der Bot braucht dort die Berechtigung ?Kan?le verwalten?; Umbenennungsversuche erfolgen h?chstens alle zehn Minuten. Bei Serverbeitritt versucht der Bot, die Anleitung per DM zu senden.


## Changelog (Pflicht)
Jede Bot-Aenderung braucht einen neuen Eintrag in `changelog.json` (version, date, changes[type: neu|verbessert|fix, text: kurz, deutsch, spielerverstaendlich]). Creepy postet neue Versionen beim Start in `CHANGELOG_CHANNEL_ID`; gepostete Versionen stehen in `data/changelog-posted.json` (kein Doppelpost nach Rebuild).


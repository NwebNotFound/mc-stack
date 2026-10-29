# CraftCore 0.1

Paper 26.3, Java 25, Vault 1.7.3 und MariaDB. Gradle-Build aus dem Projektverzeichnis:

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace/plugins-src/craftcore gradle:9-jdk25 gradle clean build --no-daemon
```

Die MariaDB l?uft im internen Compose-Netz ohne Host-Port. Das Passwort steht in der lokalen `.env`, Daten im Docker-Volume `craftcore-db`; beide zusammen sichern. Startguthaben ist 0 CP, Betr?ge haben maximal zwei Nachkommastellen. `/pay` und Team-Zielbefehle erfordern derzeit, dass das Ziel auf demselben Backend online ist. Teams: `/team create <name>`, `/team invite <spieler>`, `/team join <name>`, `/team leave`, `/team kick <spieler>`, `/team info`, `/team promote <spieler>`, `/team demote <spieler>`. Der Owner l?st das Team beim Verlassen auf; Admins d?rfen Mitglieder einladen und einfache Mitglieder entfernen. Sidebar aktualisiert sich alle f?nf Sekunden und ersetzt derzeit andere Scoreboards. Datenbankzugriffe laufen synchron auf dem Serverthread; Ausfall oder langsame DB kann zu Verz?gerungen f?hren. Bankkonten ?ber Vault sind nicht implementiert.

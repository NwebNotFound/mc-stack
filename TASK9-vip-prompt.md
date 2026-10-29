Arbeitsverzeichnis C:\Users\nilsw\mc-stack. bot\ NICHT anfassen. Lies STATUS.md und docker-compose.yml.
VIP-Welten: Jeder VIP (LuckPerms-Gruppe vip) bekommt max. EINEN eigenen Paper-Server (2 GB RAM, offline-mode, Velocity modern forwarding, keine veroeffentlichten Ports, gleiche Plugins wie Survival soweit sinnvoll, Whitelist/Owner-Rechte fuer den VIP).
- Velocity-Plugin (neu, plugins-src\vipworlds): /vip start, /vip stop, /vip join, /vip invite <spieler>, /vip status. Startet/stoppt Container ueber Docker (Docker-Socket in einem eigenen Controller-Container oder Docker-API, sicher und nur intern), registriert Server dynamisch in Velocity.
- Max. gleichzeitig laufend: 2 (config). Weitere Starts kommen in eine Queue; bei freiem Platz automatisch starten und Spieler benachrichtigen.
- Auto-Stop: 2 Minuten nachdem der letzte Spieler den VIP-Server verlassen hat (speichern, dann stoppen).
- AFK: auf VIP-Servern Kick nach 5 Minuten Inaktivitaet; auf Survival nach 5 Minuten AFK-Markierung (Tablist/Chat), Kick erst nach 30 Minuten. Lobby nicht kicken. Alles konfigurierbar.
- Weltdaten persistent unter data\vip\<uuid>.
Tests, bauen, deployen; Lobby/Survival nur neu starten wenn noetig. STATUS.md aktualisieren.

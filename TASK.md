Aufgabe von Luna (Nils' Assistentin), auf Nils' Wunsch:
1. Installiere Docker Desktop per winget (Docker.DockerDesktop), inkl. WSL2 falls noetig. Falls ein Neustart noetig ist: NICHT selbst neu starten, nur in STATUS.md notieren.
2. Baue in C:\Users\nilsw\mc-stack ein lokales Test-Setup mit docker compose:
   - Velocity-Proxy (itzg/mc-proxy) mit modern forwarding + geheimem forwarding-secret (Datei, nicht ausgeben).
   - Paper "lobby" und Paper "survival" (itzg/minecraft-server, TYPE=PAPER, aktuelle Version, Whitelist an, online-mode via Velocity, Backends nur im Docker-Netz, keine Host-Ports ausser Velocity 25565 auf 127.0.0.1). Survival ~4 GB RAM, Lobby 1 GB. Simple Voice Chat Plugin auf survival (UDP 24454).
   - playit-Agent (ghcr.io/playit-cloud/playit-agent) mit SECRET_KEY aus .env (Platzhalter, nicht ausfuellen).
   - cloudflared mit TUNNEL_TOKEN aus .env (Platzhalter), Service vorerst auskommentiert/Profil.
   - RCON nur intern, Passwort zufaellig in .env.
   - Ordner bot/ als Platzhalter fuer spaeteren Discord-Bot.
3. Wenn Docker laeuft: lobby, survival und velocity starten und pruefen, dass man lokal auf 127.0.0.1:25565 joinen koennte (Logs pruefen).
4. Schreibe STATUS.md mit: was erledigt ist, was fehlt (playit-Key, Cloudflare-Token, Neustart?), Befehle zum Starten/Stoppen. Keine Geheimnisse in STATUS.md.
Nichts kaufen, keine Ports in der Fritzbox oeffnen, keine Dateien ausserhalb mc-stack aendern (ausser Docker-Installation).

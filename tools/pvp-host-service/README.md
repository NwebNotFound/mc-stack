# PvP-Host-Dienst

Python 3 (Standardbibliothek) und Docker CLI sind erforderlich. `PVP_HOST_TOKEN` muss in der Umgebung des Dienstes gesetzt sein; `PVP_HOST_PORT` ist optional (Standard 25599), ebenso `PVP_HOST_BIND` (Standard 127.0.0.1). Start: `python service.py`. Unter Windows kann der Taskplaner den Prozess beim Anmelden oder Systemstart mit diesen Umgebungsvariablen starten.

Velocity in einem Container erreicht den Host unter `host.docker.internal`; localhost im Container ist nicht der Host. Dafür muss der Dienst an einer vom Container erreichbaren Host-Adresse gebunden werden (`PVP_HOST_BIND`) und der Zugriff per Host-Firewall auf vertrauenswürdige Quellen begrenzt werden. Der Token wird im Header `X-PvP-Token` übergeben. HTTP bietet keine Transportverschlüsselung; nur vertrauenswürdige Netze verwenden. Der Dienst steuert ausschließlich `mc-stack-pvp-1`.

## VIP-Welten

Der Dienst startet, wenn mindestens einer der getrennten Tokens `PVP_HOST_TOKEN` oder `VIP_HOST_TOKEN` gesetzt ist. `run.ps1` lädt auch `VIP_HOST_TOKEN` aus der `.env`, ohne ihn auszugeben. VIP-Anfragen benötigen `X-VIP-Token`; ein fehlender VIP-Token deaktiviert nur die VIP-Endpunkte. Bind und Port verwenden dieselben `PVP_HOST_BIND`- und `PVP_HOST_PORT`-Einstellungen wie oben.

- `POST /vip/start` mit `{"worldId":"<uuid>"}` startet eine Welt aus `data/vipworlds/<uuid>` im ersten freien Slot. Das Weltverzeichnis wird beim ersten Start angelegt; das Basisverzeichnis `data/vipworlds` muss bereits existieren (sonst `500 base_missing`). Symlinks und Einträge, die keine Verzeichnisse sind, werden mit `400 invalid_world_path` abgelehnt. Laufzeitlimit und RAM-Budget stammen aus `config/vipworlds.yml`; Survival wird nicht gestoppt.
- `POST /vip/stop` mit `{"slot":"vipworld-1","worldId":"<uuid>"}` stoppt einen konfigurierten Slot. Mit `worldId` nur, wenn der Slot noch genau diese Welt betreibt (sonst `409 world_mismatch`).
- `GET /vip/status` liefert Slots, Welt-IDs, das begrenzte Laufzeitlimit und `budgetOk` für den Start einer weiteren Welt.

Das Repository wird relativ zum Dienst gefunden; `MC_STACK_DIR` kann den Pfad überschreiben. Docker Compose und die konfigurierten VIP-Slots müssen vorhanden sein.

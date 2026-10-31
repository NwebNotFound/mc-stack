# PvP-Host-Dienst

Python 3 (Standardbibliothek) und Docker CLI sind erforderlich. `PVP_HOST_TOKEN` muss in der Umgebung des Dienstes gesetzt sein; `PVP_HOST_PORT` ist optional (Standard 25599), ebenso `PVP_HOST_BIND` (Standard 127.0.0.1). Start: `python service.py`. Unter Windows kann der Taskplaner den Prozess beim Anmelden oder Systemstart mit diesen Umgebungsvariablen starten.

Velocity in einem Container erreicht den Host unter `host.docker.internal`; localhost im Container ist nicht der Host. Dafür muss der Dienst an einer vom Container erreichbaren Host-Adresse gebunden werden (`PVP_HOST_BIND`) und der Zugriff per Host-Firewall auf vertrauenswürdige Quellen begrenzt werden. Der Token wird im Header `X-PvP-Token` übergeben. HTTP bietet keine Transportverschlüsselung; nur vertrauenswürdige Netze verwenden. Der Dienst steuert ausschließlich `mc-stack-pvp-1`.

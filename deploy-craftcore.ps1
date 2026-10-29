# deploy-craftcore.ps1 - legt die gebaute CraftCore-Jar in die Vorlagen (config\*-plugins).
# Grund: itzg COPY_PLUGINS_SRC synchronisiert beim Containerstart die Vorlagen nach /data/plugins
# und ueberschreibt damit Jars aus plugins\update. Quelle der Wahrheit sind daher die Vorlagen.
# Startet KEINEN Server neu. Alte Vorlagen-Jar wird nach backups\craftcore-jar-<zeit> gesichert.
param([string]$Jar = "$PSScriptRoot\plugins-src\craftcore\build\libs\craftcore.jar")
$ErrorActionPreference = 'Stop'
foreach ($t in 'lobby','survival') { if (Test-Path "$PSScriptRoot\config\$t-plugins\CraftCore\config.yml") { throw "config\$t-plugins\CraftCore\config.yml darf NICHT in der Vorlage liegen (itzg-Sync scheitert mit 'Operation not permitted' -> Restart-Loop). Config-Aenderungen direkt in data\$t\plugins\CraftCore\config.yml." } }
if (!(Test-Path $Jar)) { throw "Jar fehlt: $Jar (erst gradle build)" }
$h = (Get-FileHash $Jar).Hash
$bk = "$PSScriptRoot\backups\craftcore-jar-$(Get-Date -f yyyyMMdd-HHmmss)"; New-Item -ItemType Directory $bk | Out-Null
foreach ($t in 'lobby','survival') {
  $dst = "$PSScriptRoot\config\$t-plugins\craftcore.jar"
  if (Test-Path $dst) { Copy-Item $dst "$bk\$t-craftcore.jar" }
  Copy-Item $Jar $dst -Force
  if ((Get-FileHash $dst).Hash -ne $h) { throw "Hash-Fehler $dst" }
  $upd = "$PSScriptRoot\data\$t\plugins\update\craftcore.jar"
  if (Test-Path (Split-Path $upd)) { Copy-Item $Jar $upd -Force }
}
"CraftCore $($h.Substring(0,12)) in config\lobby-plugins + config\survival-plugins (Backup $bk). Wirksam nach Neustart lobby+survival."


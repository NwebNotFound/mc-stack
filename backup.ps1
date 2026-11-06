param([switch]$LeaveStopped, [switch]$PreserveBackups, [switch]$StopServers, [switch]$NoWarning, [int]$ReadyAttempts = 12, [int]$LockWaitSeconds = 0)
# Default: online backup, nothing is stopped (RCON save-off + save-all flush on every server, dump craftcore and
# luckperms, copy lobby/survival/pvp/vipworlds, save-on).
# -StopServers: old behaviour (stop lobby+survival, copy, start). -LeaveStopped implies -StopServers (deploys).
if($LeaveStopped){ $StopServers=$true }
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot 'tools/maintenance-common.ps1')
Start-MaintLog 'backup' $PSScriptRoot | Out-Null
$lock=$null
$stopped=$false
$savesOff=@()
$optional=@()
$complete=$false
$backup=$null
$exitCode=0
$errorText=''
try {
  $lock=Enter-MaintLock $PSScriptRoot $LockWaitSeconds
  # Catch-up runs after a reboot used to start before Docker and craftcore-db were up (05.11., 11:36).
  Wait-MaintReady $ReadyAttempts
  $stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
  $backup=Join-Path $PSScriptRoot "backups\daily-$stamp"
  New-Item -ItemType Directory -Path $backup | Out-Null
  Write-MaintLog 'backup' 'info' "Ziel $backup"
  if($StopServers){
    $stopped=$true
    docker compose stop lobby survival
    if($LASTEXITCODE -ne 0){throw 'Server konnten nicht gestoppt werden.'}
    Write-MaintLog 'stop' 'info' 'lobby und survival gestoppt'
  }
  # Order matters (issue #145): pause saving everywhere, flush, dump BOTH databases while saving is still off, copy
  # the worlds, and only then turn saving back on. World files and DB dumps then describe (almost) the same moment.
  $optional=@(Get-MaintRunningServices | Where-Object { $_ -match '^(pvp|vipworld-[1-3])$' })
  foreach($server in @('lobby','survival')) {
    if(-not $StopServers){
      # Online: the server keeps running but writes nothing to the region files while we copy.
      if(-not $NoWarning){ Invoke-MaintRcon $server 'say Backup laeuft, kurz kein Speichern. Spiel einfach weiter.' -Optional }
      $savesOff+=$server
      Invoke-MaintRcon $server 'save-off'
      Invoke-MaintRcon $server 'save-all flush'
      Write-MaintLog 'save' 'info' "${server}: save-off, save-all flush"
    }
  }
  # Running PvP / VIP servers: best effort (they are short-lived and may be stopping right now).
  foreach($server in $optional){
    try { Invoke-MaintRcon $server 'save-off' -AllProfiles; $savesOff+=$server; Invoke-MaintRcon $server 'save-all flush' -Optional -AllProfiles; Write-MaintLog 'save' 'info' "${server}: save-off, save-all flush" }
    catch { Write-MaintLog 'save' 'warn' "${server}: save-off nicht moeglich, Kopie ohne Pause: $($_.Exception.Message)" }
  }
  $dumpCommand='MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb-dump -u "$MARIADB_USER" --single-transaction --no-tablespaces --routines --triggers "$MARIADB_DATABASE"'
  $dump=Join-Path $backup 'craftcore.sql'
  if($global:MaintDumpOverride){ & $global:MaintDumpOverride $dump } else {
    python -c "import subprocess,sys; f=open(sys.argv[1], 'wb'); r=subprocess.run(['docker','compose','exec','-T','craftcore-db','sh','-c',sys.argv[2]],stdout=f); f.close(); sys.exit(r.returncode)" $dump $dumpCommand
  }
  if($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $dump).Length -lt 100){throw "MariaDB-Dump fehlgeschlagen (exit $LASTEXITCODE, $((Get-Item -LiteralPath $dump).Length) Bytes)."}
  Write-MaintLog 'dump' 'info' "craftcore.sql $((Get-Item -LiteralPath $dump).Length) Bytes"
  # Ranks and purchased VIP permissions live in the separate `luckperms` database (same script as go-live.ps1).
  $lpDump=Join-Path $backup 'luckperms.sql'
  if($global:MaintDumpOverride){ & $global:MaintDumpOverride $lpDump } else {
    & python (Join-Path $PSScriptRoot 'scripts\golive-luckperms-dump.py') $lpDump
  }
  if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $lpDump) -or (Get-Item -LiteralPath $lpDump).Length -lt 100){throw "LuckPerms-Dump fehlgeschlagen (exit $LASTEXITCODE)."}
  Write-MaintLog 'dump' 'info' "luckperms.sql $((Get-Item -LiteralPath $lpDump).Length) Bytes"
  foreach($server in @('lobby','survival')) {
    $dest=Join-Path $backup $server
    New-Item -ItemType Directory -Path $dest | Out-Null
    $worlds=@(Get-MaintWorlds (Join-Path $PSScriptRoot "data\$server"))
    if($worlds.Count -eq 0){throw "Keine Welten fuer $server gefunden."}
    foreach($world in $worlds){ Copy-MaintWorld $world.FullName $dest }
    Write-MaintLog 'worlds' 'info' "${server}: $($worlds.Count) Welten kopiert"
  }
  # PvP arena and the player VIP worlds (data/vipworlds/<uuid>): optional, but copied whenever they exist.
  $pvpDir=Join-Path $PSScriptRoot 'data\pvp'
  if(Test-Path -LiteralPath $pvpDir){
    $worlds=@(Get-MaintWorlds $pvpDir)
    if($worlds.Count -gt 0){
      $dest=Join-Path $backup 'pvp'; New-Item -ItemType Directory -Path $dest | Out-Null
      foreach($world in $worlds){ Copy-MaintWorld $world.FullName $dest }
    }
    Write-MaintLog 'worlds' 'info' "pvp: $($worlds.Count) Welten kopiert"
  }
  $vipDir=Join-Path $PSScriptRoot 'data\vipworlds'
  if(Test-Path -LiteralPath $vipDir){
    $vips=@(Get-ChildItem -LiteralPath $vipDir -Directory)
    if($vips.Count -gt 0){
      $dest=Join-Path $backup 'vipworlds'; New-Item -ItemType Directory -Path $dest | Out-Null
      foreach($world in $vips){ Copy-MaintWorld $world.FullName $dest }
    }
    Write-MaintLog 'worlds' 'info' "vipworlds: $($vips.Count) Welten kopiert"
  }
  foreach($server in @($savesOff)){
    if($optional -contains $server){
      # A PvP/VIP server may have stopped meanwhile; that must not fail the backup, but it is logged as an error.
      try { Invoke-MaintRcon $server 'save-on' -AllProfiles; Write-MaintLog 'save' 'info' "${server}: save-on" }
      catch { Write-MaintLog 'save' 'error' "${server}: save-on fehlgeschlagen: $($_.Exception.Message)" }
    } else { Invoke-MaintRcon $server 'save-on'; Write-MaintLog 'save' 'info' "${server}: save-on" }
    $savesOff=@($savesOff | Where-Object { $_ -ne $server })
  }
  Set-Content -LiteralPath (Join-Path $backup 'COMPLETE.txt') -Value "Backup abgeschlossen: $(Get-Date -Format o)" -Encoding ASCII
  # PROJECT.md rule 5: no automatic deletion. Old complete backups are only reported (Gatekeeper #137).
  foreach($old in @(Get-RetentionCandidates (Join-Path $PSScriptRoot 'backups') 7)) { Write-MaintLog 'retention' 'warn' "aelter als 7 Tage, nur mit Nils' OK von Hand entfernen: $old" }
  $complete=$true
  Write-Host "Backup: $backup"
} catch {
  $exitCode=1
  $errorText=$_.Exception.Message
  Write-MaintLog 'error' 'error' $errorText
  if($backup){ Set-BackupFailed $backup $errorText | Out-Null }
} finally {
  # Saving must never stay off, also when a copy or the dump failed.
  foreach($server in $savesOff){
    try { Invoke-MaintRcon $server 'save-on' -AllProfiles:($optional -contains $server); Write-MaintLog 'save' 'info' "${server}: save-on (Aufraeumen)" }
    catch { Write-MaintLog 'save' 'error' "${server}: save-on fehlgeschlagen: $($_.Exception.Message)"; $exitCode=1 }
  }
  if($stopped -and (-not $LeaveStopped -or -not $complete)){
    docker compose up -d --no-deps lobby survival
    if($LASTEXITCODE -ne 0){ Write-MaintLog 'restart' 'error' 'Serverstart nach Backup fehlgeschlagen.'; $exitCode=1 }
    else { Write-MaintLog 'restart' 'info' 'lobby und survival gestartet' }
  }
  Exit-MaintLock $lock
  Stop-MaintLog $exitCode $errorText
}
# Same contract as before for callers (Homelab Deployer, tasks): a failure throws, so -File exits 1.
if($exitCode -ne 0){ throw $(if($errorText){$errorText}else{'Backup fehlgeschlagen.'}) }

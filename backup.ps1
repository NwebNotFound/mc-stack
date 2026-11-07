param([switch]$LeaveStopped, [switch]$PreserveBackups, [switch]$StopServers, [switch]$NoWarning, [int]$ReadyAttempts = 12, [int]$LockWaitSeconds = 600)
# Default: online backup, nothing is stopped (RCON save-off, save-all flush, copy, save-on).
# -StopServers: old behaviour (stop lobby+survival, copy, start). -LeaveStopped implies -StopServers (deploys).
if($LeaveStopped){ $StopServers=$true }
# Lock wait 10 min by default: the watchdog (tools/watchdog) holds the maintenance lock while it revives a service
# (a graceful restart can take 2+ min), and the backup must wait for that instead of failing.
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot 'tools/maintenance-common.ps1')
. (Join-Path $PSScriptRoot 'tools/watchdog/watchdog-common.ps1')
Start-MaintLog 'backup' $PSScriptRoot | Out-Null
$lock=$null
$stopped=$false
$savesOff=@()
$complete=$false
$backup=$null
$exitCode=0
$errorText=''
try {
  $lock=Enter-MaintLock $PSScriptRoot $LockWaitSeconds
  # The watchdog (tools/watchdog) must not revive what a backup stops on purpose.
  Set-WatchdogHold $PSScriptRoot 'backup' 'backup.ps1 laeuft' | Out-Null   # keeps an existing sticky deploy hold
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
  foreach($server in @('lobby','survival')) {
    $dest=Join-Path $backup $server
    New-Item -ItemType Directory -Path $dest | Out-Null
    $worlds=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "data\$server") -Directory | Where-Object { $_.Name -match '^world($|_)' -or (Test-Path -LiteralPath (Join-Path $_.FullName 'level.dat')) })
    if($worlds.Count -eq 0){throw "Keine Welten fuer $server gefunden."}
    if(-not $StopServers){
      # Online: the server keeps running but writes nothing to the region files while we copy.
      if(-not $NoWarning){ Invoke-MaintRcon $server 'say Backup laeuft, kurz kein Speichern. Spiel einfach weiter.' -Optional }
      $savesOff+=$server
      Invoke-MaintRcon $server 'save-off'
      Invoke-MaintRcon $server 'save-all flush'
      Write-MaintLog 'save' 'info' "${server}: save-off, save-all flush"
    }
    foreach($world in $worlds){ Copy-MaintWorld $world.FullName $dest }
    Write-MaintLog 'worlds' 'info' "${server}: $($worlds.Count) Welten kopiert"
    if(-not $StopServers){ Invoke-MaintRcon $server 'save-on'; $savesOff=@($savesOff | Where-Object { $_ -ne $server }); Write-MaintLog 'save' 'info' "${server}: save-on" }
  }
  $dumpCommand='MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb-dump -u "$MARIADB_USER" --single-transaction --no-tablespaces --routines --triggers "$MARIADB_DATABASE"'
  $dump=Join-Path $backup 'craftcore.sql'
  if($global:MaintDumpOverride){ & $global:MaintDumpOverride $dump } else {
    python -c "import subprocess,sys; f=open(sys.argv[1], 'wb'); r=subprocess.run(['docker','compose','exec','-T','craftcore-db','sh','-c',sys.argv[2]],stdout=f); f.close(); sys.exit(r.returncode)" $dump $dumpCommand
  }
  if($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $dump).Length -lt 100){throw "MariaDB-Dump fehlgeschlagen (exit $LASTEXITCODE, $((Get-Item -LiteralPath $dump).Length) Bytes)."}
  Write-MaintLog 'dump' 'info' "craftcore.sql $((Get-Item -LiteralPath $dump).Length) Bytes"
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
    try { Invoke-MaintRcon $server 'save-on'; Write-MaintLog 'save' 'info' "${server}: save-on (Aufraeumen)" }
    catch { Write-MaintLog 'save' 'error' "${server}: save-on fehlgeschlagen: $($_.Exception.Message)"; $exitCode=1 }
  }
  if($stopped -and (-not $LeaveStopped -or -not $complete)){
    docker compose up -d --no-deps lobby survival
    if($LASTEXITCODE -ne 0){ Write-MaintLog 'restart' 'error' 'Serverstart nach Backup fehlgeschlagen.'; $exitCode=1 }
    else { Write-MaintLog 'restart' 'info' 'lobby und survival gestartet' }
  }
  if($lock){
    # -LeaveStopped: lobby/survival stay down for the deploy. Sticky: the hold never expires, however long the deploy
    # takes; it ends only with `tools/watchdog/pause.ps1 -Resume -Owner backup`.
    if($LeaveStopped -and $complete){ Set-WatchdogHold $PSScriptRoot 'backup' 'backup.ps1 -LeaveStopped: lobby/survival fuer den Deploy gestoppt' -Sticky | Out-Null }
    else { Clear-WatchdogHold $PSScriptRoot 'backup' | Out-Null }   # never clears a sticky deploy hold
  }
  Exit-MaintLock $lock
  Stop-MaintLog $exitCode $errorText
}
# Same contract as before for callers (Homelab Deployer, tasks): a failure throws, so -File exits 1.
if($exitCode -ne 0){ throw $(if($errorText){$errorText}else{'Backup fehlgeschlagen.'}) }

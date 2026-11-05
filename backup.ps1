param([switch]$LeaveStopped, [switch]$PreserveBackups, [int]$ReadyAttempts = 12, [int]$LockWaitSeconds = 0)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot 'tools/maintenance-common.ps1')
Start-MaintLog 'backup' $PSScriptRoot | Out-Null
$lock=$null
$stopped=$false
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
  $stopped=$true
  docker compose stop lobby survival
  if($LASTEXITCODE -ne 0){throw 'Server konnten nicht gestoppt werden.'}
  Write-MaintLog 'stop' 'info' 'lobby und survival gestoppt'
  foreach($server in @('lobby','survival')) {
    $dest=Join-Path $backup $server
    New-Item -ItemType Directory -Path $dest | Out-Null
    $worlds=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "data\$server") -Directory | Where-Object { $_.Name -match '^world($|_)' -or (Test-Path -LiteralPath (Join-Path $_.FullName 'level.dat')) })
    if($worlds.Count -eq 0){throw "Keine Welten fuer $server gefunden."}
    foreach($world in $worlds){Copy-Item -LiteralPath $world.FullName -Destination $dest -Recurse}
    Write-MaintLog 'worlds' 'info' "${server}: $($worlds.Count) Welten kopiert"
  }
  $dumpCommand='MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb-dump -u "$MARIADB_USER" --single-transaction --no-tablespaces --routines --triggers "$MARIADB_DATABASE"'
  $dump=Join-Path $backup 'craftcore.sql'
  python -c "import subprocess,sys; f=open(sys.argv[1], 'wb'); r=subprocess.run(['docker','compose','exec','-T','craftcore-db','sh','-c',sys.argv[2]],stdout=f); f.close(); sys.exit(r.returncode)" $dump $dumpCommand
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

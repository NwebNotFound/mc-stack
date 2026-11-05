# Shared helpers for backup.ps1 and dev-reset.ps1: run log, readiness check, maintenance lock, failed-backup marker.
# No Docker or DB access happens at dot-source time, so tools/test-maintenance.ps1 can mock `docker`.

$script:MaintLog = $null
$script:MaintRun = $null

# One JSON line per event in logs/maintenance/<name>-<yyyy-MM>.log. A new file per month keeps files small;
# nothing is ever deleted here (old months are pruned by hand with Nils' OK, see DEPLOY.md).
function Start-MaintLog([string]$Name, [string]$Root = $PSScriptRoot) {
  $dir = Join-Path $Root 'logs/maintenance'
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $script:MaintLog = Join-Path $dir ("{0}-{1}.log" -f $Name, (Get-Date -Format 'yyyy-MM'))
  $script:MaintRun = "{0}-{1}" -f $Name, (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
  Write-MaintLog 'start' 'info' ("pid {0}, user {1}" -f $PID, [Environment]::UserName)
  return $script:MaintLog
}

function Write-MaintLog([string]$Step, [string]$Level, [string]$Message) {
  $line = [ordered]@{ ts = (Get-Date).ToString('o'); run = $script:MaintRun; step = $Step; level = $Level; msg = $Message } | ConvertTo-Json -Compress
  if ($script:MaintLog) { Add-Content -LiteralPath $script:MaintLog -Value $line -Encoding UTF8 }
  if ($Level -eq 'error') { Write-Warning "${Step}: $Message" } else { Write-Host "[$Step] $Message" }
}

function Stop-MaintLog([int]$ExitCode, [string]$ErrorText = '') {
  $level = if ($ExitCode -eq 0) { 'info' } else { 'error' }
  Write-MaintLog 'end' $level ("exit {0}{1}" -f $ExitCode, $(if ($ErrorText) { ": $ErrorText" } else { '' }))
}

# Docker up, craftcore-db healthy and answering `SELECT 1`. Bounded retries, then a clear error.
function Wait-MaintReady([int]$Attempts = 12, [int]$DelaySeconds = 10) {
  $last = ''
  for ($i = 1; $i -le $Attempts; $i++) {
    $null = & docker info --format '{{.ServerVersion}}' 2>&1
    if ($LASTEXITCODE -ne 0) { $last = 'Docker antwortet nicht' }
    else {
      $health = (& docker inspect --format '{{.State.Health.Status}}' mc-stack-craftcore-db-1 2>&1) -join ''
      if ($LASTEXITCODE -ne 0 -or $health.Trim() -ne 'healthy') { $last = "craftcore-db nicht healthy ($($health.Trim()))" }
      else {
        $out = 'SELECT 1;' | & docker compose exec -T craftcore-db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb -N -u "$MARIADB_USER" "$MARIADB_DATABASE"' 2>&1
        if ($LASTEXITCODE -eq 0 -and (($out -join '').Trim() -eq '1')) { Write-MaintLog 'ready' 'info' "Docker und craftcore-db bereit (Versuch $i)"; return }
        $last = 'craftcore-db beantwortet SELECT 1 nicht'
      }
    }
    Write-MaintLog 'ready' 'warn' "Versuch $i/${Attempts}: $last"
    if ($i -lt $Attempts) { & $global:MaintSleep $DelaySeconds }
  }
  throw "Nicht bereit nach ${Attempts} Versuchen: $last"
}
if (-not $global:MaintSleep) { $global:MaintSleep = { param($s) Start-Sleep -Seconds $s } }

# Cross-session lock (scheduled tasks may run in another logon session, where a Local\ mutex is not shared).
# An exclusive file handle is released by the OS even if the process dies.
function Enter-MaintLock([string]$Root = $PSScriptRoot, [int]$WaitSeconds = 0, [int]$PollSeconds = 15) {
  $dir = Join-Path $Root 'backups'
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $path = Join-Path $dir '.maintenance.lock'
  $deadline = (Get-Date).AddSeconds($WaitSeconds)
  while ($true) {
    try {
      $stream = [IO.File]::Open($path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
      Write-MaintLog 'lock' 'info' 'Wartungssperre erhalten'
      return $stream
    } catch [IO.IOException] {
      if ((Get-Date) -ge $deadline) { throw 'Ein anderer Wartungsvorgang laeuft (Sperre belegt).' }
      Write-MaintLog 'lock' 'warn' 'Sperre belegt, warte ...'
      & $global:MaintSleep $PollSeconds
    }
  }
}
function Exit-MaintLock($Stream) { if ($Stream) { $Stream.Dispose() } }

# A backup without COMPLETE.txt never looks valid: write FAILED.txt with the reason and rename the folder to
# <name>-FAILED. Nothing is deleted.
function Set-BackupFailed([string]$Backup, [string]$Reason) {
  if (-not $Backup -or -not (Test-Path -LiteralPath $Backup)) { return $null }
  if (Test-Path -LiteralPath (Join-Path $Backup 'COMPLETE.txt')) { return $Backup }
  Set-Content -LiteralPath (Join-Path $Backup 'FAILED.txt') -Value "Backup fehlgeschlagen: $(Get-Date -Format o)`n$Reason" -Encoding UTF8
  $target = "$Backup-FAILED"
  if (Test-Path -LiteralPath $target) { $target = "$Backup-FAILED-$(Get-Date -Format 'HHmmss-fff')" }
  Rename-Item -LiteralPath $Backup -NewName (Split-Path $target -Leaf)
  Write-MaintLog 'backup' 'error' "als fehlgeschlagen markiert: $target"
  return $target
}

# Complete daily backups older than $Days days. Only reported; deleting them needs Nils' explicit OK (rule 5).
function Get-RetentionCandidates([string]$BackupRoot, [int]$Days = 7) {
  if (-not (Test-Path -LiteralPath $BackupRoot)) { return @() }
  @(Get-ChildItem -LiteralPath $BackupRoot -Directory -Filter 'daily-*' |
    Where-Object { $_.Name -notlike '*-FAILED*' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'COMPLETE.txt')) -and $_.CreationTime -lt (Get-Date).AddDays(-$Days) } |
    ForEach-Object { $_.FullName })
}

# Survival dev-reset zips beyond the newest $Keep. Only reported (rule 5).
function Get-DevResetCandidates([string]$BackupDir, [int]$Keep = 7) {
  if (-not (Test-Path -LiteralPath $BackupDir)) { return @() }
  @(Get-ChildItem -LiteralPath $BackupDir -Filter 'survival-dev-reset-*.zip' -File | Sort-Object Name -Descending | Select-Object -Skip $Keep | ForEach-Object { $_.FullName })
}

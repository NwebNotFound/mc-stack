param([switch]$LeaveStopped, [switch]$PreserveBackups)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$mutex=New-Object System.Threading.Mutex($false,'Local\MCStackMaintenance')
if(-not $mutex.WaitOne(0)){throw 'Ein anderer Wartungsvorgang laeuft.'}
$stopped=$false
$complete=$false
try {
  $stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
  $backup=Join-Path $PSScriptRoot "backups\daily-$stamp"
  New-Item -ItemType Directory -Path $backup | Out-Null
  $stopped=$true
  docker compose stop lobby survival
  if($LASTEXITCODE -ne 0){throw 'Server konnten nicht gestoppt werden.'}
  $stopped=$true
  foreach($server in @('lobby','survival')) {
    $dest=Join-Path $backup $server
    New-Item -ItemType Directory -Path $dest | Out-Null
    $worlds=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "data\$server") -Directory | Where-Object { $_.Name -match '^world($|_)' -or (Test-Path -LiteralPath (Join-Path $_.FullName 'level.dat')) })
    if($worlds.Count -eq 0){throw "Keine Welten fuer $server gefunden."}
    foreach($world in $worlds){Copy-Item -LiteralPath $world.FullName -Destination $dest -Recurse}
  }
  $dumpCommand='exec mariadb-dump -u "$MARIADB_USER" -p"$MARIADB_PASSWORD" --single-transaction --no-tablespaces --routines --triggers "$MARIADB_DATABASE"'
  $dump=Join-Path $backup 'craftcore.sql'
  python -c "import subprocess,sys; f=open(sys.argv[1], 'wb'); r=subprocess.run(['docker','compose','exec','-T','craftcore-db','sh','-c',sys.argv[2]],stdout=f); f.close(); sys.exit(r.returncode)" $dump $dumpCommand
  if($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $dump).Length -lt 100){throw 'MariaDB-Dump fehlgeschlagen.'}
  Set-Content -LiteralPath (Join-Path $backup 'COMPLETE.txt') -Value "Backup abgeschlossen: $(Get-Date -Format o)" -Encoding ASCII
  $backupRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'backups')).Path.TrimEnd('\') + '\'
  foreach($old in @(Get-ChildItem -LiteralPath $backupRoot -Directory -Filter 'daily-*' | Where-Object { -not $PreserveBackups -and (Test-Path -LiteralPath (Join-Path $_.FullName 'COMPLETE.txt')) -and $_.CreationTime -lt (Get-Date).AddDays(-7) })) {
    $target=(Resolve-Path -LiteralPath $old.FullName).Path
    if(-not $target.StartsWith($backupRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Ungueltiger Backup-Aufbewahrungspfad.'}
    Remove-Item -LiteralPath $target -Recurse -Force
  }
  $complete=$true
  Write-Host "Backup: $backup"
} finally {
  if($stopped -and (-not $LeaveStopped -or -not $complete)){docker compose up -d --no-deps lobby survival; if($LASTEXITCODE -ne 0){Write-Error 'Serverstart nach Backup fehlgeschlagen.'}}
  $mutex.ReleaseMutex();$mutex.Dispose()
}

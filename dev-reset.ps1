param([switch]$DryRun, [switch]$ClearChat, [int]$ReadyAttempts = 12, [int]$LockWaitSeconds = 1800)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot 'tools/maintenance-common.ps1')
. (Join-Path $PSScriptRoot 'tools/watchdog/watchdog-common.ps1')
function Get-ServerMode {
    $sql = "SELECT mode FROM server_status WHERE id=1;"
    $result = $sql | & docker compose exec -T craftcore-db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb -N -u "$MARIADB_USER" "$MARIADB_DATABASE"' 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Servermodus aus der Datenbank nicht lesbar; keine Aktion.' }
    $mode = ($result -join '').Trim()
    if ($mode -cnotin @('dev','online','wartung')) { throw 'Ungueltiger Servermodus; keine Aktion.' }
    return $mode
}
function Check-Dev { return (Get-ServerMode) -ceq 'dev' }
function Invoke-Docker([string[]]$Arguments) {
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker fehlgeschlagen: $($output -join ' ')" }
    return $output
}
function Clear-AllChat {
    # A single JSON text component contains 120 line breaks, without console spam.
    $json = '{\"text\":\"' + ('\n' * 120) + '\"}'
    foreach ($server in @('lobby','survival')) {
        try { Invoke-Docker @('compose','exec','-T',$server,'rcon-cli',"tellraw @a $json") | Out-Null }
        catch { Write-Warning "Chat auf $server nicht geleert: $_" }
    }
}
Start-MaintLog 'dev-reset' $PSScriptRoot | Out-Null
$maintLock = $null
$maintExit = 0
$maintError = ''
try {
# A catch-up run right after a backup waits for the backup's lock (up to 30 min) instead of colliding with it,
# and only then checks that Docker and craftcore-db answer (05.11., 11:36: both tasks started while the DB was down).
if (-not $DryRun) { $maintLock = Enter-MaintLock $PSScriptRoot $LockWaitSeconds; Set-WatchdogHold $PSScriptRoot 'dev-reset' 'dev-reset.ps1 laeuft' | Out-Null }
Wait-MaintReady $ReadyAttempts
if (-not (Check-Dev)) {
    $online = (Get-ServerMode) -ceq 'online'
    if ($online -and -not $ClearChat) {
        if ($DryRun) { Write-Host 'TROCKENLAUF: wuerde Lobby und Survival sauber neu starten.'; exit 0 }
        Invoke-Docker @('compose','restart','lobby','survival') | Out-Null
        Write-Host 'Online-Neustart abgeschlossen.'; exit 0
    }
    Write-Host 'Server nicht im Dev-Modus: keine Aktion.'; exit 0
}
if ($DryRun) {
    Write-Host 'TROCKENLAUF: Dev-Modus aktiv. Keine Aenderungen, keine RCON-Nachrichten.'
    if ($ClearChat) { Write-Host 'Wuerde Chat auf Lobby und Survival leeren.'; exit 0 }
    foreach ($name in @('world','world_nether','world_the_end')) { Write-Host "Wuerde sichern und entfernen: data/survival/$name (falls vorhanden)" }
    Write-Host 'Wuerde vorher warnen, 5 Minuten warten, Survival stoppen und ZIP mit Welten und CraftCore-Weltdaten erstellen und pruefen.'
    Write-Host 'Wuerde nur Survival-Daten fuer world, world_nether, world_the_end aus claims, homes, graves entfernen; CP, Teams und Lobby/Parkour bleiben.'
    Write-Host 'Wuerde Survival starten, Chat leeren und Reset-Backups ueber den letzten 7 nur im Log melden (nichts loeschen).'
    exit 0
}
    if ($ClearChat) { Clear-AllChat; exit 0 }
    foreach ($server in @('lobby','survival')) {
        Invoke-Docker @('compose','exec','-T',$server,'rcon-cli','say [DEV] Survival wird in 5 Minuten zurueckgesetzt.') | Out-Null
    }
    Start-Sleep -Seconds 300
    if (-not (Check-Dev)) { Write-Host 'DEV_MODE inzwischen deaktiviert; Reset abgebrochen.'; exit 0 }
    if (Select-String -Path 'data/survival/server.properties' -Pattern '^level-seed=.+' -Quiet) { throw 'Ein fester Survival-Seed ist gesetzt; Reset abgebrochen.' }
    Invoke-Docker @('compose','stop','survival') | Out-Null
    $destructive = $false
    try {
        $backupDir = Join-Path $PSScriptRoot 'backups'
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
        $temp = Join-Path $backupDir "dev-reset-$stamp.tmp"
        $zip = Join-Path $backupDir "survival-dev-reset-$stamp.zip"
        New-Item -ItemType Directory -Path $temp | Out-Null
        foreach ($name in @('world','world_nether','world_the_end')) {
            $path = Join-Path $PSScriptRoot "data/survival/$name"
            if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination $temp -Recurse }
        }
        if (-not (Test-Path -LiteralPath (Join-Path $temp 'world'))) { throw 'Survival-Hauptwelt fehlt; Reset abgebrochen.' }
        $dumpCommand = 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb-dump -u "$MARIADB_USER" --single-transaction --no-tablespaces "$MARIADB_DATABASE" claims homes graves'
        $dump = Invoke-Docker @('compose','exec','-T','craftcore-db','sh','-c',$dumpCommand)
        [IO.File]::WriteAllLines((Join-Path $temp 'craftcore-claims-homes-graves.sql'), [string[]]$dump, (New-Object Text.UTF8Encoding $false))
        Compress-Archive -Path (Join-Path $temp '*') -DestinationPath $zip -CompressionLevel Optimal
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($zip)
        try {
            foreach ($file in Get-ChildItem -LiteralPath $temp -Recurse -File) {
                $relative = $file.FullName.Substring($temp.Length + 1).Replace('\','/')
                $entry = $archive.GetEntry($relative)
                if (-not $entry) { $entry = $archive.GetEntry($relative.Replace('/','\')) }
                if (-not $entry) { throw "Backup-Eintrag fehlt: $relative" }
                $sha = [Security.Cryptography.SHA256]::Create()
                $stream = $entry.Open()
                try { $zipHash = [BitConverter]::ToString($sha.ComputeHash($stream)) } finally { $stream.Dispose(); $sha.Dispose() }
                $fileHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
                if ($zipHash.Replace('-','') -ne $fileHash) { throw "Backup-Pruefung fehlgeschlagen: $relative" }
            }
        } finally { $archive.Dispose() }
        Remove-Item -LiteralPath $temp -Recurse -Force
        $sql = "START TRANSACTION; DELETE FROM claims WHERE server='Survival' AND world IN ('world','world_nether','world_the_end'); DELETE FROM homes WHERE server='Survival' AND world IN ('world','world_nether','world_the_end'); DELETE FROM graves WHERE server='Survival' AND world IN ('world','world_nether','world_the_end'); COMMIT;"
        $dbCommand = 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb -u "$MARIADB_USER" "$MARIADB_DATABASE"'
        $dbOutput = $sql | & docker compose exec -T craftcore-db sh -c $dbCommand 2>&1
        if ($LASTEXITCODE -ne 0) { throw "SQL-Reset fehlgeschlagen: $($dbOutput -join ' ')" }
        $destructive = $true
        foreach ($name in @('world','world_nether','world_the_end')) {
            $path = Join-Path $PSScriptRoot "data/survival/$name"
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
        }
        Invoke-Docker @('compose','up','-d','--no-deps','survival') | Out-Null
        $deadline = (Get-Date).AddMinutes(10)
        do { Start-Sleep 5; $health = (Invoke-Docker @('inspect','--format','{{.State.Health.Status}}','mc-stack-survival-1')) -join '' } while ($health -ne 'healthy' -and (Get-Date) -lt $deadline)
        if ($health -ne 'healthy') { throw 'Survival wurde nicht rechtzeitig healthy.' }
        Clear-AllChat
        # PROJECT.md rule 5: older reset backups are only reported, never deleted (Gatekeeper #137).
        foreach ($old in @(Get-DevResetCandidates $backupDir 7)) { Write-MaintLog 'retention' 'warn' "mehr als 7 Reset-Backups, nur mit Nils' OK von Hand entfernen: $old" }
        Write-Host "DEV-Reset abgeschlossen. Backup: $zip"
    } catch {
        if (-not $destructive) { try { Invoke-Docker @('compose','up','-d','--no-deps','survival') | Out-Null } catch { Write-Warning "Survival-Neustart fehlgeschlagen: $_" } }
        throw
    }
} catch {
    $maintExit = 1
    $maintError = $_.Exception.Message
    Write-MaintLog 'error' 'error' $maintError
    throw
} finally {
    if ($maintLock) {
        # A failed destructive reset leaves Survival stopped for a human: the watchdog must not start it.
        if ($maintExit -ne 0 -and $destructive) { Set-WatchdogHold $PSScriptRoot 'dev-reset' 'Dev-Reset fehlgeschlagen, Survival bleibt gestoppt: erst pruefen, dann tools/watchdog/pause.ps1 -Resume -Owner dev-reset' -Sticky | Out-Null }
        else { Clear-WatchdogHold $PSScriptRoot 'dev-reset' | Out-Null }   # never clears a sticky failure hold
    }
    Exit-MaintLock $maintLock
    Stop-MaintLog $maintExit $maintError
}

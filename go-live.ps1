param([switch]$DryRun)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$resetTables = @('accounts','ah_journal','bank_daily','bank_journal','bank_revenue_daily','buy_order_sales','buy_orders','invite_reward_notices','invites','invites_legacy_migrated','market_prices','motivation','online_players','order_fulfillment','pending_deliveries','player_challenges','player_inventory','player_inventory_sync','player_season_points','purchase_ids','rcon_purchases','sale_notices','shop_events','shop_journal','shop_notifications','spawn_transfers')
$preservedTables = @('claims','graves','home_slots','homes','known_players','members','parkour_times','server_status','team_invites','teams')
$expectedViews = @('v_ah_listings','v_order_fulfillment','v_player_balance','v_player_inventory','v_server_status','v_shop_listings','v_shop_events')
function Invoke-Docker([string[]]$Arguments) {
    $out = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker-Aufruf fehlgeschlagen ($($Arguments[0..1] -join ' '))." }
    return $out
}
function Sql([string]$Query) {
    $out = $Query | & docker compose exec -T craftcore-db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb -N -u "$MARIADB_USER" "$MARIADB_DATABASE"' 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Datenbankabfrage fehlgeschlagen.' }
    return @($out | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
}
function Get-Worlds([string]$Server) {
    $base = Join-Path $PSScriptRoot "data\$Server"
    @(Get-ChildItem -LiteralPath $base -Directory -Force | Where-Object { $_.Name -match '^world($|_)' -or (Test-Path -LiteralPath (Join-Path $_.FullName 'level.dat')) })
}
function Check-Inputs {
    $actual = @(Sql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;")
    $expected = @($resetTables + $preservedTables | Sort-Object)
    $unknown = @($actual | Where-Object { $_ -notin $expected })
    if ($unknown.Count) { throw 'Unbekannte CraftCore-Tabelle: Resetplan vor Go-Live aktualisieren.' }
    $script:presentTables = $actual
    $views = @(Sql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='VIEW' ORDER BY TABLE_NAME;")
    if (@($views | Where-Object { $_ -notin $expectedViews }).Count) { throw 'Unbekannte CraftCore-View: Resetplan vor Go-Live aktualisieren.' }
    $engines = @(Sql "SELECT DISTINCT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE';")
    if ($engines.Count -ne 1 -or $engines[0] -ne 'InnoDB') { throw 'Tabellen sind nicht ausschliesslich transaktional (InnoDB).' }
    if (@(Sql 'SELECT mode FROM server_status WHERE id=1;').Count -ne 1) { throw 'Serverstatus fehlt oder ist nicht eindeutig.' }
    $task = Get-ScheduledTask -TaskName 'MCStack-Dev-Reset' -ErrorAction Stop
    if ($task.State -eq 'Running') { throw 'DEV-Reset-Aufgabe laeuft bereits.' }
    $worlds = @(Get-Worlds 'survival')
    if (-not ($worlds | Where-Object { $_.Name -eq 'world' })) { throw 'Survival-Hauptwelt fehlt.' }
    foreach ($world in $worlds) {
        if ($world.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Welt ist ein Verweis; Abbruch.' }
    }
    & python (Join-Path $PSScriptRoot 'scripts\golive-luckperms-dump.py') --check
    if ($LASTEXITCODE -ne 0) { throw 'LuckPerms-Backupzugriff nicht verfuegbar.' }
    return $worlds
}
function Health([string]$Service) {
    $deadline = (Get-Date).AddMinutes(10)
    do {
        $id = @(Invoke-Docker @('compose','ps','-q',$Service))
        if ($id.Count -ne 1 -or -not $id[0]) { throw "Container fuer $Service fehlt." }
        $health = @(Invoke-Docker @('inspect','--format','{{.State.Health.Status}}',"$($id[0])"))[0]
        if ($health -eq 'healthy') { return }
        if ($health -eq 'unhealthy') { throw "$Service ist unhealthy." }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw "Healthcheck fuer $Service abgelaufen."
}
Write-Host 'GO-LIVE-PLAN (Trockenlauf aendert nichts):'
Write-Host '1. Wartungs-Mutex; Velocity, Lobby und Survival sauber stoppen. MariaDB bleibt fuer Dumps erreichbar.'
Write-Host '2. Zeitstempel-Backup: komplette Datenverzeichnisse von Lobby, Survival, Velocity, config, compose.yaml, .env und secrets; SQL-Dumps CraftCore und LuckPerms. Kopien per SHA-256 pruefen; Abschlussmarkierung.'
Write-Host '3. Survival-Welten samt Inventaren, Fortschritten und Stats loeschen; Lobby-Stats und Advancements loeschen, Lobby-Spielerdaten und Bauten behalten.'
Write-Host "4. DB leeren: $($resetTables -join ', '); Claims, Homes, Home-Slots und Graeber nur auf Survival entfernen. Teams, Parkour-Zeiten, LuckPerms, Whitelist und OP bleiben."
Write-Host '5. Windows-Aufgabe MCStack-Dev-Reset deaktivieren; DB-Modus auf online setzen; Lobby, Survival und Velocity starten; Healthchecks und lokaler Status-Ping.'
Write-Host 'Bei Fehler nach Resetbeginn bleiben Server gestoppt; Backup manuell wiederherstellen. Backup enthaelt vertrauliche Zugangsdaten.'
if ($DryRun) {
    $worlds = @(Check-Inputs)
    Write-Host "Aktuelle Survival-Welten: $(($worlds.Name) -join ', ')"
    Write-Host "DEV-Aufgabe: $((Get-ScheduledTask -TaskName 'MCStack-Dev-Reset').State); Datenbank- und LuckPerms-Zugriff geprueft."
    Write-Host 'TROCKENLAUF abgeschlossen; keine Aenderungen.'
    return
}
if ((Read-Host 'Zum unwiderruflichen Reset exakt GO LIVE eingeben') -cne 'GO LIVE') { throw 'Bestaetigung stimmt nicht; keine Aenderungen.' }
$mutex = New-Object System.Threading.Mutex($false,'Local\MCStackMaintenance')
if (-not $mutex.WaitOne(0)) { $mutex.Dispose(); throw 'Ein anderer Wartungsvorgang laeuft.' }
$stopped = $false; $destructive = $false; $backup = $null
try {
    $worlds = @(Check-Inputs)
    $backup = Join-Path $PSScriptRoot ("backups\golive-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $backup | Out-Null
    $stopped = $true
    Invoke-Docker @('compose','stop','velocity','lobby','survival') | Out-Null
    foreach ($path in @('data\lobby','data\survival','data\velocity','config','secrets','.env','compose.yaml')) {
        $source = Join-Path $PSScriptRoot $path
        if (-not (Test-Path -LiteralPath $source)) { throw "Backup-Quelle fehlt: $path" }
        $dest = Join-Path $backup $path
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
        Copy-Item -LiteralPath $source -Destination $dest -Recurse -Force
        $files = if (Test-Path -LiteralPath $source -PathType Container) { @(Get-ChildItem -LiteralPath $source -Recurse -Force -File) } else { @(Get-Item -LiteralPath $source) }
        foreach ($file in $files) {
            $copy = if ($file.FullName -eq $source) { $dest } else { Join-Path $dest $file.FullName.Substring($source.Length + 1) }
            if (-not (Test-Path -LiteralPath $copy -PathType Leaf) -or (Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $copy).Hash) { throw "Backup-Pruefung fehlgeschlagen: $path" }
        }
    }
    $dump = Join-Path $backup 'craftcore.sql'
    $dumpCommand = 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb-dump -u "$MARIADB_USER" --single-transaction --quick --no-tablespaces --routines --triggers "$MARIADB_DATABASE"'
    & python -c "import subprocess,sys; f=open(sys.argv[1],'xb'); r=subprocess.run(['docker','compose','exec','-T','craftcore-db','sh','-c',sys.argv[2]],stdout=f,stderr=subprocess.DEVNULL); f.close(); sys.exit(r.returncode)" $dump $dumpCommand
    if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $dump).Length -lt 100 -or -not (Select-String -LiteralPath $dump -Pattern '-- Dump completed' -Quiet -SimpleMatch)) { throw 'CraftCore-Dump fehlgeschlagen.' }
    & python (Join-Path $PSScriptRoot 'scripts\golive-luckperms-dump.py') (Join-Path $backup 'luckperms.sql')
    if ($LASTEXITCODE -ne 0) { throw 'LuckPerms-Dump fehlgeschlagen.' }
    Set-Content -LiteralPath (Join-Path $backup 'COMPLETE.txt') -Value "Backup abgeschlossen: $(Get-Date -Format o)" -Encoding ASCII
    $destructive = $true
    Disable-ScheduledTask -TaskName 'MCStack-Dev-Reset' | Out-Null
    if ((Get-ScheduledTask -TaskName 'MCStack-Dev-Reset').State -ne 'Disabled') { throw 'DEV-Aufgabe konnte nicht deaktiviert werden.' }
    $deletes = ($resetTables | Where-Object { $_ -in $script:presentTables } | ForEach-Object { "DELETE FROM ``$_``;" }) -join ' '
    $sql = "START TRANSACTION; $deletes DELETE FROM claims WHERE server='Survival'; DELETE FROM homes WHERE server='Survival'; DELETE FROM home_slots WHERE server='Survival'; DELETE FROM graves WHERE server='Survival'; COMMIT;"
    Sql $sql | Out-Null
    foreach ($world in $worlds) { Remove-Item -LiteralPath $world.FullName -Recurse -Force }
    foreach ($world in @(Get-Worlds 'lobby')) {
        foreach ($sub in @('players\stats','players\advancements','stats','advancements')) {
            $path = Join-Path $world.FullName $sub
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
        }
    }
    Sql "UPDATE server_status SET mode='online' WHERE id=1;" | Out-Null
    if (@(Sql 'SELECT mode FROM server_status WHERE id=1;')[0] -cne 'online') { throw 'Servermodus konnte nicht auf online gesetzt werden.' }
    Invoke-Docker @('compose','up','-d','--no-deps','lobby','survival') | Out-Null
    Health 'lobby'; Health 'survival'
    Invoke-Docker @('compose','up','-d','--no-deps','velocity') | Out-Null
    Health 'velocity'
    & python (Join-Path $PSScriptRoot 'scripts\check-local.py')
    if ($LASTEXITCODE -ne 0) { throw 'Minecraft-Status-Ping fehlgeschlagen.' }
    Write-Host "Go-Live abgeschlossen. Backup: $backup"
} catch {
    if ($stopped -and -not $destructive) {
        try { Invoke-Docker @('compose','up','-d','--no-deps','lobby','survival','velocity') | Out-Null } catch { Write-Warning 'Automatischer Start nach Backupfehler fehlgeschlagen.' }
    } elseif ($destructive) {
        try { Invoke-Docker @('compose','stop','velocity','lobby','survival') | Out-Null } catch { Write-Warning 'Server konnten nicht alle gestoppt werden.' }
        Write-Warning "Reset unvollstaendig: Server gestoppt lassen und Backup manuell wiederherstellen: $backup"
    }
    throw
} finally { $mutex.ReleaseMutex(); $mutex.Dispose() }


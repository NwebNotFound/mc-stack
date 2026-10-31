# Registriert (idempotent) die Aufgabe "mc-stack PvP host service": Start bei Anmeldung des aktuellen Users,
# Neustart bei Fehler (3x, 1 min), keine Laufzeitgrenze. Startet sie sofort. Bind/Port/Token kommen aus .env via run.ps1.
$ErrorActionPreference = 'Stop'
$name = 'mc-stack PvP host service'
$run = Join-Path $PSScriptRoot 'run.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$run`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Description 'PvP host service 127.0.0.1:25599 (nur mc-stack-pvp-1 start/stop/status)' -Force | Out-Null
Start-ScheduledTask -TaskName $name
Start-Sleep 3
Get-NetTCPConnection -LocalPort 25599 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress, LocalPort
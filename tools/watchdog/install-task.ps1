# Registers (idempotent) the scheduled task "mc-stack watchdog": runs tools\watchdog\watchdog.ps1 every
# 2 minutes as the current user (same session as Docker Desktop and the PvP host service), never two at once,
# at most 5 minutes per run. -Uninstall removes the task again (logs and status stay).
# Modelled on tools\pvp-host-service\install-task.ps1.
param([switch]$Uninstall)
$ErrorActionPreference = 'Stop'
$name = 'mc-stack watchdog'
if ($Uninstall) {
  if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName $name -Confirm:$false; "Task '$name' removed." } else { "Task '$name' is not registered." }
  return
}
$script = Join-Path $PSScriptRoot 'watchdog.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`""
$every = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 2) -RepetitionDuration (New-TimeSpan -Days 3650)
$logon = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 5) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $name -Action $action -Trigger @($every, $logon) -Settings $settings -Description 'mc-stack watchdog: revives dead always-on services (tools\watchdog\README.md)' -Force | Out-Null
"Task '$name' registered: every 2 minutes. Status: logs\watchdog\status.json"

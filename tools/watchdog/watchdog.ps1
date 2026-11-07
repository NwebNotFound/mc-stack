# One watchdog pass over the allowlist in targets.json (see README.md). Run every 2 minutes by the
# scheduled task "mc-stack watchdog" (install-task.ps1). Exit 0 also when a target was revived or
# given up on: the result is in logs/watchdog/status.json and the monthly log, not in the exit code.
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root
. (Join-Path $root 'tools/maintenance-common.ps1')   # Enter-/Exit-MaintLock: revives happen under the maintenance lock
. (Join-Path $PSScriptRoot 'watchdog-common.ps1')
try {
  Invoke-Watchdog $root | Out-Null
} catch {
  Write-WdLog '-' 'error' $_.Exception.Message 'error'
  exit 1
}

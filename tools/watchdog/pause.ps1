# Pause or resume the watchdog, or reset a target that needs a human.
#   pause.ps1 -Hours 2 -Reason "Umbau"      pause (manual hold, max holdMaxHours from targets.json)
#   pause.ps1 -Resume                      end the manual pause
#   pause.ps1 -Resume -Owner backup        end the hold that backup.ps1 -LeaveStopped leaves for a deploy
#   pause.ps1 -Reset survival              clear needsHuman and the revive history of one target
#   pause.ps1                              show active holds and targets that need a human
param([double]$Hours = 1, [string]$Reason = 'manual', [switch]$Resume, [string]$Owner = 'manual', [string]$Reset)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
. (Join-Path $PSScriptRoot 'watchdog-common.ps1')
$cfg = Get-WdConfig (Join-Path $PSScriptRoot 'targets.json')
if ($Reset) {
  $p = Join-Path $root 'logs/watchdog/state.json'
  $s = Read-WdJson $p $null
  if (-not $s -or -not ($s.PSObject.Properties.Name -contains $Reset)) { "No state for '$Reset'."; return }
  $s.$Reset.needsHuman = $false; $s.$Reset.revives = @(); $s.$Reset.unhealthyStreak = 0
  Write-WdJson $p $s
  "'$Reset' reset: the watchdog may revive it again."
  return
}
if ($Resume) { Clear-WatchdogHold $root $Owner; "Hold '$Owner' cleared." }
elseif ($PSBoundParameters.ContainsKey('Hours') -or $PSBoundParameters.ContainsKey('Reason')) {
  Set-WatchdogHold $root $Owner $Reason ([Math]::Min($Hours, [double]$cfg.holdMaxHours))
  "Watchdog paused ($Owner, $Reason) for $([Math]::Min($Hours, [double]$cfg.holdMaxHours)) h."
}
$holds = @(Get-WdHolds $root $cfg.holdMaxHours)
if ($holds.Count) { 'Active holds:'; $holds | Format-Table -AutoSize | Out-String } else { 'No active hold.' }
$st = Read-WdJson (Join-Path $root 'logs/watchdog/status.json') $null
if ($st -and @($st.needsHuman).Count) { "Needs a human: $(@($st.needsHuman) -join ', ')" }

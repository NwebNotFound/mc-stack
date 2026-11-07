# No Docker, no scheduled tasks: tests tools/watchdog with a mocked `docker` and mocked Wd* probes.
# Run: powershell -NoProfile -File tools/test-watchdog.ps1   (or pwsh)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$root = Join-Path ([IO.Path]::GetTempPath()) ('wd-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$root/backups" | Out-Null
Set-Content -LiteralPath "$root/.env" -Value "PVP_HOST_PORT=25599`nVIP_HOST_TOKEN=secret-token-value" -Encoding UTF8
$config = Join-Path $repo 'tools/watchdog/targets.json'
$global:MaintSleep = { param($s) }   # no real waiting in tests
. (Join-Path $repo 'tools/maintenance-common.ps1')
. (Join-Path $repo 'tools/watchdog/watchdog-common.ps1')
$fails = 0
function Check($cond, $msg) { if ($cond) { Write-Host "  ok  $msg" } else { Write-Host "  FAIL $msg"; $script:fails++ } }

# Mock world: container states per service, host task state, and every action taken.
$global:Ct = @{}; $global:Calls = New-Object System.Collections.Generic.List[string]
$global:TaskState = 'Running'; $global:PortOpen = $true; $global:HttpOk = $true; $global:DockerUp = $true; $global:HttpHeaders = $null
function Reset-World { $global:Calls.Clear(); foreach ($s in 'velocity','lobby','survival','craftcore-db','bot','playit','ops-helper','docker-socket-filter','plugin-builder') { $global:Ct[$s] = 'running|healthy' }; $global:TaskState = 'Running'; $global:PortOpen = $true; $global:HttpOk = $true; $global:DockerUp = $true }
function global:docker {
  $a = @($args); $global:LASTEXITCODE = 0
  if ($a[0] -eq 'info') { if (-not $global:DockerUp) { $global:LASTEXITCODE = 1 }; return '27.0' }
  if ($a[0] -eq 'inspect') { $svc = ($a[-1] -replace '^mc-stack-', '' -replace '-1$', ''); if ($global:OnInspect) { & $global:OnInspect $svc }; if (-not $global:Ct.ContainsKey($svc) -or $global:Ct[$svc] -eq 'missing') { $global:LASTEXITCODE = 1; return 'Error: No such object' }; return $global:Ct[$svc] }
  $global:Calls.Add('docker ' + ($a -join ' ')); return ''
}
function Get-WdTaskState([string]$Task) { $global:Calls.Add("probe-task $Task"); return $global:TaskState }
function Start-WdTask([string]$Task) { $global:Calls.Add("start-task $Task") }
function Stop-WdTask([string]$Task) { $global:Calls.Add("stop-task $Task") }
function Test-WdPort([int]$Port) { return $global:PortOpen }
function Test-WdHttp([string]$Url, [hashtable]$Headers) { $global:HttpHeaders = $Headers; return $global:HttpOk }

$t0 = Get-Date '2026-11-07T10:00:00'
function Pass([int]$Minutes) { Invoke-Watchdog $root $config ($t0.AddMinutes($Minutes)) }
function Revives { @($global:Calls | Where-Object { $_ -like 'docker compose*' -or $_ -like 'start-task*' -or $_ -like 'stop-task*' }) }

Write-Host 'healthy'
Reset-World
$st = Pass 0
Check ((Revives).Count -eq 0 -and $st.docker -eq 'up' -and @($st.needsHuman).Count -eq 0) 'all healthy: no action'
Check ($global:HttpHeaders['X-VIP-Token'] -eq 'secret-token-value') 'status check sends X-VIP-Token from .env'

Write-Host 'dead -> revived'
Reset-World; $global:Ct['survival'] = 'exited|'; $global:Ct['bot'] = 'missing'
$st = Pass 2
Check ((Revives) -contains 'docker compose up -d --no-deps survival') 'exited survival: compose up -d --no-deps'
Check ((Revives) -contains 'docker compose --profile bot up -d --no-deps bot') 'missing bot: up with its profile'
Check ($st.targets['survival'].revivesLastHour -eq 1 -and $st.targets['survival'].lastRevive) 'revive counted in status'

Write-Host 'host task'
Reset-World; $global:TaskState = 'Ready'
Pass 4 | Out-Null
Check ((Revives) -contains 'start-task mc-stack PvP host service' -and -not ((Revives) -like 'stop-task*')) 'task not running (clean exit, 06.11.): Start-ScheduledTask'
Reset-World; $global:HttpOk = $false
Pass 6 | Out-Null
Check ((Revives).Count -eq 0) 'task running but /vip/status fails once: wait'
Pass 8 | Out-Null
Check (((Revives) -join ';') -eq 'stop-task mc-stack PvP host service;start-task mc-stack PvP host service') 'fails twice in a row: stop + start'

Write-Host 'unhealthy twice -> restarted'
Reset-World; $global:Ct['velocity'] = 'running|unhealthy'
Pass 10 | Out-Null
Check ((Revives).Count -eq 0) 'unhealthy once: no action yet'
Pass 12 | Out-Null
Check ((Revives) -contains 'docker compose restart velocity') 'unhealthy twice: compose restart'
Reset-World; $global:Ct['velocity'] = 'running|unhealthy'
Pass 14 | Out-Null
$global:Ct['velocity'] = 'running|healthy'; Pass 16 | Out-Null
$global:Ct['velocity'] = 'running|unhealthy'; Pass 18 | Out-Null
Check ((Revives).Count -eq 0) 'unhealthy, healthy, unhealthy: streak resets, no restart'
Reset-World; $global:Ct['lobby'] = 'running|starting'; $global:Ct['playit'] = 'restarting|'; $global:Ct['bot'] = 'paused|'
Pass 20 | Out-Null
Check ((Revives).Count -eq 0) 'starting, restarting (Docker handles it) and paused: left alone'

Write-Host 'lock / hold set -> no action'
Reset-World; $global:Ct['survival'] = 'exited|'; $global:TaskState = 'Ready'
Set-WatchdogHold $root 'backup' 'backup.ps1 -LeaveStopped'
$st = Pass 30
Check ((Revives).Count -eq 0 -and @($st.paused).Count -eq 1 -and $st.paused[0].owner -eq 'backup') 'backup hold: nothing checked or revived'
Clear-WatchdogHold $root 'backup'
Set-Content -LiteralPath "$root/backups/.maintenance.active" -Value 'pid 1' -Encoding UTF8
Pass 32 | Out-Null
Check ((Revives).Count -eq 0) '.maintenance.active marker: no action'
Remove-Item -LiteralPath "$root/backups/.maintenance.active" -Force
Set-WatchdogHold $root 'manual' 'test' 1
(Get-Item -LiteralPath "$root/backups/.watchdog-hold-manual" -Force).LastWriteTime = (Get-Date).AddHours(-4)
Set-Content -LiteralPath "$root/backups/.watchdog-hold-manual" -Value '{"owner":"manual","reason":"old","until":"2099-01-01T00:00:00"}' -Encoding UTF8
(Get-Item -LiteralPath "$root/backups/.watchdog-hold-manual" -Force).LastWriteTime = (Get-Date).AddHours(-4)
Check (@(Get-WdHolds $root 3).Count -eq 0) 'a hold older than holdMaxHours is ignored (forgotten hold)'
Clear-WatchdogHold $root 'manual'
Pass 34 | Out-Null
Check ((Revives) -contains 'docker compose up -d --no-deps survival') 'hold gone: revives again'

Write-Host 'race: maintenance starts during a pass (Gatekeeper #174 review)'
# The pass sees no hold; while it probes, backup.ps1 takes the lock and sets its hold, then stops survival.
Reset-World; $global:Ct['survival'] = 'exited|'
$global:BackupLock = $null
$global:OnInspect = { param($svc) if ($svc -eq 'survival' -and -not $global:BackupLock) { $global:BackupLock = Enter-MaintLock $root 0; Set-WatchdogHold $root 'backup' 'backup.ps1 laeuft' } }
Pass 60 | Out-Null
$global:OnInspect = $null
Check ((Revives).Count -eq 0) 'backup took the lock mid-pass: no revive'
# backup.ps1 -LeaveStopped finished in between: lock free again, its hold stays -> re-checked under the lock.
Exit-MaintLock $global:BackupLock; $global:BackupLock = $null
Clear-WatchdogHold $root 'backup'
$global:OnInspect = { param($svc) if ($svc -eq 'survival') { Set-WatchdogHold $root 'backup' 'backup.ps1 -LeaveStopped' } }
Pass 62 | Out-Null
$global:OnInspect = $null
Check ((Revives).Count -eq 0) 'hold appeared during the pass: re-checked under the lock, no revive'
Clear-WatchdogHold $root 'backup'
# While the watchdog holds the lock for a revive, a backup can't get it (it waits: LockWaitSeconds 600).
$global:InnerLockFailed = $false
function Invoke-WdCompose([string]$Profile, [string[]]$Arguments) { $global:Calls.Add('docker compose ' + ($Arguments -join ' ')); try { Exit-MaintLock (Enter-MaintLock $root 0) } catch { $global:InnerLockFailed = $true } }
Pass 64 | Out-Null
Check ($global:InnerLockFailed -and ((Revives) -contains 'docker compose up -d --no-deps survival')) 'revive runs while holding the maintenance lock'
Remove-Item Function:\Invoke-WdCompose
. (Join-Path $repo 'tools/watchdog/watchdog-common.ps1')
function Get-WdTaskState([string]$Task) { $global:Calls.Add("probe-task $Task"); return $global:TaskState }
function Start-WdTask([string]$Task) { $global:Calls.Add("start-task $Task") }
function Stop-WdTask([string]$Task) { $global:Calls.Add("stop-task $Task") }
function Test-WdPort([int]$Port) { return $global:PortOpen }
function Test-WdHttp([string]$Url, [hashtable]$Headers) { $global:HttpHeaders = $Headers; return $global:HttpOk }

Write-Host 'holds that must not expire'
# Maintenance still running after holdMaxHours: its hold file is old, but the lock is held -> no revive.
Reset-World; $global:Ct['survival'] = 'exited|'
Set-WatchdogHold $root 'backup' 'backup.ps1 laeuft'
(Get-Item -LiteralPath "$root/backups/.watchdog-hold-backup" -Force).LastWriteTime = (Get-Date).AddHours(-5)
$running = Enter-MaintLock $root 0
Pass 66 | Out-Null
Check ((Revives).Count -eq 0) 'maintenance running beyond 3 h (old hold, lock held): no revive'
Exit-MaintLock $running; Clear-WatchdogHold $root 'backup'
# Failed destructive dev-reset: sticky hold, 10 h old, still effective until cleared by hand.
Set-WatchdogHold $root 'dev-reset' 'Dev-Reset fehlgeschlagen' -Sticky
(Get-Item -LiteralPath "$root/backups/.watchdog-hold-dev-reset" -Force).LastWriteTime = (Get-Date).AddHours(-10)
$st = Pass 68
Check ((Revives).Count -eq 0 -and @($st.paused | Where-Object { $_.owner -eq 'dev-reset' -and $_.sticky }).Count -eq 1) 'failed dev-reset hold after 10 h: still paused, nothing started'
Clear-WatchdogHold $root 'dev-reset'
Pass 74 | Out-Null
Check ((Revives) -contains 'docker compose up -d --no-deps survival') 'cleared by hand: revives again'
# backup.ps1 -LeaveStopped hold: sticky, the lock is free again, 5 h later the deploy may still be running.
$global:Calls.Clear(); $global:Ct['survival'] = 'exited|'; $global:Ct['lobby'] = 'exited|'
Set-WatchdogHold $root 'backup' 'backup.ps1 -LeaveStopped' -Sticky
(Get-Item -LiteralPath "$root/backups/.watchdog-hold-backup" -Force).LastWriteTime = (Get-Date).AddHours(-5)
$st = Pass 90
Check ((Revives).Count -eq 0 -and @($st.paused | Where-Object { $_.owner -eq 'backup' -and $_.sticky }).Count -eq 1) '-LeaveStopped hold after 5 h, lock free: still paused, lobby/survival not started'
Clear-WatchdogHold $root 'backup'; $global:Ct['lobby'] = 'running|healthy'; $global:Ct['survival'] = 'running|healthy'
$d = Get-Content -LiteralPath (Join-Path $repo 'dev-reset.ps1') -Raw
Check ($d -match 'Set-WatchdogHold \$PSScriptRoot ''dev-reset'' ''[^'']*'' -Sticky') 'dev-reset.ps1: the failure hold is sticky'
Check ((Get-Content -LiteralPath (Join-Path $repo 'backup.ps1') -Raw) -match 'if\(\$LeaveStopped -and \$complete\)\{ Set-WatchdogHold [^}]*-Sticky \}') 'backup.ps1: the -LeaveStopped hold is sticky'
Check ((Get-Content -LiteralPath (Join-Path $repo 'backup.ps1') -Raw) -match '\[int\]\$LockWaitSeconds = 600') 'backup.ps1 waits for the lock (watchdog may hold it during a revive)'

Write-Host 'crash loop -> gives up'
Remove-Item -LiteralPath "$root/logs/watchdog/state.json" -Force
Reset-World; $global:Ct['lobby'] = 'exited|'
$n = 0
foreach ($m in 40, 42, 44, 46, 48, 50, 52, 54, 56) { $global:Calls.Clear(); Pass $m | Out-Null; if ((Revives) -contains 'docker compose up -d --no-deps lobby') { $n++ } }
Check ($n -eq 3) "3 revives in the hour, with backoff ($n)"
$global:Calls.Clear(); $st = Pass 58
Check ((Revives).Count -eq 0 -and $st.targets['lobby'].needsHuman -and ($st.needsHuman -contains 'lobby')) '4th failure in the hour: gives up, needs a human'
$global:Calls.Clear(); $st = Pass 120
Check ((Revives).Count -eq 0) 'after giving up: no more revives, also a later hour'
$global:Ct['lobby'] = 'running|healthy'; $st = Pass 122
Check (-not $st.targets['lobby'].needsHuman) 'healthy again: needsHuman cleared'
$log = Get-ChildItem "$root/logs/watchdog" -Filter 'watchdog-*.log' | Select-Object -First 1
$lines = @(Get-Content -LiteralPath $log.FullName | ForEach-Object { $_ | ConvertFrom-Json })
Check (@($lines | Where-Object { $_.event -eq 'give-up' -and $_.target -eq 'lobby' }).Count -eq 1) 'give-up logged once'
Check (@($lines | Where-Object { $_.event -eq 'revived' }).Count -ge 3) 'revives logged'
Check (-not ((Get-Content -LiteralPath $log.FullName -Raw) -like '*secret-token-value*') -and -not ((Get-Content -LiteralPath "$root/logs/watchdog/status.json" -Raw) -like '*secret-token-value*')) 'token never in log or status'

Write-Host 'excluded targets'
Reset-World
foreach ($x in 'pvp', 'vipworld-1', 'vipworld-3', 'hermes-mcp', 'cloudflared-mcp', 'cloudflared') { $global:Ct[$x] = 'exited|' }
Pass 130 | Out-Null
Check (-not (@(Revives) | Where-Object { $_ -match 'pvp(\s|$)|vipworld|hermes|cloudflared' })) 'exited pvp, vipworld-*, hermes-mcp, cloudflared*: never touched'
$bad = Join-Path $root 'bad.json'
$cfg = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
$cfg.compose += [pscustomobject]@{ service = 'vipworld-2' }
Set-Content -LiteralPath $bad -Value ($cfg | ConvertTo-Json -Depth 5) -Encoding UTF8
$msg = ''; try { Invoke-Watchdog $root $bad (Get-Date) | Out-Null } catch { $msg = $_.Exception.Message }
Check ($msg -like '*vipworld-2*excluded*') 'an excluded name in the allowlist is refused'
$cfg = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
$cfg.hostTasks += [pscustomobject]@{ name = 'panel'; task = 'AIPanel_proxy'; portEnv = 'X'; defaultPort = 1; statusPath = '/'; tokenEnv = 'X'; tokenHeader = 'X' }
Set-Content -LiteralPath $bad -Value ($cfg | ConvertTo-Json -Depth 5) -Encoding UTF8
$msg = ''; try { Invoke-Watchdog $root $bad (Get-Date) | Out-Null } catch { $msg = $_.Exception.Message }
Check ($msg -like '*AIPanel_proxy*excluded*') 'AIPanel_* task refused'

Write-Host 'docker down'
Reset-World; $global:DockerUp = $false; $global:Ct['survival'] = 'exited|'
$st = Pass 140
Check ($st.docker -eq 'down' -and -not ((Revives) -like 'docker compose*')) 'Docker down: compose targets skipped, Docker not started'

Write-Host 'backup.ps1 / dev-reset.ps1 holds'
$b = Get-Content -LiteralPath (Join-Path $repo 'backup.ps1') -Raw
Check ($b -match "Set-WatchdogHold \`$PSScriptRoot 'backup'" -and $b -match 'if\(\$LeaveStopped -and \$complete\)\{ Set-WatchdogHold' -and $b -match "Clear-WatchdogHold \`$PSScriptRoot 'backup'") 'backup.ps1 holds while running, keeps the hold after -LeaveStopped'
$d = Get-Content -LiteralPath (Join-Path $repo 'dev-reset.ps1') -Raw
Check ($d -match "Set-WatchdogHold \`$PSScriptRoot 'dev-reset' 'dev-reset.ps1 laeuft'" -and $d -match "Clear-WatchdogHold \`$PSScriptRoot 'dev-reset'") 'dev-reset.ps1 holds while running'

Remove-Item -LiteralPath $root -Recurse -Force
if ($fails) { Write-Host "$fails failure(s)"; exit 1 }
Write-Host 'all watchdog tests passed'
exit 0

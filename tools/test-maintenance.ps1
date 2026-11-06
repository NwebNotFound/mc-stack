# No Docker/DB access: tests backup.ps1/dev-reset.ps1 helpers with a mocked `docker`.
# Run: powershell -NoProfile -File tools/test-maintenance.ps1   (or pwsh)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$root = Join-Path ([IO.Path]::GetTempPath()) ('maint-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$root/tools", "$root/backups", "$root/data/lobby/world", "$root/data/survival/world" | Out-Null
Copy-Item "$repo/tools/maintenance-common.ps1" "$root/tools/"
Copy-Item "$repo/backup.ps1", "$repo/dev-reset.ps1" "$root/"
$global:MaintSleep = { param($s) }   # no real waiting in tests
. "$root/tools/maintenance-common.ps1"
$fails = 0
function Check($cond, $msg) { if ($cond) { Write-Host "  ok  $msg" } else { Write-Host "  FAIL $msg"; $script:fails++ } }

# Mock: $global:DbHealthy is a queue of states returned by `docker inspect` (last one repeats).
$global:DockerCalls = New-Object System.Collections.Generic.List[string]
$global:DbStates = @('healthy')
function global:docker {
  begin { $a = @($args) }
  process { }
  end {
    $global:DockerCalls.Add(($a -join ' '))
    $global:LASTEXITCODE = 0
    if ($a[0] -eq 'info') { if ($global:DockerDown) { $global:LASTEXITCODE = 1; return 'Cannot connect' } ; return '27.0' }
    if ($a[0] -eq 'inspect') {
      $state = $global:DbStates[0]
      if ($global:DbStates.Count -gt 1) { $global:DbStates = $global:DbStates[1..($global:DbStates.Count - 1)] }
      return $state
    }
    if ($global:DockerStderr -and ($a -join ' ') -like '*rcon-cli*') { Write-Error 'warning: stderr noise'; return }
    if (($a -join ' ') -like '*mariadb*') { return '1' }
    return ''
  }
}

Write-Host 'readiness'
$global:DockerDown = $false; $global:DbStates = @('healthy')
Start-MaintLog 'test' $root | Out-Null
$ok = $true; try { Wait-MaintReady 3 } catch { $ok = $false }
Check $ok 'success: ready on the first attempt'

$global:DbStates = @('starting', 'unhealthy', 'healthy')
$ok = $true; try { Wait-MaintReady 5 } catch { $ok = $false }
Check $ok 'DB slow: ready after two unhealthy probes'

$global:DbStates = @('exited')
$msg = ''; try { Wait-MaintReady 3 } catch { $msg = $_.Exception.Message }
Check ($msg -like 'Nicht bereit nach 3 Versuchen*craftcore-db*') "DB down: clear failure after bounded retries ($msg)"

$global:DockerDown = $true
$msg = ''; try { Wait-MaintReady 2 } catch { $msg = $_.Exception.Message }
Check ($msg -like '*Docker antwortet nicht*') 'Docker down: clear failure'
$global:DockerDown = $false

$log = Get-ChildItem "$root/logs/maintenance" -Filter 'test-*.log' | Select-Object -First 1
$lines = @(Get-Content -LiteralPath $log.FullName | ForEach-Object { $_ | ConvertFrom-Json })
Check ($lines.Count -ge 8 -and @($lines | Where-Object { $_.step -eq 'ready' -and $_.level -eq 'warn' }).Count -ge 5) 'every attempt is in the structured log'

Write-Host 'lock'
$first = Enter-MaintLock $root 0
$markerPath = Join-Path $root 'backups/.maintenance.active'
$markerInfo = $null; try { $markerInfo = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json } catch { }
Check ($null -ne $markerInfo -and $markerInfo.name -eq 'test' -and $markerInfo.pid -eq $PID) 'the lock writes the maintenance marker for ops-helper'
$msg = ''; try { Enter-MaintLock $root 0 | Out-Null } catch { $msg = $_.Exception.Message }
Check ($msg -like '*Sperre belegt*') 'a second run fails fast with WaitSeconds 0'
$global:MaintSleep = { param($s) Exit-MaintLock $first }   # the backup finishes while dev-reset waits
$second = $null; try { $second = Enter-MaintLock $root 60 } catch { }
Check ($null -ne $second) 'dev-reset waits for a running backup, then gets the lock'
Check (Test-Path -LiteralPath $markerPath) 'the marker stays while the second holder has the lock'
Exit-MaintLock $second
Check (-not (Test-Path -LiteralPath $markerPath)) 'releasing the lock removes the marker'
$global:MaintSleep = { param($s) }

Write-Host 'failed backup marker'
$bad = Join-Path $root 'backups/daily-20261105-113647-816'
New-Item -ItemType Directory -Path "$bad/lobby/world" -Force | Out-Null
Set-Content "$bad/lobby/world/level.dat" 'x'
New-Item -ItemType File -Path "$bad/craftcore.sql" | Out-Null
$before = @(Get-ChildItem -LiteralPath $bad -Recurse -File).Count
$marked = Set-BackupFailed $bad 'MariaDB-Dump fehlgeschlagen'
Check ((Split-Path $marked -Leaf) -eq 'daily-20261105-113647-816-FAILED') 'incomplete backup renamed to -FAILED'
Check (Test-Path "$marked/FAILED.txt") 'FAILED.txt with the reason'
Check (@(Get-ChildItem -LiteralPath $marked -Recurse -File).Count -eq $before + 1) 'nothing deleted (only FAILED.txt added)'
$good = Join-Path $root 'backups/daily-ok'
New-Item -ItemType Directory -Path $good | Out-Null; Set-Content "$good/COMPLETE.txt" 'ok'
Check ((Set-BackupFailed $good 'x') -eq $good -and (Test-Path $good)) 'a COMPLETE backup is never marked failed'

Write-Host 'retention only reports'
$old = Join-Path $root 'backups/daily-20261020-045000-000'
New-Item -ItemType Directory -Path $old | Out-Null; Set-Content "$old/COMPLETE.txt" 'ok'
(Get-Item $old).CreationTime = (Get-Date).AddDays(-10)
$cands = @(Get-RetentionCandidates (Join-Path $root 'backups') 7)
Check ($cands.Count -eq 1 -and $cands[0] -like '*daily-20261020-045000-000') 'old COMPLETE backup is a candidate'
Check (Test-Path $old) 'and is not deleted'
Check (-not (Select-String -Path "$root/backup.ps1" -Pattern 'Remove-Item' -Quiet)) 'backup.ps1 contains no Remove-Item'
1..9 | ForEach-Object { Set-Content (Join-Path $root ("backups/survival-dev-reset-2026110{0}-050000-000.zip" -f $_)) 'zip' }
$zips = @(Get-DevResetCandidates (Join-Path $root 'backups') 7)
Check ($zips.Count -eq 2 -and $zips[0] -like '*20261102*' -and $zips[1] -like '*20261101*') 'the two oldest reset zips are reported'
Check (@(Get-ChildItem (Join-Path $root 'backups') -Filter 'survival-dev-reset-*.zip').Count -eq 9) 'and all nine stay'
Check (-not (Select-String -Path "$root/dev-reset.ps1" -Pattern "survival-dev-reset-\*\.zip.*Remove-Item" -Quiet)) 'dev-reset.ps1 never removes reset zips'

Write-Host 'backup.ps1 with the DB down (the 05.11. 11:36 case)'
$global:DbStates = @('exited')
$msg = ''; try { & "$root/backup.ps1" -ReadyAttempts 2 } catch { $msg = $_.Exception.Message }
Check ($msg -like 'Nicht bereit*') "backup throws a clear error ($msg)"
Check (-not (Test-Path -LiteralPath (Join-Path $root 'backups/.maintenance.active'))) 'a failed backup leaves no maintenance marker'
Check (@(Get-ChildItem "$root/backups" -Directory -Filter 'daily-2*' | Where-Object { $_.Name -notlike '*FAILED*' -and -not (Test-Path (Join-Path $_.FullName 'COMPLETE.txt')) }).Count -eq 0) 'no half backup that looks valid'
Check (-not ($global:DockerCalls | Where-Object { $_ -like 'compose stop*' })) 'servers were not stopped'
$blog = Get-ChildItem "$root/logs/maintenance" -Filter 'backup-*.log' | Select-Object -First 1
$bl = @(Get-Content -LiteralPath $blog.FullName | ForEach-Object { $_ | ConvertFrom-Json })
Check (@($bl | Where-Object { $_.step -eq 'end' -and $_.msg -like 'exit 1*' }).Count -eq 1) 'backup log ends with exit 1 and the error'

Write-Host 'dev-reset.ps1 waits for the backup lock and fails clearly with the DB down'
$held = Enter-MaintLock $root 0
$global:MaintSleep = { param($s) Exit-MaintLock $held }
$global:DbStates = @('exited')
$msg = ''; try { & "$root/dev-reset.ps1" -ReadyAttempts 2 -LockWaitSeconds 60 } catch { $msg = $_.Exception.Message }
Check ($msg -like 'Nicht bereit*') "dev-reset got the lock after the backup, then a clear DB error ($msg)"
$dlog = Get-ChildItem "$root/logs/maintenance" -Filter 'dev-reset-*.log' | Select-Object -First 1
$dl = @(Get-Content -LiteralPath $dlog.FullName | ForEach-Object { $_ | ConvertFrom-Json })
Check (@($dl | Where-Object { $_.step -eq 'lock' -and $_.level -eq 'warn' }).Count -ge 1 -and @($dl | Where-Object { $_.step -eq 'end' -and $_.level -eq 'error' }).Count -eq 1) 'dev-reset log: waited for the lock, ended with the error'

Write-Host 'online backup (default: nothing stopped)'
foreach ($srv in 'lobby','survival') { New-Item -ItemType Directory -Force -Path "$root/data/$srv/world" | Out-Null; Set-Content "$root/data/$srv/world/level.dat" 'x'; Set-Content "$root/data/$srv/world/session.lock" 'lock' }
$global:DbStates = @('healthy')
# pvp arena and two player VIP worlds (issue #145: they were never backed up)
New-Item -ItemType Directory -Force -Path "$root/data/pvp/world", "$root/data/vipworlds/11111111-2222-3333-4444-555555555555", "$root/data/vipworlds/66666666-7777-8888-9999-000000000000" | Out-Null
Set-Content "$root/data/pvp/world/level.dat" 'x'
Set-Content "$root/data/vipworlds/11111111-2222-3333-4444-555555555555/level.dat" 'x'
Set-Content "$root/data/vipworlds/11111111-2222-3333-4444-555555555555/session.lock" 'lock'
Set-Content "$root/data/vipworlds/66666666-7777-8888-9999-000000000000/level.dat" 'x'
$global:MaintDumpOverride = { param($path) $global:DockerCalls.Add('dump ' + (Split-Path $path -Leaf)); Set-Content -LiteralPath $path -Value ('-- dump ' + ('x' * 200)) }
function Reset-Calls { $global:DockerCalls.Clear() }
Reset-Calls
$msg = ''; try { & "$root/backup.ps1" -NoWarning -ReadyAttempts 1 } catch { $msg = $_.Exception.Message }
Check ($msg -eq '') "online backup succeeds ($msg)"
$calls = @($global:DockerCalls)
function Idx($pattern) { for ($i = 0; $i -lt $calls.Count; $i++) { if ($calls[$i] -like $pattern) { return $i } }; return -1 }
foreach ($srv in 'lobby','survival') {
  $off = Idx "compose exec -T $srv rcon-cli save-off"; $flush = Idx "compose exec -T $srv rcon-cli save-all flush"; $on = Idx "compose exec -T $srv rcon-cli save-on"
  Check ($off -ge 0 -and $flush -gt $off -and $on -gt $flush) "${srv}: save-off -> save-all flush -> save-on"
}
$lastOff = [Math]::Max((Idx 'compose exec -T lobby rcon-cli save-all flush'), (Idx 'compose exec -T survival rcon-cli save-all flush'))
$firstOn = [Math]::Min((Idx 'compose exec -T lobby rcon-cli save-on'), (Idx 'compose exec -T survival rcon-cli save-on'))
Check ((Idx 'dump craftcore.sql') -gt $lastOff -and (Idx 'dump luckperms.sql') -gt $lastOff) 'both DB dumps run after save-off + flush on both servers'
Check ((Idx 'dump craftcore.sql') -lt $firstOn -and (Idx 'dump luckperms.sql') -lt $firstOn) 'and before saving is turned back on'
Check ((Idx 'compose --profile pvp --profile vip --profile vip3 ps --status running --services') -ge 0) 'running pvp/vip servers are looked up (with their profiles)'
Check ((Idx 'compose stop*') -lt 0 -and (Idx 'compose up*') -lt 0) 'no container stop or start in the default path'
$latest = Get-ChildItem "$root/backups" -Directory -Filter 'daily-2*' | Where-Object { Test-Path (Join-Path $_.FullName 'COMPLETE.txt') } | Sort-Object Name | Select-Object -Last 1
Check ($latest -and (Test-Path "$($latest.FullName)/lobby/world/level.dat") -and -not (Test-Path "$($latest.FullName)/lobby/world/session.lock")) 'worlds copied, session.lock skipped, COMPLETE written'
Check ($latest -and (Test-Path "$($latest.FullName)/craftcore.sql") -and (Test-Path "$($latest.FullName)/luckperms.sql")) 'craftcore and luckperms are both dumped'
Check ($latest -and (Test-Path "$($latest.FullName)/pvp/world/level.dat")) 'the pvp world is copied'
Check ($latest -and (Test-Path "$($latest.FullName)/vipworlds/11111111-2222-3333-4444-555555555555/level.dat") -and (Test-Path "$($latest.FullName)/vipworlds/66666666-7777-8888-9999-000000000000/level.dat") -and -not (Test-Path "$($latest.FullName)/vipworlds/11111111-2222-3333-4444-555555555555/session.lock")) 'every VIP world is copied (session.lock skipped)'
$ol = @(Get-Content -LiteralPath (Get-ChildItem "$root/logs/maintenance" -Filter 'backup-*.log').FullName | ForEach-Object { $_ | ConvertFrom-Json })
$lastRun = ($ol | Select-Object -Last 1).run
$steps = @($ol | Where-Object { $_.run -eq $lastRun } | ForEach-Object { $_.step + ':' + $_.msg })
$iOff = [array]::FindIndex([string[]]$steps, [Predicate[string]]{ param($x) $x -like 'save:lobby: save-off*' })
$iCopy = [array]::FindIndex([string[]]$steps, [Predicate[string]]{ param($x) $x -like 'worlds:lobby:*' })
$iOn = [array]::FindIndex([string[]]$steps, [Predicate[string]]{ param($x) $x -eq 'save:lobby: save-on' })
$iDump = [array]::FindIndex([string[]]$steps, [Predicate[string]]{ param($x) $x -like 'dump:luckperms.sql*' })
Check ($iOff -ge 0 -and $iDump -gt $iOff -and $iCopy -gt $iDump -and $iOn -gt $iCopy) 'log order: save-off, dumps, copy, save-on'

Write-Host 'online backup: copy failure still turns saving back on'
Reset-Calls
$global:MaintCopyFailFor = 'survival'
$msg = ''; try { & "$root/backup.ps1" -NoWarning -ReadyAttempts 1 } catch { $msg = $_.Exception.Message }
$global:MaintCopyFailFor = $null
$calls = @($global:DockerCalls)
Check ($msg -like 'Kopieren fehlgeschlagen*') "copy failure is reported ($msg)"
Check ((Idx 'compose exec -T survival rcon-cli save-off') -ge 0 -and (Idx 'compose exec -T survival rcon-cli save-on') -gt (Idx 'compose exec -T survival rcon-cli save-off')) 'survival: save-on after the failed copy (finally)'
Check ((Idx 'compose stop*') -lt 0) 'still no container stop'
Check (@(Get-ChildItem "$root/backups" -Directory -Filter '*-FAILED').Count -ge 2) 'the failed run is marked FAILED'

Write-Host 'online backup: stderr from docker (exit 0) does not abort'
Reset-Calls
$global:DockerStderr = $true
$msg = ''; try { & "$root/backup.ps1" -NoWarning -ReadyAttempts 1 } catch { $msg = $_.Exception.Message }
$global:DockerStderr = $false
Check ($msg -eq '') "docker stderr with exit 0 does not fail the backup ($msg)"

Write-Host '-StopServers keeps the old behaviour'
Reset-Calls
$msg = ''; try { & "$root/backup.ps1" -StopServers -ReadyAttempts 1 } catch { $msg = $_.Exception.Message }
$calls = @($global:DockerCalls)
Check ($msg -eq '' -and (Idx 'compose stop lobby survival') -ge 0 -and (Idx 'compose up -d --no-deps lobby survival') -gt (Idx 'compose stop lobby survival')) "-StopServers stops and starts the servers ($msg)"
Check ((Idx '*rcon-cli save-off*') -lt 0) '-StopServers does not use save-off'
$global:MaintDumpOverride = $null

Remove-Item Function:\docker
Write-Host ''
if ($fails -gt 0) { Write-Host "$fails check(s) failed"; exit 1 }
Write-Host 'all maintenance checks passed'

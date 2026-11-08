# Manual/local check for pvp-host-service\run.ps1 (Windows only). Uses a TEST port (default 25598), never 25599, and a
# dummy token, so it does not touch the live service. Checks: start -> stop the wrapper like Task Scheduler does
# (TerminateProcess) -> no python.exe with our service.py is left and the port is free -> an orphan holding the port is
# killed on the next start -> start works again.
$port = if ($args[0]) { [int]$args[0] } else { 25598 }
if ($port -eq 25599) { throw 'refusing to run against the live port 25599' }
$run = Join-Path $PSScriptRoot 'pvp-host-service\run.ps1'
$svc = (Join-Path $PSScriptRoot 'pvp-host-service\service.py').ToLower()
$py = 'C:\Program Files\Python311\python.exe'
$env:PVP_HOST_PORT = "$port"; $env:PVP_HOST_TOKEN = 'test-token-not-secret'
$fail = 0
function Check($ok, $name) { if ($ok) { Write-Host "ok   $name" } else { Write-Host "FAIL $name"; $script:fail++ } }
function Ours { @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.ToLower().Contains($svc) }) }
function Listening { $c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', $port); $true } catch { $false } finally { $c.Close() } }
function WaitFor($cond, $sec = 15) { for ($i = 0; $i -lt $sec * 4 -and -not (& $cond); $i++) { Start-Sleep -Milliseconds 250 }; & $cond }
Check (@(Ours).Count -eq 0 -and -not (Listening)) 'precondition: no service.py running, test port free'
$w = Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$run`"" -WindowStyle Hidden -PassThru
Check (WaitFor { Listening }) 'start: service listens on the test port'
Check (@(Ours).Count -eq 1) "start: exactly one service.py process (found $(@(Ours).Count))"
Stop-Process -Id $w.Id -Force          # what Stop-ScheduledTask does: only the wrapper dies
Check (WaitFor { @(Ours).Count -eq 0 -and -not (Listening) }) 'stop: no service.py left and the port is free'
# an orphan from an older run holds the port: the next start must kill it and take over
$orphan = Start-Process $py -ArgumentList "`"$((Join-Path $PSScriptRoot 'pvp-host-service\service.py'))`"" -WindowStyle Hidden -PassThru
Check (WaitFor { Listening }) 'orphan holds the test port'
$w2 = Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$run`"" -WindowStyle Hidden -PassThru
Check (WaitFor { $orphan.Refresh(); $orphan.HasExited }) 'start: the stale orphan is killed'
Check (WaitFor { @(Ours).Count -eq 1 -and (Listening) }) 'start: the new service listens again'
Stop-Process -Id $w2.Id -Force
Check (WaitFor { @(Ours).Count -eq 0 -and -not (Listening) }) 'cleanup: nothing left'
if ($fail) { Write-Host "$fail check(s) failed"; exit 1 } else { Write-Host 'all checks passed' }

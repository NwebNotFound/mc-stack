# Manual/local check for pvp-host-service\run.ps1 (Windows only). Uses TEST ports (default 25598, plus 25597 for a
# second, unrelated instance), never 25599, and a dummy token, so it does not touch the live service. It records the
# PIDs it starts and only ever stops those. Checks: start -> stop the wrapper like Task Scheduler does (TerminateProcess)
# -> its python is gone and the port free -> a second instance on ANOTHER port keeps running through all of it ->
# a stale instance holding the test port is killed on the next start -> start works again.
$port = if ($args[0]) { [int]$args[0] } else { 25598 }
$other = $port - 1
if ($port -eq 25599 -or $other -eq 25599) { throw 'refusing to run near the live port 25599' }
$run = Join-Path $PSScriptRoot 'pvp-host-service\run.ps1'
$svcPath = Join-Path $PSScriptRoot 'pvp-host-service\service.py'
$py = 'C:\Program Files\Python311\python.exe'
$pidFile = Join-Path $env:LOCALAPPDATA "mc-stack\pvp-host-service-$port.pid"
$env:PVP_HOST_TOKEN = 'test-token-not-secret'
$mine = New-Object System.Collections.Generic.List[int]     # every PID this test started
$fail = 0
function Check($ok, $name) { if ($ok) { Write-Host "ok   $name" } else { Write-Host "FAIL $name"; $script:fail++ } }
function Up($p) { $c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', $p); $true } catch { $false } finally { $c.Close() } }
function Alive($id) { [bool](Get-Process -Id $id -ErrorAction SilentlyContinue) }
function WaitFor($cond, $sec = 15) { for ($i = 0; $i -lt $sec * 4 -and -not (& $cond); $i++) { Start-Sleep -Milliseconds 250 }; & $cond }
function StartPy($p) { $env:PVP_HOST_PORT = "$p"; $x = Start-Process $py -ArgumentList "`"$svcPath`"" -WindowStyle Hidden -PassThru; $mine.Add($x.Id); $x }
function StartWrapper($p) { $env:PVP_HOST_PORT = "$p"; $x = Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$run`"" -WindowStyle Hidden -PassThru; $mine.Add($x.Id); $x }
try {
  Check (-not (Up $port) -and -not (Up $other)) "precondition: test ports $other and $port are free"
  $o = StartPy $other                                  # an unrelated second instance (same service.py, other port)
  Check (WaitFor { Up $other }) "second instance listens on $other"
  $w = StartWrapper $port
  Check (WaitFor { (Up $port) -and (Test-Path $pidFile) }) "start: wrapper's service listens on $port"
  $pyId = [int](Get-Content $pidFile | Select-Object -First 1); $mine.Add($pyId)
  Check (Alive $o.Id -and (Up $other)) "start: the instance on $other was NOT touched by the start of $port"
  Stop-Process -Id $w.Id -Force                        # what Stop-ScheduledTask does: only the wrapper dies
  Check (WaitFor { -not (Alive $pyId) -and -not (Up $port) }) 'stop: the wrapper''s service.py is gone and the port is free'
  Check (Alive $o.Id -and (Up $other)) "stop: the instance on $other is still running"
  $orphan = StartPy $port                               # a stale instance holding the test port
  Check (WaitFor { Up $port }) 'a stale instance holds the test port'
  $w2 = StartWrapper $port
  Check (WaitFor { -not (Alive $orphan.Id) }) 'start: the stale instance on this port is killed'
  Check (WaitFor { (Up $port) -and (Test-Path $pidFile) }) 'start: the new service listens again'
  Check (Alive $o.Id -and (Up $other)) "start: the instance on $other is still running"
  Stop-Process -Id $w2.Id -Force
  Check (WaitFor { -not (Up $port) }) "cleanup: port $port free"
} finally {
  foreach ($id in $mine) { if (Alive $id) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue } }   # only PIDs this test started
}
if ($fail) { Write-Host "$fail check(s) failed"; exit 1 } else { Write-Host 'all checks passed' }

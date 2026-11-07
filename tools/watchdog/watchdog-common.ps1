# Watchdog logic (entry: tools/watchdog/watchdog.ps1). Needs tools/maintenance-common.ps1 dot-sourced too
# (Enter-/Exit-MaintLock); not loaded from here, so backup.ps1 can dot-source both. Nothing runs at dot-source time, so
# tools/test-watchdog.ps1 can mock `docker` and the Wd* probe functions below.
#
# One pass (Invoke-Watchdog): load the allowlist (targets.json), stop if a hold is active
# (backups/.watchdog-hold-*, or the .maintenance.active marker), then check each target once.
#   dead   = compose container exited/created/dead/missing, or the host task is not running
#   dying  = compose container unhealthy, or the host task runs but its port/status fails,
#            for `unhealthyChecks` passes in a row
#   revive = `docker compose up -d --no-deps <svc>` (dead) / `docker compose restart <svc>` (dying),
#            Start-ScheduledTask (dead) / Stop- then Start-ScheduledTask (dying)
# Crash-loop guard: at most maxRevivesPerHour per target, spaced by backoffMinutes; after that the
# target is marked needsHuman and left alone until it is healthy again or reset with pause.ps1 -Reset.
# Secrets (.env tokens) are only sent to the local service, never written to log or status.

$script:WdLogFile = $null

function Write-WdLog([string]$Target, [string]$Event, [string]$Message, [string]$Level = 'info') {
  $line = [ordered]@{ ts = (Get-Date).ToString('o'); target = $Target; event = $Event; level = $Level; msg = $Message } | ConvertTo-Json -Compress
  if ($script:WdLogFile) { Add-Content -LiteralPath $script:WdLogFile -Value $line -Encoding UTF8 }
  Write-Host "[$Target] $Event $Message"
}

function Read-WdJson([string]$Path, $Default) {
  if (-not (Test-Path -LiteralPath $Path)) { return $Default }
  try { return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $Default }
}
function Write-WdJson([string]$Path, $Object) {
  $tmp = "$Path.tmp"
  Set-Content -LiteralPath $tmp -Value ($Object | ConvertTo-Json -Depth 6) -Encoding UTF8
  Move-Item -LiteralPath $tmp -Destination $Path -Force
}

function Test-WdNever([string]$Name, $Patterns) { foreach ($p in $Patterns) { if ($Name -like $p) { return $true } } return $false }

# Allowlist with a hard guard: an excluded name in the allowlist is a config error, not a warning.
function Get-WdConfig([string]$Path) {
  $cfg = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
  foreach ($c in @($cfg.compose)) { if (Test-WdNever $c.service $cfg.never) { throw "targets.json: '$($c.service)' is excluded (never) and must not be a target" } }
  foreach ($h in @($cfg.hostTasks)) { if ((Test-WdNever $h.task $cfg.never) -or (Test-WdNever $h.name $cfg.never)) { throw "targets.json: '$($h.task)' is excluded (never) and must not be a target" } }
  return $cfg
}

# .env as a hashtable (KEY=VALUE lines only); values never leave this process except to the local service.
function Read-WdEnv([string]$Root) {
  $vars = @{}
  $path = Join-Path $Root '.env'
  if (Test-Path -LiteralPath $path) { foreach ($l in Get-Content -LiteralPath $path) { if ($l -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $vars[$Matches[1]] = $Matches[2].Trim() } } }
  return $vars
}

# Active holds: backups/.watchdog-hold-<owner> (JSON: owner, reason, until) and, once present, the
# .maintenance.active marker written while backup.ps1/dev-reset.ps1 hold the maintenance lock.
# A hold is capped at holdMaxHours after it was written, so a forgotten hold can't disable the watchdog for good.
function Get-WdHolds([string]$Root, [double]$MaxHours) {
  $dir = Join-Path $Root 'backups'
  $now = Get-Date
  $active = @()
  if (-not (Test-Path -LiteralPath $dir)) { return $active }
  foreach ($f in @(Get-ChildItem -LiteralPath $dir -Force -File -Filter '.watchdog-hold-*')) {
    $h = Read-WdJson $f.FullName $null
    $cap = $f.LastWriteTime.AddHours($MaxHours)
    $until = $cap
    if ($h -and $h.until) { try { $u = [datetime]$h.until; if ($u -lt $cap) { $until = $u } } catch {} }
    # A sticky hold (failed destructive dev-reset) never expires: only pause.ps1 -Resume -Owner <owner> ends it.
    if ($h -and $h.sticky) { $active += [pscustomobject]@{ owner = $f.Name.Substring('.watchdog-hold-'.Length); reason = [string]$h.reason; until = 'until cleared'; sticky = $true }; continue }
    if ($until -gt $now) { $active += [pscustomobject]@{ owner = $f.Name.Substring('.watchdog-hold-'.Length); reason = $(if ($h) { [string]$h.reason } else { '' }); until = $until.ToString('o') } }
  }
  $marker = Join-Path $dir '.maintenance.active'
  if ((Test-Path -LiteralPath $marker) -and (Get-Item -LiteralPath $marker -Force).LastWriteTime.AddHours($MaxHours) -gt $now) {
    $active += [pscustomobject]@{ owner = 'maintenance'; reason = 'maintenance lock held'; until = '' }
  }
  return $active
}

# ---- probes (overridden in tests) ----
# Native docker calls run with ErrorActionPreference 'Continue': under Windows PowerShell 5.1 a stderr line captured
# with 2>&1 is a terminating error under 'Stop' (watchdog.ps1). Only the exit code decides (as Invoke-MaintRcon).
function Test-WdDocker { $ErrorActionPreference = 'Continue'; $null = & docker info --format '{{.ServerVersion}}' 2>&1; return ($LASTEXITCODE -eq 0) }
# "running|healthy", "exited|", "missing|" ... from `docker inspect` of mc-stack-<svc>-1.
function Get-WdContainer([string]$Service) {
  $ErrorActionPreference = 'Continue'
  $out = (& docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}' "mc-stack-$Service-1" 2>&1) -join ''
  if ($LASTEXITCODE -ne 0) { return [pscustomobject]@{ status = 'missing'; health = '' } }
  $p = $out.Trim().Split('|')
  return [pscustomobject]@{ status = $p[0]; health = $(if ($p.Count -gt 1) { $p[1] } else { '' }) }
}
function Invoke-WdCompose([string]$Profile, [string[]]$Arguments) {
  $ErrorActionPreference = 'Continue'   # compose writes its progress lines to stderr
  $a = @('compose') + $(if ($Profile) { @('--profile', $Profile) } else { @() }) + $Arguments
  $out = & docker @a 2>&1
  if ($LASTEXITCODE -ne 0) { throw "docker $($a -join ' ') failed: $(($out | Out-String).Trim())" }
}
function Get-WdTaskState([string]$Task) { $t = Get-ScheduledTask -TaskName $Task -ErrorAction SilentlyContinue; if (-not $t) { return 'Missing' } return [string]$t.State }
function Start-WdTask([string]$Task) { Start-ScheduledTask -TaskName $Task }
function Stop-WdTask([string]$Task) { Stop-ScheduledTask -TaskName $Task }
function Test-WdPort([int]$Port) {
  $c = New-Object Net.Sockets.TcpClient
  try { return $c.ConnectAsync('127.0.0.1', $Port).Wait(2000) -and $c.Connected } catch { return $false } finally { $c.Dispose() }
}
function Test-WdHttp([string]$Url, [hashtable]$Headers) {
  try { return ((Invoke-WebRequest -Uri $Url -Headers $Headers -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200) } catch { return $false }
}

# ---- one pass ----
function Invoke-WdProbe($t) {
  if ($t.kind -eq 'compose') { return Get-WdComposeVerdict (Get-WdContainer $t.service) }
  $ts = Get-WdTaskState $t.task
  if ($ts -eq 'Missing') { return 'missing-task' }
  if ($ts -ne 'Running') { return 'dead' }
  # With Docker down the service's /vip/status answers 503 (it asks docker per VIP slot): then only "running and
  # listening" counts, so a healthy service is not killed while Docker Desktop starts or updates.
  if ($t.dockerDown) { if (Test-WdPort $t.port) { return 'ok' } else { return 'unhealthy' } }
  if ((Test-WdPort $t.port) -and (Test-WdHttp $t.url $t.headers)) { return 'ok' }
  return 'unhealthy'
}
function Invoke-WdRevive($t, [string]$Verdict) {
  if ($t.kind -eq 'compose') {
    if ($Verdict -eq 'dead') { Invoke-WdCompose $t.profile @('up', '-d', '--no-deps', $t.service) } else { Invoke-WdCompose $t.profile @('restart', $t.service) }
    return
  }
  if ($Verdict -eq 'unhealthy') { Stop-WdTask $t.task }   # running but not answering: a plain Start would be a no-op
  Start-WdTask $t.task
}
function Get-WdComposeVerdict($c) {
  if ($c.status -in @('exited', 'created', 'dead', 'missing')) { return 'dead' }
  if ($c.status -eq 'running' -and $c.health -eq 'unhealthy') { return 'unhealthy' }
  return 'ok'   # running (healthy/starting/no healthcheck), restarting (Docker handles it), paused (deliberate)
}

function Invoke-Watchdog([string]$Root, [string]$ConfigPath = (Join-Path $PSScriptRoot 'targets.json'), [datetime]$Now = (Get-Date)) {
  $dir = Join-Path $Root 'logs/watchdog'
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $script:WdLogFile = Join-Path $dir ("watchdog-{0}.log" -f $Now.ToString('yyyy-MM'))   # one file per month, nothing deleted
  $statePath = Join-Path $dir 'state.json'
  $statusPath = Join-Path $dir 'status.json'
  $cfg = Get-WdConfig $ConfigPath
  $raw = Read-WdJson $statePath $null
  $state = @{}
  if ($raw) { foreach ($p in $raw.PSObject.Properties) { $state[$p.Name] = $p.Value } }
  $status = [ordered]@{ lastCheck = $Now.ToString('o'); paused = $null; docker = $null; needsHuman = @(); targets = [ordered]@{} }

  $holds = @(Get-WdHolds $Root $cfg.holdMaxHours)
  if ($holds.Count) {
    $status.paused = $holds
    $was = $state['_paused']
    if (-not $was) { Write-WdLog '-' 'paused' ("hold: " + (($holds | ForEach-Object { "$($_.owner) ($($_.reason))" }) -join ', ')) }
    $state['_paused'] = $true
    foreach ($k in @($state.Keys | Where-Object { $_ -ne '_paused' })) { $status.targets[$k] = $state[$k] }
    $status.needsHuman = @($state.Keys | Where-Object { $_ -ne '_paused' -and $state[$_].needsHuman })
    Write-WdJson $statePath $state; Write-WdJson $statusPath $status
    return $status
  }
  if ($state['_paused']) { Write-WdLog '-' 'resumed' 'no hold active' }
  $state['_paused'] = $false

  $checks = @()
  $dockerUp = Test-WdDocker
  $status.docker = $(if ($dockerUp) { 'up' } else { 'down' })
  if (-not $dockerUp) { Write-WdLog '-' 'docker-down' 'Docker answers nothing: compose targets skipped (the watchdog does not start Docker)' 'warn' }
  foreach ($c in @($cfg.compose)) {
    if (-not $dockerUp) { continue }
    $svc = $c.service; $prof = [string]$c.profile
    $checks += [pscustomobject]@{ kind = 'compose'; name = $svc; service = $svc; profile = $prof }
  }
  $envVars = $null
  foreach ($h in @($cfg.hostTasks)) {
    if (-not $envVars) { $envVars = Read-WdEnv $Root }
    $task = $h.task
    $port = [int]$(if ($envVars[$h.portEnv]) { $envVars[$h.portEnv] } else { $h.defaultPort })
    $headers = @{}; if ($envVars[$h.tokenEnv]) { $headers[$h.tokenHeader] = $envVars[$h.tokenEnv] }
    $url = "http://127.0.0.1:$port$($h.statusPath)"
    $checks += [pscustomobject]@{ kind = 'task'; name = $h.name; task = $task; port = $port; url = $url; headers = $headers; dockerDown = (-not $dockerUp) }
  }

  $pending = @()
  foreach ($t in $checks) {
    $s = $state[$t.name]
    if (-not $s) { $s = [pscustomobject]@{ verdict = ''; unhealthyStreak = 0; revives = @(); lastRevive = $null; needsHuman = $false } }
    $s.revives = @(@($s.revives) | Where-Object { $_ -and ([datetime]$_) -gt $Now.AddHours(-1) })
    # one target that cannot be probed must not abort the pass (state, other targets, the host task)
    try { $verdict = Invoke-WdProbe $t } catch { Write-WdLog $t.name 'probe-failed' $_.Exception.Message 'error'; $s.verdict = 'unknown'; $state[$t.name] = $s; $status.targets[$t.name] = [ordered]@{ verdict = 'unknown'; lastRevive = $s.lastRevive; revivesLastHour = @($s.revives).Count; needsHuman = [bool]$s.needsHuman }; continue }
    $s.verdict = $verdict
    if ($verdict -eq 'missing-task') {
      if (-not $s.needsHuman) { Write-WdLog $t.name 'needs-human' 'scheduled task is not registered: run its install-task.ps1' 'error' }
      $s.needsHuman = $true
    } elseif ($verdict -eq 'ok') {
      if ($s.needsHuman) { Write-WdLog $t.name 'recovered' 'healthy again, cleared needsHuman' }
      $s.unhealthyStreak = 0; $s.needsHuman = $false
    } else {
      if ($verdict -eq 'unhealthy') { $s.unhealthyStreak = [int]$s.unhealthyStreak + 1 } else { $s.unhealthyStreak = 0 }
      $act = ($verdict -eq 'dead') -or ($s.unhealthyStreak -ge [int]$cfg.unhealthyChecks)
      if ($verdict -eq 'unhealthy' -and -not $act) { Write-WdLog $t.name 'unhealthy' "check $($s.unhealthyStreak)/$($cfg.unhealthyChecks), waiting" 'warn' }
      if ($act -and -not $s.needsHuman) {
        $n = @($s.revives).Count
        if ($n -ge [int]$cfg.maxRevivesPerHour) {
          $s.needsHuman = $true
          Write-WdLog $t.name 'give-up' "$verdict again after $n revives in the last hour: needs a human (pause.ps1 -Reset $($t.name) after fixing)" 'error'
        } else {
          $wait = [double]@($cfg.backoffMinutes)[[Math]::Min($n, @($cfg.backoffMinutes).Count - 1)]
          if ($n -gt 0 -and $s.lastRevive -and ([datetime]$s.lastRevive).AddMinutes($wait) -gt $Now) {
            Write-WdLog $t.name 'backoff' "$verdict, next revive not before $(([datetime]$s.lastRevive).AddMinutes($wait).ToString('HH:mm'))" 'warn'
          } else {
            $pending += [pscustomobject]@{ t = $t; s = $s; verdict = $verdict; n = $n }
          }
        }
      }
    }
    $state[$t.name] = $s
    $status.targets[$t.name] = [ordered]@{ verdict = $s.verdict; lastRevive = $s.lastRevive; revivesLastHour = @($s.revives).Count; needsHuman = [bool]$s.needsHuman }
  }
  # Revives only while holding the maintenance lock (backups/.maintenance.lock, tools/maintenance-common.ps1):
  # backup.ps1/dev-reset.ps1 can't stop servers meanwhile, and the holds are checked again under the lock,
  # so a maintenance run that started during this pass always wins. Busy lock = maintenance running: skip.
  if ($pending.Count) {
    $lock = $null
    try { $lock = Enter-MaintLock $Root 0 } catch { Write-WdLog '-' 'maintenance-busy' "maintenance lock held: $($pending.Count) revive(s) skipped" 'warn' }
    if ($lock) {
      try {
        $late = @(Get-WdHolds $Root $cfg.holdMaxHours)
        if ($late.Count) {
          $status.paused = $late
          Write-WdLog '-' 'paused' ("hold appeared during the pass, revives skipped: " + (($late | ForEach-Object { $_.owner }) -join ', '))
        } else {
          foreach ($p in $pending) {
            $again = try { Invoke-WdProbe $p.t } catch { $p.verdict }   # state may have changed since the first probe
            if ($again -eq 'ok') { $p.s.verdict = 'ok'; $p.s.unhealthyStreak = 0; continue }
            try {
              Invoke-WdRevive $p.t $p.verdict
              Write-WdLog $p.t.name 'revived' "$($p.verdict) -> revive $($p.n + 1)/$($cfg.maxRevivesPerHour) this hour" 'warn'
            } catch {
              Write-WdLog $p.t.name 'revive-failed' $_.Exception.Message 'error'
            }
            $p.s.revives = @(@($p.s.revives) + $Now.ToString('o'))
            $p.s.lastRevive = $Now.ToString('o'); $p.s.unhealthyStreak = 0
          }
        }
      } finally { Exit-MaintLock $lock }
    }
    foreach ($p in $pending) { $status.targets[$p.t.name] = [ordered]@{ verdict = $p.s.verdict; lastRevive = $p.s.lastRevive; revivesLastHour = @($p.s.revives).Count; needsHuman = [bool]$p.s.needsHuman } }
  }
  $status.needsHuman = @($status.targets.Keys | Where-Object { $status.targets[$_].needsHuman })
  Write-WdJson $statePath $state
  Write-WdJson $statusPath $status
  return $status
}

# ---- holds, used by backup.ps1, dev-reset.ps1 and pause.ps1 ----
# A sticky hold (deploy after backup.ps1 -LeaveStopped, failed destructive dev-reset) is never replaced by a
# normal hold and never cleared by the next backup/dev-reset run of the same owner: only an explicit
# `pause.ps1 -Resume` (-Force) ends it. Returns $false when an existing sticky hold was kept.
function Get-WdHoldFile([string]$Root, [string]$Owner) { Join-Path (Join-Path $Root 'backups') ".watchdog-hold-$Owner" }
function Test-WdStickyHold([string]$Root, [string]$Owner) { $h = Read-WdJson (Get-WdHoldFile $Root $Owner) $null; return [bool]($h -and $h.sticky) }
function Set-WatchdogHold([string]$Root, [string]$Owner, [string]$Reason, [double]$Hours = 3, [switch]$Sticky) {
  $dir = Join-Path $Root 'backups'
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  if (-not $Sticky -and (Test-WdStickyHold $Root $Owner)) { Write-Host "[watchdog] sticky hold '$Owner' kept (end it with tools/watchdog/pause.ps1 -Resume -Owner $Owner)"; return $false }
  $o = [ordered]@{ owner = $Owner; reason = $Reason; since = (Get-Date).ToString('o'); until = (Get-Date).AddHours($Hours).ToString('o'); sticky = [bool]$Sticky }
  Set-Content -LiteralPath (Get-WdHoldFile $Root $Owner) -Value ($o | ConvertTo-Json -Compress) -Encoding UTF8
  return $true
}
function Clear-WatchdogHold([string]$Root, [string]$Owner, [switch]$Force) {
  $p = Get-WdHoldFile $Root $Owner
  if (-not (Test-Path -LiteralPath $p)) { return $true }
  if (-not $Force -and (Test-WdStickyHold $Root $Owner)) { Write-Host "[watchdog] sticky hold '$Owner' kept (end it with tools/watchdog/pause.ps1 -Resume -Owner $Owner)"; return $false }
  Remove-Item -LiteralPath $p -Force
  return $true
}

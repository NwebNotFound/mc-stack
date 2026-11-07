# mc-stack watchdog

Revives always-on homelab processes that died or hang. Background: on 06.11. at 12:38, the PvP host service exited cleanly. Its scheduled task only restarts on failure, so PvP and VIP worlds were down for about 13 hours.

One pass every 2 minutes, from the scheduled task **"mc-stack watchdog"**:

| What | Dead | Dying | Revive |
|---|---|---|---|
| Compose services in `targets.json` | container `exited`/`created`/`dead`/missing | `unhealthy` for 2 checks in a row | dead: `docker compose [--profile p] up -d --no-deps <svc>`; dying: `docker compose [--profile p] restart <svc>` |
| Scheduled task "mc-stack PvP host service" | task not running | task runs, but no listener on `PVP_HOST_PORT` (default 25599) or `GET /vip/status` with `X-VIP-Token` (from `.env`, never logged) isn't 200, 2 checks in a row | dead: `Start-ScheduledTask`; dying: `Stop-` then `Start-ScheduledTask` |

`starting`, `restarting` (Docker is already on it) and `paused` (someone paused it on purpose) are left alone. If Docker itself doesn't answer, compose targets are skipped: the watchdog doesn't start Docker.

## Targets
Only the allowlist in `targets.json` is ever touched:

- **Compose services:** velocity, lobby, survival, craftcore-db, bot (profile `bot`), playit (`playit`), ops-helper, docker-socket-filter and plugin-builder (`ops`).
- **Host tasks:** "mc-stack PvP host service".
- **Never touched:** `pvp`/`pvp-1` and `vipworld-*` (the PvP host service starts and stops them), `hermes-mcp`, `cloudflared`, `cloudflared-mcp`, `hermes-test`, `Hermes_Gateway` and `AIPanel_*` (retired). This is a hard guard: an excluded name in the allowlist makes the watchdog refuse to run.

## Crash-loop guard
At most **3 revives per target per hour**, spaced by a backoff of 0, 4 and 8 minutes. A 4th failure within the hour marks the target **needs a human**, and the watchdog leaves it alone.

That mark clears by itself once the target is healthy again. You can also clear it after a fix with `pause.ps1 -Reset <target>`.

## Logs and status
- `logs/watchdog/watchdog-<yyyy-MM>.log` holds one JSON line per event: revived, unhealthy, backoff, give-up, recovered, paused/resumed, docker-down, errors. Healthy passes write nothing. There's a new file each month, and nothing is deleted.
- `logs/watchdog/status.json` is for Homelab Manager's checkup: `lastCheck`, `docker`, `paused` (active holds), `needsHuman` (list), and per target the `verdict`, `lastRevive`, `revivesLastHour` and `needsHuman`.
- `logs/watchdog/state.json` is internal (streaks, revive times).
- There's no chat alert: the repo has no ops alert channel yet, and no new external service is added. `status.json` is the alert path.

## Pausing (deploys, maintenance)
The watchdog does nothing while a hold is active:

- **`backup.ps1`** holds while it runs. With **`-LeaveStopped`** the hold stays after it finishes, so lobby and survival stay down for the deploy. End it after the deploy has started the servers again:
  `powershell -File tools\watchdog\pause.ps1 -Resume -Owner backup`
- **`dev-reset.ps1`** holds while it runs. If a destructive reset fails, the hold stays, so Survival isn't started half-reset.
- **`backups/.maintenance.active`** (the maintenance marker, once present) also pauses it.
- **Manual pause:** `pause.ps1 -Hours 2 -Reason "Umbau"`; resume with `pause.ps1 -Resume`. Running `pause.ps1` alone shows the active holds and any target that needs a human.

Every hold ends after **3 hours at the latest** (`holdMaxHours`), so a forgotten hold can't switch the watchdog off for good.

## Install / uninstall (Homelab Deployer, after merge)
```
powershell -ExecutionPolicy Bypass -File tools\watchdog\install-task.ps1             # register (idempotent)
powershell -ExecutionPolicy Bypass -File tools\watchdog\install-task.ps1 -Uninstall  # remove the task (logs stay)
```
- **Account:** the task runs as the current user, in the same session as Docker Desktop and the PvP host service.
- **Schedule:** every 2 minutes, never two runs at once, at most 5 minutes per run.
- **Run one pass by hand:** `powershell -ExecutionPolicy Bypass -File tools\watchdog\watchdog.ps1`

## Tests
`tools/test-watchdog.ps1` mocks docker and the scheduled-task probes, and runs in CI (pwsh). It covers:
- dead → revived;
- unhealthy twice → restarted;
- hold or marker → no action;
- 4th failure in an hour → gives up;
- excluded targets are never touched and refused in the config;
- Docker down;
- the token is never logged.

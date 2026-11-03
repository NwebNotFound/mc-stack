# ops-helper

Small privileged executor that gives **hermes-mcp** ops capabilities (status,
logs, builds, restarts, plugin deploys) without ever handing the MCP container a
Docker socket.

```
Claude Code / claude.ai
        │  HTTPS + Bearer token
        ▼
Cloudflare Tunnel (prepared, NOT created)
        │
        ▼
hermes-mcp            (compose network `mcp`; read_only, cap_drop ALL,
        │              no-new-privileges, NO docker socket)
        │  POST /op  +  Bearer OPS_HELPER_TOKEN     ── network `mc-stack-ops`
        │              (internal: true, no host port, no internet)
        ▼
ops-helper            (the ONLY service with /var/run/docker.sock,
        │              repo mounted read-only, fixed allowlist)
        │  docker ps/logs/inspect/exec/restart/run (argv lists, never a shell)
        ▼
mc-stack services: velocity · lobby · survival · pvp · vipworld-1..3 · bot
                   craftcore-db · playit · cloudflared · hermes-mcp
```

Nothing in this service starts by itself: it lives in the compose profile `ops`
and is disarmed (`OPS_ARMED=false`) until Nils says otherwise.

## Ops tools: threat model

**What the attack surface actually is.** The MCP endpoint is the only piece of
this stack that is meant to be reachable from the internet (through the
Cloudflare Tunnel, `mcp.nwebnotfound.dev`). Everything below assumes an attacker
who already holds the MCP bearer token — i.e. the strongest thing they can steal
from the MCP side.

1. **A stolen MCP bearer token cannot restart a game server by itself.** T2
   actions need three independent things: `confirm=true`, an integer
   `delay_seconds >= 60`, **and** the global `OPS_ARMED=true` on the helper. That
   switch is not reachable through any tool — it is an env var in `.env` plus a
   container restart. With the default (`false`) the token can read status/logs
   and run builds, and nothing else.
2. **Why hermes-mcp must not have the socket.** `/var/run/docker.sock` is
   root-equivalent on the host: anything that can talk to it can start a
   container with `/` mounted and take the whole machine. The MCP container is
   the one with the internet-facing tunnel, the most exposed code path and the
   least predictable input (LLM-driven tool calls). Keeping the socket out of it
   means a bug in the MCP server — or in the model's tool use — cannot become
   host root. The helper is a separate, tiny process with no tunnel, no published
   port, no internet route and a fixed allowlist.
3. **The shared secret is the second boundary.** `OPS_HELPER_TOKEN` (>= 24
   chars) is compared in constant time over SHA-256 digests; a missing or short
   token makes the helper refuse to start (fail closed). It is never logged,
   never put in a URL and never returned. hermes-mcp only advertises the ops
   tools when both `OPS_HELPER_URL` and `OPS_HELPER_TOKEN` are set, so an
   unconfigured deployment shows no capability that would fail anyway.
4. **No generic exec.** There is no "run this command" action. The allowlist
   (`config/ops-allowlist.json`) names the actions; each implementation builds an
   **argv list** from constants plus validated values and calls
   `spawn(..., { shell: false })`. Service names must match
   `^[a-z0-9][a-z0-9-]{0,31}$` and be listed for that action; every other
   parameter is an enum, an integer range or a sha256. `bot; rm -rf /`,
   `../../etc/passwd`, `lobby --privileged` and a smuggled `argv` key are refused
   before a single docker call happens (see `test/injection.test.mjs`).
5. **Blast radius of the helper itself.** Even if an attacker got the shared
   secret, the allowlist forbids — in code, not only in the JSON —
   `craftcore-db`, `hermes-mcp`, `ops-helper`, `hermes-test`, and there is no
   action that maps to `docker compose down`, `down -v`, `rm`, `volume
   rm/prune`, `system prune` or `kill -9`. Writes are limited to
   `config/lobby-plugins` and `config/survival-plugins` (one fixed file name per
   service) plus the artifact volume and `backups/`. The rest of the repo is a
   read-only mount. `read_only: true`, `cap_drop: ALL`,
   `no-new-privileges:true` still apply to the helper container.
6. **Kill switch.** `touch data/ops-helper/DISABLED` (or `OPS_DISABLED=true`)
   makes the helper refuse every action above T0, immediately and without a
   restart of anything else. The audit log keeps recording the refusals.
7. **Order of operations before going live.** Cloudflare **Access (service
   token or IdP) and the OAuth 2.1 work for the claude.ai connector come
   first**, then the tunnel, and only then `OPS_ARMED=true`. Reason: the bearer
   token is the only authentication today; as long as `mcp.nwebnotfound.dev` is
   exposed with just that token, the ops surface must stay disarmed. Also note
   that `OPS_HELPER_TOKEN` has to be set in `.env` before `ops-helper` will even
   start.

### What a stolen token can still do (be honest about it)

* read container status, health, uptime and (redacted, capped) logs;
* read repo branch/HEAD/dirty count and backup listings;
* run `gradle clean build` / `npm test` in throwaway containers — CPU/disk work,
  and the build container may pull images and dependencies from the internet;
* **T1**: restart `bot` and `cloudflared-mcp` (reversible, no player impact) and
  run the repo's `backup.ps1` (which briefly stops and restarts lobby+survival —
  see the caveat below);
* **T2, only when armed**: restart lobby/survival/velocity, deploy or roll back
  the CraftCore jar.

That is a deliberate, bounded list — not "arbitrary root on the host".

## Tiers and gates

| Tier | Actions | Gates |
| --- | --- | --- |
| T0 | `ops_status`, `ops_logs`, `ops_disk`, `ops_backups_list`, `ops_players`, `ops_git_state`, `ops_build_plugin`, `ops_run_tests` | none (read-only) |
| T1 | `ops_restart_service` (bot, cloudflared-mcp), `ops_backup_now` | `confirm=true` + cooldown 300 s |
| T2 | `ops_restart_game` (lobby, survival, velocity), `ops_deploy_plugin`, `ops_rollback_plugin` | `confirm=true` + `OPS_ARMED=true` + `delay_seconds >= 60` + fresh backup + cooldown 1800 s |

Gate order is fixed and has no bypass flag: kill switch → action enabled →
confirm → armed → delay → cooldown → per-action rate limit (12 calls / 60 s by
default). A call that passes the gates but fails during execution still starts
its cooldown, so a broken action cannot be hammered.

T2 additionally:

* **announcement** — `docker exec <container> rcon-cli say …` with a fixed
  message built from the validated service name and the integer delay. If no
  container answers (velocity has `ENABLE_RCON=FALSE` and no `rcon-cli` in its
  image), the result says `announced: false` and explicitly states that **no
  in-game announcement was made** — the action still proceeds, because the gate
  is the delay and the report, not the chat message;
* **backup before touching anything** — see below;
* **health check** after each restart (poll `docker inspect .State`, up to
  `deploy.health_timeout_seconds`);
* **deploy**: rolling per service with `delay_seconds` between them, the previous
  jar kept under `/artifacts/rollback/<service>-craftcore-<sha12>.jar`, jar hash
  verified after writing, and an automatic rollback of everything already
  touched when a step fails (`code: health_check_failed_rolled_back`).

## Backup handling (important caveat)

T2 requires a **fresh backup**: a `backups/daily-*/COMPLETE.txt` younger than
`limits.backup_max_age_seconds` (default 900 s). The helper uses the repo's own
`backup.ps1` — it does not invent a second backup procedure. Two honest
limitations:

* `backup.ps1` is PowerShell and the helper image has no `pwsh`, so
  `ops_backup_now` currently returns `backup_interpreter_missing` with the exact
  host command (`powershell -ExecutionPolicy Bypass -File <repo>\backup.ps1`).
  The T2 gate then accepts a *recent* backup that was made on the host, and
  refuses the action if there is none.
* `backup.ps1` stops lobby+survival for the duration of the copy (that is what
  the script does), so `ops_backup_now` is T1 by the letter of the design but not
  player-free in practice. Nils may prefer to keep backups manual/host-side and
  leave `ops_backup_now` unused.

Alternative, not implemented here: the Discord bot already does an RCON-pause +
`tar` backup (`bot/bot.py::backup`, it has `data/lobby` and `data/survival`
mounted read-only). Doing the same in ops-helper would mean mounting both world
directories read-only — a bigger surface than a PowerShell interpreter, so this
is left as a decision, not a surprise.

## Allowlist format (`config/ops-allowlist.json`)

Read once at startup from the read-only repo mount. **No tool can change it**;
editing it needs a repo commit and a helper restart — the same trust level as the
helper's own source. Validation is paranoid and fails closed: unknown `impl`,
malformed service name, a `..` in any path, an argv token that is not a plain
token, a mutating list naming a forbidden service, or a missing plugin directory
all make the helper refuse to start.

| Field | Meaning |
| --- | --- |
| `limits` | output cap, max log lines, rate window/calls, backup max age |
| `tiers` | per-tier `confirm_required`, `armed_required`, `cooldown_seconds`, `min_delay_seconds`, `backup_required` |
| `paths` | repo / backups / artifacts mounts, plugin jar file name, backup script name |
| `services` | per action kind: `observe`, `logs`, `players`, `restart_t1`, `restart_game`, `deploy_plugin` |
| `forbidden_services` / `forbidden_operations` | documented never-list; the mutating service lists are checked against it at load time |
| `build` | image + argv for the plugin build and each test target (`source`, `command`, `setup?`, `artifact?`, `test_results?`) |
| `deploy` | rollout order, plugin dir per service, health timeout/poll |
| `actions` | which `ops_*` action exists, its tier, its `impl` and whether it is enabled |

## Audit log

Append-only JSONL at `data/ops-helper/audit.jsonl` (bind mount
`./data/ops-helper`). One line per call that reaches the dispatcher — **including
refused ones**: `ts`, `id`, `action`, `tier`, `impl`, `decision`
(`allow`/`deny`/`error`), `code`, redacted `params`, `duration_ms`, `ok`, a small
`summary`. Values are redacted with the same module hermes-mcp uses
(`tools/hermes-mcp/src/redact.ts`; `src/redact.ts` and `src/logger.ts` are
byte-identical copies, and a test fails if they drift). A line is capped at 8 KiB
so a huge log tail cannot fill the disk. The helper never reads, truncates or
deletes the file.

## Configuration (env)

| Env | Default | Effect |
| --- | --- | --- |
| `OPS_HELPER_TOKEN` | *(empty)* | shared secret with hermes-mcp; missing or < 24 chars → refuse to start |
| `OPS_ARMED` | `false` | global T2 arm switch; only an explicit `true`/`1`/`yes`/`on` arms it |
| `OPS_DISABLED` | `false` | kill switch (equivalent to the `DISABLED` file) |
| `OPS_HELPER_PORT` / `OPS_HELPER_HOST` | `8766` / `0.0.0.0` | listener inside the compose network (no host port) |
| `OPS_REPO_DIR` | `/repo` | read-only repo mount |
| `OPS_ALLOWLIST_PATH` | `/repo/config/ops-allowlist.json` | allowlist location |
| `OPS_DATA_DIR` | `/app/data/ops-helper` | audit log, cooldown state, `DISABLED` |
| `OPS_ARTIFACTS_DIR` | `/artifacts` | artifact volume mount |
| `OPS_ARTIFACTS_VOLUME` / `OPS_GRADLE_CACHE_VOLUME` | `mc-stack-ops-artifacts` / `mc-stack-ops-gradle-cache` | named volumes handed to the throwaway build containers (no host path is ever guessed) |
| `OPS_ACTION_TIMEOUT_SECONDS` / `OPS_BUILD_TIMEOUT_SECONDS` | `120` / `1800` | per-command and per-build timeouts |
| `OPS_DOCKER_BIN` | `docker` | docker CLI path |
| `OPS_BACKUP_INTERPRETERS` | `pwsh,powershell` | candidates for running `backup.ps1` |

## Setup (once, not done by this PR)

1. Put a shared secret into `.env` (never in git):

   ```
   OPS_HELPER_TOKEN=<openssl rand -hex 32>
   ```

2. Start the helper and MCP (this **recreates** `hermes-mcp`, because it gains a
   second network — do it in a maintenance window):

   ```
   docker compose --profile ops --profile mcp up -d --build ops-helper hermes-mcp
   docker compose logs -f ops-helper
   ```

3. Verify it is locked down:

   ```
   docker port mc-stack-ops-helper-1            # empty: no host port
   curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8766/healthz   # unreachable from the host
   docker exec mc-stack-hermes-mcp-1 node -e "fetch('http://ops-helper:8766/healthz').then(r=>console.log(r.status))"  # 200
   docker exec mc-stack-hermes-mcp-1 node -e "fetch('http://ops-helper:8766/op',{method:'POST'}).then(r=>console.log(r.status))"  # 401
   ```

4. Arm T2 only after Cloudflare Access + OAuth are in front of the MCP endpoint:
   set `OPS_ARMED=true` in `.env` and `docker compose --profile ops up -d ops-helper`.

## Tests

```
cd tools/ops-helper
npm ci && npm test          # node --test over dist/, no docker and no network
```

The suite never talks to Docker, to a container or to the internet: the runner is
faked, so running it cannot touch the running stack. It covers allowlist
validation (bad impl, bad service names, `..`, argv tokens with shell
metacharacters, forbidden services, T0/T2 invariants), the tier gates (T2 without
armed/confirm/delay, kill switch, cooldown, rate limit), injection attempts
(`bot; rm -rf /`, `$(id)`, `../../etc/passwd`, smuggled `argv`/`command` keys,
prototype-shaped action names), the audit log (append-only, redacted, truncated),
the kill switch, and the T2 deploy/rollback flow end-to-end against a fake
runner (including the automatic rollback when a health check fails).

## Not in phase 1 (documented concepts, deliberately not implemented)

* **Website deploy.** Needs a Cloudflare API token that the helper does not have
  and must not get in this PR. Concept if it is ever wanted: a *separate* token
  with the narrowest possible scope (Workers Scripts:Edit for that one script
  only), `wrangler deploy` as a fixed argv, `--env` pinned, only from a clean
  `main` checkout, no `wrangler` subcommand other than `deploy`, and a diff-free
  `git status` check before it runs. Until then the website belongs to the
  cloudflare profile's own workflow.
* **D1 migrations.** Same reasoning, plus they are not reversible by design.
  Concept: only `.sql` files that exist on `main`, only statements matching an
  allowlisted additive pattern (`CREATE TABLE IF NOT EXISTS`, `ALTER TABLE … ADD
  COLUMN`, `CREATE INDEX IF NOT EXISTS`), never `DROP`/`DELETE`/`UPDATE`, a
  `d1 migrations` dry-run first, and a backup of the database before anything is
  applied. Not implemented.
* **`ops_ci_status(pr)`.** Left out on purpose: the token available to this
  stack has no `checks:read`, so the tool could only guess. CI status stays with
  the GitHub UI / `gh` on the host.
* **`ops_players` TPS/MSPT.** Only the `list` command is queried (it exists on
  every Paper server and `rcon-cli` ships with the itzg image). Paper's `/tps`
  availability was not verified in this stack, so it is not called.

## Troubleshooting

* `refusing to start` — `OPS_HELPER_TOKEN` missing/short, or
  `config/ops-allowlist.json` unreadable/invalid. The log line names the field.
* `ops_helper_not_configured` in hermes-mcp — `OPS_HELPER_TOKEN` is empty, so the
  ops tools are not even advertised.
* `ops_helper_auth_failed` — the two containers have different tokens.
* `backup_interpreter_missing` — expected without `pwsh` in the image; run
  `backup.ps1` on the host, or see "Backup handling" above.
* `not_armed` — T2 is refused on purpose; set `OPS_ARMED=true` and restart the
  helper (after Access/OAuth are in place).
* `cooldown_active` — T1 300 s / T2 1800 s; the answer carries
  `retry_after_seconds`.
* `source_missing` / `artifacts_volume_unset` — the repo mount or the volume
  wiring is wrong; nothing was built and nothing was touched.

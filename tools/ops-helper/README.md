# ops-helper

Small privileged executor that gives **hermes-mcp** ops capabilities (status,
logs, builds, restarts, plugin deploys) without ever handing the MCP container a
Docker socket.

```
Claude Code / claude.ai
        │  HTTPS + Bearer token
        ▼
Cloudflare Tunnel  mcp.nwebnotfound.dev  (already live; the tunnel maps ONLY
        │                                   to hermes-mcp:8765, never to 8767)
        ▼
hermes-mcp            (compose networks `mcp` + `mc-stack-ops`; read_only,
        │              cap_drop ALL, no-new-privileges, NO docker socket)
        │              listener 8765 = tunnel-facing  -> T0/T1 only
        │              listener 8767 = 127.0.0.1 only -> T0/T1/T2
        │  POST /op  +  Bearer OPS_HELPER_TOKEN     ── network `mc-stack-ops`
        │              (internal: true, no host port, no internet)
        ▼
ops-helper            (NO docker socket: DOCKER_HOST=tcp://docker-socket-filter:2375,
        │              repo mounted read-only, fixed allowlist)
        │  Bearer OPS_FILTER_TOKEN                  ── network `mc-stack-ops-docker`
        │              (internal: true; ONLY ops-helper + the filter)
        │  GET ps/inspect/logs + POST restart of the T1/T2 names; create/exec/
        │  start/stop/kill are refused by the filter
        │  build/test  ->  job-<uuid>.json  ->  volume `mc-stack-ops-jobs`
        ▼
docker-socket-filter  (this repo, no dependencies; the ONLY
        │              service with /var/run/docker.sock; no host port)
        ▼
mc-stack services: velocity · lobby · survival · pvp · vipworld-1..3 · bot
                   craftcore-db · playit · cloudflared · hermes-mcp

plugin-builder        (NO docker socket, network_mode: none, read_only,
        │              cap_drop ALL, no-new-privileges, CPU/RAM/PID caps)
        │  polls `ops-jobs`, validates strictly, runs ONE fixed command from
        ▼  config/ops-allowlist.json, writes `result-<uuid>.json` + the jar into
           the artifact volume `mc-stack-ops-artifacts`
```

Nothing in this service starts by itself: it lives in the compose profile `ops`
and is disarmed (`OPS_ARMED=false`) until Nils says otherwise. `plugin-builder`
is in the same profile and is the only new service; it holds no socket and has no
network at all.

## Ops tools: threat model

**What the attack surface actually is.** `mcp.nwebnotfound.dev` is **already
live** and is the only part of this stack reachable from the internet. It is
guarded by nothing but the bearer token — Cloudflare Access exists on the
website, but only in front of `/admin`, not in front of the MCP hostname.

**The real risk, stated plainly:** as soon as the ops tools are usable, every
holder of the MCP token becomes an operator of this server. The bearer token is
a long shared secret that ends up in Claude connectors, in shell history and in
config files. Treating it as "read-only" is therefore not a security property by
itself — it is enforced by the two-level token scheme below, and by the local
listener for T2.

1. **A stolen MCP bearer token cannot restart a game server by itself.** Three
   independent things are needed, and none of them is reachable through a tool:
   the **second token** (`HERMES_MCP_OPS_TOKEN`, a different secret the read-only
   token does not grant), the **local listener** (T2 tools exist on 8767 only,
   which the tunnel does not route), and the global `OPS_ARMED=true` env var on
   the helper. With the defaults (no ops token, disarmed) a stolen read-only
   token can read status/logs and ask for a build/test — nothing else.

   The level is decided by *which* bearer token the caller presents: a plain
   `HERMES_MCP_TOKEN` is the `base` level (T0 only), `HERMES_MCP_OPS_TOKEN` is
   the `ops` level (T0 + T1). Both are compared in constant time over SHA-256
   digests, the ops listener has its own, stricter brute-force brake
   (`HERMES_MCP_OPS_LOCKOUT_AFTER`, default 3) and an unset ops token means
   nobody gets the elevated level (fail closed). A tool the caller's level may
   not use is not even listed — see `test/ops.test.mjs`.
2. **Why hermes-mcp must not have the socket.** `/var/run/docker.sock` is
   root-equivalent on the host: anything that can talk to it can start a
   container with `/` mounted and take the whole machine. The MCP container is
   the one with the internet-facing tunnel, the most exposed code path and the
   least predictable input (LLM-driven tool calls). Keeping the socket out of it
   means a bug in the MCP server — or in the model's tool use — cannot become
   host root. The helper is a separate, tiny process with no tunnel, no published
   port, no internet route and a fixed allowlist — and it does not hold the
   socket either: it speaks to the docker-socket-filter over TCP.
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
   before a single docker call happens (see `test/injection.test.mjs`). Builds and
   tests are not an exception: the helper writes a 5-field job file and
   `plugin-builder` re-validates it (`test/validate.test.mjs`).
5. **Blast radius of the helper itself.** Even if an attacker got the shared
   secret, the allowlist forbids — in code, not only in the JSON —
   `craftcore-db`, `hermes-mcp`, `ops-helper`, `hermes-test`, and there is no
   action that maps to `docker compose down`, `down -v`, `rm`, `volume
   rm/prune`, `system prune` or `kill -9`. Writes are limited to
   `config/lobby-plugins` and `config/survival-plugins` (one fixed file name per
   service) plus the artifact/job volumes and `backups/`. The rest of the repo is
   a read-only mount. `read_only: true`, `cap_drop: ALL`,
   `no-new-privileges:true` still apply to the helper container.
6. **Kill switch.** `touch data/ops-helper/DISABLED` (or `OPS_DISABLED=true`)
   makes the helper refuse every action above T0, immediately and without a
   restart of anything else. The audit log keeps recording the refusals.
7. **Order of operations before arming T2.** `OPS_HELPER_TOKEN` and
   `HERMES_MCP_OPS_TOKEN` have to be set in `.env`. Then decide who may hold the
   ops token, because from that moment on they can restart `bot`/
   `cloudflared-mcp` (T1) even through the tunnel. Warm the offline caches (see
   "plugin-builder" below). `OPS_ARMED=true` is the last step and the only one
   that unlocks game-server restarts, plugin deploys and rollbacks — and those
   still require the local listener, so they cannot be triggered from the
   internet at all. **OAuth for the claude.ai connector is still not implemented
   and is not part of this PR**; it remains the recommended future replacement
   for the shared bearer token.

### Threat model, part 2: what a *stolen ops token plus local access* can do

T2 exists now, so the honest bar is higher than "read status/logs". Assume an
attacker holds `HERMES_MCP_OPS_TOKEN` **and** can reach `127.0.0.1:8767` (i.e.
they are already on the host/WSL). With `OPS_ARMED=true` they can:

* restart `lobby`, `survival` or `velocity` (players are kicked; the world is not
  wiped) — `ops_restart_game`;
* deploy whatever jar is currently recorded as the last successful build into
  `config/lobby-plugins` + `config/survival-plugins` and restart both servers —
  `ops_deploy_plugin`;
* roll those jars back — `ops_rollback_plugin`;
* restart `bot` / `cloudflared-mcp` (T1) and read everything the read-only level
  can read.

They still **cannot**: build a malicious jar through this stack (the build runs
the repo's own gradle command in a no-network container from a read-only
checkout — they would have to change the repo, which is the same trust level as
editing the helper's source), deploy an arbitrary file (only the last-build jar
can be deployed: its builder-reported path must resolve under
`/artifacts/artifacts/`, it is re-hashed and copied into the helper-only
`data/ops-helper/jars/`, and that copy is re-hashed again before the deploy),
restart the database, `hermes-mcp`, `hermes-test` or the helper itself, exec in
any container, create/stop/kill containers, or touch `.env`/`secrets/`/`data/`.

Every one of those actions costs a 1800 s cooldown and requires
`confirm=true` **and** `acknowledge_no_announce=true` (see "Announcement" below),
and every call lands in the audit log with its `audit_id`.

What is **not** mitigated by design: a local operator can restart a game server
in a way that loses in-flight chunk writes (that is why the backup gate exists),
and a stolen token plus local access is, by construction, an operator. If the
token is suspected leaked, `touch data/ops-helper/DISABLED` stops everything
above T0 instantly.

### What a stolen token can still do (be honest about it)

Through the socket filter the helper can **read** Docker state and restart two
named containers — nothing else. With the *read-only* token (`base` level):

* read container status, health, uptime and (redacted, capped) logs;
* read repo branch/HEAD/dirty count and backup listings;
* read filesystem/`du` sizes.

With the *ops* token (`ops` level) the extra surface is exactly:
`ops_restart_service` with `confirm=true` — restarting `bot` or `cloudflared-mcp`
(300 s cooldown) **plus, only on the local listener with the helper armed**, the
three T2 actions (game-server restart, plugin deploy, plugin rollback, 1800 s
cooldown, confirm + delay >= 60 s + recent backup + `acknowledge_no_announce=true`).
Everything else is `"enabled": false` in `config/ops-allowlist.json` (see
"Disabled actions" below) **and** has no route in the filter, so it is refused
twice: exec and container create/stop/kill stay impossible even with the ops
token.

That is a deliberate, bounded list — not "arbitrary root on the host".

## Tiers and gates

| Tier | Actions | Token level | Enabled now | Gates |
| --- | --- | --- | --- | --- |
| T0 | `ops_status`, `ops_logs`, `ops_disk`, `ops_backups_list`, `ops_git_state` | `base` | **yes** (read-only) | none |
| T0 | `ops_build_plugin`, `ops_run_tests` | `base` | **yes** (no player impact) | no container is created: jobs go to plugin-builder |
| T0 | `ops_players` | `base` | **no** – needs docker exec | – |
| T1 | `ops_restart_service` (bot, cloudflared-mcp) | `ops` | **yes** | `confirm=true` + cooldown 300 s |
| T1 | `ops_backup_now` | `ops` | **no** – no PowerShell in the helper image | `confirm=true` + cooldown 300 s |
| T2 | `ops_restart_game` (lobby, survival, velocity), `ops_deploy_plugin`, `ops_rollback_plugin` | `ops` **+ local listener** | **yes** (armed + gates) | `confirm=true` + `OPS_ARMED=true` + `delay_seconds >= 60` + **recent backup** + `acknowledge_no_announce=true` + cooldown 1800 s |

### Disabled actions (current state)

| Action | Why it is still paused |
| --- | --- |
| `ops_players` | needs `POST /exec/{id}/start`; the filter has no exec route |
| `ops_backup_now` | the helper image has no PowerShell, so `backup.ps1` cannot run here |

Both stay `"enabled": false` with a `disabled_reason` that is returned verbatim,
so a paused tool explains itself instead of failing vaguely.

Everything else is enabled and covered above. Disabled is still the safe default:
re-enabling one action is a one-line change in the allowlist (needs a helper
restart), and a `POST`-based action additionally needs the proxy to grant the
matching method — which this PR still refuses for everything except the single
`/containers/<name>/restart` route for exact allowlisted names.

Gate order is fixed and has no bypass flag: token level + listener → kill switch →
action enabled → confirm → armed → delay → no-announce acknowledgement → cooldown
→ per-action rate limit (12 calls / 60 s by default). A call that passes the gates
but fails during execution still starts its cooldown, so a broken action cannot
be hammered.

T2 additionally:

* **one at a time** — a process-wide T2 lock: while any T2 action runs (including
  its `delay_seconds` lead time) every other T2 call is refused with `t2_busy`
  (not queued, no cooldown started);
* **never during a backup** — while `backup.ps1` / `dev-reset.ps1` hold the
  maintenance lock they write `backups/.maintenance.active` (the Windows file
  lock itself is not visible through the bind mount). A T2 call is refused with
  `maintenance_in_progress` while it exists, and right before each restart (after
  the lead time) the helper waits up to `OPS_MAINTENANCE_WAIT_SECONDS` for a
  backup that started in the meantime, then gives up without restarting. A
  marker older than `OPS_MAINTENANCE_STALE_SECONDS` (a killed script) is ignored;
* **no announcement (and it says so)** — see below;
* **recent backup, never a create** — see below;
* **health check** after each restart (poll `docker inspect .State`, up to
  `deploy.health_timeout_seconds`);
* **deploy**: rolling per service with `delay_seconds` between them, the previous
  jar kept (with its sha256) under the helper-only
  `data/ops-helper/rollback/<service>-craftcore-<sha12>.jar`, jar hash verified
  after writing, and an automatic rollback of everything already touched when a
  step fails (`code: health_check_failed_rolled_back`);
* **rollback** restores a jar only if it lies in `data/ops-helper/rollback/`
  (realpath, no symlink escape) and still matches its recorded sha256; otherwise
  the whole rollback is refused (`rollback_jar_untrusted`) before anything is
  touched.

**Where the state lives.** `last-build.json` and `last-deploy.json` are in
`data/ops-helper/state/` (`/app/data/ops-helper/state`), which only ops-helper
mounts. The artifact volume is shared with plugin-builder, which runs repo
build/test code and is the untrusted component, so nothing the helper later
trusts is kept there. (Records under `/artifacts/state/` from older versions are
ignored: run `ops_build_plugin` once after upgrading; a rollback becomes
available again after the next deploy.)

### Announcement: there is none (and `acknowledge_no_announce` makes that explicit)

Reaching a server console needs `docker exec` (or an RCON/broadcast route the ops
layer does not have), and the filter refuses every exec route. So a T2 action does
**not** warn players: an `ops_restart_game` (or the restart at the end of a deploy)
**kills the running game server process and disconnects everyone online without
warning** — no countdown, no `/say`, no title. The helper cannot even reach the
game console to do better today. Instead of pretending, the tier gate requires
`acknowledge_no_announce=true` (otherwise `announcement_not_acknowledged`), and
the result carries `announcement: { announced: false, detail: "NO in-game
announcement was made…" }`. If a real channel is added later (e.g. an RCON line
emitted by the Discord bot), `announcementStatus()` in `src/actions.ts` is the one
place to change and the gate flag can then be dropped.

## Backup handling (important caveat)

T2 requires a **recent backup**, it never creates one: the newest
`backups/daily-.../` directory with a `COMPLETE.txt` marker must be younger than
`tiers.T2.backup_max_age_hours` (default **12 h**, configurable in
`config/ops-allowlist.json`). Missing → `no_recent_backup`; too old →
`stale_backup`, and the message says to run `backup.ps1` on the host. The
`ops_backups_list` tool is what tells you the age.

`ops_backup_now` stays **paused**, because the helper image has no PowerShell and
the repo's own `backup.ps1` also stops lobby+survival for the duration of the
copy — i.e. it is not player-free. **Design options, deliberately not built in
this PR:**

1. **Host task with a fixed script allowlist** (preferred): a Windows scheduled
   task runs `backup.ps1` nightly; the helper only ever *reads* the freshness of
   its output. No new privilege in the helper at all, and "recent backup" is then
   always satisfiable.
2. **Hot backup (dump + `save-off`)** — the approach the Discord bot already uses
   (`bot/bot.py::backup`: RCON `save-off`/`save-all flush`, then `tar`, then
   `save-on`). Doing it from ops-helper would mean mounting `data/lobby` and
   `data/survival` read-only *and* granting an exec/RCON route, which is a bigger
   surface than a PowerShell interpreter and re-opens the exec question the filter
   closed. Not worth it while option 1 exists.

Alternative, not implemented here: the Discord bot already owns that RCON-pause +
`tar` backup. Delegating "please back up now" to the bot would be a *different*
service boundary (bot, not helper) and is left as a decision, not a surprise.

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
| `build` | `network` (`none`), `limits` (`cpus`/`memory`/`pids`), image + argv for the plugin build and each test target (`source`, `command`, `setup?`, `artifact?`, `test_results?`, `cache?`) — these are the fixed commands plugin-builder runs |
| `deploy` | rollout order, plugin dir per service, health timeout/poll |
| `actions` | which `ops_*` action exists, its tier, its `impl` and whether it is enabled |

`build.network` defaults to `none` and `build.limits` is mandatory (cpus, memory,
pids) — the helper refuses to start without it, so there is no configuration in
which a build container runs unlimited or on the host network.

## Docker socket filter

ops-helper does **not** hold `/var/run/docker.sock`. Its `DOCKER_HOST` points at
`docker-socket-filter` (a compose service **built from this repo**,
`tools/docker-socket-filter`), which is the only container with the socket, has
no host port and lives only in the internal `mc-stack-ops-docker` network, which
only ops-helper shares (hermes-mcp is not a member). Every request except
`/_ping` must carry `Authorization: Bearer $OPS_FILTER_TOKEN`; the helper writes a
private Docker CLI config (`HttpHeaders`, `/tmp/ops-docker-config`, mode 0600) at
startup and points `DOCKER_CONFIG` at it, so every `docker` call sends it.

The filter is a ~400-line, dependency-free Node HTTP filter in front of the
socket. It allowlists **exact method + path + query** and returns `403` for
everything else:

| Method | Path | Query | Used by |
| --- | --- | --- | --- |
| `HEAD`, `GET` | `/_ping` | *(none)* | the CLI's version-negotiation/health probe (documented exception) |
| `GET` | `/containers/json` | `all`, `filters` | `ops_status` (`docker ps`) |
| `GET` | `/containers/<ref>/json` | *(none)* | `ops_logs`, `ops_restart_service` (`docker inspect`) |
| `GET` | `/containers/<ref>/logs` | `tail` (required, `<=300`), `stdout`, `stderr`, `timestamps`, `since`, `until` | `ops_logs` |
| `POST` | `/containers/<name>/restart` | `t` ∈ 0..30 | `ops_restart_service`, `ops_restart_game`, `ops_deploy_plugin` — **only** for an exact name from `OPS_FILTER_RESTART_ALLOW` (T1) or `OPS_FILTER_RESTART_ALLOW_T2` (T2) |

The restart allowlist is split in two on purpose:

| Env | Contents | Who triggers it |
| --- | --- | --- |
| `OPS_FILTER_RESTART_ALLOW` | `mc-stack-bot-1`, `mc-stack-cloudflared-mcp-1` | T1 `ops_restart_service` |
| `OPS_FILTER_RESTART_ALLOW_T2` | `mc-stack-lobby-1`, `mc-stack-survival-1`, `mc-stack-velocity-1` | T2 `ops_restart_game` / deploy / rollback |

The Docker API has **one** restart path, so the filter accepts the union and the
T1/T2 split is enforced one level up, by the helper's own `services.restart_t1` /
`services.restart_game` lists (a test asserts they never overlap). The audit line
says which list matched (`restarts: "t1" | "t2"`). `craftcore-db`, `hermes-mcp`,
`hermes-test`, `ops-helper` and the filter itself are in **neither** list.

> **What the filter does NOT enforce, plainly:** the T1/T2 separation is *only*
> enforced by this helper. `POST /containers/<name>/restart` is a single route to
> the Docker API, so the filter can only say yes/no per exact container name — it
> cannot tell whether the caller is a T1 restart of `bot` or a T2 restart of
> `lobby`. A caller that bypassed the helper and talked to the filter directly
> (i.e. already had the internal `ops` network plus a way to reach it) could
> therefore restart a game server without any of the T2 gates — no `OPS_ARMED`,
> no cooldown, no recent backup, no no-announce acknowledgement. The T2 gates and
> the local-only 8767 listener are the helper's job, and the helper is the only
> thing allowed to speak to the filter. The `t1`/`t2` split in the filter config
> exists to keep the *lists* auditable and disjoint, not to enforce the tiers.

Everything else is refused: `POST /containers/create`, `/start`, `/stop`,
`/kill`, `/pause`, `/rename`, `/update`, `DELETE /containers/<x>`,
`/containers/prune`, `/exec/...`, `/volumes`, `/images`, `/networks`, `/build`,
`/events`, `/info`, `/version`, `/system/...`, `/auth`, `/swarm`, `/secrets`,
`/tasks`, `/services` — plus traversal (`..`, `%2e%2e`), `//`, `;`, CRLF,
duplicate `Content-Length`, `Transfer-Encoding`, `Upgrade` and any request body.

A unit test reads the service block out of `compose.yaml` and asserts that
**exactly one** service mounts the socket, that it is the filter, that it has no
host port, that it lives only in the internal network, that the restart lists keep
the T1 names separate from the T2 game servers with no overlap, that
`plugin-builder` has `network_mode: none`, no socket and hard CPU/RAM/PID caps,
that its repo mount is **narrow** (sources + allowlist + `.git`, all `:ro`) and
that it mounts **neither** `.env`/`secrets/`/`data/` **nor** the repo root, and
that `ops-helper` still keeps the whole repo read-only on purpose
(`tools/ops-helper/test/proxy.test.mjs`). The filter's own behaviour is covered
by `tools/docker-socket-filter/test/filter.test.mjs` (fake upstream) and
`tools/docker-socket-filter/test/real-daemon.sh` (real socket, throwaway
containers).

### Why the filter replaced tecnativa/docker-socket-proxy

`tecnativa/docker-socket-proxy` filters by API **section** (`CONTAINERS`, `POST`,
`EXEC`, ...), which cannot express "GET is fine, but only this one POST path, and
only for these two container names". It is all-or-nothing: `POST=0` makes
`docker restart` impossible too (the first HAProxy rule denies every non-GET
request before `ALLOW_RESTARTS` is reached — measured against the real daemon),
and `POST=1` re-opens the whole mutable container API, `create` and `exec`
included. The path-exact filter was the only way to get the T1 restart back
**without** re-granting create/exec.

### What this changes for the tools

| Tool | Before | Now |
| --- | --- | --- |
| `ops_status`, `ops_logs`, `ops_disk`, `ops_backups_list`, `ops_git_state` | enabled | enabled (unchanged) |
| `ops_restart_service` (T1) | paused | **enabled** — `confirm=true` + ops token, restart by exact container name |
| `ops_build_plugin`, `ops_run_tests` | paused | **enabled** — they no longer create a container; the job goes to `plugin-builder` over a volume |
| `ops_players` | paused | still paused: needs `exec`, which the filter refuses |
| `ops_backup_now` | paused | still paused: no PowerShell in the helper image |
| `ops_restart_game`, `ops_deploy_plugin`, `ops_rollback_plugin` (T2) | paused | **enabled** — the T2 names are on the (separate) filter list; no announcement, so the gate demands `acknowledge_no_announce=true` |

`ops_restart_service` now restarts by **container name**, never by id: the
filter's allowlist is a list of exact names, so a name is the only reference that
passes both checks. The helper resolves the name from `docker ps` and the filter
re-validates it independently. That is why a game-server restart stays impossible
even if the helper asked for one — and a test asserts exactly that.

### How it was verified (reproducible)

The filter is tested twice, and both are automated:

* `tools/docker-socket-filter/test/filter.test.mjs` — a fake Docker daemon on a
  UNIX socket, the real filter in front of it, raw HTTP over a TCP socket. It
  covers the allow table, every forbidden route, traversal/encoding/CRLF/
  smuggling vectors, prefix names, container ids, the `t` bounds and pipelining.
* `tools/docker-socket-filter/test/real-daemon.sh` — builds the image, runs it
  with the **real** `/var/run/docker.sock` on a throwaway network next to two
  throwaway canaries (one on the T1 list, one on the T2 list), and probes it:
  both canaries restart (`204`), a real mc-stack container / game server /
  database / hermes-mcp / container id / `create` / `exec` / `stop` / every
  injection variant is `403`. Only `hermes-dsf-*` containers are created, each
  self-exits, and no `docker compose up` happens.

```
sh tools/docker-socket-filter/test/real-daemon.sh
# -> REAL-DAEMON PROBE: ALL PASS   (45 cases + pipelining)
# -> containers still running that mount the socket: only the ones that
#    self-exit within their timeout
```

### Residual risk, stated honestly

The filter is a genuinely tight boundary, but it is not a sandbox:

* **Whoever compromises the filter process has the socket.** It is the boundary,
  so it is small and dependency-free — but a bug in `src/filter.mjs` is host
  root. That is the price of holding the socket at all.
* **Whoever can change `OPS_FILTER_RESTART_ALLOW` can widen the restart set.**
  That is a compose/env change, i.e. the same trust level as editing the repo.
* `GET /containers/json` and `/containers/<ref>/json|logs` still let the helper
  **read** any container on the host, not just mc-stack ones. That is the
  intended read surface and is capped + redacted by the helper.
* The helper's own argv allowlist and its authentication remain the first
  boundary; the filter is defence in depth, and the two now agree far more
  closely than the section-based proxy ever did.

### Why the repo mount is not narrowed (unlike plugin-builder's)

`ops-helper` mounts the whole repo read-only (`./:/repo:ro`). `plugin-builder`
deliberately does **not**: it sees only `plugins-src/`, `tools/`,
`config/ops-allowlist.json` and `.git` (see "Builds and tests" below). The
asymmetry is intentional, and the helper's mount stays whole because narrowing it
would change behaviour, not just blast radius:

* `ops_git_state` runs `git status --porcelain --untracked-files=no` over the
  **whole** worktree. With a narrowed mount every unmounted tracked file looks
  deleted, so the tool would report a permanently dirty repo — a lying status is
  worse than an honest read-only mount.
* `ops_disk` runs `df` on the repo **root** and `du` on `/repo/data`. A narrower
  mount would move the root onto the container's overlay and report the
  container's filesystem numbers instead of the repo's.
* `ops_deploy_plugin` / `ops_rollback_plugin` read `/repo/backup.ps1` and the T2
  gate inspects the backup directory; deploy writes plugin jars into
  `config/lobby-plugins` / `config/survival-plugins`.

On top of that, the helper's trust level already equals the repo's own code —
editing `config/ops-allowlist.json` or the helper's source requires a repo commit
plus a restart, and the helper can already restart game servers and write plugin
jars. Keeping `.env`, `secrets/` and `data/` out of reach matters for the service
that *runs build commands from source* (`plugin-builder`, no network, no socket),
not for the control plane whose whole job is host-level ops. `.env` is never read
by the helper (no tool takes it as input, no log line contains it) and `data/` is
only ever measured, never read — but the mount itself is whole, and that is the
honest statement.

## Builds and tests: plugin-builder, offline and capped

`ops_build_plugin` and `ops_run_tests` no longer start a container. The helper
writes a five-field job file into the shared `mc-stack-ops-jobs` volume and polls
for the answer; **`plugin-builder`** (a permanent service in the same `ops`
profile) reads it, re-validates everything and runs one fixed command from
`config/ops-allowlist.json`. See `tools/plugin-builder/README.md` for the full
protocol and threat model. The properties that matter here:

* `network_mode: none` — no interface at all; dependencies come from pre-warmed
  cache volumes (`ops-gradle-cache` → `/home/gradle/.gradle`, `ops-npm-cache` →
  `/root/.npm`). Every gradle command carries `--offline` and the npm setup does
  too, so a cold cache fails loudly instead of silently reaching the network.
* CPU/RAM/PID caps (`cpus: "1.5"`, `mem_limit: 2g`, `pids_limit: 512`) so a build
  cannot starve the Minecraft servers.
* `read_only`, `cap_drop: ALL`, `no-new-privileges`, a tmpfs `/work` for the build
  tree, and the repo mounted **read-only** — the builder never writes into the
  checkout.
* **The builder's repo mount is NARROW**: `plugins-src/`, `tools/`,
  `config/ops-allowlist.json` and `.git` only — never the repo root, so `.env`,
  `secrets/` and `data/` are unreachable from it (a test asserts that). It builds
  the tree exported by `git archive <ref> -- <source>`, i.e. exactly the commit a
  job names, and reports a diverging working tree as `dirty` / `dirty_files`
  (a count) instead of silently baking local edits into an artifact. See
  `tools/plugin-builder/README.md`.
* **No Docker socket.** It is the second service the socket never touches (after
  `ops-helper`); a test asserts it.

Cold-cache warm-up (once, on the host; see the plugin-builder README for the exact
commands):

```
docker volume create mc-stack-ops-gradle-cache mc-stack-ops-npm-cache
# then run `gradle dependencies` / `npm ci` once with each cache mounted
```

`build.limits` stays in the allowlist and is asserted by a test, but the enforced
caps now live in the compose service (`plugin-builder`), which is what actually
runs.

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
| `OPS_ARTIFACTS_VOLUME` / `OPS_GRADLE_CACHE_VOLUME` / `OPS_NPM_CACHE_VOLUME` | `mc-stack-ops-artifacts` / `mc-stack-ops-gradle-cache` / `mc-stack-ops-npm-cache` | named volumes handed to the build path (no host path is ever guessed) |
| `OPS_JOBS_DIR` | `/jobs` | shared volume with plugin-builder: the helper writes `job-<uuid>.json` here |
| `OPS_BUILDER_RESULTS_DIR` | `/artifacts/builder` | where plugin-builder writes `result-<uuid>.json` |
| `OPS_BUILDER_TIMEOUT_SECONDS` / `OPS_BUILDER_POLL_MS` | `1800` / `1000` | how long to wait for a job answer, and how often to look |
| `OPS_ACTION_TIMEOUT_SECONDS` / `OPS_BUILD_TIMEOUT_SECONDS` | `120` / `1800` | per-command and per-build timeouts |
| `OPS_DOCKER_BIN` | `docker` | docker CLI path |
| `OPS_MAINTENANCE_WAIT_SECONDS` / `OPS_MAINTENANCE_POLL_SECONDS` | `1800` / `15` | how long a T2 step waits for a running backup before it gives up, and how often it looks |
| `OPS_MAINTENANCE_STALE_SECONDS` | `14400` | a `backups/.maintenance.active` marker older than this is ignored |
| `OPS_FILTER_TOKEN` | *(empty)* | bearer token for docker-socket-filter (>= 24 chars, same value as the filter's); empty → every docker call is refused with 401 |
| `OPS_DOCKER_CONFIG_DIR` | `/tmp/ops-docker-config` | private Docker CLI config dir that carries the filter token |
| `DOCKER_HOST` | *(unset in the image)* | set by compose to `tcp://docker-socket-filter:2375`; without the filter the helper cannot reach Docker at all |
| `DOCKER_API_VERSION` | *(unset in the image)* | set by compose (`1.51`): with an explicit API version the CLI skips `GET /version`, which the filter refuses |
| `OPS_BACKUP_INTERPRETERS` | `pwsh,powershell` | candidates for running `backup.ps1` |

## Setup (once, not done by this PR)

1. Put two shared secrets into `.env` (never in git):

   ```
   OPS_HELPER_TOKEN=<openssl rand -hex 32>
   HERMES_MCP_OPS_TOKEN=<openssl rand -hex 32>    # the second, elevated token
   ```

2. Start the socket filter, the helper, plugin-builder and MCP (this
   **recreates** `hermes-mcp`: it gains a second network and the 127.0.0.1 ops
   port — do it in a maintenance window):

   ```
   docker compose --profile ops --profile mcp up -d --build docker-socket-filter ops-helper plugin-builder hermes-mcp
   docker compose logs -f ops-helper docker-socket-filter plugin-builder
   ```

3. Warm the two offline build caches once (see the plugin-builder README; the
   builder container itself has no network).

4. Verify it is locked down:

   ```
   docker port mc-stack-ops-helper-1                     # empty: no host port
   docker port mc-stack-plugin-builder-1                 # empty: no host port
   docker port mc-stack-docker-socket-filter-1           # empty: no host port
   docker inspect -f '{{.HostConfig.NetworkMode}}' mc-stack-plugin-builder-1   # none
   docker compose logs docker-socket-filter | tail       # the audit log
   curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8766/healthz   # unreachable from the host
   docker exec mc-stack-hermes-mcp-1 node -e "fetch('http://ops-helper:8766/healthz').then(r=>console.log(r.status))"  # 200
   docker exec mc-stack-hermes-mcp-1 node -e "fetch('http://ops-helper:8766/op',{method:'POST'}).then(r=>console.log(r.status))"  # 401
   # the T2 listener is reachable on the host loopback only:
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8767/mcp   # 401 (no token)
   ```

5. Arm T2 last: set `OPS_ARMED=true` in `.env` and
   `docker compose --profile ops up -d ops-helper`. Game-server restarts, plugin
   deploys and rollbacks then also require the ops token **and** the local
   listener, so they cannot be triggered from the internet. **Note:** every T2
   action additionally needs `confirm=true`, `delay_seconds >= 60`, a backup
   younger than 12 h and `acknowledge_no_announce=true`, so arming is not the same
   as "one call and it happens".

## Tests

```
cd tools/ops-helper
npm ci && npm test          # node --test over dist/, no docker and no network
```

The suite never talks to Docker, to a container or to the internet: the runner and
the job channel are faked, so running it cannot touch the running stack. It covers
allowlist validation (bad impl, bad service names, `..`, argv tokens with shell
metacharacters, forbidden services, T0/T2 invariants, the mandatory build
limits/`network`, the T2 backup window), the tier gates (T2 without armed/confirm/
delay/acknowledgement, kill switch, cooldown, rate limit), injection attempts
(`bot; rm -rf /`, `$(id)`, `../../etc/passwd`, smuggled `argv`/`command` keys,
prototype-shaped action names), the job the helper hands to plugin-builder (exact
key set, sha shape, no container started), the artifact hash re-check, the audit
log (append-only, redacted, truncated), the kill switch, the real `docker ps`
NDJSON lines that used to blank `ops_status`, and the T2 deploy/rollback flow
end-to-end against a fake runner (including the automatic rollback when a health
check fails).

Two suites are specific to this PR:

* `test/proxy.test.mjs` parses the compose service blocks and asserts that
  **exactly one** service mounts the Docker socket (the filter), that it has no
  host port, lives only in the internal `mc-stack-ops-docker` network (shared
  only with ops-helper), shares `OPS_FILTER_TOKEN` with the helper, is hardened
  (`read_only`, `cap_drop: ALL`, `no-new-privileges`), that `ops-helper` mounts
  no socket and talks to it over TCP, that the two restart lists are disjoint and
  contain no forbidden container, and that `plugin-builder` has `network_mode:
  none`, no socket, no host port and hard resource caps — so the boundary cannot
  drift without a red test;
* the paused actions are asserted twice: `allowlist.test.mjs` proves the shipped
  allowlist pauses exactly `ops_players` and `ops_backup_now` (and enables the
  rest), and `dispatch.test.mjs` proves a paused action returns `action_disabled`
  with that reason **and never reaches Docker**. The implementations themselves
  stay covered by forcing those actions enabled in a test-local copy of the
  allowlist.

The filter itself has its own suite in `tools/docker-socket-filter/`:
`test/filter.test.mjs` (fake upstream, unit + live TCP) and
`test/real-daemon.sh` (real socket, throwaway container). Both are run outside
`npm test` on purpose — `npm test` stays offline.

## Not in phase 1 (documented concepts, deliberately not implemented)

* **`ops_backup_now`.** Stays paused; the design options (host task with a fixed
  script allowlist vs. a hot dump + `save-off`) are written down under "Backup
  handling" — deliberately not built here, because both need access this PR is
  not willing to grant.
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
* `ops_token_required` / a T1 tool missing from `tools/list` — the caller presented
  the plain `HERMES_MCP_TOKEN`; the T1/T2 tools need `HERMES_MCP_OPS_TOKEN`.
* `ops_t2_local_only` / a T2 tool missing from `tools/list` — the call arrived on
  the tunnel-facing listener (8765). T2 exists on the local listener (8767) only.
* `backup_interpreter_missing` — expected without `pwsh` in the image; run
  `backup.ps1` on the host, or see "Backup handling" above.
* `not_armed` — T2 is refused on purpose; set `OPS_ARMED=true` and restart the
  helper (after you have decided who may hold the ops token).
* `docker_failed` on every call — the helper cannot reach Docker. Check
  `docker compose logs docker-socket-filter` and that the filter is on the same
  `mc-stack-ops-docker` network; a `401 Unauthorized` message means
  `OPS_FILTER_TOKEN` is unset or differs between the two services; a `403 Forbidden` message means the filter refused the
  method/path/query, which is the intended behaviour for anything not in the
  table above. `GET /version` being refused is normal (the helper pins
  `DOCKER_API_VERSION` so the CLI never asks for it).
* `cooldown_active` — T1 300 s / T2 1800 s; the answer carries
  `retry_after_seconds`.
* `source_missing` / `artifacts_volume_unset` — the repo mount or the volume
  wiring is wrong; nothing was built and nothing was touched.
* `build_failed` right after a cache change — the offline cache is cold. Warm it
  once on the host (see "Build containers").

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
ops-helper            (NO docker socket: DOCKER_HOST=tcp://docker-socket-proxy:2375,
        │              repo mounted read-only, fixed allowlist)
        │  docker ps/logs/inspect only (GET); create/exec/restart/run are denied
        ▼                 by the proxy
docker-socket-proxy   (tecnativa/docker-socket-proxy, pinned by digest; the ONLY
        │              service with /var/run/docker.sock; no host port)
        ▼
mc-stack services: velocity · lobby · survival · pvp · vipworld-1..3 · bot
                   craftcore-db · playit · cloudflared · hermes-mcp
```

Nothing in this service starts by itself: it lives in the compose profile `ops`
and is disarmed (`OPS_ARMED=false`) until Nils says otherwise.

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
   token can read status/logs and run builds — nothing else.

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
   socket either: it speaks to the socket proxy over TCP.
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
7. **Order of operations before arming T2.** `OPS_HELPER_TOKEN` and
   `HERMES_MCP_OPS_TOKEN` have to be set in `.env`. Then decide who may hold the
   ops token, because from that moment on they can restart `bot`/
   `cloudflared-mcp` (T1) even through the tunnel. `OPS_ARMED=true` is the last
   step and the only one that unlocks game-server restarts — and those still
   require the local listener, so they cannot be triggered from the internet at
   all. **OAuth for the claude.ai connector is still not implemented and is not
   part of this PR**; it remains the recommended future replacement for the
   shared bearer token.

### What a stolen token can still do (be honest about it)

After the proxy hardening below, the helper is **read-only over Docker**: the
proxy grants `CONTAINERS=1` and nothing else, so the only Docker calls that
succeed are `docker ps`, `inspect` and `logs` (all GET). With the *read-only*
token (`base` level):

* read container status, health, uptime and (redacted, capped) logs;
* read repo branch/HEAD/dirty count and backup listings;
* read filesystem/`du` sizes.

Everything that needs a non-GET call to the daemon is **disabled** in
`config/ops-allowlist.json` (see "Disabled actions" below), so even the *ops*
token cannot build, test, exec, restart or deploy. The two token levels still
exist and are enforced, but with the current proxy config only the read-only
tools are enabled, so the elevated level buys nothing today.

That is a deliberate, bounded list — not "arbitrary root on the host".

## Tiers and gates

| Tier | Actions | Token level | Enabled now | Gates |
| --- | --- | --- | --- | --- |
| T0 | `ops_status`, `ops_logs`, `ops_disk`, `ops_backups_list`, `ops_git_state` | `base` | **yes** (read-only) | none |
| T0 | `ops_players`, `ops_build_plugin`, `ops_run_tests` | `base` | **no** – see below | – |
| T1 | `ops_restart_service` (bot, cloudflared-mcp), `ops_backup_now` | `ops` | **no** – see below | `confirm=true` + cooldown 300 s |
| T2 | `ops_restart_game` (lobby, survival, velocity), `ops_deploy_plugin`, `ops_rollback_plugin` | `ops` **+ local listener** | **no** – see below | `confirm=true` + `OPS_ARMED=true` + `delay_seconds >= 60` + fresh backup + cooldown 1800 s |

### Disabled actions (current state)

Every action that needs a **non-GET** Docker call is disabled in
`config/ops-allowlist.json` (`"enabled": false` plus a `disabled_reason`). The
reason is returned verbatim to the caller, so a paused tool explains itself
instead of failing vaguely. Right now the whole mutating half of the helper is
paused:

| Action | Why it is paused |
| --- | --- |
| `ops_build_plugin`, `ops_run_tests` | starting a throwaway container needs `POST /containers/create`; the proxy denies it (`POST=0`) |
| `ops_players` | needs `POST /exec/{id}/start`; the proxy denies it (`EXEC=0`) |
| `ops_restart_service` | `docker restart` is a `POST`; the proxy denies every non-GET request while `POST=0` (see below) |
| `ops_backup_now` | the helper image has no PowerShell, so `backup.ps1` cannot run here |
| `ops_restart_game`, `ops_deploy_plugin`, `ops_rollback_plugin` | T2: need a POST restart and/or an exec for the announcement |

Disabled is the default and the safe state; re-enabling one action is a
one-line change in the allowlist (needs a helper restart), and re-enabling a
`POST`-based action also needs the proxy to grant the matching method, which is
exactly what this PR refuses to do (see the next section).

Gate order is fixed and has no bypass flag: token level + listener → kill switch →
action enabled → confirm → armed → delay → cooldown → per-action rate limit
(12 calls / 60 s by default). A call that passes the gates but fails during
execution still starts its cooldown, so a broken action cannot be hammered.

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
| `build` | `network` (`none`), `limits` (`cpus`/`memory`/`pids`), image + argv for the plugin build and each test target (`source`, `command`, `setup?`, `artifact?`, `test_results?`, `cache?`) |
| `deploy` | rollout order, plugin dir per service, health timeout/poll |
| `actions` | which `ops_*` action exists, its tier, its `impl` and whether it is enabled |

`build.network` defaults to `none` and `build.limits` is mandatory (cpus, memory,
pids) — the helper refuses to start without it, so there is no configuration in
which a build container runs unlimited or on the host network.

## Docker socket proxy

ops-helper does **not** hold `/var/run/docker.sock`. Its `DOCKER_HOST` points at
`docker-socket-proxy` (compose service, `tecnativa/docker-socket-proxy` **pinned
by digest**), which is the only container with the socket, has no host port and
lives only in the internal `mc-stack-ops` network.

The proxy returns `403 Forbidden` for every API section it was not granted. This
repo grants **exactly one** section:

| Flag | Value | Why |
| --- | --- | --- |
| `CONTAINERS` | `1` | `docker ps`, `inspect`, `logs` — all GET, the read-only tool set |
| `POST` | `0` | the method gate in front of every non-GET request; **off** |
| `EXEC` | `0` | `POST /exec/{id}/start`; **off** |
| `ALLOW_RESTARTS` | `1` | kept to record the intent, but **inert while `POST=0`** (see below) |

Every other section — `ALLOW_START`, `ALLOW_STOP`, `ALLOW_PAUSE`, `ALLOW_UNPAUSE`,
`AUTH`, `BUILD`, `COMMIT`, `CONFIGS`, `DISTRIBUTION`, `EVENTS`, `GRPC`, `IMAGES`,
`INFO`, `NETWORKS`, `NODES`, `PING`, `PLUGINS`, `SECRETS`, `SERVICES`, `SESSION`,
`SWARM`, `SYSTEM`, `TASKS`, `VERSION`, `VOLUMES` — is pinned to `0`. A unit test
reads this block out of `compose.yaml` and asserts exactly that, so the proxy
config cannot drift silently (`tools/ops-helper/test/proxy.test.mjs`).

### Why POST and EXEC are off (and what that pauses)

`POST=0` makes the proxy's very first HAProxy rule
(`http-request deny unless METH_GET || { env(POST) -m bool }`) refuse **every
non-GET request**. With `EXEC=0` the `/exec` section is refused too. The effect,
**verified against the real daemon with throwaway `tecnativa/docker-socket-proxy`
instances** (see the test recipe below):

| Request | `POST=0`, `EXEC=0` | `POST=1`, `EXEC=0` |
| --- | --- | --- |
| `GET /containers/json` | `200` | `200` |
| `docker logs` / `docker inspect` | `200` | `200` |
| `POST /containers/create` | **`403`** | `400` (section-scoped: reachable) |
| `docker restart` | **`403`** | `204` (works) |
| `docker stop` / `start` / `kill` | **`403`** | `204` / `204` / `409` |
| `POST /exec/{id}/start` | **`403`** | **`403`** |
| `GET /volumes` / `/images` / `/networks` / `/info` | `403` | `403` |

Two consequences, stated plainly:

* **Container creation and exec are impossible for the helper.** That is the
  whole point of the change, and it is why `ops_build_plugin`, `ops_run_tests`
  and `ops_players` are disabled: they cannot work, and the alternative — giving
  the proxy `POST`/`EXEC` back — is exactly what this PR refuses to do.
* **`docker restart` is also impossible.** `ALLOW_RESTARTS=1` does **not** open a
  hole around `POST=0`: the POST gate is evaluated first, so the restart rule is
  never reached. This was measured, not assumed. `ops_restart_service` (and the
  T2 restarts) are therefore disabled as well. **If Nils wants restart back**, the
  minimal change is to set `POST: "1"` on `docker-socket-proxy` and re-enable the
  restart actions — but note that `POST=1` together with `CONTAINERS=1` re-opens
  the whole `/containers` section (create/start/stop/kill), i.e. the section-scope
  residual risk below returns. That trade-off is a decision for a human, so it is
  **not** taken here.

### The follow-up (not built now): a path-scoped proxy

`tecnativa/docker-socket-proxy` filters by API **section**, not by path or
container name, so it can never express "restart this one container but never
create one". A proxy that filters per **path** — e.g. a small HAProxy/nginx with
our own filter config, or a tiny shim that only rewrites
`/containers/<allowlisted-name>/(restart|stop)` — is the natural next step and
the only way to get restart back **without** re-granting `POST`.

The condition to build it: **only once a T2 action or a build/exec tool is
genuinely needed over MCP again.** Until then, keeping the proxy at `POST=0` /
`EXEC=0` and the mutating tools disabled is strictly safer and needs no new code.
This is a deliberate "do not build it yet", not an oversight.

### How it was verified (reproducible)

Two throwaway proxies were started against the real socket, plus one throwaway
target container, all on a throwaway network:

```
docker network create hermes-proxy-test-net
docker run -d --name hermes-proxy-target --network hermes-proxy-test-net alpine sleep 900
# POST=0 instance and POST=1 instance, same image digest, same other env
docker run -d --name hermes-socket-proxy-post0 --network hermes-proxy-test-net \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e CONTAINERS=1 -e POST=0 -e ALLOW_RESTARTS=1 -e EXEC=0 -e PING=0 -e VERSION=0 \
  -e EVENTS=0 -e DISABLE_IPV6=1 -e LOG_LEVEL=warning \
  tecnativa/docker-socket-proxy@sha256:1f5038b54f06c3e18422902cf00ba21803d1c97805aae032e5e6673d532d3459
# ... then DOCKER_HOST=tcp://hermes-socket-proxy-post0:2375 docker ps / logs / restart
```

No mc-stack container was touched; the running stack was read-only for these
tests.

### Residual risk, stated honestly

With `POST=0` + `EXEC=0` the proxy is now a fairly hard boundary for the helper:
no create, no exec, no restart, no volumes/images/networks. What remains:

* `CONTAINERS=1` still lets the helper **read** any container's `inspect` and
  `logs` on the host (not just mc-stack ones). That is the intended read surface
  and is capped + redacted by the helper.
* The hard boundary for *what the helper asks for* is still the helper's own
  argv allowlist plus its authentication; the proxy is defence in depth. With
  `POST=0`/`EXEC=0` the two now agree far more closely than before.

## Build containers: offline and capped

Every build/test container is a throwaway that runs with:

* `--network none` — no interface at all. Dependencies therefore come from
  **pre-warmed cache volumes** mounted at the tool's canonical path
  (`build.plugin.cache` / `build.tests.<name>.cache`): `gradle` →
  `mc-stack-ops-gradle-cache:/home/gradle/.gradle`, `npm` →
  `mc-stack-ops-npm-cache:/root/.npm`. The gradle commands carry `--offline` and
  the npm setup carries `--offline`, so a missing cache fails loudly instead of
  silently reaching the network.
* `--cpus`, `--memory`, `--pids-limit` from `build.limits`, so a build cannot
  starve the Minecraft servers.
* `--rm --cap-drop ALL --security-opt no-new-privileges`, and only the volumes it
  needs (the staged source + its cache) — never a host path.

Cold-cache note: the two cache volumes must be warmed **once on the host**, which
(of course) needs network on the host side, not in the build container:

```
docker volume create mc-stack-ops-gradle-cache mc-stack-ops-npm-cache
docker run --rm -v mc-stack-ops-gradle-cache:/home/gradle/.gradle -v "$PWD/plugins-src/craftcore:/src" -w /src gradle:9-jdk gradle --no-daemon dependencies
docker run --rm -v mc-stack-ops-npm-cache:/root/.npm -v "$PWD/tools/hermes-mcp:/src" -w /src node:22-alpine npm ci --no-audit --no-fund
```

If a cache genuinely cannot be warmed, `build.network` can name a dedicated,
narrowly scoped bridge network instead of `none` — `host`, `bridge` and
`default` are rejected at load time. That is an explicit, documented exception,
not the default.

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
| `OPS_ARTIFACTS_VOLUME` / `OPS_GRADLE_CACHE_VOLUME` / `OPS_NPM_CACHE_VOLUME` | `mc-stack-ops-artifacts` / `mc-stack-ops-gradle-cache` / `mc-stack-ops-npm-cache` | named volumes handed to the throwaway build containers (no host path is ever guessed) |
| `OPS_ACTION_TIMEOUT_SECONDS` / `OPS_BUILD_TIMEOUT_SECONDS` | `120` / `1800` | per-command and per-build timeouts |
| `OPS_DOCKER_BIN` | `docker` | docker CLI path |
| `DOCKER_HOST` | *(unset in the image)* | set by compose to `tcp://docker-socket-proxy:2375`; without the proxy the helper cannot reach Docker at all |
| `OPS_BACKUP_INTERPRETERS` | `pwsh,powershell` | candidates for running `backup.ps1` |

## Setup (once, not done by this PR)

1. Put two shared secrets into `.env` (never in git):

   ```
   OPS_HELPER_TOKEN=<openssl rand -hex 32>
   HERMES_MCP_OPS_TOKEN=<openssl rand -hex 32>    # the second, elevated token
   ```

2. Start the socket proxy, the helper and MCP (this **recreates**
   `hermes-mcp`: it gains a second network and the 127.0.0.1 ops port — do it in a
   maintenance window):

   ```
   docker compose --profile ops --profile mcp up -d --build docker-socket-proxy ops-helper hermes-mcp
   docker compose logs -f ops-helper docker-socket-proxy
   ```

3. Warm the two offline build caches once (see "Build containers" above).

4. Verify it is locked down:

   ```
   docker port mc-stack-ops-helper-1                     # empty: no host port
   docker port mc-stack-docker-socket-proxy-1            # empty: no host port
   curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8766/healthz   # unreachable from the host
   docker exec mc-stack-hermes-mcp-1 node -e "fetch('http://ops-helper:8766/healthz').then(r=>console.log(r.status))"  # 200
   docker exec mc-stack-hermes-mcp-1 node -e "fetch('http://ops-helper:8766/op',{method:'POST'}).then(r=>console.log(r.status))"  # 401
   # the T2 listener is reachable on the host loopback only:
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8767/mcp   # 401 (no token)
   ```

5. Arm T2 last: set `OPS_ARMED=true` in `.env` and
   `docker compose --profile ops up -d ops-helper`. Game-server restarts then
   also require the ops token **and** the local listener, so they cannot be
   triggered from the internet. **Note:** in the current allowlist the T2 actions
   are `"enabled": false` anyway (they need the denied POST/exec paths), so
   arming alone does not enable them — see "Disabled actions".

## Tests

```
cd tools/ops-helper
npm ci && npm test          # node --test over dist/, no docker and no network
```

The suite never talks to Docker, to a container or to the internet: the runner is
faked, so running it cannot touch the running stack. It covers allowlist
validation (bad impl, bad service names, `..`, argv tokens with shell
metacharacters, forbidden services, T0/T2 invariants, the mandatory build
limits/`network`), the tier gates (T2 without armed/confirm/delay, kill switch,
cooldown, rate limit), injection attempts (`bot; rm -rf /`, `$(id)`,
`../../etc/passwd`, smuggled `argv`/`command` keys, prototype-shaped action
names), the build container argv (`--network none`, `--cpus`, `--memory`,
`--pids-limit`, the offline cache mount), the audit log (append-only, redacted,
truncated), the kill switch, and the T2 deploy/rollback flow end-to-end against a
fake runner (including the automatic rollback when a health check fails).

Two suites are specific to this PR:

* `test/proxy.test.mjs` parses the `docker-socket-proxy` env block out of
  `compose.yaml` and asserts `CONTAINERS=1`, `POST=0`, `EXEC=0`,
  `ALLOW_RESTARTS=1` and that **every** other section is `0` — so the boundary
  cannot drift without a red test;
* the disabled actions are asserted twice: `allowlist.test.mjs` proves the
  shipped allowlist pauses the right actions and that each carries a
  `disabled_reason`, and `dispatch.test.mjs` proves a paused action returns
  `action_disabled` with that reason **and never reaches Docker**. The
  implementations themselves stay covered by forcing those actions enabled in a
  test-local copy of the allowlist.

The real-daemon proxy behaviour (403 for create/exec/restart, 200 for read) was
checked once, manually, with throwaway proxy containers — see "How it was
verified" above. It is not part of `npm test`, which stays offline.

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
* `ops_token_required` / a T1 tool missing from `tools/list` — the caller presented
  the plain `HERMES_MCP_TOKEN`; the T1/T2 tools need `HERMES_MCP_OPS_TOKEN`.
* `ops_t2_local_only` / a T2 tool missing from `tools/list` — the call arrived on
  the tunnel-facing listener (8765). T2 exists on the local listener (8767) only.
* `backup_interpreter_missing` — expected without `pwsh` in the image; run
  `backup.ps1` on the host, or see "Backup handling" above.
* `not_armed` — T2 is refused on purpose; set `OPS_ARMED=true` and restart the
  helper (after you have decided who may hold the ops token).
* `docker_failed` on every call — the helper cannot reach Docker. Check
  `docker compose logs docker-socket-proxy` and that the proxy is on the same
  `mc-stack-ops` network; a `403 Forbidden` message means the proxy refused the
  API section, which is the intended behaviour for anything not in the table
  above.
* `cooldown_active` — T1 300 s / T2 1800 s; the answer carries
  `retry_after_seconds`.
* `source_missing` / `artifacts_volume_unset` — the repo mount or the volume
  wiring is wrong; nothing was built and nothing was touched.
* `build_failed` right after a cache change — the offline cache is cold. Warm it
  once on the host (see "Build containers").

# hermes-mcp

Remote MCP server (Streamable HTTP at `/mcp`) that lets Claude Code and
claude.ai manage Nils' Hermes agents: list profiles and models, start runs,
follow status/progress/logs, cancel runs and check health.

```
Claude Code / claude.ai
        │  HTTPS + Bearer token
        ▼
Cloudflare Tunnel  mcp.nwebnotfound.dev  (already live; maps to :8765 only)
        │
        ▼
hermes-mcp  (compose networks `mcp` + `mc-stack-ops`, no public host port)
        │  8765 tunnel-facing  -> T0/T1 tools
        │  8767 127.0.0.1 only -> T0/T1/T2 tools
        │  http://host.docker.internal:8642  + per-profile API_SERVER_KEY
        ▼
Hermes gateway  (127.0.0.1:8642, multiplexer)
        │  /p/<profile>/v1/runs
        ▼
Hermes profiles: cloudflare · discord · homelab · minecraft
```

## Security model

- No *public* host port. The container is reachable from the `mcp` compose
  network (and hence through the tunnel), plus `127.0.0.1:8767` on the host for
  the local-only T2 listener. No router/Fritzbox forwarding. It reaches the
  Hermes gateway via `host.docker.internal`, and ops-helper via the internal
  `mc-stack-ops` network.
- **Two token levels.** `HERMES_MCP_TOKEN` is the read-only (`base`) level and
  may call the T0 ops tools. `HERMES_MCP_OPS_TOKEN` is the elevated (`ops`) level
  and additionally unlocks T1; the T2 tools need the `ops` token **and** the
  local listener (see below). Both are compared in constant time (SHA-256
  digests, no length leak), are never logged
  and never returned by a tool. Repeated bad tokens from one client IP are
  braked with an exponential lockout (`429` + `Retry-After`), in-memory.
- Proxy headers are only trusted when the direct peer is in
  `HERMES_MCP_TRUSTED_PROXIES` (IPs/CIDRs, default empty) or is one of the
  addresses the compose service names in `HERMES_MCP_TRUSTED_PROXY_HOSTS`
  resolve to (compose default `cloudflared-mcp`, refreshed every 30 s via Docker
  DNS). Otherwise the socket address is used, so a client cannot spoof its
  identity. Behind a trusted peer, `CF-Connecting-IP` (set by Cloudflare's edge)
  is the client; without it, the right-most untrusted `X-Forwarded-For` hop.
  This matters for the lockout: through the tunnel the socket peer is always the
  cloudflared container, so without it every internet caller would share one
  lockout bucket and a few bad tokens would lock out the owner too.
- Requests that carry **no** `Authorization: Bearer` header get a `401` but do
  not count toward the lockout; only presented-but-wrong tokens do.
- The Hermes gateway keys stay in the gateway call: each profile has its own
  profile-scoped `API_SERVER_KEY`. hermes-mcp reads them from
  `HERMES_PROFILE_KEYS` (JSON in `.env`) and never prints them.
- Prompts and outputs are stored only under `data/hermes-mcp/runs/` (git-ignored,
  same trust level as the Hermes session store). Prompt previews are capped at
  200 characters in list output.
- **Redaction** runs on the container log, on the persisted run events/output and
  again on the `get_run` / `get_run_logs` / `list_runs` responses. It removes the
  shapes it can recognise: `Bearer …` headers, GitHub PATs (`ghp_…`,
  `github_pat_…`), OpenAI/OpenRouter/Kimi `sk-…` keys, `NAME=value` / `NAME: …`
  assignments whose name looks secret-bearing (`…TOKEN`, `…SECRET`, `…_KEY`,
  `…PASSWORD`, `…CREDENTIAL`, `…AUTHORIZATION`, `…COOKIE`) — the value is
  redacted up to the end of the line, quotes and backticks included — the MariaDB
  image's `GENERATED ROOT PASSWORD: …` line, and long hex blobs. It
  is a safety net, not a proof: it normalises known patterns and leaves
  unrecognised secrets alone.
- `cancel_run` requires `confirm=true`, otherwise `code: confirmation_required`.
- The container runs `read_only`, `cap_drop: ALL`, `no-new-privileges`, as the
  unprivileged `node` user, with a tmpfs for `/tmp`.

## Tools

| Tool | Purpose |
| --- | --- |
| `health` | hermes-mcp + gateway reachability/version, profiles, active runs |
| `list_profiles` | profiles, default model, gateway auth status |
| `list_models(profile, search?, limit?)` | active model + provider catalog |
| `start_run(profile, prompt, model?, reasoning_effort?, provider?, session_id?)` | async run, returns `run_id` |
| `get_run(run_id)` | status, progress (stage, last event, tool calls, elapsed), single capped `last_output`, usage |
| `list_runs(profile?, status?, limit?)` | newest first, local index (status incl. `timed_out`) |
| `get_run_logs(run_id, tail?)` | tail of lifecycle/tool events |
| `cancel_run(run_id, confirm=true)` | interrupt the agent (destructive, gated) |

Ops tools (only advertised when `OPS_HELPER_URL` **and** `OPS_HELPER_TOKEN` are
set; the whole security model lives in
[`../ops-helper/README.md`](../ops-helper/README.md)):

| Tool | Tier | Token level | Enabled now | Purpose |
| --- | --- | --- | --- | --- |
| `ops_status(service?)` | T0 | base | yes | container state/health/uptime of the mc-stack project |
| `ops_logs(service, tail<=300)` | T0 | base | yes | redacted, capped container log tail |
| `ops_disk()` | T0 | base | yes | filesystem free bytes + `du` sizes of `backups/` and `data/` |
| `ops_backups_list()` | T0 | base | yes | `daily-*` backups with mtime/`COMPLETE.txt` |
| `ops_git_state()` | T0 | base | yes | branch, short HEAD, dirty count, ahead/behind |
| `ops_players(service)` | T0 | base | **paused** | online players via `rcon-cli list`; needs docker exec |
| `ops_build_plugin()` | T0 | base | **paused** | gradle build in a throwaway container; needs container create |
| `ops_run_tests(target)` | T0 | base | **paused** | run a test suite in a throwaway container; needs container create |
| `ops_restart_service(service, confirm)` | T1 | ops | **yes** | restart `bot`/`cloudflared-mcp`; `confirm=true`, exact-name allowlist in the socket filter |
| `ops_backup_now(confirm)` | T1 | ops | **paused** | needs PowerShell, which the helper image lacks |
| `ops_restart_game(service, confirm, delay_seconds)` | T2 | ops + local listener | **paused** | restart lobby/survival/velocity (armed + fresh backup) |
| `ops_deploy_plugin(artifact_sha256, confirm, delay_seconds)` | T2 | ops + local listener | **paused** | rolling CraftCore deploy (needs a build + exec) |
| `ops_rollback_plugin(confirm, delay_seconds)` | T2 | ops + local listener | **paused** | restore the jars the last deploy kept |

**Paused tools:** every action the `docker-socket-filter` cannot serve (needs a
container create, an exec, or a game-server restart) is `"enabled": false` in
`config/ops-allowlist.json`. The tools are still advertised (so a call returns a
clear `action_disabled` with the reason), and only the five read-only tools plus
`ops_restart_service` actually work. `ops_ci_status(pr)` is deliberately **not**
implemented: the token available here has no `checks:read`, so the tool could
only guess.

## Setup (once)

1. **Gateway keys.** Named profiles are refused by the gateway unless they have
   their own `API_SERVER_KEY` in `profiles/<name>/.env` (a profile-scoped key,
   the default profile's key is intentionally not accepted). Add one per profile
   and put the same values in the compose `.env` as a JSON map:

   ```
   HERMES_PROFILE_KEYS='{"cloudflare":"…","discord":"…","homelab":"…","minecraft":"…"}'
   ```

   The gateway picks the key up on the next request (profile re-scan); no
   restart needed. `get_run` etc. still work after a gateway restart because the
   run index is persisted locally.

2. **MCP token.** `HERMES_MCP_TOKEN` (32 random bytes hex) in `.env`. Rotate by
   putting the new token first: `HERMES_MCP_TOKEN=new,old`.

3. **Ops token (optional, needed for T1/T2).** `HERMES_MCP_OPS_TOKEN` (32 random
   bytes hex). Leave it empty and the T1/T2 tools are not advertised at all
   (fail closed). The T2 tools additionally need the local listener, which is
   published on `127.0.0.1:8767` only.

4. Start the service:

   ```
   docker compose --profile mcp up -d --build hermes-mcp
   docker compose logs -f hermes-mcp
   ```

5. Smoke test from the host (the main listener has no published port, so use a
   container or the host-only dev launcher; the local listener is on 8767):

   ```
   cd tools/hermes-mcp
   npm ci && npm run build
   npm test                         # unit tests (node --test over dist/)
   node test/dev-local.mjs          # terminal 1 (host, 127.0.0.1:8765)
   node test/smoke.mjs              # terminal 2
   ```

## Limits & hardening knobs (env)

All optional; the defaults are fail-closed.

| Env | Default | Effect |
| --- | --- | --- |
| `HERMES_MCP_MAX_ACTIVE_RUNS_PER_PROFILE` | `2` | concurrent runs per profile (slot reserved atomically before the run starts) |
| `HERMES_MCP_MAX_RUNS_PER_DAY` | `50` | runs started per UTC day across all profiles |
| `HERMES_MCP_ALLOWED_MODELS` | `deepseek/deepseek-v4.1-flash,anthropic/claude-haiku-5.5` | models accepted by `start_run(model=…)`; omit `model` for the profile default |
| `HERMES_MCP_ALLOWED_PROVIDERS` | `openrouter,anthropic` | providers accepted by `start_run(provider=…)` |
| `HERMES_MCP_ALLOWED_REASONING_EFFORTS` | `minimal,low,medium,high` | `reasoning_effort` accepted (`xhigh`/`none` excluded) |
| `HERMES_MCP_TRUSTED_PROXIES` | *(empty)* | peers allowed to set `CF-Connecting-IP` / `X-Forwarded-For` (exact IP or CIDR) |
| `HERMES_MCP_TRUSTED_PROXY_HOSTS` | *(empty; compose: `cloudflared-mcp`)* | compose service names resolved to trusted proxy addresses |
| `HERMES_MCP_LOCKOUT_AFTER` | `5` | bad tokens before an IP is locked out |
| `HERMES_MCP_LOCKOUT_SECONDS` | `300` | exponential lockout cap |
| `HERMES_MCP_APPROVAL_WAIT_SECONDS` | `900` | a run waiting for approval is stopped after this (hermes-mcp never approves) |
| `HERMES_MCP_UNKNOWN_TIMEOUT_SECONDS` | `7200` | an `unknown` run is marked `timed_out` after this, freeing its slot |
| `OPS_HELPER_URL` | `http://ops-helper:8766` | ops-helper base URL (internal network only) |
| `OPS_HELPER_TOKEN` | *(empty)* | shared secret with ops-helper; empty → the ops tools are not advertised |
| `OPS_HELPER_TIMEOUT_SECONDS` | `120` | default per-call timeout (builds/deploys raise it to 1800 s) |
| `HERMES_MCP_OPS_TOKEN` | *(empty)* | the elevated (`ops`) token; empty → T1/T2 are refused for everyone |
| `HERMES_MCP_OPS_PORT` / `HERMES_MCP_OPS_HOST` | `8767` / `0.0.0.0` | the local-only listener that serves T2 (published as `127.0.0.1:8767`) |
| `HERMES_MCP_OPS_ALLOWED_PEERS` | *(empty)* | explicit peer allowlist (IP/CIDR) for the local listener; empty = built-in rule (see "The two listeners") |
| `HERMES_MCP_OPS_ALLOWED_HOSTS` | `localhost,127.0.0.1,::1` | Host headers the local listener accepts |
| `HERMES_MCP_OPS_LOCKOUT_AFTER` | `3` | consecutive bad tokens on the ops listener before that IP is locked out (its own brake) |

## Ops tools: threat model

hermes-mcp holds **no Docker socket** and no extra privileges (`read_only`,
`cap_drop: ALL`, `no-new-privileges` stay as they are). Ops work happens in the
separate `ops-helper` service over the internal network `mc-stack-ops`
(`internal: true`, no host port, no internet route), authenticated with the
`OPS_HELPER_TOKEN` shared secret (constant-time compared, fail closed when
missing). The helper runs only the actions in `config/ops-allowlist.json` with
argv lists — never a shell — and enforces the tier gates, the backup requirement,
the cooldowns, the kill switch and the audit log.

Consequences worth stating explicitly:

* A **stolen read-only MCP token** (`base` level) can read status/logs and
  backups — and nothing else. It cannot see the T1/T2 tools at all.
* The **ops token** unlocks the T1 tool set, and exactly one of those tools can
  mutate anything: `ops_restart_service` (restart `bot` or `cloudflared-mcp`,
  `confirm=true`, 300 s cooldown). Everything else that would need a container
  create, an exec or a game-server restart is `"enabled": false` **and** has no
  route in the socket filter, so a stolen ops token cannot build, test, exec,
  deploy or restart a game server either. See the ops-helper README for the
  (verified) reason and the filter's allowlist.
* Nothing in the ops path can reach `craftcore-db`, `hermes-mcp`, `ops-helper`,
  `hermes-test`, `docker compose down`, `volume rm/prune` or `kill -9`; that is
  enforced in code, not only in the JSON.
* **`mcp.nwebnotfound.dev` is already live** and is protected by the bearer token
  alone (Cloudflare Access only covers `/admin` on the website). That is exactly
  why there are two token levels and a local-only T2 listener instead of a
  documented "keep it disarmed" promise. OAuth 2.1 remains the recommended future
  replacement for the shared token, but is not implemented here.
* Kill switch: `touch data/ops-helper/DISABLED` (or `OPS_DISABLED=true`) refuses
  everything above T0 without restarting anything else.

Full detail (allowlist format, tier gates, audit log, socket filter, offline build
caches, activation steps, what is deliberately not implemented) is in
[`tools/ops-helper/README.md`](../ops-helper/README.md).

## The two listeners, in one place

| Listener | Port | Reachable from | T0 | T1 | T2 |
| --- | --- | --- | --- | --- | --- |
| main (tunnel-facing) | `HERMES_MCP_PORT` (8765) | `mcp` network → Cloudflare Tunnel | yes | yes (ops token) | **never** |
| local (ops) | `HERMES_MCP_OPS_PORT` (8767) | host `127.0.0.1` only | yes | yes (ops token) | yes (ops token) |

Why two listeners: a TCP port is the only thing Cloudflare's tunnel routes by, so
"came from the internet" and "came from the host" are distinguishable without
trusting a header. The tunnel's public hostname points at `:8765`; `:8767` is
published as `127.0.0.1:8767:8767` and the tunnel config must never mention it.
A request on `:8765` can therefore not reach `ops_restart_game`,
`ops_deploy_plugin` or `ops_rollback_plugin` — those tools are not even in its
`tools/list`. Verified end-to-end against the built image:

```
base token, :8765 -> T1/T2 absent      ops token, :8765 -> T1 present, T2 absent
base token, :8767 -> T1/T2 absent      ops token, :8767 -> T1 + T2 present
unknown token     -> 401
```

The local listener does not rely on the port number alone. It binds `0.0.0.0`
inside the container (the host port mapping needs that), so any container that
shares a network with hermes-mcp — `cloudflared-mcp`, or a container of another
compose project that joins the fixed-name `mcp` network — could otherwise
connect to `hermes-mcp:8767`, and a single tunnel ingress rule pointing there
would publish T2. The listener therefore refuses, before auth:

* any peer inside one of the container's own subnets that is not that subnet's
  gateway (Docker delivers the `127.0.0.1:8767` mapping from the gateway, from
  loopback or, on Docker Desktop, from a proxy address outside these subnets) →
  `403 forbidden_peer`, logged with the address;
* any `Host` header other than `HERMES_MCP_OPS_ALLOWED_HOSTS` (default
  `localhost,127.0.0.1,::1`), so a request carrying the tunnel hostname is
  refused → `403 forbidden_host`.

If your own host requests are refused as `forbidden_peer`, set
`HERMES_MCP_OPS_ALLOWED_PEERS` to the logged address (it then replaces the
built-in rule; loopback stays allowed). Residual risk: on Docker Desktop a
container that can reach `host.docker.internal:8767` arrives through the host
mapping like the host itself; the Host check and the ops token still apply.

The local listener has its own stricter brute-force brake
(`HERMES_MCP_OPS_LOCKOUT_AFTER`, default 3 consecutive bad tokens), separate from
the main listener's `HERMES_MCP_LOCKOUT_AFTER`.

## Before the tunnel goes fully live (not in this PR)

Decisions for Nils, tracked here rather than implemented:

1. **Cloudflare Access in front of `mcp.nwebnotfound.dev`** (service token or
   IdP). It does not exist today — Access only guards `/admin` on the website — so
   the bearer token is the entire authentication in front of the MCP hostname.
2. **OAuth 2.1 for the claude.ai connector** (PKCE + dynamic client
   registration): the connector UI does not accept a static `Authorization`
   header. Claude Code works today with the static bearer token.
3. **Deviations from PR #51's plan** (kill-switch, proposal/approval stage):
   this PR implements neither, and hermes-mcp never calls the gateway's
   `/approval` endpoint — a run that waits for approval is stopped, not approved.

## Claude Code

`~/.claude.json` (or `claude mcp add --transport http`):

```json
{
  "mcpServers": {
    "hermes": {
      "type": "http",
      "url": "https://mcp.nwebnotfound.dev/mcp",
      "headers": { "Authorization": "Bearer ${HERMES_MCP_TOKEN}" }
    }
  }
}
```

For a purely local test, `http://127.0.0.1:8765/mcp` works while
`test/dev-local.mjs` runs.

## claude.ai custom connector

The connector UI does not accept a static Authorization header, so it needs
OAuth 2.1. This release ships the bearer-token path only (`/healthz` and `/mcp`
with `WWW-Authenticate: Bearer`, resource metadata URL prepared in the 401
header). Adding the connector to claude.ai is therefore an open step: either
expose it with a connector that supports custom headers, or add the OAuth 2.1
authorization server (PKCE + dynamic client registration) as a follow-up.

## Cloudflare Tunnel (`mcp.nwebnotfound.dev`) — already live

The tunnel and the public hostname exist; nothing here needs creating. The
mapping that matters for this PR is that the hostname points at
`http://hermes-mcp:8765` and **must never be changed to `:8767`** — `:8767` is
the local-only listener that serves T2.

1. Public hostname: `mcp.nwebnotfound.dev` → `http://hermes-mcp:8765`
   (the tunnel runs only in the `mcp` network, the same network as
   `hermes-mcp`, so no host port is published and no hostname/port mapping on
   the host is needed).
2. Tunnel token in `.env` as `HERMES_MCP_TUNNEL_TOKEN=…` (never in git).
3. Start/refresh it:
   `docker compose --profile mcp-tunnel --env-file .env up -d --no-deps cloudflared-mcp`
   The `cloudflared-mcp` service is defined in `compose.yaml` and attaches only
   to the `mcp` network, so it reaches `hermes-mcp` directly; no separate
   compose file and no `external: true` wiring is needed.
4. Optional hardening in Cloudflare (still open): WAF rule on `/mcp` limiting to
   your countries/ASN, and Access (service token) in front of the hostname.
   Today Access only covers `/admin` on the website, not this hostname.
5. Verify: `curl -sS -o /dev/null -w '%{http_code}\n' https://mcp.nwebnotfound.dev/healthz`
   → `200`; `POST /mcp` without a token → `401`.

Local-config variant (own tunnel credentials file): see
`cloudflared.config.example.yml`.

## Fallback backend (CLI)

`HERMES_BACKEND=cli` makes hermes-mcp spawn
`hermes -p <profile> chat -q "<prompt>"` and track the process itself instead of
using the gateway. It only works where the `hermes` CLI is installed (host
run via `test/dev-local.mjs`), not inside the container. `HERMES_CLI` overrides
the binary path.

## Troubleshooting

- `refusing to start` — `HERMES_MCP_TOKEN` missing/short or `HERMES_PROFILE_KEYS`
  empty; check `.env`.
- `gateway_unreachable` — the gateway must run on the host and stay bound to
  `127.0.0.1:8642`; from the container it is
  `http://host.docker.internal:8642`. On Linux set
  `extra_hosts: host.docker.internal:host-gateway` (already in compose).
- `gateway_auth_failed` — the profile key in `HERMES_PROFILE_KEYS` is not the
  value in `profiles/<profile>/.env`.
- `unknown_profile` — only profiles listed in `HERMES_PROFILE_KEYS` are served.
- Runs showing `unknown` — the gateway restarted or dropped the run; start a new
  run, the old one cannot be resumed.

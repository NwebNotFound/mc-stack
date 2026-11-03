# hermes-mcp

Remote MCP server (Streamable HTTP at `/mcp`) that lets Claude Code and
claude.ai manage Nils' Hermes agents: list profiles and models, start runs,
follow status/progress/logs, cancel runs and check health.

```
Claude Code / claude.ai
        │  HTTPS + Bearer token
        ▼
Cloudflare Tunnel (prepared, NOT created yet)
        │
        ▼
hermes-mcp  (compose network `mcp`, port 8765, no host port)
        │  http://host.docker.internal:8642  + per-profile API_SERVER_KEY
        ▼
Hermes gateway  (127.0.0.1:8642, multiplexer)
        │  /p/<profile>/v1/runs
        ▼
Hermes profiles: cloudflare · discord · homelab · minecraft
```

## Security model

- No published host port. The container is only reachable from the `mcp`
  compose network (and later from the tunnel). No router/Fritzbox forwarding.
  hermes-mcp sits in that one network only; it reaches the Hermes gateway via
  `host.docker.internal`, so it needs no Minecraft network.
- Every request needs `Authorization: Bearer <HERMES_MCP_TOKEN>`; the token is
  compared in constant time (SHA-256 digests, no length leak), is never logged
  and never returned by a tool. Repeated bad tokens from one client IP are
  braked with an exponential lockout (`429` + `Retry-After`), in-memory.
- `X-Forwarded-For` is only trusted when the direct peer is in
  `HERMES_MCP_TRUSTED_PROXIES` (default: empty → the socket address is used, so
  a client cannot spoof its identity). Behind a trusted proxy, the right-most
  untrusted hop is the client.
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
  `…PASSWORD`, `…CREDENTIAL`, `…AUTHORIZATION`, `…COOKIE`) and long hex blobs. It
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

3. Start the service:

   ```
   docker compose --profile mcp up -d --build hermes-mcp
   docker compose logs -f hermes-mcp
   ```

4. Smoke test from the host (no published port, so use a container or the
   host-only dev launcher):

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
| `HERMES_MCP_TRUSTED_PROXIES` | *(empty)* | peers allowed to set `X-Forwarded-For` (exact IP or CIDR) |
| `HERMES_MCP_LOCKOUT_AFTER` | `5` | bad tokens before an IP is locked out |
| `HERMES_MCP_LOCKOUT_SECONDS` | `300` | exponential lockout cap |
| `HERMES_MCP_APPROVAL_WAIT_SECONDS` | `900` | a run waiting for approval is stopped after this (hermes-mcp never approves) |
| `HERMES_MCP_UNKNOWN_TIMEOUT_SECONDS` | `7200` | an `unknown` run is marked `timed_out` after this, freeing its slot |

## Before the tunnel goes live (not in this PR)

Decisions for Nils, tracked here rather than implemented:

1. **Cloudflare Access in front of `mcp.nwebnotfound.dev`** (service token or
   IdP) — the MCP bearer token alone is the second factor behind it.
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

## Cloudflare Tunnel (`mcp.nwebnotfound.dev`) — prepared, not created

Nothing has been created, changed or bought. Steps for Nils/Luna when you want
it live:

1. Zero Trust → Networks → Tunnels → **Create a tunnel** (cloudflared, free).
2. Public hostname: `mcp.nwebnotfound.dev` → `http://hermes-mcp:8765`
   (the tunnel runs only in the `mcp` network, the same network as
   `hermes-mcp`, so no host port is published and no hostname/port mapping on
   the host is needed).
3. Put the tunnel token into `.env` as `HERMES_MCP_TUNNEL_TOKEN=…` (never in git).
4. Start it:
   `docker compose --profile mcp-tunnel --env-file .env up -d --no-deps cloudflared-mcp`
   The `cloudflared-mcp` service is defined in `compose.yaml` and attaches only
   to the `mcp` network, so it reaches `hermes-mcp` directly; no separate
   compose file and no `external: true` wiring is needed.
5. Optional hardening in Cloudflare: WAF rule on `/mcp` limiting to your
   countries/ASN, and Access (service token) in front of the hostname.
6. Verify: `curl -sS -o /dev/null -w '%{http_code}\n' https://mcp.nwebnotfound.dev/healthz`
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

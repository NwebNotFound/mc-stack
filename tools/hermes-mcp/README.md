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
hermes-mcp  (compose network `minecraft`, port 8765, no host port)
        │  http://host.docker.internal:8642  + per-profile API_SERVER_KEY
        ▼
Hermes gateway  (127.0.0.1:8642, multiplexer)
        │  /p/<profile>/v1/runs
        ▼
Hermes profiles: cloudflare · discord · homelab · minecraft
```

## Security model

- No published host port. The container is only reachable from the compose
  network (and later from the tunnel). No router/Fritzbox forwarding.
- Every request needs `Authorization: Bearer <HERMES_MCP_TOKEN>`; the token is
  compared in constant time, is never logged and never returned by a tool.
- The Hermes gateway keys stay in the gateway call: each profile has its own
  profile-scoped `API_SERVER_KEY`. hermes-mcp reads them from
  `HERMES_PROFILE_KEYS` (JSON in `.env`) and never prints them.
- Prompts and outputs are stored only under `data/hermes-mcp/runs/` (git-ignored,
  same trust level as the Hermes session store). Prompt previews are capped at
  200 characters in list output.
- `cancel_run` and any future config-changing tool require `confirm=true`.
- The container runs `read_only`, `cap_drop: ALL`, `no-new-privileges`, as the
  unprivileged `node` user, with a tmpfs for `/tmp`.

## Tools

| Tool | Purpose |
| --- | --- |
| `health` | hermes-mcp + gateway reachability/version, profiles, active runs |
| `list_profiles` | profiles, default model, gateway auth status |
| `list_models(profile, search?, limit?)` | active model + provider catalog |
| `start_run(profile, prompt, model?, reasoning_effort?, provider?, session_id?)` | async run, returns `run_id` |
| `get_run(run_id)` | status, progress (stage, last event, tool calls, elapsed), last output, usage |
| `list_runs(profile?, status?, limit?)` | newest first, local index |
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
   node test/dev-local.mjs          # terminal 1 (host, 127.0.0.1:8765)
   node test/smoke.mjs              # terminal 2
   ```

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
   (same `minecraft` network, so no host port is needed).
3. Copy the tunnel token into `.env` as `MCP_TUNNEL_TOKEN=…` (never in git).
4. Start it: `docker compose --profile mcp --profile mcp-tunnel up -d`.
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

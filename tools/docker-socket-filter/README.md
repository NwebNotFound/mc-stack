# docker-socket-filter

The **only** service in this stack that mounts `/var/run/docker.sock`. It is a
path-exact HTTP filter in front of the socket: everything on the allowlist is
forwarded, everything else is a `403` and lands in the audit log.

Zero npm dependencies, two source files, ~400 lines. That is the point: this
process *is* the privilege boundary, so there is nothing to audit but the code
below.

```
ops-helper              (NO socket: DOCKER_HOST=tcp://docker-socket-filter:2375)
        │               internal network `mc-stack-ops` (internal: true, no host port)
        ▼
docker-socket-filter    (the ONLY socket holder; Node, no dependencies)
        │               read_only, cap_drop ALL, no-new-privileges, no host port
        ▼
/var/run/docker.sock    → the real Docker daemon
```

## Why this replaced tecnativa/docker-socket-proxy

`tecnativa/docker-socket-proxy` is an HAProxy in front of the socket that filters
by **API section**: `CONTAINERS`, `POST`, `EXEC`, `VOLUMES`, ... That granularity
cannot express what this stack actually needs:

* `GET /containers/json`, `/containers/<id>/json`, `/containers/<id>/logs` → yes;
* `POST /containers/<name>/restart` → yes, **but only for two exact container
  names**;
* `POST /containers/create`, `/stop`, `/start`, `/kill`, `/exec`, `/containers/prune`
  → **never**.

The proxy's `POST` flag is all-or-nothing: with `POST=0` a restart is impossible
too (the first HAProxy rule denies every non-GET request before `ALLOW_RESTARTS`
is even reached — measured against the real daemon), and with `POST=1` the whole
mutable container API, including `create` and `exec`, re-opens. There is no
per-path, per-container-name setting. Hence this filter.

## The allowlist (everything else is 403)

| Method | Path | Query | Notes |
| --- | --- | --- | --- |
| `HEAD`, `GET` | `/_ping` | *(none)* | documented exception: the Docker CLI's version-negotiation/health probe. Read-only, returns only the API version. |
| `GET` | `/containers/json` | `all` ∈ {0,1}, `filters` (≤ 2048 chars) | the container list (`docker ps`) |
| `GET` | `/containers/<ref>/json` | *(none)* | `docker inspect` |
| `GET` | `/containers/<ref>/logs` | `stdout`, `stderr`, `timestamps` ∈ {0,1}; `tail` ≤ 300; `since`, `until` | `docker logs` |
| `POST` | `/containers/<name>/restart` | `t` ∈ 0..30 | **only** for an exact name from `OPS_FILTER_RESTART_ALLOW` |

`<ref>` is a container name or id (`^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$`).
`<name>` for `restart` must match an entry of the allowlist **exactly** — no ids,
no prefix matches (`mc-stack-bot-10` is not `mc-stack-bot-1`), no globs.

Default allowlist: `mc-stack-bot-1,mc-stack-cloudflared-mcp-1`. The game-server
names (`mc-stack-lobby-1`, `mc-stack-survival-1`, `mc-stack-velocity-1`, …) are
deliberately **absent**, so a game-server restart is impossible *even if
ops-helper asked for one* — the helper's own allowlist and this one are
independent checks.

## What is refused, and why the parser is strict

Rejected at parse time (before any routing decision):

* any method other than `GET`/`POST`/`HEAD` (case-sensitive: `get` is not `GET`);
* `Transfer-Encoding` of any kind, a duplicate `Content-Length`, `Upgrade`, or
  `Connection: … Upgrade` → **no request smuggling**;
* any request body: `Content-Length` must be absent or exactly `0`;
* non-ASCII bytes, bare `LF`, header continuation lines (obs-fold), a header line
  without `:`;
* a request line that is not exactly `METHOD SP TARGET SP HTTP/1.1`.

Rejected at routing time:

* any path other than the table above — in particular `/containers/create`,
  `/containers/<x>/start|stop|kill|pause|rename|update`, `/containers/prune`,
  `/exec/...`, `/volumes`, `/images`, `/networks`, `/build`, `/events`, `/info`,
  `/version`, `/system/...`, `/auth`, `/swarm`, `/secrets`, `/tasks`, `/services`;
* `%` anywhere in the path (no percent-encoded `/` or `..`), `..` or `.`
  segments, `//`, a trailing `/`, a backslash;
* unknown or duplicated query keys (`size=1` is refused — see below);
* `tail > 300`, `t > 30` or a non-integer `t`;
* a restart target that is not an exact allowlist name.

The optional API-version prefix is exactly `v1.<digits>`; `/v1.51/v1.51/...` is
refused.

**Note on `size`:** the Docker CLI adds `size=1` to `GET /containers/json` when
`docker ps` is asked for `--format '{{json .}}'`. Instead of widening the filter,
ops-helper uses an explicit `--format` template (`PS_FORMAT` in
`tools/ops-helper/src/docker.ts`), which keeps the query surface at exactly
`all` + `filters`.

**Note on `/_ping` and `GET /version`:** the CLI pings before every request, so
`HEAD /_ping` must stay allowed. `GET /version` is **not** allowed; ops-helper
sets `DOCKER_API_VERSION`, which makes the CLI skip version negotiation entirely
(verified: with it set, no `GET /version` is sent).

## Hardening

* the socket is mounted into this service and nowhere else;
* `read_only: true`, `cap_drop: ALL`, `no-new-privileges:true`, `tmpfs: /tmp`;
* **no host port** — it is reachable only from the internal `mc-stack-ops`
  network (which has no route off the host);
* the client connection is handled one request at a time; a pipelined second
  request is validated like any other (a smuggled `POST /containers/create`
  after a valid `GET` gets its own `403`);
* upstream responses are fully buffered, capped (`OPS_FILTER_MAX_RESPONSE_BYTES`,
  default 8 MiB) and re-framed with a computed `Content-Length`, so response
  framing can never be ambiguous;
* no request headers are forwarded except `Host` and (for POST) `Content-Length:
  0`; hop-by-hop headers from the daemon are dropped.

## Residual risk (stated plainly)

* **Whoever compromises this process has the socket.** It is the boundary, so it
  is small, dependency-free, has no outbound network and is the only socket
  holder — but it is not a sandbox. A bug in `src/filter.mjs` is host root.
* **Whoever can write `OPS_FILTER_RESTART_ALLOW` can widen the restart set.**
  That is a compose/env change, i.e. the same trust level as editing the repo.
* **A stolen ops-helper token cannot create or exec.** Through the filter the
  only mutation available is restarting the two named containers.
* The audit log is stdout (`docker compose logs docker-socket-filter`): it
  survives as long as the container's log file does. Allowed requests are logged
  as `allowed`, refusals as `denied` with a reason code. No header values, bodies
  or secrets are ever logged.

## Configuration

| Env | Default | Meaning |
| --- | --- | --- |
| `OPS_FILTER_PORT` | `2375` | listen port inside the container |
| `OPS_FILTER_HOST` | `0.0.0.0` | listen address (only reachable inside `mc-stack-ops`) |
| `OPS_FILTER_UPSTREAM` | `/var/run/docker.sock` | the real socket |
| `OPS_FILTER_RESTART_ALLOW` | `mc-stack-bot-1,mc-stack-cloudflared-mcp-1` | exact container names a restart may target |
| `OPS_FILTER_MAX_RESPONSE_BYTES` | `8388608` | hard cap on a forwarded response body |

An allowlist entry that is not a plain container-name shape aborts startup (fail
closed).

## Tests

```sh
# unit + live-filter tests against a fake daemon on a UNIX socket (no docker)
docker run --rm -v "$PWD/tools/docker-socket-filter:/w" -w /w node:22-alpine \
  node --test test/filter.test.mjs

# the throwaway real-daemon test (needs the real socket; see the script header)
sh tools/docker-socket-filter/test/real-daemon.sh
```

`test/filter.test.mjs` covers the allow table, every forbidden Docker route,
traversal/encoding/CRLF/smuggling vectors, prefix names, container ids, the
`t` bounds and a real TCP round-trip through the filter to a fake upstream.

`test/real-daemon.sh` builds this image, runs it with the **real** socket on a
throwaway network next to a throwaway canary container, and probes it: the
canary restarts (`204`), everything else is `403`. It also proves that no
container that mounts the socket is left running (each one self-exits).

## Response handling (review of PR 125)

- dockerd answers larger bodies with `Transfer-Encoding: chunked`. The filter decodes the chunks strictly (hex sizes only, no extensions; malformed framing is a 502) and re-frames with a plain `Content-Length`. Without this every response over ~2 KB (e.g. `GET /containers/json` with many containers) reached the client with the chunk size lines inside the JSON.
- `GET /containers/<name>/json` is returned with `Config.Env` emptied. The inspect document contains the container's environment (database passwords, tokens); no caller of this filter needs it.

/**
 * docker-socket-filter entrypoint.
 *
 * Fail closed:
 *  - without OPS_FILTER_TOKEN (>= 24 chars) the filter refuses to start; every
 *    request except `/_ping` must carry `Authorization: Bearer <token>`;
 *  - a restart-allowlist entry that is not a plain container name shape aborts
 *    startup (a typo must never widen the surface);
 *  - an empty restart allowlist is allowed (then no restart passes at all).
 *
 * Two restart lists, both applied to the exact `/containers/<name>/restart`
 * path only:
 *  - OPS_FILTER_RESTART_ALLOW     -> T1, reversible helper services;
 *  - OPS_FILTER_RESTART_ALLOW_T2  -> T2, the game servers.
 * The Docker API has one restart path, so the filter accepts the union and the
 * T1/T2 split is enforced by the helper's own service lists. The database, the
 * proxy DB, hermes-test, hermes-mcp, ops-helper and the filter itself must never
 * appear in either list - a unit test asserts that.
 *
 * The audit log is stdout as one JSON object per line (`docker compose logs
 * docker-socket-filter`). Allowed requests are logged at `allowed`, everything
 * refused at `denied` with the reason code. No header values, tokens or bodies
 * are ever logged.
 */
import {
  createFilterServer,
  DEFAULT_MAX_RESPONSE_BYTES,
  DEFAULT_RESTART_ALLOW_T1,
  DEFAULT_RESTART_ALLOW_T2,
  MIN_TOKEN_LENGTH,
  parseAllow,
  REF_RE,
} from "./filter.mjs";

const port = Number(process.env.OPS_FILTER_PORT ?? "2375");
const host = process.env.OPS_FILTER_HOST ?? "0.0.0.0";
const upstreamSocketPath = process.env.OPS_FILTER_UPSTREAM ?? "/var/run/docker.sock";
const token = (process.env.OPS_FILTER_TOKEN ?? "").trim();
const maxResponseBytes = Number(process.env.OPS_FILTER_MAX_RESPONSE_BYTES ?? String(DEFAULT_MAX_RESPONSE_BYTES));

const restartAllow = parseAllow(process.env.OPS_FILTER_RESTART_ALLOW, DEFAULT_RESTART_ALLOW_T1);
const restartAllowT2 = parseAllow(process.env.OPS_FILTER_RESTART_ALLOW_T2, DEFAULT_RESTART_ALLOW_T2);

for (const name of [...restartAllow, ...restartAllowT2]) {
  if (!REF_RE.test(name)) {
    console.error(JSON.stringify({ ts: new Date().toISOString(), event: "error", code: "bad_restart_allow_entry", name }));
    process.exit(1);
  }
}

if (token.length < MIN_TOKEN_LENGTH) {
  console.error(JSON.stringify({ ts: new Date().toISOString(), event: "error", code: "missing_token", detail: `OPS_FILTER_TOKEN is required (>= ${MIN_TOKEN_LENGTH} chars)` }));
  process.exit(1);
}

if (!Number.isFinite(port) || port < 1 || port > 65535) {
  console.error(JSON.stringify({ ts: new Date().toISOString(), event: "error", code: "bad_port" }));
  process.exit(1);
}

const log = (entry) => {
  console.log(JSON.stringify({ ts: new Date().toISOString(), ...entry }));
};

const server = createFilterServer({ upstreamSocketPath, token, restartAllow, restartAllowT2, maxResponseBytes, log });

server.listen(port, host, () => {
  log({
    event: "start",
    port,
    host,
    upstream: upstreamSocketPath,
    restart_allow_t1: restartAllow,
    restart_allow_t2: restartAllowT2,
    max_response_bytes: maxResponseBytes,
  });
});

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    log({ event: "stop", signal });
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  });
}

/**
 * docker-socket-filter entrypoint.
 *
 * Fail closed:
 *  - a restart-allowlist entry that is not a plain container name shape aborts
 *    startup (a typo must never widen the surface);
 *  - an empty restart allowlist is allowed (then no restart passes at all).
 *
 * The audit log is stdout as one JSON object per line (`docker compose logs
 * docker-socket-filter`). Allowed requests are logged at `allowed`, everything
 * refused at `denied` with the reason code. No header values, tokens or bodies
 * are ever logged.
 */
import { createFilterServer, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_RESTART_ALLOW, REF_RE } from "./filter.mjs";

const port = Number(process.env.OPS_FILTER_PORT ?? "2375");
const host = process.env.OPS_FILTER_HOST ?? "0.0.0.0";
const upstreamSocketPath = process.env.OPS_FILTER_UPSTREAM ?? "/var/run/docker.sock";
const maxResponseBytes = Number(process.env.OPS_FILTER_MAX_RESPONSE_BYTES ?? String(DEFAULT_MAX_RESPONSE_BYTES));

const rawAllow = process.env.OPS_FILTER_RESTART_ALLOW;
const restartAllow = (rawAllow === undefined || rawAllow.trim() === "" ? DEFAULT_RESTART_ALLOW.join(",") : rawAllow)
  .split(",")
  .map((v) => v.trim())
  .filter(Boolean);

for (const name of restartAllow) {
  if (!REF_RE.test(name)) {
    console.error(JSON.stringify({ ts: new Date().toISOString(), event: "error", code: "bad_restart_allow_entry", name }));
    process.exit(1);
  }
}

if (!Number.isFinite(port) || port < 1 || port > 65535) {
  console.error(JSON.stringify({ ts: new Date().toISOString(), event: "error", code: "bad_port" }));
  process.exit(1);
}

const log = (entry) => {
  console.log(JSON.stringify({ ts: new Date().toISOString(), ...entry }));
};

const server = createFilterServer({ upstreamSocketPath, restartAllow, maxResponseBytes, log });

server.listen(port, host, () => {
  log({
    event: "start",
    port,
    host,
    upstream: upstreamSocketPath,
    restart_allow: restartAllow,
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

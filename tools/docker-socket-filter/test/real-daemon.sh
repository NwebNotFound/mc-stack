#!/bin/sh
# Real-daemon test for docker-socket-filter.
#
# Builds this image, runs it with the REAL /var/run/docker.sock on a throwaway
# network next to a throwaway canary container, and probes it:
#   - the canary (the only allowlisted name here) restarts -> 204;
#   - a real mc-stack container, a game server, a container id, create/exec/stop,
#     a prefix name and every injection variant -> 403.
#
# Safety: only hermes-dsf-* containers and the hermes-dsf-net network are
# created. No mc-stack container is started, stopped or restarted, and no
# `docker compose up` happens anywhere. Both containers self-exit (`--rm` plus a
# timeout), so nothing that mounts the socket is left running.
#
# Usage: sh tools/docker-socket-filter/test/real-daemon.sh
set -u
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIR=$(CDPATH= cd -- "$HERE/.." && pwd)

SUFFIX=$$
IMAGE=hermes-dsf:test
NET=hermes-dsf-net
CANARY="hermes-dsf-canary-$SUFFIX"
T2CANARY="hermes-dsf-t2canary-$SUFFIX"
FILTER="hermes-dsf-filter-$SUFFIX"
FILTER_TOKEN="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"

echo "### 1. build $IMAGE"
docker build -q -t "$IMAGE" "$DIR" || exit 1

echo "### 2. throwaway network $NET"
docker network create "$NET" >/dev/null 2>&1 || true

echo "### 3. throwaway canaries ($CANARY, $T2CANARY, self-remove after 300s)"
docker run -d --rm --name "$CANARY" --network "$NET" alpine sleep 300 >/dev/null || exit 1
docker run -d --rm --name "$T2CANARY" --network "$NET" alpine sleep 300 >/dev/null || exit 1

echo "### 4. filter holding the REAL socket ($FILTER, self-exits after 240s)"
docker run -d --rm --name "$FILTER" --network "$NET" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e OPS_FILTER_TOKEN="$FILTER_TOKEN" \
  -e OPS_FILTER_RESTART_ALLOW="$CANARY" \
  -e OPS_FILTER_RESTART_ALLOW_T2="$T2CANARY" \
  --entrypoint sh "$IMAGE" -c 'timeout 240 node src/server.mjs' >/dev/null || exit 1
sleep 2

echo "### 5. probe (raw HTTP from a throwaway client)"
docker run --rm -i --network "$NET" -e PROBE_HOST="$FILTER" -e FILTER_TOKEN="$FILTER_TOKEN" -e CANARY="$CANARY" -e T2CANARY="$T2CANARY" \
  node:22-alpine node --input-type=module - <<'PROBE'
import net from "node:net";
const HOST = process.env.PROBE_HOST;
const CANARY = process.env.CANARY;
const T2CANARY = process.env.T2CANARY;
const AUTH = `Authorization: Bearer ${process.env.FILTER_TOKEN}\r\n`;
const raw = (request) =>
  new Promise((resolve) => {
    const chunks = [];
    const sock = net.connect(2375, HOST);
    let idle = null;
    const done = () => { clearTimeout(idle); sock.destroy(); resolve(Buffer.concat(chunks).toString("latin1")); };
    sock.on("connect", () => sock.write(request));
    sock.on("data", (c) => { chunks.push(c); clearTimeout(idle); idle = setTimeout(done, 400); });
    sock.on("error", done);
    sock.on("close", done);
  });
const status = (t) => { const m = /HTTP\/1\.1 (\d{3})/.exec(t); return m ? Number(m[1]) : 0; };
const all = (t) => [...t.matchAll(/HTTP\/1\.1 (\d{3})/g)].map((m) => Number(m[1]));
const get = (t) => `GET ${t} HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`;
const post = (t) => `POST ${t} HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`;
const ID = "f".repeat(64);

const cases = [
  ["denied without bearer token (401)", `GET /containers/json?all=1 HTTP/1.1\r\nHost: docker\r\n\r\n`, 401],
  ["denied restart without bearer token (401)", `POST /containers/${CANARY}/restart HTTP/1.1\r\nHost: docker\r\nContent-Length: 0\r\n\r\n`, 401],
  ["denied logs without tail", get(`/containers/${CANARY}/logs?stdout=1`), 403],
  ["allowed HEAD /_ping", `HEAD /_ping HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`, 200],
  ["allowed GET /containers/json", get("/containers/json?all=1"), 200],
  ["allowed GET /containers/json with filters", get("/containers/json?all=1&filters=%7B%22label%22%3A%7B%22com.docker.compose.project%3Dmc-stack%22%3Atrue%7D%7D"), 200],
  ["allowed GET canary inspect", get(`/containers/${CANARY}/json`), 200],
  ["allowed GET canary logs", get(`/containers/${CANARY}/logs?stderr=1&stdout=1&tail=200&timestamps=1`), 200],
  ["allowed restart canary", post(`/containers/${CANARY}/restart`), 204],
  ["allowed restart T2 canary (the T2 list is honoured separately)", post(`/containers/${T2CANARY}/restart`), 204],
  ["denied restart mc-stack-bot-1 (real bot: not on this throwaway T1 list)", post("/containers/mc-stack-bot-1/restart"), 403],
  ["denied restart mc-stack-lobby-1 (game server: not on this throwaway T2 list)", post("/containers/mc-stack-lobby-1/restart"), 403],
  ["denied restart mc-stack-survival-1", post("/containers/mc-stack-survival-1/restart"), 403],
  ["denied restart mc-stack-velocity-1", post("/containers/mc-stack-velocity-1/restart"), 403],
  ["denied restart lobby", post("/containers/lobby/restart"), 403],
  ["denied restart survival", post("/containers/survival/restart"), 403],
  ["denied restart velocity", post("/containers/velocity/restart"), 403],
  ["denied restart craftcore-db", post("/containers/craftcore-db/restart"), 403],
  ["denied restart hermes-mcp", post("/containers/hermes-mcp/restart"), 403],
  ["denied restart hermes-test", post("/containers/hermes-test/restart"), 403],
  ["denied restart by container id", post(`/containers/${ID}/restart`), 403],
  ["denied restart prefix name", post(`/containers/${CANARY}-2/restart`), 403],
  ["denied restart t=31", post(`/containers/${CANARY}/restart?t=31`), 403],
  ["denied restart t=-1", post(`/containers/${CANARY}/restart?t=-1`), 403],
  ["denied POST /containers/create", post("/containers/create"), 403],
  ["denied POST stop", post(`/containers/${CANARY}/stop`), 403],
  ["denied POST start", post(`/containers/${CANARY}/start`), 403],
  ["denied POST kill", post(`/containers/${CANARY}/kill`), 403],
  ["denied POST /containers/prune", post("/containers/prune"), 403],
  ["denied POST /exec/x/start", post("/exec/x/start"), 403],
  ["denied DELETE container", `DELETE /containers/${CANARY} HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`, 403],
  ["denied GET ?size=1", get("/containers/json?all=1&size=1"), 403],
  ["denied GET /volumes", get("/volumes"), 403],
  ["denied GET /images/json", get("/images/json"), 403],
  ["denied GET /networks", get("/networks"), 403],
  ["denied GET /version", get("/version"), 403],
  ["denied GET /info", get("/info"), 403],
  ["denied GET /events", get("/events"), 403],
  ["denied GET /system/df", get("/system/df"), 403],
  ["denied traversal", post(`/containers/${CANARY}/../create`), 403],
  ["denied encoded traversal", post("/containers/%2e%2e/create"), 403],
  ["denied double slash", get("//containers/json"), 403],
  ["denied ;create suffix", post(`/containers/${CANARY}/restart;create`), 403],
  ["denied upgrade", `GET /containers/json HTTP/1.1\r\nHost: docker\r\n${AUTH}Upgrade: tcp\r\nConnection: Upgrade\r\n\r\n`, 403],
  ["denied double content-length", `POST /containers/${CANARY}/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\nContent-Length: 0\r\n\r\n`, 403],
  ["denied transfer-encoding", `POST /containers/${CANARY}/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n`, 403],
  ["denied body on restart", `POST /containers/${CANARY}/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 4\r\n\r\njunk`, 403],
  ["denied lowercase method", `get /containers/json HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`, 403],
];

let failures = 0;
for (const [label, request, expected] of cases) {
  const got = status(await raw(request));
  if (got !== expected) failures += 1;
  console.log(`${got === expected ? "PASS" : "FAIL"}  ${label} -> ${got} (expected ${expected})`);
}
{
  const pipelined =
    `GET /containers/json?all=1 HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n` +
    `POST /containers/create HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`;
  const statuses = all(await raw(pipelined));
  const ok = statuses[0] === 200 && statuses[1] === 403;
  if (!ok) failures += 1;
  console.log(`${ok ? "PASS" : "FAIL"}  pipelined smuggling -> [${statuses.join(", ")}] (expected [200, 403])`);
}
console.log(failures === 0 ? "REAL-DAEMON PROBE: ALL PASS" : `REAL-DAEMON PROBE: ${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
PROBE
RC=$?

echo "### 6. audit log (allowed + denied)"
docker logs "$FILTER" 2>&1 | head -50

echo "### 7. containers still running that mount the socket:"
docker ps --filter name=hermes-dsf --format '{{.Names}} {{.Status}}'

echo "probe exit code: $RC"
exit "$RC"

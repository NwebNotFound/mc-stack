/**
 * docker-socket-filter tests.
 *
 * Two layers:
 *  1. `classify`/`parseHead` are pure, so every allow/deny case is asserted
 *     directly (including the injection, smuggling, traversal and encoding
 *     variants from the review).
 *  2. A live end-to-end run: a fake Docker daemon on a UNIX socket, the real
 *     filter in front of it on a TCP port, raw HTTP over a socket. This proves
 *     that allowed requests are really forwarded and that denied ones never
 *     reach the upstream.
 *
 * No docker daemon and no dependencies are needed: `node --test` is enough.
 */
import assert from "node:assert/strict";
import http from "node:http";
import net from "node:net";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  checkBearer,
  classify,
  createFilterServer,
  dechunk,
  DEFAULT_RESTART_ALLOW_T1,
  DEFAULT_RESTART_ALLOW_T2,
  Denied,
  parseAllow,
  parseHead,
  resolveRestartAllow,
  rewriteResponse,
  restartTierOf,
  stripInspectEnv,
} from "../src/filter.mjs";

const TOKEN = "t".repeat(32);
const AUTH = `Authorization: Bearer ${TOKEN}\r\n`;
const CFG = { restartAllow: DEFAULT_RESTART_ALLOW_T1, restartAllowT2: DEFAULT_RESTART_ALLOW_T2 };
const ID64 = "a".repeat(64);

function allowed(method, target, cfg = CFG) {
  const route = classify(method, target, cfg);
  assert.ok(route, `${method} ${target} should be allowed`);
  return route;
}

function denied(method, target, code = null, cfg = CFG) {
  assert.throws(
    () => classify(method, target, cfg),
    (err) => {
      assert.ok(err instanceof Denied, `${method} ${target} should throw Denied, got ${err}`);
      if (code !== null) assert.equal(err.code, code, `${method} ${target}`);
      return true;
    },
    `${method} ${target} must be refused`,
  );
}

/* ------------------------------------------------------------------ */
/* 1. allowed surface                                                  */
/* ------------------------------------------------------------------ */

test("the exact requests the docker CLI sends are allowed", () => {
  // Captured from the real CLI (docker 29.x) talking to a fake daemon.
  allowed("HEAD", "/_ping");
  allowed("GET", "/_ping");
  allowed(
    "GET",
    '/containers/json?all=1&filters=%7B%22label%22%3A%7B%22com.docker.compose.project%3Dmc-stack%22%3Atrue%7D%7D',
  );
  allowed("GET", "/containers/json?all=1");
  allowed("GET", "/containers/json");
  allowed("GET", `/containers/${ID64}/json`);
  allowed("GET", "/containers/mc-stack-bot-1/json");
  allowed("GET", `/containers/${ID64}/logs?stderr=1&stdout=1&tail=200&timestamps=1`);
  allowed("GET", "/containers/mc-stack-bot-1/logs?tail=300");
  allowed("POST", "/containers/mc-stack-bot-1/restart");
  allowed("POST", "/containers/mc-stack-cloudflared-mcp-1/restart");
  allowed("POST", "/containers/mc-stack-bot-1/restart?t=30");
  allowed("POST", "/containers/mc-stack-bot-1/restart?t=0");
});

test("an API version prefix v1.<digits> is accepted", () => {
  allowed("GET", "/v1.51/containers/json?all=1");
  allowed("GET", "/v1.24/containers/mc-stack-bot-1/json");
  allowed("POST", "/v1.51/containers/mc-stack-bot-1/restart");
  allowed("GET", "/v1.51/containers/mc-stack-bot-1/logs?tail=10");
  denied("GET", "/v1/containers/json");
  denied("GET", "/v1.5x/containers/json");
  denied("GET", "/v2.0/containers/json");
  denied("GET", "/v1.51/v1.51/containers/json");
});

test("the parsed request carries no body and exposes the route", () => {
  const req = parseHead("POST /containers/mc-stack-bot-1/restart HTTP/1.1\r\nHost: docker\r\nContent-Length: 0");
  assert.equal(req.method, "POST");
  assert.equal(req.target, "/containers/mc-stack-bot-1/restart");
  const route = classify(req.method, req.target, CFG);
  assert.equal(route.route, "container_restart");
  assert.equal(route.ref, "mc-stack-bot-1");
});

/* ------------------------------------------------------------------ */
/* 2. forbidden docker surface                                         */
/* ------------------------------------------------------------------ */

test("container lifecycle beyond restart is refused", () => {
  denied("POST", "/containers/create");
  denied("POST", "/containers/mc-stack-bot-1/start");
  denied("POST", "/containers/mc-stack-bot-1/stop");
  denied("POST", "/containers/mc-stack-bot-1/kill");
  denied("POST", "/containers/mc-stack-bot-1/pause");
  denied("POST", "/containers/mc-stack-bot-1/unpause");
  denied("POST", "/containers/mc-stack-bot-1/rename");
  denied("POST", "/containers/mc-stack-bot-1/update");
  denied("DELETE", "/containers/mc-stack-bot-1");
  denied("POST", "/containers/prune");
  denied("POST", "/containers/mc-stack-bot-1/exec");
  denied("POST", "/exec/abc/start");
  denied("POST", "/exec/create");
});

test("non-container API sections are refused", () => {
  denied("GET", "/volumes");
  denied("GET", "/volumes/json");
  denied("POST", "/volumes/create");
  denied("GET", "/images/json");
  denied("POST", "/build");
  denied("GET", "/events");
  denied("GET", "/info");
  denied("GET", "/version");
  denied("GET", "/system/df");
  denied("GET", "/networks");
  denied("POST", "/networks/create");
  denied("GET", "/swarm");
  denied("GET", "/secrets");
  denied("GET", "/auth");
  denied("GET", "/tasks");
  denied("GET", "/services");
});

test("restart is only possible for an exact allowlisted container name", () => {
  // Prefix matches, ids and globs must all fail.
  denied("POST", "/containers/mc-stack-bot-10/restart");
  denied("POST", "/containers/mc-stack-bot/restart");
  denied("POST", "/containers/bot/restart");
  denied("POST", "/containers/mc-stack-bot-1-extra/restart");
  denied("POST", `/containers/${ID64}/restart`);
  denied("POST", "/containers/mc-stack-*/restart");
  denied("POST", "/containers/MC-STACK-BOT-1/restart");
  denied("POST", "/containers/mc-stack-bot-1/../restart");

  // The services a game-server restart would need are NOT on the filter list.
  for (const name of ["craftcore-db", "lobby", "survival", "velocity", "pvp", "hermes-test", "hermes-mcp", "ops-helper", "vipworld-1"]) {
    denied("POST", `/containers/${name}/restart`);
  }
});

test("the T1 list alone cannot restart a game server; the union can", () => {
  // Defence in depth: the filter is the second, independent check. With only the
  // T1 list configured (the pre-T2 state) a game server can never pass...
  const t1Only = { restartAllow: DEFAULT_RESTART_ALLOW_T1, restartAllowT2: [] };
  for (const name of ["mc-stack-lobby-1", "mc-stack-survival-1", "mc-stack-velocity-1", "mc-stack-craftcore-db-1"]) {
    denied("POST", `/containers/${name}/restart`, null, t1Only);
  }
  allowed("POST", "/containers/mc-stack-bot-1/restart", t1Only);
  allowed("POST", "/containers/mc-stack-cloudflared-mcp-1/restart", t1Only);

  // ...and with the full configuration the game servers pass on the T2 list
  // while the database / mcp / helper containers still do not.
  for (const name of ["mc-stack-lobby-1", "mc-stack-survival-1", "mc-stack-velocity-1"]) {
    const route = allowed("POST", `/containers/${name}/restart`);
    assert.equal(route.restarts, "t2", `${name} must be classified as T2`);
  }
  for (const name of ["mc-stack-craftcore-db-1", "mc-stack-hermes-mcp-1", "mc-stack-hermes-test-1", "mc-stack-ops-helper-1", "mc-stack-docker-socket-filter-1"]) {
    denied("POST", `/containers/${name}/restart`, null, t1Only);
    denied("POST", `/containers/${name}/restart`);
  }
});

test("the two restart lists stay separate and are resolved as a union", () => {
  assert.deepEqual(DEFAULT_RESTART_ALLOW_T1, ["mc-stack-bot-1", "mc-stack-cloudflared-mcp-1"]);
  assert.deepEqual(DEFAULT_RESTART_ALLOW_T2, ["mc-stack-lobby-1", "mc-stack-survival-1", "mc-stack-velocity-1"]);
  assert.deepEqual(
    resolveRestartAllow(CFG).sort(),
    [...DEFAULT_RESTART_ALLOW_T1, ...DEFAULT_RESTART_ALLOW_T2].sort(),
    "the union is what the one restart path accepts",
  );
  assert.equal(restartTierOf("mc-stack-bot-1", CFG), "t1");
  assert.equal(restartTierOf("mc-stack-lobby-1", CFG), "t2");
  assert.equal(restartTierOf("mc-stack-craftcore-db-1", CFG), null);
  assert.equal(restartTierOf("mc-stack-hermes-mcp-1", CFG), null);
  // An empty T2 list (the old configuration) never widens anything.
  assert.deepEqual(resolveRestartAllow({ restartAllow: DEFAULT_RESTART_ALLOW_T1, restartAllowT2: [] }), DEFAULT_RESTART_ALLOW_T1);
});

test("restart timeout is bounded to 0..30", () => {
  denied("POST", "/containers/mc-stack-bot-1/restart?t=31", "restart_timeout_too_large");
  denied("POST", "/containers/mc-stack-bot-1/restart?t=999", "bad_restart_timeout");
  denied("POST", "/containers/mc-stack-bot-1/restart?t=-1", "bad_restart_timeout");
  denied("POST", "/containers/mc-stack-bot-1/restart?t=1.5", "bad_restart_timeout");
  denied("POST", "/containers/mc-stack-bot-1/restart?t=abc", "bad_restart_timeout");
  denied("POST", "/containers/mc-stack-bot-1/restart?t=1&t=2", "duplicate_query_key");
  allowed("POST", "/containers/mc-stack-bot-1/restart?t=30");
});

/* ------------------------------------------------------------------ */
/* 3. injection / traversal / encoding / smuggling                     */
/* ------------------------------------------------------------------ */

test("path traversal and encoding tricks are refused", () => {
  denied("POST", "/containers/mc-stack-bot-1/../create");
  denied("POST", "/containers/mc-stack-bot-1/..%2fcreate");
  denied("GET", "/containers/%2e%2e/create");
  denied("GET", "/containers/..%2f..%2fjson");
  denied("GET", "//containers/json");
  denied("GET", "/containers//json");
  denied("GET", "/containers/./json");
  denied("GET", "/containers/mc-stack-bot-1/json/");
  denied("GET", "/containers/\\mc-stack-bot-1\\json");
  denied("POST", "/containers/mc-stack-bot-1/;create");
  denied("POST", "/containers/mc-stack-bot-1/restart;create");
  denied("POST", "/containers/mc-stack-bot-1/restart%3fcreate");
  denied("GET", "/containers/mc-stack-bot-1/json?size=1");
});

test("query strings are strictly allowlisted", () => {
  denied("GET", "/containers/json?size=1", "query_key_not_allowed:size");
  denied("GET", "/containers/json?all=1&all=1", "duplicate_query_key");
  denied("GET", "/containers/json?all=2", "bad_all_value");
  denied("GET", "/containers/json?all=1&prune=1", "query_key_not_allowed:prune");
  denied("GET", "/containers/mc-stack-bot-1/logs?follow=1", "query_key_not_allowed:follow");
  denied("GET", "/containers/mc-stack-bot-1/logs?tail=301", "tail_too_large");
  denied("GET", "/containers/mc-stack-bot-1/logs?tail=-1", "bad_tail_value");
  denied("GET", "/containers/mc-stack-bot-1/logs?tail=10&stdout=2", "bad_stdout_value");
  // tail is mandatory: without it the daemon would return the whole log.
  denied("GET", "/containers/mc-stack-bot-1/logs", "tail_required");
  denied("GET", "/containers/mc-stack-bot-1/logs?stdout=1&stderr=1", "tail_required");
  denied("GET", "/containers/mc-stack-bot-1/json?foo=bar", "query_key_not_allowed:foo");
  denied("GET", "/containers/json?");
  denied("GET", "/containers/json?=1");
});

test("method is exact and case sensitive", () => {
  denied("get", "/containers/json");
  denied("Post", "/containers/mc-stack-bot-1/restart");
  denied("PUT", "/containers/json");
  denied("PATCH", "/containers/json");
  denied("OPTIONS", "/containers/json");
  denied("POST", "/_ping", "method_not_allowed");
  denied("POST", "/containers/json");
  denied("GET", "/containers/mc-stack-bot-1/restart");
  denied("HEAD", "/containers/mc-stack-bot-1/json");
});

test("path matching is case sensitive", () => {
  denied("GET", "/Containers/json");
  denied("GET", "/containers/JSON");
  denied("GET", "/containers/mc-stack-bot-1/JSON");
  denied("POST", "/containers/mc-stack-bot-1/Restart");
});

test("request smuggling vectors are refused at parse time", () => {
  const bad = [
    ["double content-length", "GET /containers/json HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nContent-Length: 5\r\n"],
    ["transfer-encoding", "POST /containers/mc-stack-bot-1/restart HTTP/1.1\r\nTransfer-Encoding: chunked\r\n"],
    ["transfer-encoding + content-length", "POST /containers/mc-stack-bot-1/restart HTTP/1.1\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n"],
    ["upgrade", "GET /containers/json HTTP/1.1\r\nUpgrade: tcp\r\nConnection: Upgrade\r\n"],
    ["connection upgrade only", "GET /containers/json HTTP/1.1\r\nConnection: keep-alive, Upgrade\r\n"],
    ["body on GET", "GET /containers/json HTTP/1.1\r\nContent-Length: 5\r\n"],
    ["body on restart", "POST /containers/mc-stack-bot-1/restart HTTP/1.1\r\nContent-Length: 12\r\n"],
    ["obs-fold header", "GET /containers/json HTTP/1.1\r\nHost: x\r\n evil: yes\r\n"],
    ["bare LF", "GET /containers/json HTTP/1.1\nHost: x\n"],
    ["tab in header", "GET /containers/json HTTP/1.1\r\nHost:\tx\r\n"],
    ["non-ascii target", "GET /containers/\u00e4/json HTTP/1.1\r\n"],
    ["absolute-form target", "GET http://docker/containers/json HTTP/1.1\r\n"],
    ["missing version", "GET /containers/json\r\n"],
    ["http/1.0", "GET /containers/json HTTP/1.0\r\n"],
    ["extra space in request line", "GET  /containers/json HTTP/1.1\r\n"],
    ["header without colon", "GET /containers/json HTTP/1.1\r\nHost x\r\n"],
  ];
  for (const [label, raw] of bad) {
    assert.throws(
      () => parseHead(raw),
      (err) => err instanceof Denied,
      `${label} must be refused`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* 4. live end-to-end against a fake daemon on a UNIX socket           */
/* ------------------------------------------------------------------ */

function startFakeDaemon(socketPath) {
  const hits = [];
  const server = http.createServer((req, res) => {
    hits.push({ method: req.method, url: req.url });
    const path = (req.url ?? "").split("?")[0];
    if (path === "/_ping") {
      res.writeHead(200, { "Content-Type": "text/plain", "Api-Version": "1.51", "Content-Length": "2" });
      res.end("OK");
      return;
    }
    if (path === "/containers/json") {
      const body = JSON.stringify([{ Id: ID64, Names: ["/mc-stack-bot-1"], State: "running" }]);
      res.writeHead(200, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
      res.end(body);
      return;
    }
    if (path.endsWith("/restart")) {
      res.writeHead(204);
      res.end();
      return;
    }
    if (path.endsWith("/logs")) {
      res.writeHead(200, { "Content-Type": "application/vnd.docker.raw-stream" });
      res.end("log line 1\nlog line 2\n");
      return;
    }
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ message: "no such route" }));
  });
  return new Promise((resolve) => server.listen(socketPath, () => resolve({ server, hits })));
}

function rawExchange(port, raw, { idleMs = 250, hardMs = 4000 } = {}) {
  return new Promise((resolve) => {
    const chunks = [];
    const sock = net.connect(port, "127.0.0.1");
    let idle = null;
    let hard = null;
    const finish = () => {
      clearTimeout(idle);
      clearTimeout(hard);
      sock.destroy();
      resolve(Buffer.concat(chunks).toString("latin1"));
    };
    sock.on("connect", () => sock.write(raw));
    sock.on("data", (c) => {
      chunks.push(c);
      clearTimeout(idle);
      idle = setTimeout(finish, idleMs);
    });
    sock.on("error", finish);
    sock.on("close", finish);
    hard = setTimeout(finish, hardMs);
  });
}

// Pipelined responses are concatenated without a separator, so match anywhere.
const statusesOf = (text) => [...text.matchAll(/HTTP\/1\.1 (\d{3})/g)].map((m) => Number(m[1]));

async function withFilter(run) {
  const dir = mkdtempSync(join(tmpdir(), "dsf-"));
  const socketPath = join(dir, "docker.sock");
  const { server: daemon, hits } = await startFakeDaemon(socketPath);
  const filter = createFilterServer({ upstreamSocketPath: socketPath, token: TOKEN, restartAllow: DEFAULT_RESTART_ALLOW_T1, restartAllowT2: DEFAULT_RESTART_ALLOW_T2, log: () => {} });
  await new Promise((resolve) => filter.listen(0, "127.0.0.1", resolve));
  const port = filter.address().port;
  try {
    await run({ port, hits });
  } finally {
    await new Promise((resolve) => filter.close(resolve));
    await new Promise((resolve) => daemon.close(resolve));
    rmSync(dir, { recursive: true, force: true });
  }
}

test("live: allowed requests are forwarded, forbidden ones never reach the daemon", async () => {
  await withFilter(async ({ port, hits }) => {
    const ps = await rawExchange(port, `GET /containers/json?all=1 HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`);
    assert.equal(statusesOf(ps)[0], 200, ps);
    assert.match(ps, /mc-stack-bot-1/);

    const ping = await rawExchange(port, `HEAD /_ping HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`);
    assert.equal(statusesOf(ping)[0], 200, ping);
    assert.match(ping, /Api-Version: 1\.51/);

    const logs = await rawExchange(port, `GET /containers/${ID64}/logs?stderr=1&stdout=1&tail=200&timestamps=1 HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`);
    assert.equal(statusesOf(logs)[0], 200, logs);
    assert.match(logs, /log line 1/);

    const restart = await rawExchange(port, `POST /containers/mc-stack-bot-1/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`);
    assert.equal(statusesOf(restart)[0], 204, restart);

    // A game server is on the T2 list, so it passes (the T1/T2 split is enforced
    // one level up, in the helper's own service lists)...
    const gameRestart = await rawExchange(port, `POST /containers/mc-stack-lobby-1/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`);
    assert.equal(statusesOf(gameRestart)[0], 204, gameRestart);

    const before = hits.length;

    const create = await rawExchange(port, `POST /containers/create HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`);
    assert.equal(statusesOf(create)[0], 403, create);
    assert.match(create, /route_not_allowed/);

    // ...but the database, hermes-mcp, ops-helper and the filter itself are on
    // NEITHER list and must never be restartable.
    for (const name of ["mc-stack-craftcore-db-1", "mc-stack-hermes-mcp-1", "mc-stack-ops-helper-1", "mc-stack-hermes-test-1"]) {
      const forbidden = await rawExchange(port, `POST /containers/${name}/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`);
      assert.equal(statusesOf(forbidden)[0], 403, `${name}: ${forbidden}`);
      assert.match(forbidden, /container_not_restartable/);
    }

    const shield = await rawExchange(port, `POST /containers/mc-stack-velocity-2/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`);
    assert.equal(statusesOf(shield)[0], 403, shield);
    assert.match(shield, /container_not_restartable/);

    const byId = await rawExchange(port, `POST /containers/${ID64}/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`);
    assert.equal(statusesOf(byId)[0], 403, byId);

    const exec = await rawExchange(port, `POST /exec/abc/start HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`);
    assert.equal(statusesOf(exec)[0], 403, exec);

    const size = await rawExchange(port, `GET /containers/json?all=1&size=1 HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`);
    assert.equal(statusesOf(size)[0], 403, size);

    assert.equal(hits.length, before, "a denied request must never reach the upstream daemon");
  });
});

test("live: requests without the bearer token are 401 and never reach the daemon", async () => {
  await withFilter(async ({ port, hits }) => {
    const restart = await rawExchange(port, "POST /containers/mc-stack-lobby-1/restart HTTP/1.1\r\nHost: docker\r\nContent-Length: 0\r\n\r\n");
    assert.equal(statusesOf(restart)[0], 401, restart);
    assert.match(restart, /unauthorized/);

    const wrong = await rawExchange(port, `GET /containers/json?all=1 HTTP/1.1\r\nHost: docker\r\nAuthorization: Bearer ${"x".repeat(32)}\r\n\r\n`);
    assert.equal(statusesOf(wrong)[0], 401, wrong);

    const basic = await rawExchange(port, `GET /containers/json?all=1 HTTP/1.1\r\nHost: docker\r\nAuthorization: Basic ${TOKEN}\r\n\r\n`);
    assert.equal(statusesOf(basic)[0], 401, basic);

    const dup = await rawExchange(port, `GET /containers/json?all=1 HTTP/1.1\r\nHost: docker\r\n${AUTH}${AUTH}\r\n`);
    assert.equal(statusesOf(dup)[0], 403, dup);

    const logs = await rawExchange(port, `GET /containers/${ID64}/logs?stdout=1 HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n`);
    assert.equal(statusesOf(logs)[0], 403, logs);
    assert.match(logs, /tail_required/);

    assert.equal(hits.length, 0, "an unauthenticated request must never reach the daemon");

    // The read-only health probe is the documented exception.
    const ping = await rawExchange(port, "HEAD /_ping HTTP/1.1\r\nHost: docker\r\n\r\n");
    assert.equal(statusesOf(ping)[0], 200, ping);
  });
});

test("checkBearer is exact and refuses short/missing tokens", () => {
  assert.equal(checkBearer(`Bearer ${TOKEN}`, TOKEN), true);
  assert.equal(checkBearer(`Bearer ${TOKEN}x`, TOKEN), false);
  assert.equal(checkBearer(`bearer ${TOKEN}`, TOKEN), false);
  assert.equal(checkBearer(undefined, TOKEN), false);
  assert.equal(checkBearer("Bearer short", "short"), false, "a too-short configured token never authenticates");
  assert.throws(() => createFilterServer({ upstreamSocketPath: "/nope", log: () => {} }), /token/);
});

test("live: pipelined smuggling is answered per request and the smuggled one is 403", async () => {
  await withFilter(async ({ port, hits }) => {
    const raw =
      `GET /containers/json?all=1 HTTP/1.1\r\nHost: docker\r\n${AUTH}\r\n` +
      `POST /containers/create HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 0\r\n\r\n`;
    const text = await rawExchange(port, raw);
    const statuses = statusesOf(text);
    assert.deepEqual(statuses.slice(0, 2), [200, 403], text);
    assert.equal(hits.length, 1, "only the allowed half of the pipeline may reach the daemon");
    assert.equal(hits[0].url, "/containers/json?all=1");
  });
});

test("live: a request with a body is refused before it is forwarded", async () => {
  await withFilter(async ({ port, hits }) => {
    const text = await rawExchange(port, `POST /containers/mc-stack-bot-1/restart HTTP/1.1\r\nHost: docker\r\n${AUTH}Content-Length: 10\r\n\r\n0123456789`);
    assert.equal(statusesOf(text)[0], 403, text);
    assert.equal(hits.length, 0);
  });
});

test("live: an oversized head is refused", async () => {
  await withFilter(async ({ port }) => {
    const junk = "X".repeat(40 * 1024);
    const text = await rawExchange(port, `GET /containers/json HTTP/1.1\r\nX-Junk: ${junk}`);
    assert.equal(statusesOf(text)[0], 403, text.slice(0, 200));
  });
});

/* ------------------------------------------------------------------ */
/* 5. response re-framing                                              */
/* ------------------------------------------------------------------ */

test("rewriteResponse recomputes framing and drops hop-by-hop headers", () => {
  const upstream = Buffer.from(
    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\nX-Keep: yes\r\n\r\n7\r\n{\"a\":1}\r\n0\r\n\r\n",
    "latin1",
  );
  const out = rewriteResponse(upstream, "GET").toString("latin1");
  assert.match(out, /^HTTP\/1\.1 200 OK\r\n/);
  assert.match(out, /X-Keep: yes/);
  assert.match(out, /Content-Length: 7\r\n/);
  assert.ok(!/Transfer-Encoding/i.test(out), "chunked framing must be replaced by Content-Length");
  assert.match(out, /\r\n\r\n\{"a":1\}$/);

  // HEAD answers carry no body.
  const head = rewriteResponse(upstream, "HEAD").toString("latin1");
  assert.match(head, /Content-Length: 0\r\n/);
  assert.ok(head.endsWith("\r\n\r\n"));

  assert.equal(rewriteResponse(Buffer.from("not http at all", "latin1"), "GET"), null);
});

// ---- chunked upstream responses + inspect Env stripping (review of PR 125) ----

const chunkify = (text, size) => {
  const buf = Buffer.from(text, "utf8");
  const parts = [];
  for (let i = 0; i < buf.length; i += size) {
    const piece = buf.subarray(i, i + size);
    parts.push(Buffer.from(`${piece.length.toString(16)}\r\n`), piece, Buffer.from("\r\n"));
  }
  parts.push(Buffer.from("0\r\n\r\n"));
  return Buffer.concat(parts);
};

test("dechunk decodes chunked bodies and rejects malformed framing", () => {
  const text = JSON.stringify({ a: "x".repeat(5000) });
  assert.equal(dechunk(chunkify(text, 1500)).toString("utf8"), text);
  assert.equal(dechunk(Buffer.from("0\r\n\r\n")).length, 0);
  assert.equal(dechunk(Buffer.from("zz\r\nhello\r\n0\r\n\r\n")), null, "non-hex size");
  assert.equal(dechunk(Buffer.from("5;ext=1\r\nhello\r\n0\r\n\r\n")), null, "chunk extensions refused");
  assert.equal(dechunk(Buffer.from("5\r\nhelloXX0\r\n\r\n")), null, "missing CRLF after chunk");
  assert.equal(dechunk(Buffer.from("a\r\nshort\r\n0\r\n\r\n")), null, "chunk shorter than announced");
  assert.equal(dechunk(Buffer.from("5\r\nhello\r\n")), null, "no terminating zero chunk");
});

test("rewriteResponse removes chunk framing so the client gets valid JSON", () => {
  const json = JSON.stringify([{ Id: "abc", Names: ["/x"], pad: "y".repeat(6000) }]);
  const upstream = Buffer.concat([
    Buffer.from(`HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n`, "latin1"),
    chunkify(json, 2048),
  ]);
  const out = rewriteResponse(upstream, "GET");
  const idx = out.indexOf(Buffer.from("\r\n\r\n"));
  const head = out.subarray(0, idx).toString("latin1");
  const body = out.subarray(idx + 4);
  assert.ok(head.includes(`Content-Length: ${Buffer.byteLength(json)}`));
  assert.doesNotMatch(head, /transfer-encoding/i);
  assert.deepEqual(JSON.parse(body.toString("utf8")), JSON.parse(json));
  const broken = Buffer.concat([Buffer.from(`HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n`, "latin1"), Buffer.from("zz\r\nbad\r\n0\r\n\r\n")]);
  assert.equal(rewriteResponse(broken, "GET"), null, "malformed chunking is refused, not forwarded");
});

test("inspect responses lose Config.Env, other fields stay", () => {
  const inspect = { Id: "1", Name: "/mc-stack-bot-1", State: { Running: true }, Config: { Image: "x", Env: ["DB_PASSWORD=secret", "TOKEN=abc"], Labels: { a: "b" } } };
  const out = JSON.parse(stripInspectEnv(Buffer.from(JSON.stringify(inspect))).toString("utf8"));
  assert.deepEqual(out.Config.Env, []);
  assert.equal(out.Config.Image, "x");
  assert.deepEqual(out.State, { Running: true });
  assert.equal(stripInspectEnv(Buffer.from("not json")), null);

  const raw = Buffer.concat([Buffer.from(`HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n`, "latin1"), chunkify(JSON.stringify(inspect), 40)]);
  const framed = rewriteResponse(raw, "GET", { transformBody: stripInspectEnv });
  const text = framed.subarray(framed.indexOf(Buffer.from("\r\n\r\n")) + 4).toString("utf8");
  assert.ok(!text.includes("secret") && !text.includes("TOKEN=abc"), "no secret value leaves the filter");
  assert.equal(rewriteResponse(Buffer.from(`HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nnot json`, "latin1"), "GET", { transformBody: stripInspectEnv }), null);
});

test("restart lists: unset/empty -> default, `none` -> no restart at all", () => {
  assert.deepEqual(parseAllow(undefined, DEFAULT_RESTART_ALLOW_T2), DEFAULT_RESTART_ALLOW_T2);
  assert.deepEqual(parseAllow("  ", DEFAULT_RESTART_ALLOW_T2), DEFAULT_RESTART_ALLOW_T2);
  assert.deepEqual(parseAllow("none", DEFAULT_RESTART_ALLOW_T2), []);
  assert.deepEqual(parseAllow(" a , b ", DEFAULT_RESTART_ALLOW_T2), ["a", "b"]);
  denied("POST", "/containers/mc-stack-lobby-1/restart", "container_not_restartable", { restartAllow: DEFAULT_RESTART_ALLOW_T1, restartAllowT2: parseAllow("none", DEFAULT_RESTART_ALLOW_T2) });
});

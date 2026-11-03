import assert from "node:assert/strict";
import http from "node:http";
import { test } from "node:test";
import { GatewayError } from "../dist/hermes.js";
import { OpsClient } from "../dist/ops.js";
import { registerOpsTools } from "../dist/ops-tools.js";
import { registerTools } from "../dist/tools.js";

const TOKEN = "ops-helper-shared-secret-0123456789";

function baseCfg(over = {}) {
  return {
    gatewayUrl: "http://127.0.0.1:9",
    backend: "gateway",
    maxActiveRunsPerProfile: 2,
    allowedModels: [],
    allowedProviders: [],
    allowedReasoningEfforts: [],
    opsHelperUrl: "",
    opsHelperToken: "",
    opsHelperTimeoutSeconds: 5,
    ...over,
  };
}

/** A real loopback HTTP server: the client is tested over the wire. */
async function withServer(handler, fn) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    return await fn(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

test("OpsClient posts the action to /op with the bearer header and no token in the URL", async () => {
  await withServer(
    async (req, res) => {
      const body = JSON.parse(await readBody(req));
      assert.equal(req.method, "POST");
      assert.equal(req.url, "/op", "the shared secret must never travel in the URL");
      assert.equal(req.headers.authorization, `Bearer ${TOKEN}`);
      assert.deepEqual(body, { action: "ops_status", params: { service: "lobby" } });
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, action: "ops_status", tier: "T0", impl: "status", code: null, message: null, result: { containers: [] }, audit_id: "abc" }));
    },
    async (url) => {
      const client = new OpsClient(baseCfg({ opsHelperUrl: url, opsHelperToken: TOKEN }));
      assert.equal(client.enabled(), true);
      const res = await client.call("ops_status", { service: "lobby" });
      assert.equal(res.ok, true);
      assert.equal(res.tier, "T0");
      assert.equal(res.audit_id, "abc");
    },
  );
});

test("OpsClient maps the helper's error statuses to distinct codes", async () => {
  await withServer(
    (_req, res) => {
      res.writeHead(401, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: false, code: "unauthorized" }));
    },
    async (url) => {
      const client = new OpsClient(baseCfg({ opsHelperUrl: url, opsHelperToken: TOKEN }));
      await assert.rejects(client.call("ops_status"), (exc) => {
        assert.ok(exc instanceof GatewayError);
        assert.equal(exc.code, "ops_helper_auth_failed");
        return true;
      });
    },
  );

  await withServer(
    (_req, res) => {
      res.writeHead(500, { "content-type": "application/json" });
      res.end("boom");
    },
    async (url) => {
      const client = new OpsClient(baseCfg({ opsHelperUrl: url, opsHelperToken: TOKEN }));
      await assert.rejects(client.call("ops_status"), (exc) => exc.code === "ops_helper_http_error");
    },
  );

  await withServer(
    (_req, res) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end("not json");
    },
    async (url) => {
      const client = new OpsClient(baseCfg({ opsHelperUrl: url, opsHelperToken: TOKEN }));
      await assert.rejects(client.call("ops_status"), (exc) => exc.code === "ops_helper_bad_response");
    },
  );
});

test("OpsClient fails closed when unconfigured and when the helper is unreachable", async () => {
  const unconfigured = new OpsClient(baseCfg());
  assert.equal(unconfigured.enabled(), false);
  await assert.rejects(unconfigured.call("ops_status"), (exc) => exc.code === "ops_helper_not_configured");

  // 127.0.0.1:1 is not listening: the client must report it, never fake an answer.
  const dead = new OpsClient(baseCfg({ opsHelperUrl: "http://127.0.0.1:1", opsHelperToken: TOKEN }));
  await assert.rejects(dead.call("ops_status"), (exc) => exc.code === "ops_helper_unreachable");
});

function fakeServer() {
  const handlers = {};
  return {
    handlers,
    registerTool: (name, _def, handler) => {
      handlers[name] = { handler, def: _def };
    },
  };
}

function parse(result) {
  return JSON.parse(result.content[0].text);
}

const OPS_TOOLS = [
  "ops_status",
  "ops_logs",
  "ops_disk",
  "ops_backups_list",
  "ops_players",
  "ops_git_state",
  "ops_build_plugin",
  "ops_run_tests",
  "ops_restart_service",
  "ops_backup_now",
  "ops_restart_game",
  "ops_deploy_plugin",
  "ops_rollback_plugin",
];

const T1_TOOLS = ["ops_restart_service", "ops_backup_now"];
const T2_TOOLS = ["ops_restart_game", "ops_deploy_plugin", "ops_rollback_plugin"];

/** Register the ops tools for one (level, listener) combination. */
function opsServer(over = {}) {
  const server = fakeServer();
  registerOpsTools(server, {
    cfg: baseCfg(),
    ops: { call: async () => ({ ok: true, result: {} }) },
    authLevel: "ops",
    allowT2: true,
    ...over,
  });
  return server;
}

test("the token levels are ordered: base < ops, unknown is rejected", async () => {
  const { resolveLevel, tokenMatches } = await import("../dist/auth.js");
  const cfg = { tokens: ["base-token-0123456789abcdef"], opsTokens: ["ops-token-0123456789abcdefg"] };

  assert.equal(resolveLevel(cfg.tokens[0], cfg), "base");
  assert.equal(resolveLevel(cfg.opsTokens[0], cfg), "ops", "the ops token is a superset of base");
  assert.equal(resolveLevel("something-else-0123456789abcdef", cfg), null);
  assert.equal(resolveLevel("", cfg), null);

  // Fail closed: with no ops token configured nobody reaches the ops level.
  assert.equal(resolveLevel(cfg.tokens[0], { tokens: cfg.tokens, opsTokens: [] }), "base");
  assert.equal(resolveLevel(cfg.opsTokens[0], { tokens: cfg.tokens, opsTokens: [] }), null);

  assert.equal(tokenMatches("a".repeat(40), ["a".repeat(40)]), true);
  assert.equal(tokenMatches("a".repeat(40), ["b".repeat(40)]), false);
  assert.equal(tokenMatches("", []), false);
});

test("opsAccess: T0 is free, T1 needs the ops token, T2 needs the local listener", async () => {
  const { opsAccess, OPS_TOKEN_REQUIRED, OPS_T2_LOCAL_ONLY } = await import("../dist/ops-tools.js");

  assert.equal(opsAccess("T0", "base", false), null);
  assert.equal(opsAccess("T0", "ops", true), null);

  assert.equal(opsAccess("T1", "base", true), OPS_TOKEN_REQUIRED);
  assert.equal(opsAccess("T1", "ops", false), null, "T1 is allowed on the tunnel listener with the ops token");

  assert.equal(opsAccess("T2", "base", true), OPS_TOKEN_REQUIRED);
  assert.equal(opsAccess("T2", "ops", false), OPS_T2_LOCAL_ONLY, "T2 must be refused on the tunnel listener");
  assert.equal(opsAccess("T2", "ops", true), null);
});

test("registerOpsTools exposes the phase-1 tools and marks the read-only ones", () => {
  const server = opsServer();
  for (const name of OPS_TOOLS) assert.ok(server.handlers[name], `${name} must be registered`);
  for (const name of ["ops_status", "ops_logs", "ops_disk", "ops_backups_list", "ops_players", "ops_git_state", "ops_build_plugin", "ops_run_tests"]) {
    assert.equal(server.handlers[name].def.annotations.readOnlyHint, true, `${name} is read-only`);
  }
  for (const name of T2_TOOLS) {
    assert.equal(server.handlers[name].def.annotations.destructiveHint, true, `${name} is destructive`);
  }
});

test("T1/T2 tools are not advertised without the ops token, and T2 not without the local listener", () => {
  const base = opsServer({ authLevel: "base", allowT2: false });
  for (const name of T1_TOOLS.concat(T2_TOOLS)) {
    assert.equal(base.handlers[name], undefined, `${name} must not be advertised to a base-token caller`);
  }
  for (const name of OPS_TOOLS.slice(0, 8)) {
    assert.ok(base.handlers[name], `${name} is a T0 tool and stays available`);
  }

  // Ops token through the tunnel-facing listener: T1 yes, T2 no.
  const tunnelled = opsServer({ authLevel: "ops", allowT2: false });
  assert.ok(tunnelled.handlers.ops_restart_service, "T1 is allowed on the tunnel listener");
  assert.ok(tunnelled.handlers.ops_backup_now);
  for (const name of T2_TOOLS) {
    assert.equal(tunnelled.handlers[name], undefined, `${name} must never be advertised on the tunnel listener`);
  }
});

test("a T1/T2 tool is not registered at all for a base-token caller", async () => {
  const calls = [];
  const server = opsServer({
    authLevel: "base",
    allowT2: false,
    ops: {
      call: async (a) => {
        calls.push(a);
        return { ok: true, result: {} };
      },
    },
  });
  assert.ok(server.handlers.ops_status, "T0 stays available to the base token");
  assert.equal(server.handlers.ops_restart_service, undefined, "T1 needs the ops token");
  assert.equal(server.handlers.ops_restart_game, undefined, "T2 needs the ops token and the local listener");
  assert.deepEqual(calls, [], "a refused action must not reach ops-helper");

  // Ops token on the local listener: the handler exists and forwards.
  const local = opsServer({
    authLevel: "ops",
    allowT2: true,
    ops: {
      call: async (a, params) => {
        calls.push(a);
        return { ok: true, action: a, tier: "T2", impl: "restart_game", code: null, message: null, result: params, audit_id: "z" };
      },
    },
  });
  const ok = await local.handlers.ops_restart_game.handler({ service: "lobby", confirm: true, delay_seconds: 60 });
  assert.equal(parse(ok).ok, true);
  assert.deepEqual(calls, ["ops_restart_game"]);
});

test("an ops tool forwards the call and merges the helper answer", async () => {
  const calls = [];
  const server = fakeServer();
  registerOpsTools(server, {
    cfg: baseCfg(),
    ops: {
      call: async (action, params, opts) => {
        calls.push({ action, params, opts });
        return { ok: true, action, tier: "T0", impl: "status", code: null, message: null, result: { containers: [{ service: "lobby" }] }, audit_id: "a1" };
      },
    },
  });
  const out = parse(await server.handlers.ops_status.handler({}));
  assert.equal(out.ok, true);
  assert.equal(out.tier, "T0");
  assert.equal(out.audit_id, "a1");
  assert.equal(out.result.containers[0].service, "lobby");
  assert.deepEqual(calls[0].action, "ops_status");
  assert.ok(calls[0].opts.timeoutSeconds > 0);
});

test("a refused ops action comes back as isError with the helper's code", async () => {
  const server = opsServer({
    ops: {
      call: async () => ({
        ok: false,
        action: "ops_restart_game",
        tier: "T2",
        impl: "restart_game",
        code: "not_armed",
        message: "not armed",
        result: null,
        audit_id: "a2",
      }),
    },
  });
  const out = await server.handlers.ops_restart_game.handler({ service: "lobby", confirm: true, delay_seconds: 60 });
  assert.equal(out.isError, true);
  const payload = parse(out);
  assert.equal(payload.ok, false);
  assert.equal(payload.code, "not_armed");
  assert.equal(payload.audit_id, "a2");
});

test("the T2 parameters are forwarded unchanged", async () => {
  const calls = [];
  const server = opsServer({
    ops: {
      call: async (action, params, opts) => {
        calls.push({ action, params, opts });
        return { ok: true, tier: "T2", result: {}, audit_id: "a3" };
      },
    },
  });
  await server.handlers.ops_deploy_plugin.handler({ artifact_sha256: "f".repeat(64), confirm: true, delay_seconds: 120 });
  assert.deepEqual(calls[0].params, { artifact_sha256: "f".repeat(64), confirm: true, delay_seconds: 120 });
  assert.equal(calls[0].opts.timeoutSeconds >= 1800, true, "a deploy may legitimately take minutes");

  await server.handlers.ops_logs.handler({ service: "lobby", tail: 10 });
  assert.deepEqual(calls[1].params, { service: "lobby", tail: 10 });

  await server.handlers.ops_logs.handler({ service: "lobby" });
  assert.deepEqual(calls[2].params, { service: "lobby" }, "tail must stay optional");
});

test("secrets in the helper's answer are redacted on the way out", async () => {
  const server = fakeServer();
  registerOpsTools(server, {
    cfg: baseCfg(),
    ops: {
      call: async () => ({
        ok: true,
        tier: "T0",
        result: { lines: ["Authorization: Bearer secrettokenvalue1234", "OPS_HELPER_TOKEN=deadbeefdeadbeefdeadbeefdeadbeef"] },
        audit_id: "a4",
      }),
    },
  });
  const text = (await server.handlers.ops_logs.handler({ service: "lobby" })).content[0].text;
  assert.ok(!text.includes("secrettokenvalue1234"));
  assert.ok(!text.includes("deadbeefdeadbeefdeadbeefdeadbeef"));
  assert.ok(text.includes("[redacted]"));
});

test("registerTools advertises the ops tools only when the helper is configured", () => {
  const withoutOps = fakeServer();
  registerTools(withoutOps, { cfg: baseCfg(), client: {}, runs: {}, store: null });
  assert.equal(withoutOps.handlers.ops_status, undefined);

  const withOps = fakeServer();
  registerTools(withOps, {
    cfg: baseCfg({ opsHelperUrl: "http://ops-helper:8766", opsHelperToken: TOKEN }),
    client: {},
    runs: {},
    store: null,
  });
  assert.ok(withOps.handlers.ops_status, "ops_status must be advertised once the helper is configured");
  assert.ok(withOps.handlers.cancel_run, "the run tools stay untouched");
  // Without a level/listener the caller may only see the read-only tools.
  for (const name of T1_TOOLS.concat(T2_TOOLS)) {
    assert.equal(withOps.handlers[name], undefined, `${name} must not be advertised by default`);
  }

  const elevated = fakeServer();
  registerTools(elevated, {
    cfg: baseCfg({ opsHelperUrl: "http://ops-helper:8766", opsHelperToken: TOKEN }),
    client: {},
    runs: {},
    store: null,
    authLevel: "ops",
    allowT2: true,
  });
  for (const name of OPS_TOOLS) assert.ok(elevated.handlers[name], `${name} must be advertised at the local ops level`);
});

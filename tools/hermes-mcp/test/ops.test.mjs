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

test("registerOpsTools exposes the phase-1 tools and marks the read-only ones", () => {
  const server = fakeServer();
  registerOpsTools(server, { cfg: baseCfg(), ops: { call: async () => ({ ok: true, result: {} }) } });
  for (const name of OPS_TOOLS) assert.ok(server.handlers[name], `${name} must be registered`);
  for (const name of ["ops_status", "ops_logs", "ops_disk", "ops_backups_list", "ops_players", "ops_git_state", "ops_build_plugin", "ops_run_tests"]) {
    assert.equal(server.handlers[name].def.annotations.readOnlyHint, true, `${name} is read-only`);
  }
  for (const name of ["ops_restart_game", "ops_deploy_plugin", "ops_rollback_plugin"]) {
    assert.equal(server.handlers[name].def.annotations.destructiveHint, true, `${name} is destructive`);
  }
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
  const server = fakeServer();
  registerOpsTools(server, {
    cfg: baseCfg(),
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
  const server = fakeServer();
  registerOpsTools(server, {
    cfg: baseCfg(),
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
});

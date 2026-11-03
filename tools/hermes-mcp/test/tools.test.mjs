import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { registerTools } from "../dist/tools.js";
import { RunStore } from "../dist/store.js";

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "hermes-mcp-tools-"));
}

function loadHandlers() {
  const handlers = {};
  const fakeServer = { registerTool: (name, _def, handler) => { handlers[name] = handler; } };
  const cfg = { gatewayUrl: "http://127.0.0.1:9", backend: "gateway", maxActiveRunsPerProfile: 2, allowedModels: [], allowedProviders: [], allowedReasoningEfforts: [] };
  const deps = { cfg, client: {}, runs: {}, store: null };
  return { handlers, deps, fakeServer, register: () => registerTools(fakeServer, deps) };
}

function parse(result) {
  return JSON.parse(result.content[0].text);
}

function baseRecord(overrides = {}) {
  const now = new Date().toISOString();
  return {
    run_id: "run_test",
    profile: "minecraft",
    backend: "gateway",
    status: "completed",
    created_at: now,
    updated_at: now,
    prompt_preview: "p",
    prompt_chars: 1,
    tool_calls: 0,
    event_count: 0,
    ...overrides,
  };
}

test("get_run returns the output exactly once, hard-capped", async () => {
  const { handlers, deps, register } = loadHandlers();
  const store = new RunStore(tmpDir());
  deps.store = store;
  const long = "X".repeat(3000);
  store.create(baseRecord({ output: long }));
  register();
  const payload = parse(await handlers.get_run({ run_id: "run_test" }));
  assert.equal(payload.output, undefined, "summarize must not repeat the output");
  assert.equal(payload.last_output.length, 1500, "single output is capped");
  assert.equal(payload.output_truncated, true);
  // "output" as a key must not appear twice in the serialized response.
  const text = (await handlers.get_run({ run_id: "run_test" })).content[0].text;
  const occurrences = text.split('"output"').length - 1;
  assert.equal(occurrences, 0, "no bare output key");
});

test("get_run and get_run_logs redact secrets in stored output and events", async () => {
  const { handlers, deps, register } = loadHandlers();
  const store = new RunStore(tmpDir());
  deps.store = store;
  store.create(baseRecord({ output: "leaked Bearer secrettokenvalue1234 tail" }));
  store.appendEvent("run_test", { event: "tool.started", detail: "HERMES_MCP_TOKEN=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef" });
  register();

  const run = parse(await handlers.get_run({ run_id: "run_test" }));
  assert.ok(run.last_output.includes("Bearer [redacted]"), "bearer token scrubbed from output");
  assert.ok(!run.last_output.includes("secrettokenvalue1234"));

  const logs = parse(await handlers.get_run_logs({ run_id: "run_test", tail: 10 }));
  const joined = JSON.stringify(logs.lines);
  assert.ok(joined.includes("HERMES_MCP_TOKEN=[redacted]"), "env-style secret scrubbed from events");
  assert.ok(!joined.includes("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"));
});

test("store.update redacts secrets before persisting", () => {
  const store = new RunStore(tmpDir());
  store.create(baseRecord({ output: "clean" }));
  store.update("run_test", { error: "failed with GH_TOKEN=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" });
  assert.ok(!store.get("run_test").error.includes("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
  assert.ok(store.get("run_test").error.includes("[redacted]"));
});

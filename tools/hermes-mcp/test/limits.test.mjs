import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { loadConfig } from "../dist/config.js";
import { RunManager, startOfUtcDay } from "../dist/runner.js";
import { RunStore } from "../dist/store.js";

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "hermes-mcp-test-"));
}

function makeConfig(overrides = {}) {
  return {
    port: 8765,
    host: "127.0.0.1",
    dataDir: tmpDir(),
    tokens: ["x".repeat(32)],
    publicUrl: "https://example.invalid",
    gatewayUrl: "http://127.0.0.1:9",
    profileKeys: { minecraft: "k".repeat(32) },
    allowedHosts: ["localhost"],
    maxActiveRunsPerProfile: 2,
    maxRunsPerDay: 50,
    pollIntervalMs: 1000,
    maxRunSeconds: 7200,
    approvalWaitSeconds: 900,
    unknownTimeoutSeconds: 7200,
    trustedProxies: [],
    lockoutAfter: 5,
    lockoutMaxSeconds: 300,
    allowedModels: ["deepseek/deepseek-v4.1-flash", "anthropic/claude-haiku-5.5"],
    allowedProviders: ["openrouter", "anthropic"],
    allowedReasoningEfforts: ["minimal", "low", "medium", "high"],
    backend: "gateway",
    cliCommand: "hermes",
    ...overrides,
  };
}

function stubClient(overrides = {}) {
  return {
    startRun: async () => ({ run_id: `run_${Math.random().toString(36).slice(2)}` }),
    getRun: async () => ({ run_id: "x", status: "completed" }),
    stopRun: async () => ({}),
    openEvents: async () => {
      throw new Error("no event stream in tests");
    },
    ...overrides,
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

test("loadConfig fails closed with allowlist defaults and no trusted proxy", () => {
  const saved = { ...process.env };
  process.env.HERMES_MCP_TOKEN = "a".repeat(32);
  process.env.HERMES_PROFILE_KEYS = JSON.stringify({ minecraft: "b".repeat(32) });
  for (const k of [
    "HERMES_MCP_ALLOWED_MODELS",
    "HERMES_MCP_ALLOWED_PROVIDERS",
    "HERMES_MCP_ALLOWED_REASONING_EFFORTS",
    "HERMES_MCP_TRUSTED_PROXIES",
    "HERMES_MCP_MAX_RUNS_PER_DAY",
    "HERMES_MCP_UNKNOWN_TIMEOUT_SECONDS",
    "HERMES_MCP_APPROVAL_WAIT_SECONDS",
  ]) {
    delete process.env[k];
  }
  try {
    const cfg = loadConfig();
    assert.deepEqual(cfg.allowedModels, ["deepseek/deepseek-v4.1-flash", "anthropic/claude-haiku-5.5"]);
    assert.deepEqual(cfg.allowedProviders, ["openrouter", "anthropic"]);
    assert.deepEqual(cfg.allowedReasoningEfforts, ["minimal", "low", "medium", "high"]);
    assert.deepEqual(cfg.trustedProxies, []);
    assert.equal(cfg.maxRunsPerDay, 50);
    assert.equal(cfg.unknownTimeoutSeconds, 7200);
    assert.equal(cfg.approvalWaitSeconds, 900);
  } finally {
    for (const k of Object.keys(process.env)) if (!(k in saved)) delete process.env[k];
    for (const [k, v] of Object.entries(saved)) process.env[k] = v;
  }
});

test("startOfUtcDay returns midnight UTC", () => {
  const iso = startOfUtcDay(new Date("2026-11-03T13:37:00.000Z"));
  assert.equal(iso, "2026-11-03T00:00:00.000Z");
});

test("start_run rejects a model/provider/effort outside the allowlist", async () => {
  const cfg = makeConfig();
  const runs = new RunManager(cfg, stubClient(), new RunStore(cfg.dataDir));
  await assert.rejects(
    () => runs.start("minecraft", { prompt: "hi", model: "openai/gpt-4o" }),
    (e) => e.code === "model_not_allowed",
  );
  await assert.rejects(
    () => runs.start("minecraft", { prompt: "hi", provider: "azure" }),
    (e) => e.code === "provider_not_allowed",
  );
  await assert.rejects(
    () => runs.start("minecraft", { prompt: "hi", reasoning_effort: "xhigh" }),
    (e) => e.code === "reasoning_effort_not_allowed",
  );
  const ok = await runs.start("minecraft", { prompt: "hi", model: "deepseek/deepseek-v4.1-flash" });
  assert.equal(ok.status, "queued");
});

test("start_run enforces the daily budget", async () => {
  const cfg = makeConfig({ maxRunsPerDay: 1, maxActiveRunsPerProfile: 10 });
  const runs = new RunManager(cfg, stubClient(), new RunStore(cfg.dataDir));
  await runs.start("minecraft", { prompt: "first" });
  await assert.rejects(
    () => runs.start("minecraft", { prompt: "second" }),
    (e) => e.code === "daily_run_limit",
  );
});

test("concurrent start_run cannot double-book a profile slot (race)", async () => {
  const cfg = makeConfig({ maxActiveRunsPerProfile: 1 });
  const slowClient = stubClient({ startRun: async () => { await sleep(80); return { run_id: `run_${Math.random().toString(36).slice(2)}` }; } });
  const runs = new RunManager(cfg, slowClient, new RunStore(cfg.dataDir));
  const results = await Promise.allSettled([
    runs.start("minecraft", { prompt: "a" }),
    runs.start("minecraft", { prompt: "b" }),
  ]);
  const rejected = results.filter((r) => r.status === "rejected");
  assert.equal(rejected.length, 1, "exactly one of the two concurrent starts is rejected");
  assert.equal(rejected[0].reason.code, "too_many_active_runs");
});

test("a failed start releases its reservation", async () => {
  const cfg = makeConfig({ maxActiveRunsPerProfile: 1 });
  const failing = stubClient({ startRun: async () => { throw new Error("gateway down"); } });
  const runs = new RunManager(cfg, failing, new RunStore(cfg.dataDir));
  await assert.rejects(() => runs.start("minecraft", { prompt: "a" }));
  assert.equal(runs.activeCount("minecraft"), 0, "reservation freed after failure");
});

test("unknown runs keep counting as active until swept to timed_out", async () => {
  const cfg = makeConfig({ maxActiveRunsPerProfile: 1, unknownTimeoutSeconds: 60 });
  const store = new RunStore(cfg.dataDir);
  const old = new Date(Date.now() - 120_000).toISOString();
  store.create({
    run_id: "run_unknown",
    profile: "minecraft",
    backend: "gateway",
    status: "unknown",
    created_at: old,
    updated_at: old,
    prompt_preview: "p",
    prompt_chars: 1,
    tool_calls: 0,
    event_count: 0,
  });
  const runs = new RunManager(cfg, stubClient(), store);
  assert.equal(runs.activeCount("minecraft"), 1, "unknown counts against the slot");
  await assert.rejects(
    () => runs.start("minecraft", { prompt: "blocked" }),
    (e) => e.code === "too_many_active_runs",
  );
  assert.equal(runs.sweepUnknown(), 1);
  assert.equal(store.get("run_unknown").status, "timed_out");
  assert.equal(runs.activeCount("minecraft"), 0, "slot freed after the sweep");
});

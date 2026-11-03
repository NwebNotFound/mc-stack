import assert from "node:assert/strict";
import { test } from "node:test";
import { handleOp } from "../dist/actions.js";
import { dockerRunner, makeDeps } from "./helpers.mjs";

/**
 * Injection attempts. Every one of them must be refused by the parameter
 * validation, and the fake runner proves that no docker argv was built at all.
 */
const PAYLOADS = [
  "bot; rm -rf /",
  "bot && curl http://evil",
  "lobby|tee /etc/passwd",
  "$(id)",
  "`id`",
  "../../etc/passwd",
  "/etc/passwd",
  "lobby\n--force",
  "lobby --privileged",
  "--filter=label=x",
  "lobby; docker compose down -v",
];

/** Services that exist in the stack but must never be mutated by an ops action. */
const PROTECTED_SERVICES = ["craftcore-db", "hermes-mcp", "ops-helper", "hermes-test"];

test("a malicious service parameter is refused before any docker call", async () => {
  for (const payload of PAYLOADS) {
    const deps = makeDeps();
    const out = await handleOp("ops_logs", { service: payload }, deps);
    assert.equal(out.ok, false, `${payload} must be refused`);
    assert.equal(out.code, "invalid_params");
    assert.equal(deps.runner.calls.length, 0, `${payload} must not reach docker`);
  }
});

test("the same payloads are refused for restart, players and status filters", async () => {
  for (const payload of PAYLOADS) {
    const deps = makeDeps({ cfg: { armed: true } });
    const restart = await handleOp("ops_restart_service", { service: payload, confirm: true }, deps);
    assert.equal(restart.code, "invalid_params", `restart must refuse ${payload}`);
    const game = await handleOp("ops_restart_game", { service: payload, confirm: true, delay_seconds: 60 }, deps);
    assert.equal(game.code, "invalid_params", `restart_game must refuse ${payload}`);
    const players = await handleOp("ops_players", { service: payload }, deps);
    assert.equal(players.code, "invalid_params", `players must refuse ${payload}`);
    const status = await handleOp("ops_status", { service: payload }, deps);
    assert.equal(status.code, "invalid_params", `status must refuse ${payload}`);
    assert.equal(deps.runner.calls.length, 0, `${payload} must not reach docker`);
  }
});

test("the database, hermes-mcp, ops-helper and hermes-test are never mutated", async () => {
  for (const service of PROTECTED_SERVICES) {
    const deps = makeDeps({ cfg: { armed: true } });
    const restart = await handleOp("ops_restart_service", { service, confirm: true }, deps);
    assert.equal(restart.code, "invalid_params", `${service} must not be restartable via T1`);
    const game = await handleOp("ops_restart_game", { service, confirm: true, delay_seconds: 60 }, deps);
    assert.equal(game.code, "invalid_params", `${service} must not be restartable via T2`);
    assert.equal(deps.runner.calls.length, 0, `${service} must not reach docker`);
  }
  // Reading their logs / status stays allowed: that is what monitoring is for.
  const readOnly = makeDeps();
  assert.equal((await handleOp("ops_status", { service: "craftcore-db" }, readOnly)).ok, true);
  assert.equal((await handleOp("ops_logs", { service: "craftcore-db" }, readOnly)).ok, true);
});

test("unknown parameters (a smuggled argv or command) are refused", async () => {
  const cases = [
    ["ops_status", { argv: ["rm", "-rf", "/"] }],
    ["ops_logs", { service: "lobby", command: "cat /etc/shadow" }],
    ["ops_restart_service", { service: "bot", confirm: true, force: true }],
    ["ops_build_plugin", { image: "alpine", command: ["sh", "-c", "id"] }],
    ["ops_run_tests", { target: "hermes-mcp", args: ["; rm -rf /"] }],
    ["ops_deploy_plugin", { confirm: true, delay_seconds: 60, artifact_sha256: "a".repeat(64), service: "velocity" }],
    ["ops_rollback_plugin", { confirm: true, delay_seconds: 60, jar: "/etc/passwd" }],
  ];
  for (const [action, params] of cases) {
    const deps = makeDeps({ cfg: { armed: true } });
    const out = await handleOp(action, params, deps);
    assert.equal(out.ok, false, `${action} must refuse ${JSON.stringify(params)}`);
    assert.equal(out.code, "invalid_params");
    assert.match(out.message, /unknown parameter|must be/);
    assert.equal(deps.runner.calls.length, 0, `${action} must not reach docker`);
  }
});

test("path traversal in the artifact hash and the test target is refused", async () => {
  const deps = makeDeps({ cfg: { armed: true } });
  const traversal = await handleOp(
    "ops_deploy_plugin",
    { confirm: true, delay_seconds: 60, artifact_sha256: "../../etc/passwd" },
    deps,
  );
  assert.equal(traversal.code, "invalid_params");

  const target = await handleOp("ops_run_tests", { target: "../../../etc/passwd" }, deps);
  assert.equal(target.code, "invalid_params");

  const target2 = await handleOp("ops_run_tests", { target: "constructor" }, deps);
  assert.equal(target2.code, "invalid_params");
  assert.equal(deps.runner.calls.length, 0);
});

test("unknown or prototype-shaped action names are refused", async () => {
  for (const action of ["ops_exec", "exec", "", "__proto__", "constructor", "toString", "ops_status "]) {
    const deps = makeDeps();
    const out = await handleOp(action, {}, deps);
    assert.equal(out.ok, false);
    assert.equal(out.code, "unknown_action", `${action} must be unknown`);
    assert.equal(deps.runner.calls.length, 0);
  }
});

test("the argv of an allowed call contains only plain tokens", async () => {
  const deps = makeDeps({
    runner: dockerRunner({
      ps: JSON.stringify({ Names: "mc-stack-lobby-1", State: "running", Status: "Up 1 hour (healthy)", Labels: "com.docker.compose.service=lobby" }),
    }),
  });
  const out = await handleOp("ops_logs", { service: "lobby", tail: 10 }, deps);
  assert.equal(out.ok, true);
  for (const call of deps.runner.calls) {
    for (const token of call.argv) {
      assert.equal(typeof token, "string");
      assert.ok(!/[\s;|&$`><\n]/.test(token), `argv token must be a plain token: ${JSON.stringify(token)}`);
    }
  }
});

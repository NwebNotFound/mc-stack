import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { KNOWN_IMPLS, loadAllowlist, parseAllowlist } from "../dist/allowlist.js";
import { ALLOWLIST_PATH } from "./helpers.mjs";

/** The raw JSON, so a test can mutate one field and expect a refusal. */
function rawAllowlist() {
  return JSON.parse(readFileSync(ALLOWLIST_PATH, "utf8"));
}

test("the shipped allowlist parses and covers the phase-1 actions", () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  assert.equal(allow.version, 1);
  assert.equal(allow.composeProject, "mc-stack");
  for (const name of [
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
  ]) {
    assert.ok(allow.actions[name], `${name} must be in the allowlist`);
  }
  assert.equal(allow.actions.ops_status.tier, "T0");
  assert.equal(allow.actions.ops_restart_service.tier, "T1");
  assert.equal(allow.actions.ops_restart_game.tier, "T2");
  assert.equal(allow.actions.ops_deploy_plugin.tier, "T2");
  assert.equal(allow.tiers.T2.armed_required, true);
  assert.equal(allow.tiers.T2.confirm_required, true);
  assert.ok(allow.tiers.T2.min_delay_seconds >= 60);
  assert.ok(allow.forbiddenServices.includes("craftcore-db"));
});

test("every impl in the allowlist is implemented in code", () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  for (const action of Object.values(allow.actions)) {
    assert.ok(KNOWN_IMPLS.includes(action.impl), `${action.impl} is unknown`);
  }
});

test("the shipped allowlist pauses every action the filter cannot serve, with a reason", () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  const paused = [
    "ops_players",
    "ops_build_plugin",
    "ops_run_tests",
    "ops_backup_now",
    "ops_restart_game",
    "ops_deploy_plugin",
    "ops_rollback_plugin",
  ];
  for (const name of paused) {
    assert.equal(allow.actions[name].enabled, false, `${name} must be disabled`);
    assert.ok(allow.actions[name].disabledReason, `${name} must carry a disabled_reason`);
  }
  // The read-only tools plus the T1 restart are the whole live surface: the
  // docker-socket-filter can serve exactly those.
  for (const name of ["ops_status", "ops_logs", "ops_disk", "ops_backups_list", "ops_git_state", "ops_restart_service"]) {
    assert.equal(allow.actions[name].enabled, true, `${name} must stay enabled`);
  }
  assert.equal(allow.actions.ops_restart_service.tier, "T1");
});

test("a disabled action without a disabled_reason is refused at load time", () => {
  const raw = rawAllowlist();
  raw.actions.ops_build_plugin = { tier: "T0", impl: "build_plugin", enabled: false };
  assert.throws(() => parseAllowlist(raw), /disabled_reason is required/);

  const raw2 = rawAllowlist();
  raw2.actions.ops_build_plugin.disabled_reason = "";
  assert.throws(() => parseAllowlist(raw2), /disabled_reason/);
});

test("an unknown impl is refused at load time", () => {
  const raw = rawAllowlist();
  raw.actions.ops_status.impl = "exec";
  assert.throws(() => parseAllowlist(raw), /impl "exec" is not implemented/);
});

test("a mutating list may never name a forbidden service", () => {
  const raw = rawAllowlist();
  raw.services.restart_game = ["lobby", "craftcore-db"];
  assert.throws(() => parseAllowlist(raw), /forbidden service "craftcore-db"/);

  const raw2 = rawAllowlist();
  raw2.services.restart_t1 = ["hermes-mcp"];
  assert.throws(() => parseAllowlist(raw2), /forbidden service "hermes-mcp"/);

  const raw3 = rawAllowlist();
  raw3.services.deploy_plugin = ["lobby", "ops-helper"];
  assert.throws(() => parseAllowlist(raw3), /forbidden service "ops-helper"/);
});

test("service names are validated (no shell metacharacters, no traversal)", () => {
  for (const bad of ["bot; rm -rf /", "../../etc", "lobby --force", "lobby$(id)", "lobby|x", "Lobby", ""]) {
    const raw = rawAllowlist();
    raw.services.observe = ["velocity", bad];
    assert.throws(() => parseAllowlist(raw), /unexpected shape|non-empty string/, `must refuse ${JSON.stringify(bad)}`);
  }
});

test("build sources and paths must stay inside the repo", () => {
  const raw = rawAllowlist();
  raw.build.plugin.source = "../../etc";
  assert.throws(() => parseAllowlist(raw), /repo-relative path without "\.\."/);

  const raw2 = rawAllowlist();
  raw2.paths.repo = "repo";
  assert.throws(() => parseAllowlist(raw2), /paths\.repo must be absolute/);

  const raw3 = rawAllowlist();
  raw3.paths.plugin_jar = "../evil.jar";
  assert.throws(() => parseAllowlist(raw3), /unexpected shape/);

  const raw4 = rawAllowlist();
  raw4.deploy.plugin_dirs.lobby = "/plugins/../../etc";
  assert.throws(() => parseAllowlist(raw4), /must not contain "\.\."/);
});

test("argv tokens in the allowlist may not carry shell metacharacters", () => {
  for (const bad of ["build;rm -rf /", "build && id", "clean|tee", "clean$(id)", "clean > /etc/passwd", "clean`id`", "clean\nrm"]) {
    const raw = rawAllowlist();
    raw.build.plugin.command = ["gradle", bad];
    assert.throws(() => parseAllowlist(raw), /unexpected shape/, `must refuse ${JSON.stringify(bad)}`);
  }
  const raw = rawAllowlist();
  raw.build.tests.craftcore.setup = ["npm ci; curl http://evil"];
  assert.throws(() => parseAllowlist(raw), /unexpected shape/);
});

test("T0 must stay ungated and T2 must stay armed", () => {
  const raw = rawAllowlist();
  raw.tiers.T0.confirm_required = true;
  assert.throws(() => parseAllowlist(raw), /T0 must stay ungated/);

  const raw2 = rawAllowlist();
  raw2.tiers.T2.armed_required = false;
  assert.throws(() => parseAllowlist(raw2), /T2 must require the global armed switch/);

  const raw3 = rawAllowlist();
  delete raw3.tiers.T2.min_delay_seconds;
  assert.throws(() => parseAllowlist(raw3), /min_delay_seconds/);
});

test("deploy order and plugin dirs must agree", () => {
  const raw = rawAllowlist();
  raw.deploy.order = ["lobby", "survival", "pvp"];
  assert.throws(() => parseAllowlist(raw), /not in services\.deploy_plugin/);

  const raw2 = rawAllowlist();
  delete raw2.deploy.plugin_dirs.survival;
  assert.throws(() => parseAllowlist(raw2), /plugin_dirs is missing "survival"/);

  const raw3 = rawAllowlist();
  raw3.deploy.order = [];
  assert.throws(() => parseAllowlist(raw3), /deploy\.order must not be empty/);
});

test("malformed limits and images are refused", () => {
  const raw = rawAllowlist();
  raw.limits.max_log_lines = 0;
  assert.throws(() => parseAllowlist(raw), /max_log_lines/);

  const raw2 = rawAllowlist();
  raw2.build.plugin.image = "gradle:9-jdk; rm -rf /";
  assert.throws(() => parseAllowlist(raw2), /unexpected shape/);

  const raw3 = rawAllowlist();
  raw3.version = 2;
  assert.throws(() => parseAllowlist(raw3), /version/);

  const raw4 = rawAllowlist();
  raw4.services.observe = ["lobby", "lobby"];
  assert.throws(() => parseAllowlist(raw4), /duplicates/);
});

test("an action that is not in the allowlist cannot be dispatched", async () => {
  const { actionOf } = await import("../dist/allowlist.js");
  const allow = loadAllowlist(ALLOWLIST_PATH);
  assert.equal(actionOf(allow, "ops_exec"), undefined);
  assert.equal(actionOf(allow, "constructor"), undefined);
  assert.equal(actionOf(allow, "ops_status")?.tier, "T0");
});

test("build containers are offline by default and always carry resource caps", () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  assert.equal(allow.build.network, "none");
  assert.ok(Number(allow.build.limits.cpus) > 0);
  assert.ok(/^\d+[bkmg]?$/i.test(allow.build.limits.memory));
  assert.ok(allow.build.limits.pids > 0);
  assert.equal(allow.build.plugin.cache, "gradle");
  assert.ok(allow.build.plugin.command.includes("--offline"));

  // `host`/`bridge` network and a missing limits block must be refused.
  const hostNetwork = rawAllowlist();
  hostNetwork.build.network = "host";
  assert.throws(() => parseAllowlist(hostNetwork), /build\.network/);

  const noLimits = rawAllowlist();
  delete noLimits.build.limits;
  assert.throws(() => parseAllowlist(noLimits), /build\.limits/);

  const unlimitedCpus = rawAllowlist();
  unlimitedCpus.build.limits.cpus = 64;
  assert.throws(() => parseAllowlist(unlimitedCpus), /cpus/);

  const badCache = rawAllowlist();
  badCache.build.plugin.cache = "host";
  assert.throws(() => parseAllowlist(badCache), /cache/);
});

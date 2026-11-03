import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { test } from "node:test";
import { handleOp } from "../dist/actions.js";
import { ALLOWLIST_PATH, CONTAINER_ID, CONTAINER_NAME, advancingClock, dockerRunner, enabledAllowlist, makeDeps, tmpDir } from "./helpers.mjs";
import { loadAllowlist } from "../dist/allowlist.js";

const REPO_ROOT = new URL("../../..", import.meta.url).pathname.replace(/\/$/, "");

function psJson(service, status, state = "running") {
  return JSON.stringify({
    Names: `mc-stack-${service}-1`,
    Image: "itzg/minecraft-server:latest",
    State: state,
    Status: status,
    Labels: `com.docker.compose.project=mc-stack,com.docker.compose.service=${service}`,
  });
}

test("ops_status parses docker ps and marks known services", async () => {
  const deps = makeDeps({
    runner: dockerRunner({
      ps: [psJson("lobby", "Up 3 hours (healthy)"), psJson("pvp", "Exited (0) 2 hours ago", "exited")].join("\n"),
    }),
  });
  const out = await handleOp("ops_status", {}, deps);
  assert.equal(out.ok, true);
  assert.equal(out.tier, "T0");
  assert.equal(out.result.containers.length, 2);
  assert.deepEqual(out.result.containers[0].service, "lobby");
  assert.equal(out.result.containers[0].health, "healthy");
  assert.equal(out.result.containers[1].state, "exited");
  assert.equal(out.result.counts.running, 1);
  assert.equal(out.result.counts.unhealthy.length, 0);
});

test("ops_logs redacts secrets and caps the tail", async () => {
  const deps = makeDeps({
    runner: dockerRunner({
      logs: [
        "2026-11-03T10:00:00Z [INFO] starting",
        "2026-11-03T10:00:01Z [INFO] HERMES_MCP_TOKEN=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
      ].join("\n"),
    }),
  });
  const out = await handleOp("ops_logs", { service: "survival", tail: 2 }, deps);
  assert.equal(out.ok, true);
  assert.equal(out.result.service, "survival");
  assert.equal(out.result.lines.length, 2);
  assert.equal(out.result.redacted, true);
  assert.ok(!JSON.stringify(out).includes("deadbeef"));
  assert.match(out.result.lines[1], /HERMES_MCP_TOKEN=\[redacted\]/);
});

test("ops_logs refuses an unknown service and an oversized tail", async () => {
  const deps = makeDeps();
  const unknown = await handleOp("ops_logs", { service: "not-a-service" }, deps);
  assert.equal(unknown.ok, false);
  assert.equal(unknown.code, "invalid_params");

  const tooBig = await handleOp("ops_logs", { service: "lobby", tail: 5000 }, deps);
  assert.equal(tooBig.code, "invalid_params");
  assert.equal(deps.runner.calls.length, 0, "a refused call must not reach docker");
});

test("ops_players parses the rcon list output", async () => {
  const deps = makeDeps({
    runner: dockerRunner({
      exec: { ok: true, stdout: "There are 2 of a max of 20 players online: Alice, Bob", stderr: "" },
    }),
  });
  const out = await handleOp("ops_players", { service: "lobby" }, deps);
  assert.equal(out.ok, true);
  assert.equal(out.result.online, 2);
  assert.equal(out.result.max, 20);
  assert.deepEqual(out.result.players, ["Alice", "Bob"]);
  assert.deepEqual(deps.runner.find((argv) => argv[1] === "exec").argv.slice(1, 4), ["exec", CONTAINER_ID, "rcon-cli"]);
});

test("ops_players reports a failed rcon instead of pretending", async () => {
  const deps = makeDeps({ runner: dockerRunner({ exec: { ok: false, stdout: "", stderr: "rcon-cli: not found" } }) });
  const out = await handleOp("ops_players", { service: "lobby" }, deps);
  assert.equal(out.ok, false);
  assert.equal(out.code, "rcon_failed");
});

test("ops_restart_service is T1: confirm, allowlist, cooldown", async () => {
  const deps = makeDeps();
  const noConfirm = await handleOp("ops_restart_service", { service: "bot" }, deps);
  assert.equal(noConfirm.code, "confirmation_required");

  const forbidden = await handleOp("ops_restart_service", { service: "craftcore-db", confirm: true }, deps);
  assert.equal(forbidden.code, "invalid_params");
  assert.match(forbidden.message, /not allowed here/);

  const ok = await handleOp("ops_restart_service", { service: "bot", confirm: true }, deps);
  assert.equal(ok.ok, true);
  assert.equal(ok.result.service, "bot");
  assert.equal(ok.result.health, "healthy");
  assert.ok(deps.runner.find((argv) => argv[1] === "restart"));

  const again = await handleOp("ops_restart_service", { service: "bot", confirm: true }, deps);
  assert.equal(again.code, "cooldown_active");
  assert.equal(again.result.retry_after_seconds, 300);
});

test("ops_restart_game (T2) is refused while OPS_ARMED is false and runs nothing", async () => {
  const deps = makeDeps();
  const out = await handleOp("ops_restart_game", { service: "lobby", confirm: true, delay_seconds: 60 }, deps);
  assert.equal(out.ok, false);
  assert.equal(out.code, "not_armed");
  assert.equal(deps.runner.calls.length, 0, "an unarmed T2 must not touch docker at all");
});

test("ops_restart_game (T2) restarts and reports a missing announcement honestly", async () => {
  const deps = makeDeps({ cfg: { armed: true } });
  const out = await handleOp("ops_restart_game", { service: "survival", confirm: true, delay_seconds: 90 }, deps);
  assert.equal(out.ok, true);
  assert.equal(out.result.service, "survival");
  assert.equal(out.result.delay_seconds, 90);
  assert.equal(out.result.announcement.announced, false);
  assert.match(out.result.announcement.detail, /NO in-game announcement/);
  assert.ok(deps.sleeps.includes(90_000), "the lead time must be awaited");
  assert.ok(deps.runner.find((argv) => argv[1] === "restart"));
});

test("the kill switch stops T1 but keeps T0 working", async () => {
  const dir = tmpDir();
  writeFileSync(`${dir}/DISABLED`, "");
  const deps = makeDeps({ dataDir: dir });
  const t0 = await handleOp("ops_status", {}, deps);
  assert.equal(t0.ok, true);
  const t1 = await handleOp("ops_restart_service", { service: "bot", confirm: true }, deps);
  assert.equal(t1.code, "kill_switch_active");
  assert.equal(deps.runner.find((argv) => argv[1] === "restart"), undefined);
});

test("every call - allowed or refused - lands in the audit log", async () => {
  const deps = makeDeps();
  await handleOp("ops_status", {}, deps);
  await handleOp("ops_restart_game", { service: "lobby", confirm: true, delay_seconds: 60 }, deps);
  await handleOp("ops_exec", { command: "rm -rf /" }, deps);
  const lines = readFileSync(deps.audit.path(), "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  assert.equal(lines.length, 3);
  assert.equal(lines[0].decision, "allow");
  assert.equal(lines[1].decision, "deny");
  assert.equal(lines[1].code, "not_armed");
  assert.equal(lines[2].code, "unknown_action");
  assert.equal(lines[2].action, "ops_exec");
});

test("a non-object params payload is refused", async () => {
  const deps = makeDeps();
  const out = await handleOp("ops_status", "lobby; rm -rf /", deps);
  assert.equal(out.code, "invalid_params");
  assert.equal(deps.runner.calls.length, 0);
});

test("ops_run_tests runs the fixed argv and reads the node test summary", async () => {
  const repoAllow = enabledAllowlist();
  repoAllow.paths.repo = REPO_ROOT;
  const deps = makeDeps({
    allow: repoAllow,
    runner: dockerRunner({}),
  });
  deps.runner.responses.unshift({
    match: (argv) => argv[1] === "run",
    reply: { stdout: "# tests 25\n# pass 25\n# fail 0\n" },
  });
  const out = await handleOp("ops_run_tests", { target: "hermes-mcp" }, deps);
  assert.equal(out.ok, true);
  assert.deepEqual(out.result.tests, { tests: 25, failures: 0, errors: 0, skipped: 0 });
  const runs = deps.runner.calls.filter((call) => call.argv[1] === "run");
  assert.equal(runs.length, 2, "setup (npm ci) then the test command");
  assert.deepEqual(runs[0].argv.slice(-5), ["npm", "ci", "--no-audit", "--no-fund", "--offline"]);
  assert.deepEqual(runs[1].argv.slice(-2), ["npm", "test"]);
  assert.ok(runs[0].argv.includes("--cap-drop"));
  assert.ok(runs[0].argv.includes("no-new-privileges"));
});

test("build and test containers run with --network none and hard CPU/RAM/PID caps", async () => {
  const repoAllow = enabledAllowlist();
  repoAllow.paths.repo = REPO_ROOT;
  const deps = makeDeps({ allow: repoAllow, runner: dockerRunner({}) });
  deps.runner.responses.unshift({ match: (argv) => argv[1] === "run", reply: { stdout: "# tests 1\n# pass 1\n# fail 0\n" } });

  await handleOp("ops_run_tests", { target: "ops-helper" }, deps);
  const argv = deps.runner.calls.find((call) => call.argv[1] === "run").argv;
  assert.deepEqual(argv.slice(argv.indexOf("--network"), argv.indexOf("--network") + 2), ["--network", "none"]);
  assert.deepEqual(argv.slice(argv.indexOf("--cpus"), argv.indexOf("--cpus") + 2), ["--cpus", repoAllow.build.limits.cpus]);
  assert.deepEqual(argv.slice(argv.indexOf("--memory"), argv.indexOf("--memory") + 2), ["--memory", repoAllow.build.limits.memory]);
  assert.deepEqual(argv.slice(argv.indexOf("--pids-limit"), argv.indexOf("--pids-limit") + 2), ["--pids-limit", String(repoAllow.build.limits.pids)]);
  assert.ok(argv.includes("mc-stack-ops-npm-cache:/root/.npm"), "the pre-warmed npm cache must be mounted for offline installs");
  assert.equal(argv.includes("host"), false, "a build must never share the host network");
});

test("ops_build_plugin stages the source, hashes the artifact and does not deploy", async () => {
  const repoAllow = enabledAllowlist();
  const dir = tmpDir();
  repoAllow.paths.repo = REPO_ROOT;
  const deps = makeDeps({ allow: repoAllow, dataDir: dir });
  const jarBytes = Buffer.from("PK\u0003\u0004 fake craftcore jar");
  deps.runner.responses.unshift({
    match: (argv) => argv[1] === "run",
    reply: (argv) => {
      const workdir = argv[argv.indexOf("--workdir") + 1];
      mkdirSync(`${workdir}/build/libs`, { recursive: true });
      writeFileSync(`${workdir}/build/libs/craftcore.jar`, jarBytes);
      mkdirSync(`${workdir}/build/test-results/test`, { recursive: true });
      writeFileSync(
        `${workdir}/build/test-results/test/TEST-CraftCore.xml`,
        '<testsuite name="CraftCore" tests="441" skipped="13" failures="0" errors="0"></testsuite>',
      );
      return { stdout: "BUILD SUCCESSFUL in 3m 12s" };
    },
  });

  const out = await handleOp("ops_build_plugin", {}, deps);
  assert.equal(out.ok, true, out.message);
  const expected = createHash("sha256").update(jarBytes).digest("hex");
  assert.equal(out.result.sha256, expected);
  assert.deepEqual(out.result.tests, { tests: 441, failures: 0, errors: 0, skipped: 13 });
  assert.equal(out.result.deployed, false);
  assert.ok(existsSync(out.result.artifact_path), "the artifact must be kept in the artifact volume");
  const state = JSON.parse(readFileSync(`${deps.cfg.artifactsDir}/state/last-build.json`, "utf8"));
  assert.equal(state.sha256, expected);
});

test("ops_deploy_plugin refuses without a build, without the arm switch and without a backup", async () => {
  const repoAllow = enabledAllowlist();
  const dir = tmpDir();
  repoAllow.paths.repo = REPO_ROOT;
  const mk = (over = {}) => makeDeps({ allow: repoAllow, dataDir: dir, cfg: { armed: true, ...over } });
  const params = (sha) => ({ confirm: true, delay_seconds: 60, artifact_sha256: sha });

  const noBuildDeps = mk();
  const noBuild = await handleOp("ops_deploy_plugin", params("a".repeat(64)), noBuildDeps);
  assert.equal(noBuild.code, "no_build_artifact");
  // A failed attempt still starts the cooldown: it must not be hammered either.
  const again = await handleOp("ops_deploy_plugin", params("a".repeat(64)), noBuildDeps);
  assert.equal(again.code, "cooldown_active");

  const jar = `${dir}/artifacts/craftcore-aaaa.jar`;
  mkdirSync(`${dir}/artifacts`, { recursive: true });
  writeFileSync(jar, "jar");
  const sha = createHash("sha256").update("jar").digest("hex");
  mkdirSync(`${dir}/artifacts/state`, { recursive: true });
  writeFileSync(`${dir}/artifacts/state/last-build.json`, JSON.stringify({ sha256: sha, artifact_path: jar }));

  const mismatchDeps = mk();
  const mismatch = await handleOp("ops_deploy_plugin", params("b".repeat(64)), mismatchDeps);
  assert.equal(mismatch.code, "artifact_mismatch");

  const noBackupDeps = mk();
  const noBackup = await handleOp("ops_deploy_plugin", params(sha), noBackupDeps);
  assert.equal(noBackup.code, "backup_interpreter_missing");
  assert.equal(noBackup.result.mode, "unavailable");
  assert.equal(noBackupDeps.runner.find((argv) => argv[1] === "restart"), undefined, "no restart without a backup");

  const unarmedDeps = mk({ armed: false });
  const unarmed = await handleOp("ops_deploy_plugin", params(sha), unarmedDeps);
  assert.equal(unarmed.code, "not_armed");
  assert.equal(unarmedDeps.runner.calls.length, 0);
});

test("ops_deploy_plugin rolls out with a delay, keeps a rollback jar and checks health", async () => {
  const repoAllow = enabledAllowlist();
  const dir = tmpDir();
  repoAllow.paths.repo = REPO_ROOT;
  const backups = tmpDir("ops-helper-backups-");
  mkdirSync(`${backups}/daily-20261103-120000`, { recursive: true });
  writeFileSync(`${backups}/daily-20261103-120000/COMPLETE.txt`, "ok");
  repoAllow.paths.backups = backups;
  const pluginLobby = tmpDir("ops-helper-lobby-");
  const pluginSurvival = tmpDir("ops-helper-survival-");
  writeFileSync(`${pluginLobby}/craftcore.jar`, "old-lobby-jar");
  writeFileSync(`${pluginSurvival}/craftcore.jar`, "old-survival-jar");
  repoAllow.deploy.pluginDirs = { lobby: pluginLobby, survival: pluginSurvival };

  const deps = makeDeps({ allow: repoAllow, dataDir: dir, cfg: { armed: true } });
  const jar = `${deps.cfg.artifactsDir}/artifacts/craftcore-aaaa.jar`;
  mkdirSync(`${deps.cfg.artifactsDir}/artifacts`, { recursive: true });
  writeFileSync(jar, "new-jar-bytes");
  const sha = createHash("sha256").update("new-jar-bytes").digest("hex");
  mkdirSync(`${deps.cfg.artifactsDir}/state`, { recursive: true });
  writeFileSync(`${deps.cfg.artifactsDir}/state/last-build.json`, JSON.stringify({ sha256: sha, artifact_path: jar }));

  const out = await handleOp("ops_deploy_plugin", { confirm: true, delay_seconds: 60, artifact_sha256: sha }, deps);
  assert.equal(out.ok, true, out.message);
  assert.equal(readFileSync(`${pluginLobby}/craftcore.jar`, "utf8"), "new-jar-bytes");
  assert.equal(readFileSync(`${pluginSurvival}/craftcore.jar`, "utf8"), "new-jar-bytes");
  assert.equal(out.result.steps.length, 2);
  assert.equal(out.result.steps.every((step) => step.healthy === true), true);
  assert.equal(deps.sleeps.filter((ms) => ms === 60_000).length, 2, "lead time + one delay between the services");
  const rollback = JSON.parse(readFileSync(`${deps.cfg.artifactsDir}/state/last-deploy.json`, "utf8"));
  assert.ok(existsSync(rollback.previous.lobby.jar), "the previous jar must be kept for the rollback");
  assert.equal(readFileSync(rollback.previous.lobby.jar, "utf8"), "old-lobby-jar");
});

test("ops_deploy_plugin rolls back automatically when a service does not come up healthy", async () => {
  const repoAllow = enabledAllowlist();
  const dir = tmpDir();
  repoAllow.paths.repo = REPO_ROOT;
  const backups = tmpDir("ops-helper-backups-");
  mkdirSync(`${backups}/daily-20261103-120000`, { recursive: true });
  writeFileSync(`${backups}/daily-20261103-120000/COMPLETE.txt`, "ok");
  repoAllow.paths.backups = backups;
  const pluginLobby = tmpDir("ops-helper-lobby-");
  const pluginSurvival = tmpDir("ops-helper-survival-");
  writeFileSync(`${pluginLobby}/craftcore.jar`, "old-lobby-jar");
  writeFileSync(`${pluginSurvival}/craftcore.jar`, "old-survival-jar");
  repoAllow.deploy.pluginDirs = { lobby: pluginLobby, survival: pluginSurvival };

  let inspectCalls = 0;
  const runner = dockerRunner();
  runner.responses.unshift({
    match: (argv) => argv[1] === "inspect",
    reply: () => {
      inspectCalls += 1;
      const state = inspectCalls === 1 ? { Status: "running", Health: { Status: "healthy" } } : { Status: "running", Health: { Status: "starting" } };
      return { stdout: JSON.stringify(state) };
    },
  });

  const deps = makeDeps({ allow: repoAllow, dataDir: dir, cfg: { armed: true }, runner, now: advancingClock() });
  const jar = `${deps.cfg.artifactsDir}/artifacts/craftcore-aaaa.jar`;
  mkdirSync(`${deps.cfg.artifactsDir}/artifacts`, { recursive: true });
  writeFileSync(jar, "new-jar-bytes");
  const sha = createHash("sha256").update("new-jar-bytes").digest("hex");
  mkdirSync(`${deps.cfg.artifactsDir}/state`, { recursive: true });
  writeFileSync(`${deps.cfg.artifactsDir}/state/last-build.json`, JSON.stringify({ sha256: sha, artifact_path: jar }));

  const out = await handleOp("ops_deploy_plugin", { confirm: true, delay_seconds: 60, artifact_sha256: sha }, deps);
  assert.equal(out.ok, false);
  assert.equal(out.code, "health_check_failed_rolled_back");
  assert.equal(readFileSync(`${pluginLobby}/craftcore.jar`, "utf8"), "old-lobby-jar", "the previous lobby jar must be restored");
  assert.equal(readFileSync(`${pluginSurvival}/craftcore.jar`, "utf8"), "old-survival-jar");
  assert.equal(out.result.rollback.length, 2);
});

test("ops_rollback_plugin restores the recorded jars", async () => {
  const repoAllow = enabledAllowlist();
  const dir = tmpDir();
  repoAllow.paths.repo = REPO_ROOT;
  const backups = tmpDir("ops-helper-backups-");
  mkdirSync(`${backups}/daily-20261103-120000`, { recursive: true });
  writeFileSync(`${backups}/daily-20261103-120000/COMPLETE.txt`, "ok");
  repoAllow.paths.backups = backups;
  const pluginLobby = tmpDir("ops-helper-lobby-");
  const pluginSurvival = tmpDir("ops-helper-survival-");
  repoAllow.deploy.pluginDirs = { lobby: pluginLobby, survival: pluginSurvival };
  const deps = makeDeps({ allow: repoAllow, dataDir: dir, cfg: { armed: true } });

  const keptLobby = `${deps.cfg.artifactsDir}/rollback/lobby-craftcore-old.jar`;
  const keptSurvival = `${deps.cfg.artifactsDir}/rollback/survival-craftcore-old.jar`;
  mkdirSync(`${deps.cfg.artifactsDir}/rollback`, { recursive: true });
  writeFileSync(keptLobby, "old-lobby-jar");
  writeFileSync(keptSurvival, "old-survival-jar");
  writeFileSync(`${pluginLobby}/craftcore.jar`, "broken-jar");
  writeFileSync(`${pluginSurvival}/craftcore.jar`, "broken-jar");
  mkdirSync(`${deps.cfg.artifactsDir}/state`, { recursive: true });
  writeFileSync(
    `${deps.cfg.artifactsDir}/state/last-deploy.json`,
    JSON.stringify({
      artifact_sha256: "c".repeat(64),
      previous: { lobby: { sha256: "a".repeat(64), jar: keptLobby }, survival: { sha256: "b".repeat(64), jar: keptSurvival } },
    }),
  );

  const out = await handleOp("ops_rollback_plugin", { confirm: true, delay_seconds: 60 }, deps);
  assert.equal(out.ok, true, out.message);
  assert.equal(readFileSync(`${pluginLobby}/craftcore.jar`, "utf8"), "old-lobby-jar");
  assert.equal(readFileSync(`${pluginSurvival}/craftcore.jar`, "utf8"), "old-survival-jar");
  assert.equal(out.result.steps.length, 2);
});

test("ops_backups_list and ops_disk stay read-only and tolerate a missing repo", async () => {
  const repoAllow = enabledAllowlist();
  const backups = tmpDir("ops-helper-backups-");
  mkdirSync(`${backups}/daily-20261102-010101`, { recursive: true });
  writeFileSync(`${backups}/daily-20261102-010101/COMPLETE.txt`, "ok");
  writeFileSync(`${backups}/daily-20261102-010101/craftcore.sql`, "dump");
  repoAllow.paths.backups = backups;
  const deps = makeDeps({ allow: repoAllow });

  const list = await handleOp("ops_backups_list", {}, deps);
  assert.equal(list.ok, true);
  assert.equal(list.result.count, 1);
  assert.equal(list.result.backups[0].complete, true);
  assert.equal(list.result.last_complete, "daily-20261102-010101");

  const disk = await handleOp("ops_disk", {}, deps);
  assert.equal(disk.ok, true);
  assert.ok("filesystems" in disk.result);
  assert.equal(disk.result.directory_sizes.length, 2);
});

/* ------------------------------------------------------------------ */
/* disabled actions                                                    */
/* ------------------------------------------------------------------ */

const ENABLED_ACTIONS = [
  "ops_status",
  "ops_logs",
  "ops_disk",
  "ops_backups_list",
  "ops_git_state",
  // Enabled on purpose: the docker-socket-filter can serve exactly this one
  // non-GET route (POST /containers/<name>/restart) for the two T1 names.
  "ops_restart_service",
];
const DISABLED_ACTIONS = [
  "ops_players",
  "ops_build_plugin",
  "ops_run_tests",
  "ops_backup_now",
  "ops_restart_game",
  "ops_deploy_plugin",
  "ops_rollback_plugin",
];
const DISABLED_PARAMS = {
  ops_players: { service: "lobby" },
  ops_build_plugin: {},
  ops_run_tests: { target: "hermes-mcp" },
  ops_backup_now: { confirm: true },
  ops_restart_game: { service: "lobby", confirm: true, delay_seconds: 60 },
  ops_deploy_plugin: { confirm: true, delay_seconds: 60, artifact_sha256: "a".repeat(64) },
  ops_rollback_plugin: { confirm: true, delay_seconds: 60 },
};

test("the shipped allowlist enables the read-only tools plus the T1 restart only", () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  for (const name of ENABLED_ACTIONS) {
    assert.equal(allow.actions[name].enabled, true, `${name} must stay enabled`);
  }
  for (const name of DISABLED_ACTIONS) {
    assert.equal(allow.actions[name].enabled, false, `${name} must be disabled`);
    assert.ok((allow.actions[name].disabledReason ?? "").length > 0, `${name} must carry a disabled_reason`);
  }
  assert.equal(allow.actions.ops_restart_service.tier, "T1");
});

test("ops_restart_service still demands confirm=true and the T1 cooldown", async () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  const noConfirm = await handleOp("ops_restart_service", { service: "bot" }, makeDeps({ allow }));
  assert.equal(noConfirm.code, "confirmation_required");

  const deps = makeDeps({ allow });
  const ok = await handleOp("ops_restart_service", { service: "bot", confirm: true }, deps);
  assert.equal(ok.ok, true);
  assert.equal(ok.result.container, CONTAINER_NAME);

  const again = await handleOp("ops_restart_service", { service: "bot", confirm: true }, deps);
  assert.equal(again.code, "cooldown_active");
});

test("a game server is not restartable even through the shipped allowlist", async () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  for (const service of ["lobby", "survival", "velocity", "craftcore-db", "hermes-mcp"]) {
    const deps = makeDeps({ allow });
    const out = await handleOp("ops_restart_service", { service, confirm: true }, deps);
    assert.equal(out.ok, false, `${service} must be refused`);
    assert.equal(out.code, "invalid_params");
    assert.equal(deps.runner.calls.length, 0, `${service} must never reach docker`);
  }
});

test("a paused action returns action_disabled with its reason and never reaches docker", async () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  for (const name of DISABLED_ACTIONS) {
    const deps = makeDeps({ allow, cfg: { armed: true } });
    const out = await handleOp(name, DISABLED_PARAMS[name], deps);
    assert.equal(out.ok, false, `${name} must be refused`);
    assert.equal(out.code, "action_disabled", `${name} must report action_disabled`);
    assert.match(out.message, /paused/, `${name} message must explain itself`);
    assert.equal(deps.runner.calls.length, 0, `${name} must not reach docker`);
  }
});

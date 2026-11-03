import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, utimesSync, writeFileSync } from "node:fs";
import { test } from "node:test";
import { handleOp } from "../dist/actions.js";
import { ALLOWLIST_PATH, CONTAINER_ID, CONTAINER_NAME, advancingClock, dockerRunner, enabledAllowlist, FakeJobClient, makeDeps, tmpDir } from "./helpers.mjs";
import { loadAllowlist } from "../dist/allowlist.js";

const REPO_ROOT = new URL("../../..", import.meta.url).pathname.replace(/\/$/, "");

/** Backdate a file/dir mtime so an age-sensitive gate can be tested for real. */
function ensureFreshMtime(path, when) {
  utimesSync(path, when, when);
}

/** Create a complete backup fixture and return its directory. */
function backupFixture() {
  const backups = tmpDir("ops-helper-backups-");
  mkdirSync(`${backups}/daily-20261103-120000`, { recursive: true });
  writeFileSync(`${backups}/daily-20261103-120000/COMPLETE.txt`, "ok");
  return backups;
}

/** A builder result as plugin-builder would write it. */
function builderResult(over = {}) {
  return {
    v: 1,
    id: "x",
    action: "build",
    ok: true,
    code: null,
    ref: "b".repeat(40),
    tests: null,
    dirty: null,
    dirty_files: null,
    artifact: null,
    output_tail: "",
    truncated: false,
    ...over,
  };
}

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

test("ops_status parses real docker ps NDJSON, including Windows paths in labels", async () => {
  // Verbatim `docker ps --format '<json>'` lines with the `json` template
  // function, as measured against the real daemon on Docker Desktop. The Labels
  // value carries Windows host paths, i.e. backslash escapes in JSON. With the
  // old template (`"{{.Labels}}"`) these lines were invalid JSON, the parser
  // silently swallowed them into `{}` and ops_status reported rows with
  // service/name/image/state/status all null and `known: false`.
  const lines = [
    '{"Names":"mc-stack-lobby-1","Image":"itzg/minecraft-server:latest","State":"running","Status":"Up 30 minutes (healthy)","Labels":"com.docker.compose.config-hash=9d7b,com.docker.compose.project.config_files=C:\\\\Users\\\\nilsw\\\\mc-stack\\\\compose.yaml,com.docker.compose.project=mc-stack,com.docker.compose.service=lobby,desktop.docker.io/binds/1/Source=C:\\\\Users\\\\nilsw\\\\mc-stack\\\\config\\\\lobby-plugins"}',
    '{"Names":"mc-stack-vipworld-1-1","Image":"itzg/minecraft-server:latest","State":"exited","Status":"Exited (0) 4 minutes ago","Labels":"com.docker.compose.project=mc-stack,com.docker.compose.service=vipworld-1,desktop.docker.io/ports.scheme=v2"}',
    '{"Names":"mc-stack-bot-1","Image":"mc-stack-bot","State":"running","Status":"Up 29 minutes","Labels":"com.docker.compose.project=mc-stack,com.docker.compose.service=bot"}',
  ].join("\n");
  const deps = makeDeps({ runner: dockerRunner({ ps: lines }) });
  const out = await handleOp("ops_status", {}, deps);
  assert.equal(out.ok, true, out.message);
  assert.equal(out.result.containers.length, 3);
  const lobby = out.result.containers[0];
  assert.equal(lobby.service, "lobby");
  assert.equal(lobby.name, "mc-stack-lobby-1");
  assert.equal(lobby.image, "itzg/minecraft-server:latest");
  assert.equal(lobby.state, "running");
  assert.equal(lobby.status, "Up 30 minutes (healthy)");
  assert.equal(lobby.health, "healthy");
  assert.equal(lobby.known, true, "a container with a known compose service must be marked known");
  assert.equal(out.result.containers[1].state, "exited");
  assert.equal(out.result.counts.running, 2);
});

test("ops_status fails loudly on an unparseable docker ps line instead of reporting blanks", async () => {
  const deps = makeDeps({
    runner: dockerRunner({
      ps: '{"Names":"mc-stack-lobby-1","Labels":"C:\\Users\\nilsw"}',
    }),
  });
  const out = await handleOp("ops_status", {}, deps);
  assert.equal(out.ok, false);
  assert.equal(out.code, "unexpected_docker_output");
  assert.match(out.message, /not JSON/);
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
  const repoAllow = enabledAllowlist();
  const backups = tmpDir("ops-helper-backups-");
  mkdirSync(`${backups}/daily-20261103-120000`, { recursive: true });
  writeFileSync(`${backups}/daily-20261103-120000/COMPLETE.txt`, "ok");
  repoAllow.paths.backups = backups;
  // Real clock: the backup fixture was just created, so it is genuinely fresh.
  const deps = makeDeps({ allow: repoAllow, cfg: { armed: true }, now: () => Date.now() });
  const out = await handleOp("ops_restart_game", {
    service: "survival",
    confirm: true,
    delay_seconds: 90,
    acknowledge_no_announce: true,
  }, deps);
  assert.equal(out.ok, true, out.message);
  assert.equal(out.result.service, "survival");
  assert.equal(out.result.delay_seconds, 90);
  assert.equal(out.result.announcement.announced, false);
  assert.match(out.result.announcement.detail, /NO in-game announcement/);
  assert.ok(deps.sleeps.includes(90_000), "the lead time must be awaited");
  // The restart must go out by NAME (the filter allowlists names, not ids).
  const restart = deps.runner.find((argv) => argv[1] === "restart");
  assert.ok(restart, "the restart must have run");
  assert.equal(restart.argv[2], CONTAINER_NAME);
  assert.match(restart.argv[2], /^mc-stack-/, "a container name, never a bare id");
});

test("ops_restart_game (T2) demands the no-announcement acknowledgement", async () => {
  const repoAllow = enabledAllowlist();
  const backups = tmpDir("ops-helper-backups-");
  mkdirSync(`${backups}/daily-20261103-120000`, { recursive: true });
  writeFileSync(`${backups}/daily-20261103-120000/COMPLETE.txt`, "ok");
  repoAllow.paths.backups = backups;
  const deps = makeDeps({ allow: repoAllow, cfg: { armed: true }, now: () => Date.now() });
  const out = await handleOp("ops_restart_game", { service: "lobby", confirm: true, delay_seconds: 60 }, deps);
  assert.equal(out.ok, false);
  assert.equal(out.code, "announcement_not_acknowledged");
  assert.equal(deps.runner.find((argv) => argv[1] === "restart"), undefined, "nothing may restart");
});

test("ops_restart_game (T2) refuses without a recent backup and never restarts", async () => {
  const repoAllow = enabledAllowlist();
  const backups = tmpDir("ops-helper-backups-");
  repoAllow.paths.backups = backups; // empty: no complete backup at all
  const stale = makeDeps({ allow: repoAllow, cfg: { armed: true }, now: () => Date.now() });
  const noBackup = await handleOp("ops_restart_game", { service: "lobby", confirm: true, delay_seconds: 60, acknowledge_no_announce: true }, stale);
  assert.equal(noBackup.code, "no_recent_backup");
  assert.equal(stale.runner.find((argv) => argv[1] === "restart"), undefined);

  // A backup older than tiers.T2.backup_max_age_hours is refused too.
  mkdirSync(`${backups}/daily-20260101-000000`, { recursive: true });
  writeFileSync(`${backups}/daily-20260101-000000/COMPLETE.txt`, "ok");
  const aged = new Date(Date.now() - 13 * 3600 * 1000);
  ensureFreshMtime(`${backups}/daily-20260101-000000`, aged);
  const staleDeps = makeDeps({ allow: repoAllow, cfg: { armed: true }, now: () => Date.now() });
  const old = await handleOp("ops_restart_game", { service: "lobby", confirm: true, delay_seconds: 60, acknowledge_no_announce: true }, staleDeps);
  assert.equal(old.code, "stale_backup");
  assert.match(old.message, /backup\.ps1/);
  assert.equal(staleDeps.runner.find((argv) => argv[1] === "restart"), undefined);
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

test("ops_run_tests hands one strictly shaped job to plugin-builder", async () => {
  const jobs = new FakeJobClient({
    result: builderResult({
      action: "test",
      target: "hermes-mcp",
      tests: { tests: 25, failures: 0, errors: 0, skipped: 0 },
      dirty: true,
      dirty_files: 2,
      output_tail: "# pass 25\n",
    }),
  });
  const deps = makeDeps({ jobs, now: () => 1_700_000_000_000 });
  const out = await handleOp("ops_run_tests", { target: "hermes-mcp" }, deps);
  assert.equal(out.ok, true, out.message);
  assert.deepEqual(out.result.tests, { tests: 25, failures: 0, errors: 0, skipped: 0 });
  // Provenance: the artifact came from the commit, so a diverging working tree is
  // reported to the caller as a count instead of failing the run.
  assert.equal(out.result.dirty, true);
  assert.equal(out.result.dirty_files, 2);
  assert.equal(jobs.submitted.length, 1);
  const job = jobs.submitted[0];
  // The exact key set: nothing but an action, the commit sha and a target.
  assert.deepEqual(Object.keys(job).sort(), ["action", "id", "ref", "requested_at", "target", "v"]);
  assert.equal(job.action, "test");
  assert.equal(job.target, "hermes-mcp");
  assert.match(job.ref, /^[a-f0-9]{40}$/);
  // The helper starts NO container any more (the filter refuses create).
  assert.equal(deps.runner.find((argv) => argv[1] === "run"), undefined, "the helper must not start a container");
});

test("a build job carries no target, and a failing build is reported", async () => {
  const jobs = new FakeJobClient({ result: builderResult({ ok: false, code: "build_failed", output_tail: "FAILURE" }) });
  const deps = makeDeps({ jobs, now: () => 1_700_000_000_000 });
  const out = await handleOp("ops_build_plugin", {}, deps);
  assert.equal(out.ok, false);
  assert.equal(out.code, "build_failed");
  assert.deepEqual(Object.keys(jobs.submitted[0]).sort(), ["action", "id", "ref", "requested_at", "v"]);
  assert.equal(deps.runner.find((argv) => argv[1] === "run"), undefined, "the helper must not start a container");
});

test("ops_build_plugin verifies the artifact hash itself and does not deploy", async () => {
  const dir = tmpDir();
  const jarBytes = Buffer.from("PK\u0003\u0004 fake craftcore jar");
  const sha = createHash("sha256").update(jarBytes).digest("hex");
  const jarPath = `${dir}/artifacts/artifacts/craftcore-${sha.slice(0, 12)}.jar`;
  mkdirSync(jarPath.slice(0, jarPath.lastIndexOf("/")), { recursive: true });
  writeFileSync(jarPath, jarBytes);
  const jobs = new FakeJobClient({
    result: builderResult({
      tests: { tests: 441, failures: 0, errors: 0, skipped: 13 },
      artifact: { path: jarPath, sha256: sha, size_bytes: jarBytes.length },
    }),
  });
  const deps = makeDeps({ jobs, dataDir: dir, now: () => 1_700_000_000_000 });

  const out = await handleOp("ops_build_plugin", {}, deps);
  assert.equal(out.ok, true, out.message);
  assert.equal(out.result.sha256, sha);
  assert.deepEqual(out.result.tests, { tests: 441, failures: 0, errors: 0, skipped: 13 });
  assert.equal(out.result.deployed, false);
  assert.equal(out.result.dirty, null, "a builder that could not inspect the tree reports null, not false");
  assert.ok(existsSync(out.result.artifact_path), "the artifact must stay in the artifact volume");
  const state = JSON.parse(readFileSync(`${dir}/artifacts/state/last-build.json`, "utf8"));
  assert.equal(state.sha256, sha);

  // A tampered artefact (hash does not match the file) is refused, not deployed.
  const badJobs = new FakeJobClient({
    result: builderResult({ artifact: { path: jarPath, sha256: "c".repeat(64), size_bytes: 1 } }),
  });
  const badDeps = makeDeps({ jobs: badJobs, dataDir: dir });
  const bad = await handleOp("ops_build_plugin", {}, badDeps);
  assert.equal(bad.ok, false);
  assert.equal(bad.code, "artifact_hash_mismatch");
});

test("a silent plugin-builder is reported as a timeout, never as a success", async () => {
  const deps = makeDeps({ jobs: new FakeJobClient({ result: null }) });
  const out = await handleOp("ops_run_tests", { target: "ops-helper" }, deps);
  assert.equal(out.ok, false);
  assert.equal(out.code, "builder_timeout");
  assert.equal(deps.runner.find((argv) => argv[1] === "run"), undefined);
});

test("ops_deploy_plugin refuses without a build, without the arm switch and without a backup", async () => {
  const repoAllow = enabledAllowlist();
  const dir = tmpDir();
  repoAllow.paths.repo = REPO_ROOT;
  const mk = (over = {}) => makeDeps({ allow: repoAllow, dataDir: dir, cfg: { armed: true, ...over } });
  const params = (sha) => ({
    confirm: true,
    delay_seconds: 60,
    artifact_sha256: sha,
    acknowledge_no_announce: true,
  });

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
  assert.equal(noBackup.code, "no_recent_backup");
  assert.match(noBackup.message, /backup\.ps1/, "the answer must say how to fix it");
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

  const out = await handleOp("ops_deploy_plugin", { confirm: true, delay_seconds: 60, artifact_sha256: sha, acknowledge_no_announce: true }, deps);
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

  const out = await handleOp("ops_deploy_plugin", { confirm: true, delay_seconds: 60, artifact_sha256: sha, acknowledge_no_announce: true }, deps);
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

  const out = await handleOp("ops_rollback_plugin", { confirm: true, delay_seconds: 60, acknowledge_no_announce: true }, deps);
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
  // T0 development: now served by plugin-builder through the job volume, so the
  // helper never needs `POST /containers/create`.
  "ops_build_plugin",
  "ops_run_tests",
  // Enabled on purpose: the docker-socket-filter can serve exactly this one
  // non-GET route (POST /containers/<name>/restart) for the two T1 names.
  "ops_restart_service",
  // T2: armed + confirm + delay + recent backup + acknowledge_no_announce, and
  // only over the local listener.
  "ops_restart_game",
  "ops_deploy_plugin",
  "ops_rollback_plugin",
];
const DISABLED_ACTIONS = ["ops_players", "ops_backup_now"];
const DISABLED_PARAMS = {
  ops_players: { service: "lobby" },
  ops_backup_now: { confirm: true },
};

test("the shipped allowlist enables the ops surface minus the two paused actions", () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  for (const name of ENABLED_ACTIONS) {
    assert.equal(allow.actions[name].enabled, true, `${name} must stay enabled`);
  }
  for (const name of DISABLED_ACTIONS) {
    assert.equal(allow.actions[name].enabled, false, `${name} must be disabled`);
    assert.ok((allow.actions[name].disabledReason ?? "").length > 0, `${name} must carry a disabled_reason`);
  }
  assert.equal(allow.actions.ops_restart_service.tier, "T1");
  assert.equal(allow.actions.ops_restart_game.tier, "T2");
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

test("the T1 and T2 service lists never overlap", () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  assert.deepEqual(allow.services.restartT1, ["bot", "cloudflared-mcp"]);
  assert.deepEqual(allow.services.restartGame, ["lobby", "survival", "velocity"]);
  const overlap = allow.services.restartT1.filter((name) => allow.services.restartGame.includes(name));
  assert.deepEqual(overlap, [], "no service may be in both the T1 and the T2 restart list");
  for (const name of ["craftcore-db", "hermes-mcp", "ops-helper", "hermes-test"]) {
    assert.ok(!allow.services.restartT1.includes(name), `${name} must not be T1-restartable`);
    assert.ok(!allow.services.restartGame.includes(name), `${name} must not be T2-restartable`);
    assert.ok(!allow.services.deployPlugin.includes(name), `${name} must not be a deploy target`);
  }
});

test("a game server is not restartable through the T1 path, a T1 service not through T2", async () => {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  // The T1 action must never reach a game server...
  for (const service of ["lobby", "survival", "velocity", "craftcore-db", "hermes-mcp"]) {
    const deps = makeDeps({ allow });
    const out = await handleOp("ops_restart_service", { service, confirm: true }, deps);
    assert.equal(out.ok, false, `${service} must be refused by ops_restart_service`);
    assert.equal(out.code, "invalid_params");
    assert.equal(deps.runner.calls.length, 0, `${service} must never reach docker via T1`);
  }
  // ...and the T2 action must never reach a T1 helper service.
  for (const service of ["bot", "cloudflared-mcp", "craftcore-db", "hermes-mcp"]) {
    const deps = makeDeps({ allow, cfg: { armed: true } });
    const out = await handleOp(
      "ops_restart_game",
      { service, confirm: true, delay_seconds: 60, acknowledge_no_announce: true },
      deps,
    );
    assert.equal(out.ok, false, `${service} must be refused by ops_restart_game`);
    assert.equal(out.code, "invalid_params");
    assert.equal(deps.runner.calls.length, 0, `${service} must never reach docker via T2`);
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

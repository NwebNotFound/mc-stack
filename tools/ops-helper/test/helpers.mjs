/**
 * Test helpers (not matched by `node --test test/*.test.mjs`).
 *
 * The runner is faked everywhere: no test in this suite ever talks to Docker, to
 * a container or to the network, so running the suite cannot touch Nils' stack.
 */
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AuditLog } from "../dist/audit.js";
import { loadAllowlist } from "../dist/allowlist.js";
import { DockerCli } from "../dist/docker.js";
import { OpsState } from "../dist/state.js";

export const ALLOWLIST_PATH = new URL("../../../config/ops-allowlist.json", import.meta.url).pathname;

export function tmpDir(prefix = "ops-helper-test-") {
  return mkdtempSync(join(tmpdir(), prefix));
}

/** A clock that moves forward on every read, so bounded wait loops terminate. */
export function advancingClock(start = 1_000_000_000_000, stepMs = 1000) {
  let value = start;
  return () => {
    value += stepMs;
    return value;
  };
}

export function fakeResult(partial = {}) {
  return {
    ok: true,
    code: 0,
    signal: null,
    stdout: "",
    stderr: "",
    timedOut: false,
    truncated: false,
    durationMs: 1,
    ...partial,
  };
}

/** Records every argv and answers from a list of matchers. */
export class FakeRunner {
  constructor(responses = []) {
    this.calls = [];
    this.responses = responses;
  }

  async run(argv, opts = {}) {
    this.calls.push({ argv, opts });
    for (const response of this.responses) {
      if (response.match(argv)) {
        return fakeResult(typeof response.reply === "function" ? response.reply(argv, opts) : response.reply);
      }
    }
    return fakeResult();
  }

  /** Every argv that was issued (for "nothing ran" assertions). */
  argvs() {
    return this.calls.map((call) => call.argv);
  }

  find(predicate) {
    return this.calls.find((call) => predicate(call.argv));
  }
}

export const CONTAINER_ID = "0123456789ab";

/** A runner that answers the docker calls the ops tools make. */
export function dockerRunner({ ps = "", logs = "", logsOk = true, exec = null, inspect = null } = {}) {
  return new FakeRunner([
    { match: (argv) => argv[1] === "ps" && argv.includes("{{.ID}}"), reply: { stdout: CONTAINER_ID + "\n" } },
    { match: (argv) => argv[1] === "ps", reply: { stdout: ps } },
    { match: (argv) => argv[1] === "logs", reply: { stdout: logsOk ? logs : "", stderr: logsOk ? "" : logs, ok: logsOk } },
    { match: (argv) => argv[1] === "exec", reply: exec ? exec : { ok: false, stdout: "", stderr: "rcon-cli: not found" } },
    {
      match: (argv) => argv[1] === "inspect",
      reply: inspect ? inspect : { stdout: JSON.stringify({ Status: "running", Health: { Status: "healthy" } }) },
    },
    { match: (argv) => argv[1] === "restart", reply: { stdout: CONTAINER_ID + "\n" } },
    { match: (argv) => argv[1] === "run", reply: { stdout: "fake container ran" } },
  ]);
}

export function makeDeps(overrides = {}) {
  const dir = overrides.dataDir ?? tmpDir();
  const runner = overrides.runner ?? dockerRunner();
  const allow = overrides.allow ?? loadAllowlist(ALLOWLIST_PATH);
  const now = overrides.now ?? (() => 1_000_000_000_000);
  const sleeps = [];
  const cfg = {
    port: 8766,
    host: "127.0.0.1",
    token: "x".repeat(32),
    armed: false,
    disabled: false,
    repoDir: "/repo",
    allowlistPath: ALLOWLIST_PATH,
    dataDir: dir,
    artifactsDir: `${dir}/artifacts`,
    artifactsVolume: "mc-stack-ops-artifacts",
    gradleCacheVolume: "mc-stack-ops-gradle-cache",
    npmCacheVolume: "mc-stack-ops-npm-cache",
    dockerBin: "docker",
    actionTimeoutSeconds: 30,
    buildTimeoutSeconds: 60,
    backupInterpreters: ["pwsh", "powershell"],
    ...(overrides.cfg ?? {}),
  };
  return {
    cfg,
    allow,
    docker: new DockerCli(runner, cfg.dockerBin, allow.composeProject, cfg.actionTimeoutSeconds * 1000),
    runner,
    audit: new AuditLog(`${dir}/audit.jsonl`),
    state: new OpsState(`${dir}/state.json`),
    now,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    sleeps,
    dataDir: dir,
  };
}

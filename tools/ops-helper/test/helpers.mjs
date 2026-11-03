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
/** The compose container name of the service the tests restart. */
export const CONTAINER_NAME = "mc-stack-bot-1";
/** The commit the fake repo is checked out at (git rev-parse HEAD). */
export const REPO_HEAD = "b".repeat(40);

/** A runner that answers the docker calls the ops tools make. */
export function dockerRunner({ ps = "", logs = "", logsOk = true, exec = null, inspect = null, name = CONTAINER_NAME, head = REPO_HEAD } = {}) {
  return new FakeRunner([
    { match: (argv) => argv[0] === "git" && argv.includes("rev-parse"), reply: { stdout: `${head}\n` } },
    { match: (argv) => argv[1] === "ps" && argv.includes("{{.ID}}"), reply: { stdout: CONTAINER_ID + "\n" } },
    { match: (argv) => argv[1] === "ps" && argv.includes("{{.Names}}"), reply: { stdout: name + "\n" } },
    { match: (argv) => argv[1] === "ps", reply: { stdout: ps } },
    { match: (argv) => argv[1] === "logs", reply: { stdout: logsOk ? logs : "", stderr: logsOk ? "" : logs, ok: logsOk } },
    { match: (argv) => argv[1] === "exec", reply: exec ? exec : { ok: false, stdout: "", stderr: "rcon-cli: not found" } },
    {
      match: (argv) => argv[1] === "inspect",
      reply: inspect ? inspect : { stdout: JSON.stringify({ Status: "running", Health: { Status: "healthy" } }) },
    },
    { match: (argv) => argv[1] === "restart", reply: { stdout: CONTAINER_NAME + "\n" } },
    { match: (argv) => argv[1] === "run", reply: { stdout: "fake container ran" } },
  ]);
}

/**
 * A job channel that never touches the disk: it records what the helper tried to
 * hand to plugin-builder and answers with a canned result.
 */
export class FakeJobClient {
  constructor({ result = null, failSubmit = null } = {}) {
    this.submitted = [];
    this.result = result;
    this.failSubmit = failSubmit;
  }

  submit(job) {
    if (this.failSubmit) throw new Error(this.failSubmit);
    this.submitted.push(job);
    return `/jobs/job-${job.id}.json`;
  }

  async awaitResult(id /*, timeoutMs */) {
    if (!this.result) return null;
    return { ...this.result, id };
  }

  jobPath(id) {
    return `/jobs/job-${id}.json`;
  }

  resultPath(id) {
    return `/artifacts/builder/result-${id}.json`;
  }
}

/**
 * The shipped allowlist with every action forced enabled.
 *
 * Several actions are paused in `config/ops-allowlist.json` (see the README).
 * Their implementations stay covered by flipping them back on in a test-local
 * copy, so a paused action never removes test coverage from its impl. The
 * shipped (paused) state is asserted separately in `dispatch.test.mjs` and
 * `allowlist.test.mjs`.
 */
export function enabledAllowlist() {
  const allow = loadAllowlist(ALLOWLIST_PATH);
  for (const action of Object.values(allow.actions)) action.enabled = true;
  return allow;
}

export function makeDeps(overrides = {}) {
  const dir = overrides.dataDir ?? tmpDir();
  const runner = overrides.runner ?? dockerRunner();
  const allow = overrides.allow ?? enabledAllowlist();
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
    jobsDir: `${dir}/jobs`,
    builderResultsDir: `${dir}/artifacts/builder`,
    builderTimeoutSeconds: 60,
    builderPollMs: 100,
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
    jobs: overrides.jobs ?? new FakeJobClient(),
    now,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    sleeps,
    dataDir: dir,
  };
}

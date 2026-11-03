/**
 * plugin-builder — the build/test worker behind the ops tools.
 *
 * Why a separate, permanent service instead of a throwaway container:
 * `ops_build_plugin` / `ops_run_tests` used to start a container, which needs
 * `POST /containers/create` on the Docker socket. That route is host root, and
 * docker-socket-filter refuses it on purpose. So the helper no longer starts
 * anything: it drops a tiny job file into a shared volume and this service -
 * which has NO Docker socket and NO network (`network_mode: none`) - picks it up.
 *
 * Trust model: the job file is written by ops-helper, but the builder does NOT
 * trust it. Every field is re-validated here (exact key set, action enum, full
 * 40-hex commit sha, test target from the allowlist, size cap), the current
 * commit of the read-only repo mount is asserted to equal the job's `ref`, and
 * the command that runs is a fixed argv list read from
 * `config/ops-allowlist.json` - never a string from the job. A compromised helper
 * can therefore at most ask this service to build/test something it is already
 * allowed to build/test.
 *
 * Only files this service itself names are written: `job-*.json` is consumed and
 * removed, the result lands in the artifact volume, and the build happens in a
 * disposable directory under the work dir.
 */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { DEFAULT_MAX_JOB_BYTES, JOB_FILE_RE, JOB_VERSION, validateJob } from "./validate.mjs";

const JOBS_DIR = process.env.BUILDER_JOBS_DIR ?? "/jobs";
const ARTIFACTS_DIR = process.env.BUILDER_ARTIFACTS_DIR ?? "/artifacts";
const RESULTS_DIR = process.env.BUILDER_RESULTS_DIR ?? `${ARTIFACTS_DIR}/builder`;
const REPO_DIR = process.env.BUILDER_REPO ?? "/repo";
const ALLOWLIST_PATH = process.env.BUILDER_ALLOWLIST_PATH ?? `${REPO_DIR}/config/ops-allowlist.json`;
const WORK_DIR = process.env.BUILDER_WORK_DIR ?? "/work";
const POLL_MS = Number(process.env.BUILDER_POLL_MS ?? "1000");
const JOB_TIMEOUT_SECONDS = Number(process.env.BUILDER_JOB_TIMEOUT_SECONDS ?? "1800");
const MAX_JOB_BYTES = Number(process.env.BUILDER_MAX_JOB_BYTES ?? String(DEFAULT_MAX_JOB_BYTES));
const LOG_TAIL_BYTES = Number(process.env.BUILDER_LOG_TAIL_BYTES ?? "8192");
/** Toolchains this container declares it can run; a spec naming anything else is refused. */
const TOOLCHAINS = (process.env.BUILDER_TOOLCHAINS ?? "gradle:9-jdk,node:22-alpine").split(",").map((v) => v.trim()).filter(Boolean);

const log = (entry) => console.log(JSON.stringify({ ts: new Date().toISOString(), service: "plugin-builder", ...entry }));

function sha256File(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function run(argv, { cwd, timeoutMs, maxBytes }) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(argv[0], argv.slice(1), { cwd, shell: false, stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? "/home/gradle/.gradle" } });
    } catch (exc) {
      resolve({ ok: false, code: null, stdout: "", stderr: String(exc.message), timedOut: false, truncated: false });
      return;
    }
    let out = "";
    let err = "";
    let truncated = false;
    let timedOut = false;
    let settled = false;
    const cap = (current, chunk) => {
      if (current.length >= maxBytes) {
        truncated = true;
        return current;
      }
      const next = current + chunk;
      if (next.length > maxBytes) {
        truncated = true;
        return next.slice(0, maxBytes);
      }
      return next;
    };
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 2000).unref();
    }, timeoutMs);
    child.stdout?.on("data", (c) => {
      out = cap(out, c.toString("utf8"));
    });
    child.stderr?.on("data", (c) => {
      err = cap(err, c.toString("utf8"));
    });
    const finish = (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ ok: code === 0 && !timedOut, code, stdout: out, stderr: err, timedOut, truncated });
    };
    child.on("error", (e) => {
      err = `${err}\n${e.message}`;
      finish(null);
    });
    child.on("close", (code) => finish(code));
  });
}

function loadAllowlist() {
  const parsed = JSON.parse(readFileSync(ALLOWLIST_PATH, "utf8"));
  return {
    plugin: parsed.build.plugin,
    tests: parsed.build.tests,
  };
}

function attrCounts(xml) {
  const m = /<testsuite\b[^>]*>/.exec(xml);
  if (!m) return null;
  const attr = (name) => Number(new RegExp(`${name}="(\\d+)"`).exec(m[0])?.[1] ?? "0");
  return { tests: attr("tests"), failures: attr("failures"), errors: attr("errors"), skipped: attr("skipped") };
}

function readJUnitCounts(dir) {
  if (!existsSync(dir)) return null;
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  let seen = 0;
  for (const entry of readdirSync(dir)) {
    if (!entry.endsWith(".xml")) continue;
    let content;
    try {
      content = readFileSync(join(dir, entry), "utf8");
    } catch {
      continue;
    }
    const counts = attrCounts(content);
    if (!counts) continue;
    totals.tests += counts.tests;
    totals.failures += counts.failures;
    totals.errors += counts.errors;
    totals.skipped += counts.skipped;
    seen += 1;
  }
  return seen > 0 ? totals : null;
}

function tail(text, maxBytes) {
  const redacted = text;
  if (Buffer.byteLength(redacted, "utf8") <= maxBytes) return { text: redacted, truncated: false };
  return { text: redacted.slice(redacted.length - maxBytes), truncated: true };
}

function assertRepoHead(ref) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn("git", ["-C", REPO_DIR, "rev-parse", "HEAD"], { shell: false, stdio: ["ignore", "pipe", "ignore"] });
    } catch {
      resolve(null);
      return;
    }
    let out = "";
    child.stdout?.on("data", (c) => {
      out += c.toString("utf8");
    });
    child.on("error", () => resolve(null));
    child.on("close", (code) => resolve(code === 0 ? out.trim() : null));
  });
}

/** Run one spec (setup + command) against a copy of the source in the work dir. */
async function runSpec(spec, workRoot, extraCommand) {
  if (!TOOLCHAINS.includes(spec.image)) {
    return { ok: false, code: "unknown_toolchain", stdout: "", stderr: `spec image ${spec.image} is not among ${TOOLCHAINS.join(", ")}`, timedOut: false, truncated: false, root: workRoot };
  }
  const source = join(REPO_DIR, spec.source);
  if (!existsSync(source)) return { ok: false, code: "source_missing", stdout: "", stderr: `${spec.source} not in the repo mount`, timedOut: false, truncated: false, root: workRoot };
  const root = mkdtempSync(join(workRoot, "job-"));
  cpSync(source, join(root, "src"), {
    recursive: true,
    filter: (src) => !/[/\\](node_modules|\.git|\.gradle|build|target)([/\\]|$)/.test(src),
  });
  const cwd = join(root, "src");
  const limit = { timeoutMs: JOB_TIMEOUT_SECONDS * 1000, maxBytes: LOG_TAIL_BYTES * 2 };
  if (spec.setup && spec.setup.length > 0) {
    const setup = await run(spec.setup, { cwd, ...limit });
    if (!setup.ok) return { ...setup, code: setup.timedOut ? "setup_timeout" : "setup_failed", root };
  }
  const res = await run(extraCommand ?? spec.command, { cwd, ...limit });
  return { ...res, root };
}

async function handleJob(job) {
  const allow = loadAllowlist();
  const startedAt = new Date().toISOString();
  const base = { v: JOB_VERSION, id: job.id, action: job.action, ref: job.ref, target: job.target ?? null, started_at: startedAt };

  const head = await assertRepoHead(job.ref);
  if (head !== job.ref) {
    return { ...base, ok: false, code: "ref_mismatch", finished_at: new Date().toISOString(), note: `the read-only repo is at ${head ?? "unknown"}, not at the requested ${job.ref}` };
  }

  const spec = job.action === "build" ? allow.plugin : allow.tests[job.target];
  if (!spec) return { ...base, ok: false, code: "unknown_target", finished_at: new Date().toISOString() };

  mkdirSync(WORK_DIR, { recursive: true });
  const result = await runSpec(spec, WORK_DIR);
  const tailed = tail([result.stdout, result.stderr].filter(Boolean).join("\n"), LOG_TAIL_BYTES);
  const tests = spec.test_results ? readJUnitCounts(join(result.root, "src", spec.test_results)) : null;
  const out = {
    ...base,
    ok: result.ok,
    code: result.ok ? null : result.code ?? (result.timedOut ? "timeout" : "failed"),
    finished_at: new Date().toISOString(),
    tests,
    output_tail: tailed.text,
    truncated: tailed.truncated || result.truncated === true,
  };

  // Keep a successful build's jar, with its own sha256, in the artifact volume.
  if (result.ok && job.action === "build" && spec.artifact) {
    const built = join(result.root, "src", spec.artifact);
    if (!existsSync(built)) {
      out.ok = false;
      out.code = "artifact_missing";
    } else {
      const sha = sha256File(built);
      const kept = `${ARTIFACTS_DIR}/artifacts/craftcore-${sha.slice(0, 12)}.jar`;
      mkdirSync(`${ARTIFACTS_DIR}/artifacts`, { recursive: true });
      copyFileSync(built, kept);
      out.artifact = { path: kept, sha256: sha, size_bytes: statSync(kept).size };
    }
  }
  try {
    rmSync(result.root, { recursive: true, force: true });
  } catch {
    // best effort cleanup of our own scratch dir
  }
  return out;
}

function writeResult(id, result) {
  mkdirSync(RESULTS_DIR, { recursive: true });
  const path = `${RESULTS_DIR}/result-${id}.json`;
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, JSON.stringify(result, null, 2), { encoding: "utf8", mode: 0o644 });
  renameSync(tmp, path);
}

async function pollOnce() {
  let entries;
  try {
    entries = readdirSync(JOBS_DIR);
  } catch {
    return;
  }
  for (const entry of entries) {
    const m = JOB_FILE_RE.exec(entry);
    if (!m) continue;
    const fileId = m[1];
    const path = join(JOBS_DIR, entry);
    let raw;
    try {
      const stat = statSync(path);
      if (stat.size > MAX_JOB_BYTES) {
        writeResult(fileId, { v: JOB_VERSION, id: fileId, action: "build", ok: false, code: "job_too_large", ref: "" });
        rmSync(path, { force: true });
        continue;
      }
      raw = readFileSync(path, "utf8");
    } catch {
      continue;
    }
    const checked = validateJob(raw, fileId);
    if (!checked.job) {
      log({ event: "job_rejected", id: fileId, code: checked.code });
      writeResult(fileId, { v: JOB_VERSION, id: fileId, action: "build", ok: false, code: checked.code ?? "job_invalid", ref: "" });
      rmSync(path, { force: true });
      continue;
    }
    // Consume the job before running it so the same file cannot be picked up twice.
    try {
      renameSync(path, `${path}.running`);
    } catch {
      continue;
    }
    log({ event: "job_started", id: fileId, action: checked.job.action });
    let result;
    try {
      result = await handleJob(checked.job);
    } catch (exc) {
      result = { v: JOB_VERSION, id: fileId, action: checked.job.action, ok: false, code: "builder_error", ref: checked.job.ref, finished_at: new Date().toISOString(), output_tail: String(exc.message).slice(0, 500) };
    }
    writeResult(fileId, result);
    rmSync(`${path}.running`, { force: true });
    log({ event: "job_finished", id: fileId, ok: result.ok, code: result.code ?? null });
  }
}

async function main() {
  mkdirSync(JOBS_DIR, { recursive: true });
  mkdirSync(RESULTS_DIR, { recursive: true });
  mkdirSync(WORK_DIR, { recursive: true });
  log({ event: "start", jobs_dir: JOBS_DIR, results_dir: RESULTS_DIR, repo: REPO_DIR, toolchains: TOOLCHAINS, poll_ms: POLL_MS });
  for (;;) {
    try {
      await pollOnce();
    } catch (exc) {
      log({ event: "error", code: "poll_failed", detail: String(exc.message).slice(0, 200) });
    }
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
}

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    log({ event: "stop", signal });
    process.exit(0);
  });
}

void main();

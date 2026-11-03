/**
 * Action implementations.
 *
 * Rules that hold for every implementation:
 *  - parameters are validated against a strict per-action key set first;
 *  - service names are checked against the allowlist enum before they are used;
 *  - commands are argv lists built from constants plus validated values;
 *  - output is redacted (shared redact module) and capped;
 *  - nothing is ever deleted, and no action touches .env, secrets/, data/ or the
 *    helper itself.
 */
import { createHash } from "node:crypto";
import {
  chmodSync,
  copyFileSync,
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
  statfsSync,
  writeFileSync,
} from "node:fs";
import type { ActionSpec, Allowlist, BuildSpec, Impl, ServiceList } from "./allowlist.js";
import { actionOf } from "./allowlist.js";
import type { AuditLog } from "./audit.js";
import type { OpsConfig } from "./config.js";
import { DockerCli, DockerError, pluginDir } from "./docker.js";
import { checkGates } from "./gates.js";
import { logger } from "./logger.js";
import { redactText } from "./redact.js";
import { findExecutable, type Runner } from "./runner.js";
import type { OpsState } from "./state.js";

export interface ActionDeps {
  cfg: OpsConfig;
  allow: Allowlist;
  docker: DockerCli;
  runner: Runner;
  audit: AuditLog;
  state: OpsState;
  now: () => number;
  sleep: (ms: number) => Promise<void>;
}

export type OpOutcome =
  | { ok: true; code?: string; message?: string; result: Record<string, unknown>; audit_id: string; tier: string; impl: string }
  | { ok: false; code: string; message: string; result?: Record<string, unknown>; audit_id: string; tier?: string; impl?: string };

type OpResult = { ok: true; result: Record<string, unknown> } | { ok: false; code: string; message: string; result?: Record<string, unknown> };

const SHA256_RE = /^[a-f0-9]{64}$/;

function sha256File(path: string): string {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function redactCap(text: string, maxBytes: number): { text: string; truncated: boolean } {
  const redacted = redactText(text);
  if (Buffer.byteLength(redacted, "utf8") <= maxBytes) return { text: redacted, truncated: false };
  return { text: redacted.slice(0, maxBytes), truncated: true };
}

function parseLabels(raw: unknown): Record<string, string> {
  if (typeof raw !== "string") return {};
  const out: Record<string, string> = {};
  for (const pair of raw.split(",")) {
    const eq = pair.indexOf("=");
    if (eq > 0) out[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim();
  }
  return out;
}

function healthFromStatus(status: string): string | null {
  if (/\(healthy\)/.test(status)) return "healthy";
  if (/\(unhealthy\)/.test(status)) return "unhealthy";
  if (/\(health: starting\)/.test(status)) return "starting";
  return null;
}

/* ------------------------------------------------------------------ */
/* parameter validation                                               */
/* ------------------------------------------------------------------ */

interface Validation {
  ok: boolean;
  code?: string;
  message?: string;
  params: Record<string, unknown>;
}

function rejectUnknownKeys(params: Record<string, unknown>, allowed: string[]): string | null {
  for (const key of Object.keys(params)) {
    if (!allowed.includes(key)) return `unknown parameter "${key}" (allowed: ${allowed.join(", ") || "none"})`;
  }
  return null;
}

function serviceParam(
  params: Record<string, unknown>,
  allow: Allowlist,
  list: ServiceList,
  required: boolean,
): { value: string | null; error: string | null } {
  const raw = params.service;
  if (raw === undefined || raw === null) {
    return required ? { value: null, error: `parameter "service" is required` } : { value: null, error: null };
  }
  if (typeof raw !== "string" || !allow.services[list].includes(raw)) {
    return {
      value: null,
      error: `service "${String(raw)}" is not allowed here (allowed: ${allow.services[list].join(", ")})`,
    };
  }
  return { value: raw, error: null };
}

function intParam(
  params: Record<string, unknown>,
  key: string,
  min: number,
  max: number,
  required: boolean,
): { value: number | null; error: string | null } {
  const raw = params[key];
  if (raw === undefined || raw === null) {
    return required ? { value: null, error: `parameter "${key}" is required` } : { value: null, error: null };
  }
  if (typeof raw !== "number" || !Number.isInteger(raw) || raw < min || raw > max) {
    return { value: null, error: `parameter "${key}" must be an integer between ${min} and ${max}` };
  }
  return { value: raw, error: null };
}

function boolParam(params: Record<string, unknown>, key: string): string | null {
  const raw = params[key];
  if (raw === undefined) return null;
  if (typeof raw !== "boolean") return `parameter "${key}" must be a boolean`;
  return null;
}

function validate(action: ActionSpec, rawParams: unknown, allow: Allowlist): Validation {
  const empty: Validation = { ok: false, code: "invalid_params", params: {} };
  if (rawParams !== undefined && rawParams !== null && (typeof rawParams !== "object" || Array.isArray(rawParams))) {
    return { ...empty, message: "params must be an object" };
  }
  const params = (rawParams ?? {}) as Record<string, unknown>;
  const bad = (message: string): Validation => ({ ok: false, code: "invalid_params", message, params: {} });
  const impl: Impl = action.impl;
  const limits = allow.limits;

  switch (impl) {
    case "status": {
      const unknown = rejectUnknownKeys(params, ["service"]);
      if (unknown) return bad(unknown);
      const service = serviceParam(params, allow, "observe", false);
      if (service.error) return bad(service.error);
      return { ok: true, params: { service: service.value } };
    }
    case "logs": {
      const unknown = rejectUnknownKeys(params, ["service", "tail"]);
      if (unknown) return bad(unknown);
      const service = serviceParam(params, allow, "logs", true);
      if (service.error) return bad(service.error);
      const tail = intParam(params, "tail", 1, limits.maxLogLines, false);
      if (tail.error) return bad(tail.error);
      return { ok: true, params: { service: service.value, tail: tail.value ?? 50 } };
    }
    case "players": {
      const unknown = rejectUnknownKeys(params, ["service"]);
      if (unknown) return bad(unknown);
      const service = serviceParam(params, allow, "players", true);
      if (service.error) return bad(service.error);
      return { ok: true, params: { service: service.value } };
    }
    case "run_tests": {
      const unknown = rejectUnknownKeys(params, ["target"]);
      if (unknown) return bad(unknown);
      const target = params.target;
      if (typeof target !== "string" || !Object.prototype.hasOwnProperty.call(allow.build.tests, target)) {
        return bad(`parameter "target" must be one of: ${Object.keys(allow.build.tests).join(", ")}`);
      }
      return { ok: true, params: { target } };
    }
    case "restart_service": {
      const unknown = rejectUnknownKeys(params, ["service", "confirm"]);
      if (unknown) return bad(unknown);
      const service = serviceParam(params, allow, "restartT1", true);
      if (service.error) return bad(service.error);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      return { ok: true, params: { service: service.value, confirm: params.confirm === true } };
    }
    case "restart_game": {
      const unknown = rejectUnknownKeys(params, ["service", "confirm", "delay_seconds"]);
      if (unknown) return bad(unknown);
      const service = serviceParam(params, allow, "restartGame", true);
      if (service.error) return bad(service.error);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      const delay = intParam(params, "delay_seconds", 1, 3600, true);
      if (delay.error) return bad(delay.error);
      return {
        ok: true,
        params: { service: service.value, confirm: params.confirm === true, delay_seconds: delay.value },
      };
    }
    case "deploy_plugin": {
      const unknown = rejectUnknownKeys(params, ["confirm", "delay_seconds", "artifact_sha256"]);
      if (unknown) return bad(unknown);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      const delay = intParam(params, "delay_seconds", 1, 3600, true);
      if (delay.error) return bad(delay.error);
      const sha = params.artifact_sha256;
      if (typeof sha !== "string" || !SHA256_RE.test(sha)) {
        return bad(`parameter "artifact_sha256" must be the sha256 of the last ops_build_plugin artifact`);
      }
      return { ok: true, params: { confirm: params.confirm === true, delay_seconds: delay.value, artifact_sha256: sha } };
    }
    case "rollback_plugin": {
      const unknown = rejectUnknownKeys(params, ["confirm", "delay_seconds"]);
      if (unknown) return bad(unknown);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      const delay = intParam(params, "delay_seconds", 1, 3600, true);
      if (delay.error) return bad(delay.error);
      return { ok: true, params: { confirm: params.confirm === true, delay_seconds: delay.value } };
    }
    case "disk":
    case "backups_list":
    case "git_state":
    case "build_plugin": {
      const unknown = rejectUnknownKeys(params, []);
      if (unknown) return bad(unknown);
      return { ok: true, params: {} };
    }
    case "backup_now": {
      const unknown = rejectUnknownKeys(params, ["confirm"]);
      if (unknown) return bad(unknown);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      return { ok: true, params: { confirm: params.confirm === true } };
    }
    default: {
      const exhaustive: never = impl;
      return bad(`unimplemented action ${String(exhaustive)}`);
    }
  }
}

/* ------------------------------------------------------------------ */
/* dispatcher                                                          */
/* ------------------------------------------------------------------ */

export async function handleOp(rawAction: unknown, rawParams: unknown, deps: ActionDeps): Promise<OpOutcome> {
  const started = deps.now();
  const actionName = typeof rawAction === "string" ? rawAction : String(rawAction ?? "");
  const action = actionOf(deps.allow, actionName);

  if (!action) {
    const auditId = deps.audit.append({
      action: actionName || "(empty)",
      decision: "deny",
      code: "unknown_action",
      params: rawParams,
    });
    return {
      ok: false,
      code: "unknown_action",
      message: `action "${actionName}" is not in the allowlist`,
      audit_id: auditId,
    };
  }

  const validation = validate(action, rawParams, deps.allow);
  if (!validation.ok) {
    const auditId = deps.audit.append({
      action: actionName,
      tier: action.tier,
      impl: action.impl,
      decision: "deny",
      code: validation.code,
      params: rawParams,
    });
    return {
      ok: false,
      code: validation.code ?? "invalid_params",
      message: validation.message ?? "invalid parameters",
      tier: action.tier,
      impl: action.impl,
      audit_id: auditId,
    };
  }

  const rules = deps.allow.tiers[action.tier];
  const gate = checkGates(action, rules, validation.params, {
    armed: deps.cfg.armed,
    killSwitch: deps.cfg.disabled || existsSync(`${deps.cfg.dataDir}/DISABLED`),
    now: started,
    cooldownRemaining: (key, seconds) => deps.state.cooldownRemaining(key, seconds, started),
    rateHit: (key, windowSeconds) => deps.state.rateHit(key, windowSeconds, started),
    rateWindowSeconds: deps.allow.limits.rateWindowSeconds,
    rateMaxCalls: deps.allow.limits.rateMaxCalls,
  });

  if (!gate.ok) {
    const auditId = deps.audit.append({
      action: actionName,
      tier: action.tier,
      impl: action.impl,
      decision: "deny",
      code: gate.code,
      params: validation.params,
    });
    return {
      ok: false,
      code: gate.code ?? "denied",
      message: gate.message ?? "denied",
      result: gate.retry_after_seconds === undefined ? undefined : { retry_after_seconds: gate.retry_after_seconds },
      tier: action.tier,
      impl: action.impl,
      audit_id: auditId,
    };
  }

  // Gates passed: the cooldown starts now, even if the action itself fails, so a
  // broken action cannot be hammered.
  deps.state.recordRun(actionName, started);

  let outcome: OpResult;
  try {
    outcome = await execute(action, validation.params, deps);
  } catch (exc) {
    const err = exc as Error;
    const isDocker = exc instanceof DockerError;
    outcome = {
      ok: false,
      code: isDocker ? (exc as DockerError).code : "internal_error",
      message: redactText(err.message ?? "unknown error").slice(0, 500),
    };
  }

  const auditId = deps.audit.append({
    action: actionName,
    tier: action.tier,
    impl: action.impl,
    decision: outcome.ok ? "allow" : "error",
    code: outcome.ok ? undefined : outcome.code,
    params: validation.params,
    durationMs: deps.now() - started,
    ok: outcome.ok,
    summary: outcome.ok ? summarizeForAudit(outcome.result) : { code: outcome.code, message: outcome.message },
  });

  if (!outcome.ok) {
    return {
      ok: false,
      code: outcome.code,
      message: outcome.message,
      result: outcome.result,
      tier: action.tier,
      impl: action.impl,
      audit_id: auditId,
    };
  }
  return {
    ok: true,
    result: outcome.result,
    tier: action.tier,
    impl: action.impl,
    audit_id: auditId,
  };
}

/** Keep the audit line small: a log tail belongs in the tool answer, not the log. */
function summarizeForAudit(result: Record<string, unknown>): Record<string, unknown> {
  const summary: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(result)) {
    if (Array.isArray(value)) summary[`${key}_count`] = value.length;
    else if (typeof value === "string" && value.length > 200) summary[key] = `${value.slice(0, 200)}…`;
    else summary[key] = value;
  }
  return summary;
}

async function execute(action: ActionSpec, params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  switch (action.impl) {
    case "status":
      return await opStatus(params, deps);
    case "logs":
      return await opLogs(params, deps);
    case "disk":
      return opDisk(deps);
    case "backups_list":
      return opBackupsList(deps);
    case "players":
      return await opPlayers(params, deps);
    case "git_state":
      return await opGitState(deps);
    case "build_plugin":
      return await opBuildPlugin(deps);
    case "run_tests":
      return await opRunTests(params, deps);
    case "restart_service":
      return await opRestartService(params, deps);
    case "backup_now":
      return await opBackupNow(deps);
    case "restart_game":
      return await opRestartGame(params, deps);
    case "deploy_plugin":
      return await opDeployPlugin(params, deps);
    case "rollback_plugin":
      return await opRollbackPlugin(params, deps);
    default:
      return { ok: false, code: "not_implemented", message: `impl ${String(action.impl)} has no implementation` };
  }
}

/* ------------------------------------------------------------------ */
/* T0: monitoring                                                      */
/* ------------------------------------------------------------------ */

async function opStatus(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const service = params.service as string | null;
  const rows = await deps.docker.ps(service ?? undefined);
  const containers = rows.map((row) => {
    const labels = parseLabels(row.Labels);
    const svc = labels["com.docker.compose.service"] ?? null;
    const status = typeof row.Status === "string" ? row.Status : "";
    return {
      service: svc,
      name: row.Names ?? null,
      image: row.Image ?? null,
      state: row.State ?? null,
      status,
      health: healthFromStatus(status),
      known: svc !== null && deps.allow.services.observe.includes(svc),
    };
  });
  const running = containers.filter((c) => c.state === "running").length;
  return {
    ok: true,
    result: {
      project: deps.allow.composeProject,
      containers,
      counts: {
        total: containers.length,
        running,
        stopped: containers.length - running,
        unhealthy: containers.filter((c) => c.health === "unhealthy").map((c) => c.service),
      },
      note: "docker compose ps equivalent, filtered by the compose project label",
    },
  };
}

async function opLogs(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const service = params.service as string;
  const tail = params.tail as number;
  const id = await deps.docker.containerId(service);
  const { text, truncated } = await deps.docker.logs(id, tail);
  const capped = redactCap(text, deps.allow.limits.maxOutputBytes);
  const lines = capped.text.split("\n").filter((line) => line.length > 0);
  return {
    ok: true,
    result: {
      service,
      container: id.slice(0, 12),
      tail,
      lines: lines.slice(-tail),
      truncated: truncated || capped.truncated,
      redacted: capped.text !== text,
    },
  };
}

function statfsEntry(path: string): Record<string, unknown> | null {
  try {
    const st = statfsSync(path);
    const total = Number(st.bsize) * Number(st.blocks);
    const free = Number(st.bsize) * Number(st.bavail);
    return { path, total_bytes: total, free_bytes: free, used_bytes: total - free };
  } catch {
    return null;
  }
}

function duBytes(deps: ActionDeps, path: string, timeoutMs: number): Promise<Record<string, unknown>> {
  return deps.runner
    .run(["du", "-sk", path], { timeoutMs, maxBytes: 4096 })
    .then((res) => {
      if (res.timedOut) return { path, bytes: null, timed_out: true };
      const kb = Number((res.stdout.trim().split(/\s+/)[0] ?? "").trim());
      if (!res.ok || !Number.isFinite(kb)) {
        return { path, bytes: null, error: redactText(res.stderr.trim().slice(0, 200)) || "du failed" };
      }
      return { path, bytes: kb * 1024 };
    });
}

async function opDisk(deps: ActionDeps): Promise<OpResult> {
  const timeoutMs = Math.min(deps.cfg.actionTimeoutSeconds, 60) * 1000;
  const paths = {
    repo: statfsEntry(deps.allow.paths.repo),
    backups: statfsEntry(deps.allow.paths.backups),
    artifacts: statfsEntry(deps.allow.paths.artifacts),
  };
  const sizes = [
    await duBytes(deps, deps.allow.paths.backups, timeoutMs),
    await duBytes(deps, `${deps.allow.paths.repo}/data`, timeoutMs),
  ];
  return {
    ok: true,
    result: {
      filesystems: paths,
      directory_sizes: sizes,
      note: "directory sizes use `du -sk` with a bounded timeout; a timeout is reported as timed_out instead of a wrong number",
    },
  };
}

function readBackupEntries(dir: string): Array<Record<string, unknown>> {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("daily-"))
    .map((entry) => {
      const full = `${dir}/${entry.name}`;
      let mtime: string | null = null;
      try {
        mtime = statSync(full).mtime.toISOString();
      } catch {
        mtime = null;
      }
      return {
        name: entry.name,
        created_at: mtime,
        complete: existsSync(`${full}/COMPLETE.txt`),
        has_sql_dump: existsSync(`${full}/craftcore.sql`),
      };
    })
    .sort((a, b) => String(b.name).localeCompare(String(a.name)));
}

function opBackupsList(deps: ActionDeps): OpResult {
  const dir = deps.allow.paths.backups;
  const entries = readBackupEntries(dir);
  return {
    ok: true,
    result: {
      dir,
      count: entries.length,
      backups: entries.slice(0, 50),
      last_complete: entries.find((e) => e.complete === true)?.name ?? null,
    },
  };
}

async function opPlayers(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const service = params.service as string;
  const id = await deps.docker.containerId(service);
  const res = await deps.docker.exec(id, ["rcon-cli", "list"]);
  const text = redactText(res.text).slice(0, 2000);
  if (!res.ok) {
    return {
      ok: false,
      code: "rcon_failed",
      message: `rcon-cli list failed in ${service}: ${text.slice(0, 300) || "no output"}`,
    };
  }
  const match = /There are (\d+) of a max of (\d+) players online:?\s*(.*)/i.exec(text);
  return {
    ok: true,
    result: {
      service,
      container: id.slice(0, 12),
      online: match ? Number(match[1]) : null,
      max: match ? Number(match[2]) : null,
      players: match && match[3] ? match[3].split(",").map((n) => n.trim()).filter(Boolean) : [],
      raw: match ? undefined : text,
      note: "TPS/MSPT are deliberately not queried: Paper's /tps command availability was not verified in this stack",
    },
  };
}

async function opGitState(deps: ActionDeps): Promise<OpResult> {
  const repo = deps.allow.paths.repo;
  const git = async (args: string[]) =>
    await deps.runner.run(["git", "-C", repo, ...args], {
      env: { GIT_OPTIONAL_LOCKS: "0" },
      timeoutMs: deps.cfg.actionTimeoutSeconds * 1000,
      maxBytes: 65536,
    });

  const branch = await git(["rev-parse", "--abbrev-ref", "HEAD"]);
  const head = await git(["rev-parse", "--short", "HEAD"]);
  const date = await git(["log", "-1", "--format=%cI"]);
  const status = await git(["status", "--porcelain", "--untracked-files=no"]);
  const track = await git(["rev-list", "--left-right", "--count", "HEAD...@{upstream}"]);

  if (!branch.ok || !head.ok) {
    return {
      ok: false,
      code: "git_failed",
      message: `git could not read ${repo}: ${redactText((branch.stderr || head.stderr || "").trim().slice(0, 200))}`,
    };
  }
  const dirty = status.stdout.split("\n").filter((line) => line.trim().length > 0).length;
  const counts = track.ok ? track.stdout.trim().split(/\s+/) : [];
  return {
    ok: true,
    result: {
      repo,
      branch: branch.stdout.trim(),
      head: head.stdout.trim(),
      commit_date: date.ok ? date.stdout.trim() : null,
      dirty_files: dirty,
      ahead: counts.length === 2 ? Number(counts[0]) : null,
      behind: counts.length === 2 ? Number(counts[1]) : null,
      note: "dirty files are reported as a count only; no file contents or names leave the helper",
    },
  };
}

/* ------------------------------------------------------------------ */
/* T0: development                                                     */
/* ------------------------------------------------------------------ */

function buildId(now: number): string {
  const d = new Date(now);
  const stamp = [
    d.getUTCFullYear(),
    String(d.getUTCMonth() + 1).padStart(2, "0"),
    String(d.getUTCDate()).padStart(2, "0"),
  ].join("") + "-" + [
    String(d.getUTCHours()).padStart(2, "0"),
    String(d.getUTCMinutes()).padStart(2, "0"),
    String(d.getUTCSeconds()).padStart(2, "0"),
  ].join("");
  const rand = Math.random().toString(36).slice(2, 8).padEnd(6, "0");
  return `${stamp}-${rand}`;
}

/** Copy a repo subtree into the shared artifact volume (build containers see it there). */
function stageSource(deps: ActionDeps, id: string, spec: BuildSpec): string {
  if (!deps.cfg.artifactsVolume) {
    throw new DockerError(
      "artifacts_volume_unset",
      "OPS_ARTIFACTS_VOLUME is not set: build/test containers need the named volume, otherwise the daemon would create an empty host path",
    );
  }
  const source = `${deps.allow.paths.repo}/${spec.source}`;
  if (!existsSync(source)) {
    throw new DockerError("source_missing", `build source ${spec.source} does not exist in the repo`);
  }
  const root = `${deps.cfg.artifactsDir}/build/${id}`;
  mkdirSync(root, { recursive: true, mode: 0o777 });
  cpSync(source, `${root}/src`, {
    recursive: true,
    filter: (src) => !/[/\\](node_modules|\.git|\.gradle|build|target)([/\\]|$)/.test(src),
  });
  // Build containers run as uid 1000 (gradle/node images); the staged tree must
  // be writable for them.
  chmodSync(`${root}/src`, 0o777);
  return root;
}

async function runInContainer(
  deps: ActionDeps,
  opts: { id: string; spec: BuildSpec; command: string[] },
): Promise<{ ok: boolean; code: number | null; stdout: string; stderr: string; timedOut: boolean; root: string }> {
  const root = stageSource(deps, opts.id, opts.spec);
  const volumes = [`${deps.cfg.artifactsVolume}:${deps.cfg.artifactsDir}`];
  if (deps.cfg.gradleCacheVolume) volumes.push(`${deps.cfg.gradleCacheVolume}:/home/gradle/.gradle`);
  const res = await deps.docker.runContainer(
    {
      image: opts.spec.image,
      command: opts.command,
      volumes,
      workdir: `${deps.cfg.artifactsDir}/build/${opts.id}/src`,
    },
    deps.cfg.buildTimeoutSeconds * 1000,
  );
  return { ok: res.ok, code: res.code, stdout: res.stdout, stderr: res.stderr, timedOut: res.timedOut, root };
}

interface TestCounts {
  tests: number;
  failures: number;
  errors: number;
  skipped: number;
}

function readJUnitCounts(dir: string): TestCounts | null {
  if (!existsSync(dir)) return null;
  const totals: TestCounts = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  let seen = 0;
  for (const entry of readdirSync(dir)) {
    if (!entry.endsWith(".xml")) continue;
    let content: string;
    try {
      content = readFileSync(`${dir}/${entry}`, "utf8");
    } catch {
      continue;
    }
    const m = /<testsuite\b[^>]*>/.exec(content);
    if (!m) continue;
    const attr = (name: string) => Number(new RegExp(`${name}="(\\d+)"`).exec(m[0])?.[1] ?? "0");
    totals.tests += attr("tests");
    totals.failures += attr("failures");
    totals.errors += attr("errors");
    totals.skipped += attr("skipped");
    seen += 1;
  }
  return seen > 0 ? totals : null;
}

async function opBuildPlugin(deps: ActionDeps): Promise<OpResult> {
  const spec = deps.allow.build.plugin;
  const id = buildId(deps.now());
  const run = await runInContainer(deps, { id, spec, command: spec.command });
  const tests = spec.test_results ? readJUnitCounts(`${run.root}/src/${spec.test_results}`) : null;
  const output = redactCap([run.stdout, run.stderr].filter(Boolean).join("\n"), 8192);

  if (!run.ok) {
    return {
      ok: false,
      code: run.timedOut ? "build_timeout" : "build_failed",
      message: `gradle build failed (exit ${run.code ?? "?"}) in ${spec.image}`,
      result: { build_id: id, tests, output_tail: output.text, truncated: output.truncated },
    };
  }

  const artifactPath = `${run.root}/src/${spec.artifact}`;
  if (!existsSync(artifactPath)) {
    return {
      ok: false,
      code: "artifact_missing",
      message: `build succeeded but ${spec.artifact} is missing`,
      result: { build_id: id, tests, output_tail: output.text },
    };
  }
  const sha = sha256File(artifactPath);
  const size = statSync(artifactPath).size;
  const kept = `${deps.cfg.artifactsDir}/artifacts/craftcore-${sha.slice(0, 12)}.jar`;
  mkdirSync(`${deps.cfg.artifactsDir}/artifacts`, { recursive: true, mode: 0o777 });
  copyFileSync(artifactPath, kept);
  const record = {
    build_id: id,
    sha256: sha,
    size_bytes: size,
    artifact_path: kept,
    source: spec.source,
    tests,
    at: new Date(deps.now()).toISOString(),
  };
  mkdirSync(`${deps.cfg.artifactsDir}/state`, { recursive: true, mode: 0o777 });
  writeFileSync(`${deps.cfg.artifactsDir}/state/last-build.json`, JSON.stringify(record, null, 2), { encoding: "utf8", mode: 0o644 });

  return {
    ok: true,
    result: {
      ...record,
      deployed: false,
      note: "the artifact is NOT deployed; ops_deploy_plugin needs this sha256 and the armed switch",
      output_tail: output.text,
      truncated: output.truncated,
    },
  };
}

async function opRunTests(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const target = params.target as string;
  const spec = deps.allow.build.tests[target];
  const id = buildId(deps.now());
  let root = "";
  const outputs: string[] = [];

  if (spec.setup && spec.setup.length > 0) {
    const setup = await runInContainer(deps, { id, spec, command: spec.setup });
    root = setup.root;
    outputs.push(redactCap([setup.stdout, setup.stderr].join("\n"), 4096).text);
    if (!setup.ok) {
      return {
        ok: false,
        code: setup.timedOut ? "setup_timeout" : "setup_failed",
        message: `setup step ${spec.setup.join(" ")} failed (exit ${setup.code ?? "?"})`,
        result: { target, output_tail: outputs.join("\n").slice(-4096) },
      };
    }
  }

  const run = await runInContainer(deps, { id, spec, command: spec.command });
  root = run.root;
  outputs.push(redactCap([run.stdout, run.stderr].join("\n"), 8192).text);

  const tests = spec.test_results
    ? readJUnitCounts(`${root}/src/${spec.test_results}`)
    : (() => {
        const pass = /# pass (\d+)/.exec(run.stdout);
        const fail = /# fail (\d+)/.exec(run.stdout);
        if (!pass && !fail) return null;
        return {
          tests: Number(pass?.[1] ?? 0) + Number(fail?.[1] ?? 0),
          failures: Number(fail?.[1] ?? 0),
          errors: 0,
          skipped: 0,
        };
      })();

  const result = {
    target,
    image: spec.image,
    command: spec.command,
    tests,
    output_tail: outputs.join("\n").slice(-8192),
  };
  if (!run.ok) {
    return {
      ok: false,
      code: run.timedOut ? "test_timeout" : "tests_failed",
      message: `${target} tests failed (exit ${run.code ?? "?"})`,
      result,
    };
  }
  return { ok: true, result };
}

/* ------------------------------------------------------------------ */
/* T1 / T2: mutations                                                  */
/* ------------------------------------------------------------------ */

async function opRestartService(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const service = params.service as string;
  const id = await deps.docker.containerId(service);
  await deps.docker.restart(id, (deps.cfg.actionTimeoutSeconds + 60) * 1000);
  const state = await deps.docker.state(id);
  return {
    ok: true,
    result: {
      service,
      container: id.slice(0, 12),
      state: state.status,
      health: state.health,
      note: "reversible, no player impact for the T1 allowlist (bot, cloudflared-mcp)",
    },
  };
}

interface BackupCheck {
  ok: boolean;
  code?: string;
  message?: string;
  result: Record<string, unknown>;
}

/** A backup younger than limits.backup_max_age_seconds with a COMPLETE.txt marker. */
function findFreshBackup(deps: ActionDeps): Record<string, unknown> | null {
  const entries = readBackupEntries(deps.allow.paths.backups);
  const fresh = entries.find((entry) => entry.complete === true);
  if (!fresh) return null;
  const createdAt = fresh.created_at ? Date.parse(String(fresh.created_at)) : NaN;
  if (!Number.isFinite(createdAt)) return null;
  const ageSeconds = Math.round((deps.now() - createdAt) / 1000);
  if (ageSeconds > deps.allow.limits.backupMaxAgeSeconds) return { ...fresh, age_seconds: ageSeconds, fresh: false };
  return { ...fresh, age_seconds: ageSeconds, fresh: true };
}

/**
 * Ensure a usable backup exists before a T2 action.
 *
 * The repo's own backup.ps1 is the only backup procedure we are allowed to use.
 * It is a PowerShell script and the helper image has no pwsh (see README), so the
 * helper tries the configured interpreters, and - when none is present - accepts a
 * recent backup that a human made on the host. It never invents a second backup
 * procedure.
 */
async function ensureFreshBackup(deps: ActionDeps, attemptCreate: boolean): Promise<BackupCheck> {
  const before = findFreshBackup(deps);
  if (before && before.fresh === true) {
    return {
      ok: true,
      result: { mode: "existing_recent_backup", backup: before, created: false },
    };
  }

  const script = `${deps.allow.paths.repo}/${deps.allow.paths.backupScript}`;
  const interpreter = attemptCreate
    ? deps.cfg.backupInterpreters.map((name) => findExecutable(name)).find((path) => path !== null) ?? null
    : null;

  if (!interpreter) {
    return {
      ok: false,
      code: "backup_interpreter_missing",
      message:
        "no PowerShell interpreter in the ops-helper image, so the repo's backup.ps1 cannot be run from here. " +
        "Run it on the host (powershell -ExecutionPolicy Bypass -File <repo>\\backup.ps1) or add pwsh to the helper image.",
      result: {
        mode: "unavailable",
        created: false,
        script,
        interpreters: deps.cfg.backupInterpreters,
        last_backup: before ?? null,
        max_age_seconds: deps.allow.limits.backupMaxAgeSeconds,
      },
    };
  }

  if (!existsSync(script)) {
    return {
      ok: false,
      code: "backup_script_missing",
      message: `${deps.allow.paths.backupScript} not found in the repo mount`,
      result: { mode: "unavailable", created: false, script },
    };
  }

  const res = await deps.runner.run([interpreter, "-NoProfile", "-NonInteractive", "-File", script], {
    cwd: deps.allow.paths.repo,
    timeoutMs: Math.min(deps.cfg.buildTimeoutSeconds, 3600) * 1000,
    maxBytes: 65536,
  });
  const output = redactCap([res.stdout, res.stderr].join("\n"), 4096);
  const after = findFreshBackup(deps);
  if (!res.ok || !after || after.fresh !== true) {
    return {
      ok: false,
      code: "backup_failed",
      message: `backup.ps1 did not produce a fresh backup (exit ${res.code ?? "?"})`,
      result: { mode: "script", created: false, output_tail: output.text, last_backup: after ?? null },
    };
  }
  return {
    ok: true,
    result: { mode: "script", created: true, backup: after, output_tail: output.text },
  };
}

async function opBackupNow(deps: ActionDeps): Promise<OpResult> {
  const check = await ensureFreshBackup(deps, true);
  if (!check.ok) {
    return { ok: false, code: check.code ?? "backup_failed", message: check.message ?? "backup failed", result: check.result };
  }
  return { ok: true, result: check.result };
}

async function announce(deps: ActionDeps, service: string, delaySeconds: number): Promise<Record<string, unknown>> {
  const candidates = deps.allow.services.players.includes(service) ? [service, "velocity"] : ["velocity", service];
  const message = `Server ${service} startet in ${delaySeconds} Sekunden neu (Wartung).`;
  for (const candidate of candidates) {
    let id: string;
    try {
      id = await deps.docker.containerId(candidate);
    } catch {
      continue;
    }
    const res = await deps.docker.exec(id, ["rcon-cli", `say ${message}`]);
    if (res.ok) {
      return { announced: true, via: candidate, message };
    }
  }
  return {
    announced: false,
    via: null,
    message,
    detail:
      "no container answered `rcon-cli say` (velocity has ENABLE_RCON=false and its image has no rcon-cli) - " +
      "NO in-game announcement was made, players were not warned",
  };
}

async function waitForHealthy(deps: ActionDeps, service: string): Promise<{ healthy: boolean; state: Record<string, unknown> }> {
  const id = await deps.docker.containerId(service);
  const deadline = deps.now() + deps.allow.deploy.healthTimeoutSeconds * 1000;
  let last: Record<string, unknown> = {};
  for (;;) {
    const state = await deps.docker.state(id);
    last = { status: state.status, health: state.health };
    const healthy = state.running && (state.health === null || state.health === "healthy");
    if (healthy) return { healthy: true, state: last };
    if (deps.now() >= deadline) return { healthy: false, state: last };
    await deps.sleep(deps.allow.deploy.healthPollSeconds * 1000);
  }
}

async function opRestartGame(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const service = params.service as string;
  const delay = params.delay_seconds as number;
  const id = await deps.docker.containerId(service);
  const announcement = await announce(deps, service, delay);
  await deps.sleep(delay * 1000);
  await deps.docker.restart(id, (deps.cfg.actionTimeoutSeconds + 60) * 1000);
  const health = await waitForHealthy(deps, service);
  if (!health.healthy) {
    return {
      ok: false,
      code: "health_check_failed",
      message: `${service} did not become healthy within ${deps.allow.deploy.healthTimeoutSeconds}s after the restart`,
      result: { service, announcement, state: health.state },
    };
  }
  return {
    ok: true,
    result: { service, container: id.slice(0, 12), announcement, state: health.state, delay_seconds: delay },
  };
}

function readJson(path: string): Record<string, unknown> | null {
  if (!existsSync(path)) return null;
  try {
    return JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function writeJson(path: string, value: unknown): void {
  mkdirSync(path.slice(0, path.lastIndexOf("/")), { recursive: true, mode: 0o777 });
  writeFileSync(path, JSON.stringify(value, null, 2), { encoding: "utf8", mode: 0o644 });
}

async function opDeployPlugin(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const sha = params.artifact_sha256 as string;
  const delay = params.delay_seconds as number;
  const last = readJson(`${deps.cfg.artifactsDir}/state/last-build.json`);
  if (!last || typeof last.sha256 !== "string") {
    return { ok: false, code: "no_build_artifact", message: "no ops_build_plugin artifact recorded yet" };
  }
  if (last.sha256 !== sha) {
    return {
      ok: false,
      code: "artifact_mismatch",
      message: `artifact_sha256 does not match the last build (${String(last.sha256).slice(0, 12)}…)`,
      result: { expected: last.sha256 },
    };
  }
  const artifactPath = typeof last.artifact_path === "string" ? last.artifact_path : "";
  if (!artifactPath || !existsSync(artifactPath)) {
    return { ok: false, code: "artifact_missing", message: "the recorded artifact file is gone; rebuild first" };
  }

  const backup = await ensureFreshBackup(deps, true);
  if (!backup.ok) {
    return { ok: false, code: backup.code ?? "backup_failed", message: backup.message ?? "backup failed", result: backup.result };
  }

  const rollbackDir = `${deps.cfg.artifactsDir}/rollback`;
  mkdirSync(rollbackDir, { recursive: true, mode: 0o777 });
  const previous: Record<string, Record<string, unknown>> = {};
  for (const service of deps.allow.deploy.order) {
    const jar = `${pluginDir(deps.allow, service)}/${deps.allow.paths.pluginJar}`;
    const oldSha = existsSync(jar) ? sha256File(jar) : null;
    let kept: string | null = null;
    if (oldSha) {
      kept = `${rollbackDir}/${service}-craftcore-${oldSha.slice(0, 12)}.jar`;
      copyFileSync(jar, kept);
    }
    previous[service] = { sha256: oldSha, jar: kept };
  }
  writeJson(`${deps.cfg.artifactsDir}/state/last-deploy.json`, {
    at: new Date(deps.now()).toISOString(),
    artifact_sha256: sha,
    artifact_path: artifactPath,
    previous,
  });

  const announcement = await announce(deps, deps.allow.deploy.order[0], delay);
  await deps.sleep(delay * 1000);

  const steps: Array<Record<string, unknown>> = [];
  for (const [index, service] of deps.allow.deploy.order.entries()) {
    if (index > 0) await deps.sleep(delay * 1000);
    const jar = `${pluginDir(deps.allow, service)}/${deps.allow.paths.pluginJar}`;
    copyFileSync(artifactPath, jar);
    const written = sha256File(jar);
    if (written !== sha) {
      const rolled = await rollbackServices(deps, previous, steps);
      return {
        ok: false,
        code: "jar_hash_mismatch",
        message: `the jar written to ${service} does not match the artifact hash`,
        result: { service, written, steps, rollback: rolled },
      };
    }
    try {
      await deps.docker.restart(await deps.docker.containerId(service), (deps.cfg.actionTimeoutSeconds + 60) * 1000);
    } catch (exc) {
      const rolled = await rollbackServices(deps, previous, steps);
      return {
        ok: false,
        code: "restart_failed",
        message: `restart of ${service} failed: ${redactText((exc as Error).message).slice(0, 300)}`,
        result: { steps, rollback: rolled },
      };
    }
    const health = await waitForHealthy(deps, service);
    steps.push({ service, jar_written: written, state: health.state, healthy: health.healthy });
    if (!health.healthy) {
      const rolled = await rollbackServices(deps, previous, steps);
      return {
        ok: false,
        code: "health_check_failed_rolled_back",
        message: `${service} did not become healthy; the previous jars were restored`,
        result: { steps, rollback: rolled },
      };
    }
  }

  return {
    ok: true,
    result: {
      artifact_sha256: sha,
      artifact_path: artifactPath,
      backup,
      announcement,
      delay_seconds: delay,
      steps,
      rollback_jars: previous,
    },
  };
}

async function rollbackServices(
  deps: ActionDeps,
  previous: Record<string, Record<string, unknown>>,
  steps: Array<Record<string, unknown>>,
): Promise<Array<Record<string, unknown>>> {
  const out: Array<Record<string, unknown>> = [];
  for (const service of [...deps.allow.deploy.order].reverse()) {
    const prev = previous[service];
    const kept = prev && typeof prev.jar === "string" ? prev.jar : null;
    if (!kept || !existsSync(kept)) {
      out.push({ service, restored: false, reason: "no previous jar was recorded" });
      continue;
    }
    try {
      copyFileSync(kept, `${pluginDir(deps.allow, service)}/${deps.allow.paths.pluginJar}`);
      await deps.docker.restart(await deps.docker.containerId(service), (deps.cfg.actionTimeoutSeconds + 60) * 1000);
      const health = await waitForHealthy(deps, service);
      out.push({ service, restored: true, healthy: health.healthy, state: health.state });
    } catch (exc) {
      out.push({ service, restored: false, reason: redactText((exc as Error).message).slice(0, 200) });
    }
  }
  logger.warn("deploy rolled back", { services: out.map((entry) => entry.service) });
  return out;
}

async function opRollbackPlugin(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const delay = params.delay_seconds as number;
  const record = readJson(`${deps.cfg.artifactsDir}/state/last-deploy.json`);
  if (!record || typeof record.previous !== "object" || record.previous === null) {
    return { ok: false, code: "no_rollback_available", message: "no recorded deploy to roll back" };
  }
  const previous = record.previous as Record<string, Record<string, unknown>>;
  const announcement = await announce(deps, deps.allow.deploy.order[0], delay);
  await deps.sleep(delay * 1000);
  const steps: Array<Record<string, unknown>> = [];
  for (const [index, service] of deps.allow.deploy.order.entries()) {
    if (index > 0) await deps.sleep(delay * 1000);
    const prev = previous[service];
    const kept = prev && typeof prev.jar === "string" ? prev.jar : null;
    if (!kept || !existsSync(kept)) {
      steps.push({ service, restored: false, reason: "no previous jar was recorded" });
      continue;
    }
    copyFileSync(kept, `${pluginDir(deps.allow, service)}/${deps.allow.paths.pluginJar}`);
    await deps.docker.restart(await deps.docker.containerId(service), (deps.cfg.actionTimeoutSeconds + 60) * 1000);
    const health = await waitForHealthy(deps, service);
    steps.push({ service, restored: true, healthy: health.healthy, state: health.state });
  }
  const failed = steps.filter((step) => step.restored === true && step.healthy !== true);
  const result = { rolled_back_from: record.artifact_sha256 ?? null, announcement, delay_seconds: delay, steps };
  if (failed.length > 0) {
    return {
      ok: false,
      code: "health_check_failed",
      message: `${failed.map((s) => s.service).join(", ")} did not become healthy after the rollback`,
      result,
    };
  }
  return { ok: true, result };
}

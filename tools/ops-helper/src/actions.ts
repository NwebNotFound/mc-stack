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
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
  statfsSync,
  writeFileSync,
} from "node:fs";
import type { ActionSpec, Allowlist, Impl, ServiceList } from "./allowlist.js";
import { actionOf } from "./allowlist.js";
import type { AuditLog } from "./audit.js";
import type { OpsConfig } from "./config.js";
import { DockerCli, DockerError, pluginDir } from "./docker.js";
import { checkGates } from "./gates.js";
import { makeJob, newJobId, REF_RE, type BuilderResult, type JobClient } from "./jobs.js";
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
  /** Build/test jobs go to plugin-builder through this channel. */
  jobs: JobClient;
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
      const unknown = rejectUnknownKeys(params, ["service", "confirm", "delay_seconds", "acknowledge_no_announce"]);
      if (unknown) return bad(unknown);
      const service = serviceParam(params, allow, "restartGame", true);
      if (service.error) return bad(service.error);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      const ack = boolParam(params, "acknowledge_no_announce");
      if (ack) return bad(ack);
      const delay = intParam(params, "delay_seconds", 1, 3600, true);
      if (delay.error) return bad(delay.error);
      return {
        ok: true,
        params: {
          service: service.value,
          confirm: params.confirm === true,
          delay_seconds: delay.value,
          acknowledge_no_announce: params.acknowledge_no_announce === true,
        },
      };
    }
    case "deploy_plugin": {
      const unknown = rejectUnknownKeys(params, ["confirm", "delay_seconds", "artifact_sha256", "acknowledge_no_announce"]);
      if (unknown) return bad(unknown);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      const ack = boolParam(params, "acknowledge_no_announce");
      if (ack) return bad(ack);
      const delay = intParam(params, "delay_seconds", 1, 3600, true);
      if (delay.error) return bad(delay.error);
      const sha = params.artifact_sha256;
      if (typeof sha !== "string" || !SHA256_RE.test(sha)) {
        return bad(`parameter "artifact_sha256" must be the sha256 of the last ops_build_plugin artifact`);
      }
      return {
        ok: true,
        params: {
          confirm: params.confirm === true,
          delay_seconds: delay.value,
          artifact_sha256: sha,
          acknowledge_no_announce: params.acknowledge_no_announce === true,
        },
      };
    }
    case "rollback_plugin": {
      const unknown = rejectUnknownKeys(params, ["confirm", "delay_seconds", "acknowledge_no_announce"]);
      if (unknown) return bad(unknown);
      const confirm = boolParam(params, "confirm");
      if (confirm) return bad(confirm);
      const ack = boolParam(params, "acknowledge_no_announce");
      if (ack) return bad(ack);
      const delay = intParam(params, "delay_seconds", 1, 3600, true);
      if (delay.error) return bad(delay.error);
      return {
        ok: true,
        params: {
          confirm: params.confirm === true,
          delay_seconds: delay.value,
          acknowledge_no_announce: params.acknowledge_no_announce === true,
        },
      };
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
/* T0: development (build/test via the plugin-builder job channel)     */
/* ------------------------------------------------------------------ */

/** Resolve the commit the local repo is checked out at (the builder asserts it). */
async function repoHeadSha(deps: ActionDeps): Promise<string> {
  const res = await deps.runner.run(["git", "-C", deps.allow.paths.repo, "rev-parse", "HEAD"], {
    env: { GIT_OPTIONAL_LOCKS: "0" },
    timeoutMs: deps.cfg.actionTimeoutSeconds * 1000,
    maxBytes: 4096,
  });
  const sha = res.ok ? res.stdout.trim() : "";
  if (!REF_RE.test(sha)) {
    throw new DockerError("git_failed", `could not read the current commit of ${deps.allow.paths.repo} (git rev-parse HEAD)`);
  }
  return sha;
}

/**
 * Hand ONE job to plugin-builder and wait for its answer.
 *
 * ops-helper never starts a container itself: the socket filter refuses
 * `POST /containers/create` (that route is host root), so the only build/test
 * channel is the shared `ops-jobs` volume. A job carries nothing but an action,
 * the current commit sha and - for a test - a target from the allowlist; the
 * builder re-validates all three and runs one fixed command.
 */
async function runJob(deps: ActionDeps, action: "build" | "test", target?: string): Promise<BuilderResult> {
  const ref = await repoHeadSha(deps);
  const id = newJobId();
  try {
    deps.jobs.submit(makeJob(action, ref, target, id, new Date(deps.now()).toISOString()));
  } catch (exc) {
    throw new DockerError(
      "builder_unavailable",
      `could not hand a job to plugin-builder (${(exc as Error).message}); is the ops profile up and is ${deps.cfg.jobsDir} mounted?`,
    );
  }
  logger.info("job submitted", { job_id: id, action, ref });
  const result = await deps.jobs.awaitResult(id, deps.cfg.builderTimeoutSeconds * 1000);
  if (!result || result.id !== id) {
    throw new DockerError(
      "builder_timeout",
      `plugin-builder did not answer job ${id} within ${deps.cfg.builderTimeoutSeconds}s (is the plugin-builder service running?)`,
    );
  }
  return result;
}

async function opBuildPlugin(deps: ActionDeps): Promise<OpResult> {
  const spec = deps.allow.build.plugin;
  const job = await runJob(deps, "build");
  const output = redactCap(typeof job.output_tail === "string" ? job.output_tail : "", 8192);
  const truncated = output.truncated || job.truncated === true;
  const tests = job.tests ?? null;
  // The artifact is built from the commit, so local edits are reported, not
  // fatal - surface that fact to the caller (count only, never file names).
  const dirty = { dirty: job.dirty ?? null, dirty_files: job.dirty_files ?? null };

  if (!job.ok) {
    return {
      ok: false,
      code: job.code ?? "build_failed",
      message: `plugin-builder reported a failed gradle build (${job.code ?? "build_failed"})`,
      result: { build_id: job.id, source: spec.source, ref: job.ref, tests, ...dirty, output_tail: output.text, truncated },
    };
  }

  const artifact = job.artifact ?? null;
  const artifactPath = artifact && typeof artifact.path === "string" ? artifact.path : "";
  const sha = artifact && typeof artifact.sha256 === "string" ? artifact.sha256 : "";
  if (!artifactPath || !SHA256_RE.test(sha) || !existsSync(artifactPath)) {
    return {
      ok: false,
      code: "artifact_missing",
      message: "the build reported success but the recorded artifact is missing from the artifact volume",
      result: { build_id: job.id, ref: job.ref, tests, ...dirty, output_tail: output.text, truncated },
    };
  }
  // Re-hash in the helper's own process: the artifact volume is shared, so the
  // hash the builder recorded is not trusted on its own.
  if (sha256File(artifactPath) !== sha) {
    return {
      ok: false,
      code: "artifact_hash_mismatch",
      message: "the artifact's sha256 does not match the file in the artifact volume",
      result: { build_id: job.id, ref: job.ref, artifact, ...dirty },
    };
  }
  const size = statSync(artifactPath).size;
  const record = {
    build_id: job.id,
    sha256: sha,
    size_bytes: size,
    artifact_path: artifactPath,
    source: spec.source,
    ref: job.ref,
    tests,
    ...dirty,
    at: new Date(deps.now()).toISOString(),
  };
  writeJson(`${deps.cfg.artifactsDir}/state/last-build.json`, record);

  return {
    ok: true,
    result: {
      ...record,
      deployed: false,
      note: "the artifact is NOT deployed; ops_deploy_plugin needs this sha256, the armed switch and a recent backup",
      output_tail: output.text,
      truncated,
    },
  };
}

async function opRunTests(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const target = params.target as string;
  const spec = deps.allow.build.tests[target];
  const job = await runJob(deps, "test", target);
  const output = redactCap(typeof job.output_tail === "string" ? job.output_tail : "", 8192);
  const result = {
    job_id: job.id,
    target,
    image: spec.image,
    command: spec.command,
    ref: job.ref,
    tests: job.tests ?? null,
    dirty: job.dirty ?? null,
    dirty_files: job.dirty_files ?? null,
    output_tail: output.text,
    truncated: output.truncated || job.truncated === true,
  };
  if (!job.ok) {
    return {
      ok: false,
      code: job.code ?? "tests_failed",
      message: `plugin-builder reported failing ${target} tests (${job.code ?? "tests_failed"})`,
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
  // Restart by NAME, never by id: the socket filter allowlists exact container
  // names, so this is the only reference that can pass the second, independent
  // check. A game server's name is not on that list, which is what keeps a
  // game-server restart impossible even if this action asked for one.
  const name = await deps.docker.containerName(service);
  await deps.docker.restartByName(name, (deps.cfg.actionTimeoutSeconds + 60) * 1000);
  const state = await deps.docker.stateOf(name);
  return {
    ok: true,
    result: {
      service,
      container: name,
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

/** Newest complete backup with its age, or null when there is none. */
export function newestCompleteBackup(deps: Pick<ActionDeps, "allow" | "now">): Record<string, unknown> | null {
  const entries = readBackupEntries(deps.allow.paths.backups);
  const fresh = entries.find((entry) => entry.complete === true);
  if (!fresh) return null;
  const createdAt = fresh.created_at ? Date.parse(String(fresh.created_at)) : NaN;
  if (!Number.isFinite(createdAt)) return { ...fresh, age_seconds: null, age_hours: null };
  const ageSeconds = Math.round((deps.now() - createdAt) / 1000);
  return { ...fresh, age_seconds: ageSeconds, age_hours: Math.round((ageSeconds / 3600) * 10) / 10 };
}

/**
 * The backup gate for a T2 action.
 *
 * `ops_backup_now` stays paused (the helper image has no PowerShell), so a T2
 * action does NOT create a backup - it requires a *recent* one that was made on
 * the host: the newest `backups/daily-...` directory with a `COMPLETE.txt`
 * marker, younger than `tiers.T2.backup_max_age_hours` (default 12). Older or
 * missing -> refuse, with the age in the answer so the host can just run
 * `backup.ps1`.
 */
function ensureRecentBackup(deps: ActionDeps): BackupCheck {
  const maxAgeHours = deps.allow.tiers.T2.backup_max_age_hours ?? 12;
  const latest = newestCompleteBackup(deps);
  const maxAgeSeconds = maxAgeHours * 3600;
  if (!latest || typeof latest.age_seconds !== "number") {
    return {
      ok: false,
      code: "no_recent_backup",
      message: `no complete backup found under ${deps.allow.paths.backups}; run backup.ps1 on the host first (a T2 action needs one younger than ${maxAgeHours}h)`,
      result: { mode: "recent_only", latest: latest ?? null, max_age_hours: maxAgeHours },
    };
  }
  if (latest.age_seconds > maxAgeSeconds) {
    return {
      ok: false,
      code: "stale_backup",
      message: `the newest complete backup is ${latest.age_hours}h old, which is older than the allowed ${maxAgeHours}h; run backup.ps1 on the host first`,
      result: { mode: "recent_only", latest, max_age_hours: maxAgeHours },
    };
  }
  return { ok: true, result: { mode: "recent_backup", backup: latest, created: false, max_age_hours: maxAgeHours } };
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

/**
 * The announcement status for a T2 action.
 *
 * This stack has NO in-game announcement channel that the ops layer may use:
 * messaging a server console needs `docker exec` (or an RCON/broadcast route),
 * and docker-socket-filter refuses every exec route on purpose - neither
 * velocity nor the Paper servers are reachable from here. So a T2 action never
 * warns players, reports that honestly, and the tier gate requires the caller to
 * pass `acknowledge_no_announce=true` (see gates.ts). If a real announcement
 * channel is added later (e.g. an RCON line emitted by the bot), this is the one
 * place that changes.
 */
function announcementStatus(service: string, delaySeconds: number): Record<string, unknown> {
  return {
    announced: false,
    via: null,
    message: `Server ${service} startet in ${delaySeconds} Sekunden neu (Wartung).`,
    detail:
      "NO in-game announcement was made: reaching a server console needs docker exec (or an RCON broadcast), " +
      "and docker-socket-filter refuses every exec route. The caller confirmed this with acknowledge_no_announce=true.",
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

/**
 * Restart one compose service by its exact container NAME and return the name.
 * The socket filter allowlists names for `/containers/<name>/restart`, so a
 * restart by id (a 12-hex id is not a name) would be a 403.
 */
async function restartServiceByName(deps: ActionDeps, service: string): Promise<string> {
  const name = await deps.docker.containerName(service);
  await deps.docker.restartByName(name, (deps.cfg.actionTimeoutSeconds + 60) * 1000);
  return name;
}

async function opRestartGame(params: Record<string, unknown>, deps: ActionDeps): Promise<OpResult> {
  const service = params.service as string;
  const delay = params.delay_seconds as number;
  // Restart by NAME (the filter's restart allowlist holds exact names), and only
  // after a recent backup exists: a restart can lose in-flight world writes.
  const name = await deps.docker.containerName(service);
  const backup = ensureRecentBackup(deps);
  if (!backup.ok) {
    return { ok: false, code: backup.code ?? "no_recent_backup", message: backup.message ?? "no recent backup", result: backup.result };
  }
  const announcement = announcementStatus(service, delay);
  await deps.sleep(delay * 1000);
  await deps.docker.restartByName(name, (deps.cfg.actionTimeoutSeconds + 60) * 1000);
  const health = await waitForHealthy(deps, service);
  if (!health.healthy) {
    return {
      ok: false,
      code: "health_check_failed",
      message: `${service} did not become healthy within ${deps.allow.deploy.healthTimeoutSeconds}s after the restart`,
      result: { service, container: name, announcement, backup, state: health.state },
    };
  }
  return {
    ok: true,
    result: {
      service,
      container: name,
      announcement,
      backup,
      state: health.state,
      delay_seconds: delay,
      note: "restarted through the socket filter by exact container name; T2 gates (armed, confirm, delay, recent backup, ack) all passed",
    },
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

  const backup = ensureRecentBackup(deps);
  if (!backup.ok) {
    return { ok: false, code: backup.code ?? "no_recent_backup", message: backup.message ?? "no recent backup", result: backup.result };
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

  const announcement = announcementStatus(deps.allow.deploy.order[0], delay);
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
      await restartServiceByName(deps, service);
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
      await restartServiceByName(deps, service);
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
  const backup = ensureRecentBackup(deps);
  if (!backup.ok) {
    return { ok: false, code: backup.code ?? "no_recent_backup", message: backup.message ?? "no recent backup", result: backup.result };
  }
  const announcement = announcementStatus(deps.allow.deploy.order[0], delay);
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
    await restartServiceByName(deps, service);
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

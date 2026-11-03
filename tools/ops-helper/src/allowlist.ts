/**
 * The allowlist: config/ops-allowlist.json.
 *
 * This file is the authority for which ops actions exist, which services they
 * may touch and how strict their gates are. It is read once at startup from the
 * read-only repo mount; no MCP tool can change it.
 *
 * Validation is deliberately paranoid and fails closed: an unknown action impl,
 * a malformed service name, a path with "..", an argv token that is not a plain
 * token, or a mutating service list that names a forbidden service makes the
 * helper refuse to start.
 */
import { readFileSync } from "node:fs";

export type Tier = "T0" | "T1" | "T2";

export const KNOWN_IMPLS = [
  "status",
  "logs",
  "disk",
  "backups_list",
  "players",
  "git_state",
  "build_plugin",
  "run_tests",
  "restart_service",
  "backup_now",
  "restart_game",
  "deploy_plugin",
  "rollback_plugin",
] as const;
export type Impl = (typeof KNOWN_IMPLS)[number];

export interface TierRules {
  confirm_required: boolean;
  armed_required: boolean;
  cooldown_seconds: number;
  min_delay_seconds?: number;
  backup_required?: boolean;
  /**
   * How old the newest complete backup may be for a T2 action, in hours.
   * `ops_backup_now` stays paused (the helper image has no PowerShell), so a T2
   * action does not *create* a backup - it requires a recent one that was made on
   * the host. Default 12.
   */
  backup_max_age_hours?: number;
}

export interface ActionSpec {
  name: string;
  tier: Tier;
  impl: Impl;
  enabled: boolean;
  /**
   * Why the action is disabled. Required (and only used) when `enabled` is
   * false: it is shown verbatim to the caller, so a paused tool explains itself
   * instead of failing with a generic "disabled".
   */
  disabledReason?: string;
}

export interface BuildSpec {
  source: string;
  image: string;
  command: string[];
  setup?: string[];
  artifact?: string;
  test_results?: string;
  /**
   * Which pre-warmed offline cache volume to mount into the throwaway container.
   * The build runs with `--network none`, so its dependencies must already be in
   * the cache: "gradle" -> the gradle cache volume at /home/gradle/.gradle,
   * "npm" -> the npm cache volume at /root/.npm.
   */
  cache?: "gradle" | "npm";
}

/** Hard resource caps for the throwaway build/test containers. */
export interface BuildLimits {
  cpus: string;
  memory: string;
  pids: number;
}

export interface Allowlist {
  version: number;
  composeProject: string;
  limits: {
    maxOutputBytes: number;
    maxLogLines: number;
    rateWindowSeconds: number;
    rateMaxCalls: number;
    backupMaxAgeSeconds: number;
  };
  tiers: Record<Tier, TierRules>;
  paths: {
    repo: string;
    backups: string;
    artifacts: string;
    pluginJar: string;
    backupScript: string;
  };
  services: {
    observe: string[];
    logs: string[];
    players: string[];
    restartT1: string[];
    restartGame: string[];
    deployPlugin: string[];
  };
  forbiddenServices: string[];
  forbiddenOperations: string[];
  build: { plugin: BuildSpec; tests: Record<string, BuildSpec>; network: string; limits: BuildLimits };
  deploy: {
    order: string[];
    pluginDirs: Record<string, string>;
    healthTimeoutSeconds: number;
    healthPollSeconds: number;
  };
  actions: Record<string, ActionSpec>;
}

const SERVICE_RE = /^[a-z0-9][a-z0-9-]{0,31}$/;
const IMAGE_RE = /^[a-z0-9][a-z0-9._/-]{0,127}(?::[A-Za-z0-9._-]{1,64})?$/;
/** One argv element: no spaces, quotes, globs, redirections or shell metachars. */
const TOKEN_RE = /^[A-Za-z0-9._:/@=+-]{1,64}$/;
const REL_PATH_RE = /^[A-Za-z0-9._/-]{1,160}$/;
const BASENAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

function fail(msg: string): never {
  throw new Error(`ops-allowlist.json: ${msg}`);
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function obj(v: unknown, what: string): Record<string, unknown> {
  if (!isRecord(v)) fail(`${what} must be an object`);
  return v;
}

function bool(v: unknown, what: string): boolean {
  if (typeof v !== "boolean") fail(`${what} must be a boolean`);
  return v;
}

function int(v: unknown, what: string, min: number, max: number): number {
  if (typeof v !== "number" || !Number.isInteger(v) || v < min || v > max) {
    fail(`${what} must be an integer between ${min} and ${max}`);
  }
  return v;
}

function str(v: unknown, what: string, re?: RegExp): string {
  if (typeof v !== "string" || v.length === 0) fail(`${what} must be a non-empty string`);
  if (re && !re.test(v)) fail(`${what} has an unexpected shape: ${JSON.stringify(v)}`);
  return v;
}

/** Absolute, no traversal, no double slashes. */
function absPath(v: unknown, what: string): string {
  const s = str(v, what);
  if (!s.startsWith("/")) fail(`${what} must be absolute`);
  if (s.split("/").includes("..")) fail(`${what} must not contain ".."`);
  if (!/^\/[A-Za-z0-9._/-]{0,160}$/.test(s)) fail(`${what} has an unexpected shape`);
  return s;
}

/** Repo-relative path, no traversal, no absolute paths. */
function relPath(v: unknown, what: string): string {
  const s = str(v, what, REL_PATH_RE);
  if (s.startsWith("/") || s.split("/").includes("..")) fail(`${what} must be a repo-relative path without ".."`);
  return s;
}

function serviceList(v: unknown, what: string): string[] {
  if (!Array.isArray(v)) fail(`${what} must be an array`);
  const out = v.map((entry, i) => str(entry, `${what}[${i}]`, SERVICE_RE));
  if (new Set(out).size !== out.length) fail(`${what} contains duplicates`);
  return out;
}

function argvList(v: unknown, what: string): string[] {
  if (!Array.isArray(v) || v.length === 0) fail(`${what} must be a non-empty array`);
  return v.map((entry, i) => str(entry, `${what}[${i}]`, TOKEN_RE));
}

const BUILD_NETWORK_RE = /^[a-z0-9][a-z0-9_.-]{0,63}$/;
const CPU_RE = /^\d{1,3}(\.\d{1,3})?$/;
const MEMORY_RE = /^\d{1,6}[bkmg]?$/i;

/**
 * Network for the throwaway build/test containers. `none` (no interface at all)
 * is the only supported default; a dedicated, narrowly scoped bridge network may
 * be named explicitly when an offline cache truly cannot be warmed. `host`,
 * `bridge` and `container:*` are refused: a build must never share the host or
 * another container's network namespace.
 */
function buildNetwork(v: unknown): string {
  if (v === undefined) return "none";
  const s = str(v, "build.network", BUILD_NETWORK_RE);
  if (["host", "bridge", "default"].includes(s)) {
    fail(`build.network "${s}" is not allowed: use "none" or a dedicated bridge network`);
  }
  return s;
}

function smallNumber(v: unknown, what: string, min: number, max: number): string {
  const s = typeof v === "number" ? String(v) : str(v, what);
  const n = Number(s);
  if (!Number.isFinite(n) || n < min || n > max) fail(`${what} must be between ${min} and ${max}`);
  return s;
}

/** Mandatory CPU/RAM/PID caps for every build/test container (no unlimited build). */
function buildLimits(v: unknown): BuildLimits {
  const o = obj(v, "build.limits");
  const cpus = smallNumber(o.cpus, "build.limits.cpus", 0.1, 16);
  if (!CPU_RE.test(cpus)) fail("build.limits.cpus must look like 1 or 1.5");
  const memory = str(o.memory, "build.limits.memory", MEMORY_RE);
  const pids = int(o.pids, "build.limits.pids", 16, 65536);
  return { cpus, memory, pids };
}

function buildSpec(v: unknown, what: string): BuildSpec {
  const o = obj(v, what);
  const spec: BuildSpec = {
    source: relPath(o.source, `${what}.source`),
    image: str(o.image, `${what}.image`, IMAGE_RE),
    command: argvList(o.command, `${what}.command`),
  };
  if (o.setup !== undefined) spec.setup = argvList(o.setup, `${what}.setup`);
  if (o.artifact !== undefined) spec.artifact = relPath(o.artifact, `${what}.artifact`);
  if (o.test_results !== undefined) spec.test_results = relPath(o.test_results, `${what}.test_results`);
  if (o.cache !== undefined) {
    const cache = str(o.cache, `${what}.cache`);
    if (cache !== "gradle" && cache !== "npm") fail(`${what}.cache must be "gradle" or "npm"`);
    spec.cache = cache;
  }
  return spec;
}

export function parseAllowlist(raw: unknown): Allowlist {
  const root = obj(raw, "root");
  const version = int(root.version, "version", 1, 1);

  const limitsRaw = obj(root.limits, "limits");
  const limits = {
    maxOutputBytes: int(limitsRaw.max_output_bytes, "limits.max_output_bytes", 1024, 4 * 1024 * 1024),
    maxLogLines: int(limitsRaw.max_log_lines, "limits.max_log_lines", 1, 5000),
    rateWindowSeconds: int(limitsRaw.rate_window_seconds, "limits.rate_window_seconds", 1, 3600),
    rateMaxCalls: int(limitsRaw.rate_max_calls, "limits.rate_max_calls", 1, 1000),
    backupMaxAgeSeconds: int(limitsRaw.backup_max_age_seconds, "limits.backup_max_age_seconds", 60, 86400),
  };

  const tiersRaw = obj(root.tiers, "tiers");
  const tiers = {} as Record<Tier, TierRules>;
  for (const tier of ["T0", "T1", "T2"] as Tier[]) {
    const t = obj(tiersRaw[tier], `tiers.${tier}`);
    const rules: TierRules = {
      confirm_required: bool(t.confirm_required, `tiers.${tier}.confirm_required`),
      armed_required: bool(t.armed_required, `tiers.${tier}.armed_required`),
      cooldown_seconds: int(t.cooldown_seconds, `tiers.${tier}.cooldown_seconds`, 0, 86400),
    };
    if (t.min_delay_seconds !== undefined) {
      rules.min_delay_seconds = int(t.min_delay_seconds, `tiers.${tier}.min_delay_seconds`, 1, 3600);
    }
    if (t.backup_required !== undefined) {
      rules.backup_required = bool(t.backup_required, `tiers.${tier}.backup_required`);
    }
    if (t.backup_max_age_hours !== undefined) {
      rules.backup_max_age_hours = int(t.backup_max_age_hours, `tiers.${tier}.backup_max_age_hours`, 1, 168);
    }
    tiers[tier] = rules;
  }
  if (tiers.T0.armed_required || tiers.T0.confirm_required) fail("tiers.T0 must stay ungated (read-only)");
  if (!tiers.T2.armed_required) fail("tiers.T2 must require the global armed switch");
  if (tiers.T2.min_delay_seconds === undefined || tiers.T2.min_delay_seconds < 1) {
    fail("tiers.T2 must declare min_delay_seconds >= 1");
  }
  if (tiers.T2.backup_required === true && tiers.T2.backup_max_age_hours === undefined) {
    tiers.T2.backup_max_age_hours = 12;
  }

  const pathsRaw = obj(root.paths, "paths");
  const paths = {
    repo: absPath(pathsRaw.repo, "paths.repo"),
    backups: absPath(pathsRaw.backups, "paths.backups"),
    artifacts: absPath(pathsRaw.artifacts, "paths.artifacts"),
    pluginJar: str(pathsRaw.plugin_jar, "paths.plugin_jar", BASENAME_RE),
    backupScript: str(pathsRaw.backup_script, "paths.backup_script", BASENAME_RE),
  };

  const servicesRaw = obj(root.services, "services");
  const services = {
    observe: serviceList(servicesRaw.observe, "services.observe"),
    logs: serviceList(servicesRaw.logs, "services.logs"),
    players: serviceList(servicesRaw.players, "services.players"),
    restartT1: serviceList(servicesRaw.restart_t1, "services.restart_t1"),
    restartGame: serviceList(servicesRaw.restart_game, "services.restart_game"),
    deployPlugin: serviceList(servicesRaw.deploy_plugin, "services.deploy_plugin"),
  };

  const forbiddenServices = serviceList(root.forbidden_services, "forbidden_services");
  const forbiddenOperations = (() => {
    if (!Array.isArray(root.forbidden_operations)) fail("forbidden_operations must be an array");
    return root.forbidden_operations.map((entry, i) => str(entry, `forbidden_operations[${i}]`));
  })();

  // Defence in depth: a mutating list may never name a forbidden service, even
  // if this file is edited. (craftcore-db, hermes-mcp, ops-helper, hermes-test.)
  for (const [kind, list] of [
    ["services.restart_t1", services.restartT1],
    ["services.restart_game", services.restartGame],
    ["services.deploy_plugin", services.deployPlugin],
  ] as const) {
    for (const name of list) {
      if (forbiddenServices.includes(name)) fail(`${kind} must not name the forbidden service "${name}"`);
    }
  }

  const buildRaw = obj(root.build, "build");
  const build = {
    plugin: buildSpec(buildRaw.plugin, "build.plugin"),
    tests: (() => {
      const testsRaw = obj(buildRaw.tests, "build.tests");
      const out: Record<string, BuildSpec> = {};
      for (const [name, spec] of Object.entries(testsRaw)) {
        str(name, `build.tests key`, SERVICE_RE);
        out[name] = buildSpec(spec, `build.tests.${name}`);
      }
      if (Object.keys(out).length === 0) fail("build.tests must not be empty");
      return out;
    })(),
    network: buildNetwork(buildRaw.network),
    limits: buildLimits(buildRaw.limits),
  };
  if (build.plugin.artifact === undefined) fail("build.plugin.artifact is required");

  const deployRaw = obj(root.deploy, "deploy");
  const pluginDirsRaw = obj(deployRaw.plugin_dirs, "deploy.plugin_dirs");
  const pluginDirs: Record<string, string> = {};
  for (const [service, dir] of Object.entries(pluginDirsRaw)) {
    if (!SERVICE_RE.test(service)) fail(`deploy.plugin_dirs key "${service}" is not a service name`);
    pluginDirs[service] = absPath(dir, `deploy.plugin_dirs.${service}`);
  }
  const deploy = {
    order: serviceList(deployRaw.order, "deploy.order"),
    pluginDirs,
    healthTimeoutSeconds: int(deployRaw.health_timeout_seconds, "deploy.health_timeout_seconds", 5, 1800),
    healthPollSeconds: int(deployRaw.health_poll_seconds, "deploy.health_poll_seconds", 1, 60),
  };
  if (deploy.order.length === 0) fail("deploy.order must not be empty");
  for (const service of deploy.order) {
    if (!services.deployPlugin.includes(service)) fail(`deploy.order names "${service}", which is not in services.deploy_plugin`);
    if (!(service in pluginDirs)) fail(`deploy.plugin_dirs is missing "${service}"`);
  }

  const actionsRaw = obj(root.actions, "actions");
  const actions: Record<string, ActionSpec> = {};
  for (const [name, value] of Object.entries(actionsRaw)) {
    if (!/^ops_[a-z0-9_]{2,40}$/.test(name)) fail(`actions key "${name}" must look like ops_something`);
    const a = obj(value, `actions.${name}`);
    const tier = str(a.tier, `actions.${name}.tier`) as Tier;
    if (!(tier in tiers)) fail(`actions.${name}.tier must be T0, T1 or T2`);
    const impl = str(a.impl, `actions.${name}.impl`) as Impl;
    if (!(KNOWN_IMPLS as readonly string[]).includes(impl)) {
      fail(`actions.${name}.impl "${impl}" is not implemented`);
    }
    const enabled = a.enabled === undefined ? true : bool(a.enabled, `actions.${name}.enabled`);
    let disabledReason: string | undefined;
    if (a.disabled_reason !== undefined) {
      disabledReason = str(a.disabled_reason, `actions.${name}.disabled_reason`);
    }
    if (!enabled && !disabledReason) {
      fail(`actions.${name}.disabled_reason is required when the action is disabled (enabled: false)`);
    }
    actions[name] = { name, tier, impl, enabled, ...(disabledReason ? { disabledReason } : {}) };
  }
  if (Object.keys(actions).length === 0) fail("actions must not be empty");

  return {
    version,
    composeProject: str(root.compose_project, "compose_project", /^[a-z0-9][a-z0-9_-]{0,63}$/),
    limits,
    tiers,
    paths,
    services,
    forbiddenServices,
    forbiddenOperations,
    build,
    deploy,
    actions,
  };
}

export function loadAllowlist(path: string): Allowlist {
  let raw: string;
  try {
    raw = readFileSync(path, "utf8");
  } catch (exc) {
    throw new Error(`ops-allowlist.json unreadable at ${path}: ${(exc as Error).message}`);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (exc) {
    throw new Error(`ops-allowlist.json is not valid JSON: ${(exc as Error).message}`);
  }
  return parseAllowlist(parsed);
}

/** Which allowlist list a service parameter is checked against. */
export type ServiceList = keyof Allowlist["services"];

export function actionOf(allow: Allowlist, name: string): ActionSpec | undefined {
  return Object.prototype.hasOwnProperty.call(allow.actions, name) ? allow.actions[name] : undefined;
}

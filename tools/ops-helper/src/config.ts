/**
 * Configuration for ops-helper.
 *
 * Fail closed everywhere:
 *  - no shared secret  -> the process refuses to start;
 *  - no allowlist file -> the process refuses to start;
 *  - OPS_ARMED unset/anything but an explicit truthy value -> every T2 action
 *    is refused (the helper still serves T0/T1).
 *
 * The secret is read from the environment (compose env_file) and is never
 * logged, echoed or returned by any action.
 */
import { logger } from "./logger.js";

export interface OpsConfig {
  port: number;
  /** Bind address inside the container. The container publishes no host port. */
  host: string;

  /** Shared secret with hermes-mcp (constant-time compared). */
  token: string;

  /** Global T2 arm switch. Default false. */
  armed: boolean;
  /** Env kill switch: everything above T0 is refused. */
  disabled: boolean;

  /** Read-only mount of the repo. */
  repoDir: string;
  /** Allowlist inside the read-only repo mount. */
  allowlistPath: string;
  /** Writable dir for the audit log, the kill-switch file and the cooldown state. */
  dataDir: string;
  /** Writable dir for build artifacts (a docker volume shared with build containers). */
  artifactsDir: string;
  /** Name of that volume, as mounted into throwaway build containers. */
  artifactsVolume: string;
  /** Named volume for the gradle cache of build containers (offline builds). */
  gradleCacheVolume: string;
  /** Named volume for the npm cache of the node build/test containers. */
  npmCacheVolume: string;
  /**
   * Shared volume with `plugin-builder`: the helper drops `job-<uuid>.json` here
   * and the builder only ever reads from it. The helper no longer starts a
   * container itself (the socket filter refuses `create`), so this is the single
   * build/test channel.
   */
  jobsDir: string;
  /** Where plugin-builder writes `result-<uuid>.json` (inside the artifact volume). */
  builderResultsDir: string;
  /** Timeout for one build/test job handed to plugin-builder. */
  builderTimeoutSeconds: number;
  /** Poll interval while waiting for a job result. */
  builderPollMs: number;

  dockerBin: string;
  /** Timeout for one fixed docker command. */
  actionTimeoutSeconds: number;
  /** Timeout for one throwaway build/test container. */
  buildTimeoutSeconds: number;
  /** Candidates for running the repo's PowerShell backup script (first hit wins). */
  backupInterpreters: string[];
}

function str(name: string, fallback = ""): string {
  const v = process.env[name];
  return (v === undefined || v === null ? fallback : v).trim();
}

function num(name: string, fallback: number, min: number, max: number): number {
  const raw = str(name);
  if (!raw) return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < min || n > max) {
    throw new Error(`${name} must be a number between ${min} and ${max}`);
  }
  return n;
}

/**
 * Strict boolean: only an explicit truthy value arms a switch, everything else
 * is false (so a typo can never enable T2).
 */
function bool(name: string, fallback = false): boolean {
  const raw = str(name).toLowerCase();
  if (!raw) return fallback;
  if (["true", "1", "yes", "on"].includes(raw)) return true;
  if (["false", "0", "no", "off"].includes(raw)) return false;
  throw new Error(`${name} must be true/false`);
}

export function loadConfig(): OpsConfig {
  const token = str("OPS_HELPER_TOKEN");
  if (token.length < 24) {
    throw new Error("OPS_HELPER_TOKEN is required and must be at least 24 characters");
  }

  const repoDir = str("OPS_REPO_DIR", "/repo");
  const allowlistPath = str("OPS_ALLOWLIST_PATH", `${repoDir}/config/ops-allowlist.json`);

  const cfg: OpsConfig = {
    port: num("OPS_HELPER_PORT", 8766, 1, 65535),
    host: str("OPS_HELPER_HOST", "0.0.0.0"),
    token,
    armed: bool("OPS_ARMED", false),
    disabled: bool("OPS_DISABLED", false),
    repoDir,
    allowlistPath,
    dataDir: str("OPS_DATA_DIR", "/app/data/ops-helper"),
    artifactsDir: str("OPS_ARTIFACTS_DIR", "/artifacts"),
    artifactsVolume: str("OPS_ARTIFACTS_VOLUME"),
    gradleCacheVolume: str("OPS_GRADLE_CACHE_VOLUME"),
    npmCacheVolume: str("OPS_NPM_CACHE_VOLUME"),
    jobsDir: str("OPS_JOBS_DIR", "/jobs"),
    builderResultsDir: str("OPS_BUILDER_RESULTS_DIR", `${str("OPS_ARTIFACTS_DIR", "/artifacts")}/builder`),
    builderTimeoutSeconds: num("OPS_BUILDER_TIMEOUT_SECONDS", 1800, 60, 7200),
    builderPollMs: num("OPS_BUILDER_POLL_MS", 1000, 100, 30000),
    dockerBin: str("OPS_DOCKER_BIN", "docker"),
    actionTimeoutSeconds: num("OPS_ACTION_TIMEOUT_SECONDS", 120, 5, 3600),
    buildTimeoutSeconds: num("OPS_BUILD_TIMEOUT_SECONDS", 1800, 60, 7200),
    backupInterpreters: str("OPS_BACKUP_INTERPRETERS", "pwsh,powershell")
      .split(",")
      .map((v) => v.trim())
      .filter(Boolean),
  };

  logger.info("config loaded", {
    port: cfg.port,
    armed: cfg.armed,
    disabled: cfg.disabled,
    repo_dir: cfg.repoDir,
    allowlist: cfg.allowlistPath,
    data_dir: cfg.dataDir,
    artifacts_dir: cfg.artifactsDir,
    artifacts_volume: cfg.artifactsVolume ? "set" : "unset",
  });

  return cfg;
}

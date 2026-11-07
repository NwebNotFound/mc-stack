/**
 * Configuration for hermes-mcp.
 *
 * Fail closed: no MCP token -> no server. No profile keys -> no gateway access.
 * Secrets are read from the environment (docker compose env_file / .env) and are
 * never logged, echoed or returned by a tool.
 */
import { logger } from "./logger.js";

export type Backend = "gateway" | "cli";

export interface Config {
  port: number;
  /** Bind address inside the container. The container publishes no host port. */
  host: string;
  dataDir: string;

  /** Bearer tokens accepted from MCP clients (rotation: comma separated). */
  tokens: string[];
  /** Public URL, used only for metadata/help text. */
  publicUrl: string;

  /** Hermes gateway base URL, e.g. http://host.docker.internal:8642 */
  gatewayUrl: string;
  /** profile -> profile-scoped API_SERVER_KEY (JSON object). */
  profileKeys: Record<string, string>;

  /** Host header allowlist (DNS rebinding protection). */
  allowedHosts: string[];
  maxActiveRunsPerProfile: number;
  /** Hard cap on runs started per UTC day across all profiles. */
  maxRunsPerDay: number;
  pollIntervalMs: number;
  maxRunSeconds: number;
  /** A run waiting for approval longer than this is stopped (never approved). */
  approvalWaitSeconds: number;
  /** An `unknown` run older than this is marked `timed_out`. */
  unknownTimeoutSeconds: number;

  /** Peers allowed to set X-Forwarded-For (empty: never trust the header). */
  trustedProxies: string[];
  /**
   * Compose service names whose resolved addresses are trusted proxies too
   * (default: none in code; compose sets `cloudflared-mcp`).
   */
  trustedProxyHosts: string[];
  /** Consecutive bad tokens before an IP is locked out. */
  lockoutAfter: number;
  /** Longest exponential lockout in seconds (also the first-block length). */
  lockoutMaxSeconds: number;

  /** Models accepted by start_run(model=...). Empty disables overrides. */
  allowedModels: string[];
  /** Providers accepted by start_run(provider=...). Empty disables overrides. */
  allowedProviders: string[];
  /** reasoning_effort values accepted by start_run. */
  allowedReasoningEfforts: string[];

  /**
   * Base URL of the ops-helper service (internal compose network), e.g.
   * http://ops-helper:8766. Empty (or no token) disables the ops tools.
   */
  opsHelperUrl: string;
  /** Shared secret with ops-helper. Never logged, never in a URL. */
  opsHelperToken: string;
  /** Default timeout for one ops call; long actions override it per tool. */
  opsHelperTimeoutSeconds: number;

  /**
   * Second, higher token level. A caller that presents one of these (instead of
   * a plain HERMES_MCP_TOKEN) may use the T1 tools; T2 additionally needs the
   * local listener. Empty -> T1/T2 are refused for everyone (fail closed).
   * Never logged, never in a URL.
   */
  opsTokens: string[];
  /** Port of the local-only listener that is allowed to reach T2. */
  opsPort: number;
  /** Bind address of the local-only listener (published only on 127.0.0.1). */
  opsHost: string;
  /** Consecutive bad tokens on the local (T2-capable) listener before lockout. */
  opsLockoutAfter: number;
  /**
   * Explicit peer allowlist (IPs/CIDRs) for the local listener. Empty -> the
   * built-in rule: refuse sibling containers on the container's own subnets,
   * allow loopback, the subnet gateways and the host port mapping.
   */
  opsAllowedPeers: string[];
  /** Host headers the local listener accepts (default: loopback names only). */
  opsAllowedHosts: string[];

  backend: Backend;
  /** Only used by the host-only CLI backend. */
  cliCommand: string;
}

const DEFAULT_ALLOWED_MODELS = ["deepseek/deepseek-v4.1-flash", "anthropic/claude-haiku-5.5"];
const DEFAULT_ALLOWED_PROVIDERS = ["openrouter", "anthropic"];
/** Highest effort we accept: `xhigh`/`none` are deliberately excluded. */
const DEFAULT_ALLOWED_EFFORTS = ["minimal", "low", "medium", "high"];

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

function list(name: string, fallback: string[]): string[] {
  const raw = str(name);
  if (!raw) return fallback;
  return raw
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean);
}

function parseProfileKeys(raw: string): Record<string, string> {
  if (!raw) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error("HERMES_PROFILE_KEYS must be a JSON object of {profile: key}");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error("HERMES_PROFILE_KEYS must be a JSON object of {profile: key}");
  }
  const out: Record<string, string> = {};
  for (const [profile, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/i.test(profile)) {
      throw new Error(`HERMES_PROFILE_KEYS: invalid profile name ${JSON.stringify(profile)}`);
    }
    if (typeof value !== "string" || value.length < 16) {
      throw new Error(`HERMES_PROFILE_KEYS: key for ${profile} is missing or too short`);
    }
    out[profile] = value;
  }
  return out;
}

export function loadConfig(): Config {
  const tokens = str("HERMES_MCP_TOKEN")
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean);

  if (tokens.length === 0) {
    throw new Error("HERMES_MCP_TOKEN is required (generate one with `openssl rand -hex 32`)");
  }
  for (const token of tokens) {
    if (token.length < 24) {
      throw new Error("HERMES_MCP_TOKEN must be at least 24 characters");
    }
  }

  // Second token level. Empty is valid (T1/T2 stay refused); a too-short value
  // is not, so a typo can never weaken the elevation.
  const opsTokens = str("HERMES_MCP_OPS_TOKEN")
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean);
  for (const token of opsTokens) {
    if (token.length < 24) {
      throw new Error("HERMES_MCP_OPS_TOKEN must be at least 24 characters (or unset)");
    }
  }

  const backendRaw = (str("HERMES_BACKEND", "gateway") || "gateway").toLowerCase();
  if (backendRaw !== "gateway" && backendRaw !== "cli") {
    throw new Error("HERMES_BACKEND must be 'gateway' or 'cli'");
  }

  const gatewayUrl = (str("HERMES_GATEWAY_URL", "http://host.docker.internal:8642") || "").replace(/\/+$/, "");
  if (!/^https?:\/\//.test(gatewayUrl)) {
    throw new Error("HERMES_GATEWAY_URL must be an http(s) URL");
  }

  const profileKeys = parseProfileKeys(str("HERMES_PROFILE_KEYS"));
  if (backendRaw === "gateway" && Object.keys(profileKeys).length === 0) {
    throw new Error(
      "HERMES_PROFILE_KEYS is empty: the gateway rejects named profiles without a profile-scoped API_SERVER_KEY",
    );
  }

  const allowedHosts = str("HERMES_MCP_ALLOWED_HOSTS", "localhost,127.0.0.1,hermes-mcp")
    .split(",")
    .map((h) => h.trim().toLowerCase())
    .filter(Boolean);

  const cfg: Config = {
    port: num("HERMES_MCP_PORT", 8765, 1, 65535),
    host: str("HERMES_MCP_HOST", "0.0.0.0"),
    dataDir: str("HERMES_MCP_DATA_DIR", "/app/data"),

    tokens,
    publicUrl: str("HERMES_MCP_PUBLIC_URL", "https://mcp.nwebnotfound.dev"),

    gatewayUrl,
    profileKeys,

    allowedHosts,
    maxActiveRunsPerProfile: num("HERMES_MCP_MAX_ACTIVE_RUNS_PER_PROFILE", 2, 1, 20),
    maxRunsPerDay: num("HERMES_MCP_MAX_RUNS_PER_DAY", 50, 1, 100000),
    pollIntervalMs: num("HERMES_MCP_POLL_MS", 2500, 500, 60000),
    maxRunSeconds: num("HERMES_MCP_MAX_RUN_SECONDS", 7200, 60, 86400),
    approvalWaitSeconds: num("HERMES_MCP_APPROVAL_WAIT_SECONDS", 900, 30, 86400),
    unknownTimeoutSeconds: num("HERMES_MCP_UNKNOWN_TIMEOUT_SECONDS", 7200, 60, 86400),

    trustedProxies: list("HERMES_MCP_TRUSTED_PROXIES", []),
    trustedProxyHosts: list("HERMES_MCP_TRUSTED_PROXY_HOSTS", []).filter((h) => /^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(h)),
    lockoutAfter: num("HERMES_MCP_LOCKOUT_AFTER", 5, 1, 1000),
    lockoutMaxSeconds: num("HERMES_MCP_LOCKOUT_SECONDS", 300, 1, 86400),

    allowedModels: list("HERMES_MCP_ALLOWED_MODELS", DEFAULT_ALLOWED_MODELS),
    allowedProviders: list("HERMES_MCP_ALLOWED_PROVIDERS", DEFAULT_ALLOWED_PROVIDERS),
    allowedReasoningEfforts: list("HERMES_MCP_ALLOWED_REASONING_EFFORTS", DEFAULT_ALLOWED_EFFORTS),

    opsHelperUrl: str("OPS_HELPER_URL"),
    opsHelperToken: str("OPS_HELPER_TOKEN"),
    opsHelperTimeoutSeconds: num("OPS_HELPER_TIMEOUT_SECONDS", 120, 5, 3600),

    opsTokens,
    opsPort: num("HERMES_MCP_OPS_PORT", 8767, 1, 65535),
    opsHost: str("HERMES_MCP_OPS_HOST", "0.0.0.0"),
    opsLockoutAfter: num("HERMES_MCP_OPS_LOCKOUT_AFTER", 3, 1, 1000),
    opsAllowedPeers: list("HERMES_MCP_OPS_ALLOWED_PEERS", []),
    opsAllowedHosts: list("HERMES_MCP_OPS_ALLOWED_HOSTS", ["localhost", "127.0.0.1", "::1"]).map((h) => h.toLowerCase()),

    backend: backendRaw,
    cliCommand: str("HERMES_CLI", "hermes"),
  };

  logger.info("config loaded", {
    backend: cfg.backend,
    gateway_url: cfg.gatewayUrl,
    profiles: Object.keys(cfg.profileKeys),
    auth: cfg.tokens.length,
    port: cfg.port,
    trusted_proxies: cfg.trustedProxies.length,
    trusted_proxy_hosts: cfg.trustedProxyHosts,
    allowed_models: cfg.allowedModels.length,
    max_runs_per_day: cfg.maxRunsPerDay,
    ops_helper: cfg.opsHelperUrl && cfg.opsHelperToken ? "enabled" : "disabled",
    ops_token_level: cfg.opsTokens.length > 0 ? "configured" : "unset",
    ops_local_port: cfg.opsPort,
  });

  return cfg;
}

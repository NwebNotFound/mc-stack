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
  pollIntervalMs: number;
  maxRunSeconds: number;

  backend: Backend;
  /** Only used by the host-only CLI backend. */
  cliCommand: string;
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
    pollIntervalMs: num("HERMES_MCP_POLL_MS", 2500, 500, 60000),
    maxRunSeconds: num("HERMES_MCP_MAX_RUN_SECONDS", 7200, 60, 86400),

    backend: backendRaw,
    cliCommand: str("HERMES_CLI", "hermes"),
  };

  logger.info("config loaded", {
    backend: cfg.backend,
    gateway_url: cfg.gatewayUrl,
    profiles: Object.keys(cfg.profileKeys),
    token_count: cfg.tokens.length,
    port: cfg.port,
  });

  return cfg;
}

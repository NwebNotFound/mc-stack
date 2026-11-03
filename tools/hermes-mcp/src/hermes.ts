/**
 * HTTP client for the Hermes gateway.
 *
 * Named profiles are reached through the gateway's multiplex listener:
 *   POST {gateway}/p/<profile>/v1/runs
 * and each profile authenticates with its OWN profile-scoped API_SERVER_KEY
 * (the gateway deliberately refuses the default profile's key for named
 * profiles). Keys come from HERMES_PROFILE_KEYS and never leave this process.
 */
import type { Config } from "./config.js";
import { logger } from "./logger.js";

export class GatewayError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "GatewayError";
    this.status = status;
    this.code = code;
  }
}

export interface GatewayRunStatus {
  run_id: string;
  status: string;
  last_event?: string;
  output?: string;
  error?: string;
  usage?: Record<string, number>;
  model?: string;
  session_id?: string;
  created_at?: number;
  updated_at?: number;
  [key: string]: unknown;
}

const TERMINAL = new Set(["completed", "failed", "cancelled", "interrupted", "timed_out"]);

/** A status that will not change again without a new run. */
export function isTerminal(status: string): boolean {
  return TERMINAL.has(status);
}

/**
 * A run that still occupies a profile slot: anything the gateway has not
 * finished. `unknown` counts here on purpose — a run we lost sight of may still
 * be alive, and it only frees its slot once resolved or timed out.
 */
export function isUnresolved(status: string): boolean {
  return !TERMINAL.has(status);
}

export class GatewayClient {
  constructor(private readonly cfg: Config) {}

  profileNames(): string[] {
    return Object.keys(this.cfg.profileKeys);
  }

  hasProfile(profile: string): boolean {
    return Object.prototype.hasOwnProperty.call(this.cfg.profileKeys, profile);
  }

  private keyFor(profile: string): string {
    const key = this.cfg.profileKeys[profile];
    if (!key) {
      throw new GatewayError(
        404,
        "unknown_profile",
        `Profile ${JSON.stringify(profile)} is not configured for hermes-mcp. Use list_profiles to see the available profiles.`,
      );
    }
    return key;
  }

  private async request<T>(
    profile: string | null,
    method: string,
    path: string,
    body?: unknown,
    timeoutMs = 30_000,
  ): Promise<T> {
    const url = `${this.cfg.gatewayUrl}${profile ? `/p/${encodeURIComponent(profile)}` : ""}${path}`;
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (profile) headers.Authorization = `Bearer ${this.keyFor(profile)}`;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetch(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await res.text();
      let parsed: unknown = undefined;
      if (text) {
        try {
          parsed = JSON.parse(text);
        } catch {
          parsed = { raw: text.slice(0, 2000) };
        }
      }
      if (!res.ok) {
        const payload = (parsed ?? {}) as Record<string, unknown>;
        const err = (payload.error ?? {}) as Record<string, unknown>;
        const message =
          (typeof err.message === "string" && err.message) ||
          (typeof payload.error === "string" && payload.error) ||
          (typeof payload.raw === "string" && payload.raw) ||
          `gateway responded ${res.status}`;
        const code = typeof err.code === "string" ? err.code : `http_${res.status}`;
        throw new GatewayError(res.status, code, message);
      }
      return (parsed ?? {}) as T;
    } catch (exc) {
      if (exc instanceof GatewayError) throw exc;
      const reason = controller.signal.aborted ? "request timed out" : (exc as Error).message;
      throw new GatewayError(0, "gateway_unreachable", `Hermes gateway unreachable at ${this.cfg.gatewayUrl}: ${reason}`);
    } finally {
      clearTimeout(timer);
    }
  }

  async health(): Promise<Record<string, unknown>> {
    return this.request<Record<string, unknown>>(null, "GET", "/health", undefined, 5000);
  }

  async models(profile: string): Promise<{ data?: Array<{ id: string }> }> {
    return this.request(profile, "GET", "/v1/models", undefined, 10_000);
  }

  async modelOptions(profile: string): Promise<Record<string, unknown>> {
    return this.request(profile, "GET", "/api/model/options", undefined, 45_000);
  }

  async startRun(
    profile: string,
    opts: { prompt: string; model?: string; provider?: string; reasoning_effort?: string; session_id?: string },
  ): Promise<{ run_id: string; status?: string }> {
    const body: Record<string, unknown> = { input: opts.prompt };
    if (opts.model) body.model = opts.model;
    if (opts.provider) body.provider = opts.provider;
    if (opts.reasoning_effort) body.model_options = { reasoning_effort: opts.reasoning_effort };
    if (opts.session_id) body.session_id = opts.session_id;
    const res = await this.request<{ run_id?: string; status?: string }>(profile, "POST", "/v1/runs", body, 30_000);
    if (!res.run_id) {
      throw new GatewayError(502, "bad_gateway_response", "gateway did not return a run_id");
    }
    logger.info("gateway run started", { profile, run_id: res.run_id });
    return { run_id: res.run_id, status: res.status };
  }

  async getRun(profile: string, runId: string): Promise<GatewayRunStatus> {
    return this.request<GatewayRunStatus>(profile, "GET", `/v1/runs/${encodeURIComponent(runId)}`, undefined, 15_000);
  }

  async stopRun(profile: string, runId: string): Promise<Record<string, unknown>> {
    return this.request(profile, "POST", `/v1/runs/${encodeURIComponent(runId)}/stop`, {}, 15_000);
  }

  /** Event stream (SSE) for one run. Returns the raw Response so the caller can parse it. */
  async openEvents(profile: string, runId: string, signal: AbortSignal, lastSeq: number): Promise<Response> {
    const url = `${this.cfg.gatewayUrl}/p/${encodeURIComponent(profile)}/v1/runs/${encodeURIComponent(runId)}/events${
      lastSeq >= 0 ? `?last_seq=${lastSeq}` : ""
    }`;
    const res = await fetch(url, {
      headers: {
        Authorization: `Bearer ${this.keyFor(profile)}`,
        Accept: "text/event-stream",
      },
      signal,
    });
    if (!res.ok) {
      throw new GatewayError(res.status, "event_stream_failed", `event stream responded ${res.status}`);
    }
    return res;
  }
}

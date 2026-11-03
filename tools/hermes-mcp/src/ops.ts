/**
 * HTTP client for ops-helper.
 *
 * hermes-mcp holds NO Docker socket and no extra privileges: every ops action is
 * a single POST to the small ops-helper service over a private compose network,
 * authenticated with a shared secret (constant-time compared on the helper
 * side). The secret is only ever sent in the Authorization header - never in a
 * URL, never in a log line, never in a tool answer.
 */
import type { Config } from "./config.js";
import { GatewayError } from "./hermes.js";
import { logger } from "./logger.js";

export interface OpsResponse {
  ok: boolean;
  action: string | null;
  tier: string | null;
  impl: string | null;
  code: string | null;
  message: string | null;
  result: unknown;
  audit_id: string | null;
  duration_ms?: number;
}

export class OpsClient {
  constructor(private readonly cfg: Config) {}

  /** True when both the helper URL and the shared secret are configured. */
  enabled(): boolean {
    return Boolean(this.cfg.opsHelperUrl && this.cfg.opsHelperToken);
  }

  private baseUrl(): string {
    return this.cfg.opsHelperUrl.replace(/\/+$/, "");
  }

  async call(
    action: string,
    params: Record<string, unknown> = {},
    opts: { timeoutSeconds?: number } = {},
  ): Promise<OpsResponse> {
    if (!this.enabled()) {
      throw new GatewayError(
        503,
        "ops_helper_not_configured",
        "Ops tools are disabled: set OPS_HELPER_URL and OPS_HELPER_TOKEN (see tools/ops-helper/README.md).",
      );
    }
    const url = `${this.baseUrl()}/op`;
    const timeoutSeconds = opts.timeoutSeconds ?? this.cfg.opsHelperTimeoutSeconds ?? 120;
    const timeoutMs = timeoutSeconds * 1000;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const started = Date.now();
    try {
      const res = await fetch(url, {
        method: "POST",
        headers: {
          authorization: `Bearer ${this.cfg.opsHelperToken}`,
          "content-type": "application/json",
          accept: "application/json",
        },
        body: JSON.stringify({ action, params }),
        signal: controller.signal,
      });
      const text = await res.text();
      if (res.status === 401) {
        throw new GatewayError(
          502,
          "ops_helper_auth_failed",
          "ops-helper rejected the shared secret (OPS_HELPER_TOKEN differs between the two services)",
        );
      }
      if (!res.ok) {
        throw new GatewayError(502, "ops_helper_http_error", `ops-helper answered HTTP ${res.status}`);
      }
      let parsed: OpsResponse;
      try {
        parsed = JSON.parse(text) as OpsResponse;
      } catch {
        throw new GatewayError(502, "ops_helper_bad_response", "ops-helper returned unparseable JSON");
      }
      logger.info("ops call", {
        action,
        code: parsed.code ?? null,
        ok: parsed.ok === true,
        ms: Date.now() - started,
        audit_id: parsed.audit_id ?? null,
      });
      return parsed;
    } catch (exc) {
      if (exc instanceof GatewayError) throw exc;
      if ((exc as Error).name === "AbortError") {
        throw new GatewayError(
          504,
          "ops_helper_timeout",
          `ops-helper did not answer within ${Math.round(timeoutMs / 1000)}s`,
        );
      }
      throw new GatewayError(
        502,
        "ops_helper_unreachable",
        `ops-helper is not reachable at ${this.baseUrl()}: ${(exc as Error).message}`,
      );
    } finally {
      clearTimeout(timer);
    }
  }
}

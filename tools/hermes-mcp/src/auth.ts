/**
 * Bearer-token handling for hermes-mcp.
 *
 * Two levels:
 *  - `base` (HERMES_MCP_TOKEN): read-only T0 tools.
 *  - `ops`  (HERMES_MCP_OPS_TOKEN): T0 + the T1 tools; T2 additionally needs the
 *    local listener.
 *
 * Comparison is constant time over SHA-256 digests (no length leak) and the
 * tokens are never logged, echoed or put in a URL. Kept in its own module so the
 * level rules can be unit-tested without starting the HTTP server.
 */
import { createHash, timingSafeEqual } from "node:crypto";

export type OpsLevel = "base" | "ops";

export interface TokenConfig {
  tokens: string[];
  opsTokens: string[];
}

export function sha256(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}

/** Constant-time bearer check that does not leak the token length. */
export function tokenMatches(candidate: string, tokens: string[]): boolean {
  const candidateDigest = sha256(candidate);
  let ok = false;
  for (const token of tokens) {
    if (timingSafeEqual(candidateDigest, sha256(token))) ok = true;
  }
  return ok;
}

/**
 * Which level a presented bearer token grants. `ops` is a superset of `base`.
 * An empty opsTokens list means nobody gets the elevated level (fail closed).
 * An unknown token is `null` (the caller is rejected).
 */
export function resolveLevel(token: string, cfg: TokenConfig): OpsLevel | null {
  if (!token) return null;
  if (cfg.opsTokens.length > 0 && tokenMatches(token, cfg.opsTokens)) return "ops";
  if (tokenMatches(token, cfg.tokens)) return "base";
  return null;
}

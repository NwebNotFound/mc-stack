/**
 * Bearer token for docker-socket-filter.
 *
 * The helper talks to the filter through the stock Docker CLI
 * (`DOCKER_HOST=tcp://docker-socket-filter:2375`). The CLI has no flag for an
 * auth header, but it sends every `HttpHeaders` entry of its `config.json` with
 * every API request. So at startup the helper writes a private CLI config
 * (mode 0600, on the tmpfs `/tmp`) that carries `Authorization: Bearer <token>`
 * and points `DOCKER_CONFIG` at it; every `docker` child inherits it.
 *
 * The token never appears in an argv list, a log line or an action result.
 */
import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const MIN_FILTER_TOKEN_LENGTH = 24;

/** The CLI config document (exported for tests). */
export function dockerConfigDocument(token: string): string {
  return JSON.stringify({ HttpHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Write `<dir>/config.json` and return `dir` (the value for DOCKER_CONFIG).
 * Throws when the token is too short, so a typo fails at startup.
 */
export function writeDockerAuthConfig(token: string, dir: string): string {
  if (token.length < MIN_FILTER_TOKEN_LENGTH) {
    throw new Error(`OPS_FILTER_TOKEN must be at least ${MIN_FILTER_TOKEN_LENGTH} characters`);
  }
  if (!/^[\x21-\x7e]+$/.test(token)) {
    throw new Error("OPS_FILTER_TOKEN must be printable ASCII without spaces");
  }
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  chmodSync(dir, 0o700);
  const file = join(dir, "config.json");
  writeFileSync(file, dockerConfigDocument(token), { mode: 0o600 });
  chmodSync(file, 0o600);
  return dir;
}

/**
 * Secret redaction, shared by the logger (container log) and the run store
 * (persisted events/output that get_run and get_run_logs hand back).
 *
 * Two layers:
 *  - redactText: scans a string for secret-looking tokens (bearer headers,
 *    GitHub PATs, OpenAI/OpenRouter `sk-` keys, `NAME=value` assignments whose
 *    name looks secret-bearing, long hex blobs).
 *  - redactValue: walks an object/array; a key whose name looks secret-bearing
 *    is replaced wholesale, every other string goes through redactText.
 *
 * It is a safety net, not a guarantee: it only removes values it can recognise.
 * What is certain is what is documented in the README — keep the two in sync.
 */

export const SECRET_KEY_RE = /(token|secret|key|password|passwd|authorization|cookie|credential)/i;

const BEARER_RE = /\b(bearer\s+)[A-Za-z0-9._~+/=-]{8,}/gi;
const GITHUB_PAT_RE = /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g;
// sk-…, sk-or-v1-…, sk-kimi-…, sk-proj-… — OpenAI/OpenRouter/Kimi style keys.
const SK_KEY_RE = /\bsk-[A-Za-z0-9_-]{12,}\b/g;
// `HERMES_MCP_TOKEN=…`, `API_SERVER_KEY: "…"`, `GH_TOKEN=…` — an `.env`-style line.
// (`Authorization:` headers are handled by BEARER_RE above, not here — redacting
// the scheme word there would leave the token itself exposed.)
// The value runs to the END OF THE LINE (issue #146): a generated password may
// contain quotes, backticks or spaces, and stopping at the first one leaked the
// rest. A closing quote that matches the opening one is kept.
const ENV_ASSIGN_RE =
  /(\b[A-Za-z0-9_]*(?:TOKEN|SECRET|PASSWORD|PASSWD|_KEY|KEY_|APIKEY|CREDENTIAL|COOKIE)[A-Za-z0-9_]*\s*[=:]\s*)(["'`]?)([^\r\n]{6,}?)(\2)?(?=[\r\n]|$)/gi;
// The MariaDB image prints `GENERATED ROOT PASSWORD: <pw>` on first init
// (MARIADB_RANDOM_ROOT_PASSWORD); drop everything after it, whatever its length.
const GENERATED_PASSWORD_RE = /(GENERATED\s+(?:ROOT\s+)?PASSWORD\s*:\s*)[^\r\n]*/gi;
const LONG_HEX_RE = /\b[a-f0-9]{40,}\b/gi;

export function redactText(value: string): string {
  return value
    .replace(GENERATED_PASSWORD_RE, "$1[redacted]")
    .replace(BEARER_RE, "$1[redacted]")
    .replace(GITHUB_PAT_RE, "[redacted]")
    .replace(SK_KEY_RE, "[redacted]")
    .replace(ENV_ASSIGN_RE, "$1$2[redacted]$4")
    .replace(LONG_HEX_RE, "[redacted]");
}

export function redactValue(value: unknown, depth = 0): unknown {
  if (depth > 8) return "[depth]";
  if (typeof value === "string") return redactText(value);
  if (Array.isArray(value)) return value.slice(0, 200).map((v) => redactValue(v, depth + 1));
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = SECRET_KEY_RE.test(k) ? "[redacted]" : redactValue(v, depth + 1);
    }
    return out;
  }
  return value;
}

/**
 * Value-only redaction: strings are scanned with redactText, but keys are left
 * alone. Used for stored/returned events, where a key named `input_tokens` must
 * survive (it is a token count, not a secret).
 */
export function redactStrings(value: unknown, depth = 0): unknown {
  if (depth > 8) return "[depth]";
  if (typeof value === "string") return redactText(value);
  if (Array.isArray(value)) return value.map((v) => redactStrings(v, depth + 1));
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = redactStrings(v, depth + 1);
    }
    return out;
  }
  return value;
}

/**
 * Tiny JSON-lines logger with hard redaction.
 *
 * Every line is scanned for secret-looking keys and bearer tokens before it is
 * written, so a stray `Authorization` header or API key can never reach the
 * container log (which ends up in `docker compose logs`).
 */

const SECRET_KEY_RE = /(token|secret|key|password|authorization|cookie|credential)/i;
const BEARER_RE = /\b(bearer\s+)[A-Za-z0-9._~+/=-]{12,}/gi;
const LONG_HEX_RE = /\b[a-f0-9]{40,}\b/gi;

function redactValue(value: unknown, depth = 0): unknown {
  if (depth > 6) return "[depth]";
  if (typeof value === "string") {
    return value.replace(BEARER_RE, "$1[redacted]").replace(LONG_HEX_RE, "[redacted]");
  }
  if (Array.isArray(value)) return value.slice(0, 50).map((v) => redactValue(v, depth + 1));
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = SECRET_KEY_RE.test(k) ? "[redacted]" : redactValue(v, depth + 1);
    }
    return out;
  }
  return value;
}

type Level = "debug" | "info" | "warn" | "error";

function emit(level: Level, message: string, fields?: Record<string, unknown>): void {
  const line: Record<string, unknown> = {
    ts: new Date().toISOString(),
    level,
    msg: redactValue(message),
  };
  if (fields) {
    for (const [k, v] of Object.entries(fields)) {
      line[k] = SECRET_KEY_RE.test(k) ? "[redacted]" : redactValue(v);
    }
  }
  try {
    process.stdout.write(JSON.stringify(line) + "\n");
  } catch {
    process.stdout.write(`{"level":"error","msg":"log serialization failed"}\n`);
  }
}

export const logger = {
  debug: (msg: string, fields?: Record<string, unknown>) => {
    if (process.env.DEBUG) emit("debug", msg, fields);
  },
  info: (msg: string, fields?: Record<string, unknown>) => emit("info", msg, fields),
  warn: (msg: string, fields?: Record<string, unknown>) => emit("warn", msg, fields),
  error: (msg: string, fields?: Record<string, unknown>) => emit("error", msg, fields),
};

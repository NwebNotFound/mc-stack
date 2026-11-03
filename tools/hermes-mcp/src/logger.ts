/**
 * Tiny JSON-lines logger with hard redaction.
 *
 * Every line is scanned for secret-looking keys and bearer tokens before it is
 * written, so a stray `Authorization` header or API key can never reach the
 * container log (which ends up in `docker compose logs`). The same redaction is
 * applied to the persisted run store — see redact.ts.
 */
import { SECRET_KEY_RE, redactValue } from "./redact.js";

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

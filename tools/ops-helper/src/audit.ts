/**
 * Append-only audit log (JSONL).
 *
 * Every call that reaches the dispatcher is recorded - allowed, refused or
 * failed - with time, tool, redacted parameters, tier and outcome. The file is
 * only ever opened in append mode; the helper never reads, truncates or deletes
 * it, and the entry is redacted with the shared redact module before it is
 * written.
 */
import { appendFileSync, mkdirSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { dirname } from "node:path";
import { logger } from "./logger.js";
import { redactValue } from "./redact.js";

export type Decision = "allow" | "deny" | "error";

export interface AuditInput {
  action: string;
  tier?: string;
  impl?: string;
  decision: Decision;
  code?: string;
  params?: unknown;
  durationMs?: number;
  ok?: boolean;
  summary?: unknown;
  caller?: string;
}

export interface AuditEntry extends AuditInput {
  ts: string;
  id: string;
}

/** One JSONL line is capped so a huge log tail cannot fill the disk. */
const MAX_ENTRY_BYTES = 8192;

export class AuditLog {
  private warned = false;

  constructor(private readonly file: string) {}

  path(): string {
    return this.file;
  }

  append(input: AuditInput): string {
    const id = randomUUID();
    const entry: AuditEntry = {
      ts: new Date().toISOString(),
      id,
      action: input.action,
      tier: input.tier,
      impl: input.impl,
      decision: input.decision,
      code: input.code,
      params: redactValue(input.params ?? {}),
      durationMs: input.durationMs,
      ok: input.ok,
      summary: redactValue(input.summary ?? null),
      caller: input.caller,
    };
    let line = JSON.stringify(entry);
    if (Buffer.byteLength(line, "utf8") > MAX_ENTRY_BYTES) {
      line = JSON.stringify({ ...entry, summary: "[truncated]", params: "[truncated]" });
    }
    try {
      mkdirSync(dirname(this.file), { recursive: true });
      appendFileSync(this.file, line + "\n", { encoding: "utf8", mode: 0o600 });
    } catch (exc) {
      // Never swallow silently, but never fail a request because the audit sink
      // is broken either: log loudly (redacted) and keep serving.
      if (!this.warned) {
        this.warned = true;
        logger.error("audit log write failed", { file: this.file, error: (exc as Error).message });
      }
    }
    return id;
  }
}

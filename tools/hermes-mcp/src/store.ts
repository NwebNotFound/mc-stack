/**
 * Durable run index for hermes-mcp.
 *
 * One JSON file per run plus one JSONL event log per run. The volume is small
 * (a preview of the prompt, the terminal output and the agent's lifecycle
 * events), and it survives container restarts so get_run/list_runs keep working
 * while the gateway still holds the run in memory.
 *
 * Everything written here runs through the same redaction as the log
 * (redact.ts), and the read paths redact again, so a secret echoed back by an
 * agent never ends up in the run file or in a get_run / get_run_logs response.
 */
import fs from "node:fs";
import path from "node:path";
import { isUnresolved } from "./hermes.js";
import { logger } from "./logger.js";
import { redactStrings, redactText } from "./redact.js";

export interface RunEvent {
  seq?: number;
  event?: string;
  timestamp?: number;
  [key: string]: unknown;
}

export interface RunRecord {
  run_id: string;
  profile: string;
  backend: "gateway" | "cli";
  status: string;
  created_at: string;
  updated_at: string;
  finished_at?: string;
  prompt_preview: string;
  prompt_chars: number;
  model?: string;
  provider?: string;
  reasoning_effort?: string;
  session_id?: string;
  last_event?: string;
  tool_calls: number;
  event_count: number;
  output?: string;
  error?: string;
  usage?: Record<string, number>;
  cancel_requested?: boolean;
}

const MAX_OUTPUT_CHARS = 20_000;
const MAX_LOG_LINES = 5_000;
const FINISHED_STATUSES = ["completed", "failed", "cancelled", "interrupted", "timed_out"];

export class RunStore {
  private readonly runs = new Map<string, RunRecord>();
  private readonly runsDir: string;

  constructor(dataDir: string) {
    this.runsDir = path.join(dataDir, "runs");
    fs.mkdirSync(this.runsDir, { recursive: true });
    this.load();
  }

  private recordPath(runId: string): string {
    return path.join(this.runsDir, `${runId.replace(/[^a-zA-Z0-9_-]/g, "_")}.json`);
  }

  private logPath(runId: string): string {
    return path.join(this.runsDir, `${runId.replace(/[^a-zA-Z0-9_-]/g, "_")}.jsonl`);
  }

  private load(): void {
    let files: string[];
    try {
      files = fs.readdirSync(this.runsDir).filter((f) => f.endsWith(".json"));
    } catch {
      return;
    }
    for (const file of files) {
      try {
        const rec = JSON.parse(fs.readFileSync(path.join(this.runsDir, file), "utf8")) as RunRecord;
        if (rec && typeof rec.run_id === "string") this.runs.set(rec.run_id, rec);
      } catch {
        logger.warn("skipping unreadable run record", { file });
      }
    }
    if (this.runs.size > 300) {
      const sorted = [...this.runs.values()].sort((a, b) => a.created_at.localeCompare(b.created_at));
      for (const old of sorted.slice(0, this.runs.size - 300)) {
        this.runs.delete(old.run_id);
      }
    }
    logger.info("run store loaded", { runs: this.runs.size });
  }

  private persist(rec: RunRecord): void {
    const target = this.recordPath(rec.run_id);
    const tmp = `${target}.tmp`;
    try {
      fs.writeFileSync(tmp, JSON.stringify(rec), { encoding: "utf8", mode: 0o600 });
      fs.renameSync(tmp, target);
    } catch (exc) {
      logger.warn("failed to persist run record", { run_id: rec.run_id, error: (exc as Error).message });
    }
  }

  create(rec: RunRecord): RunRecord {
    this.runs.set(rec.run_id, rec);
    this.persist(rec);
    return rec;
  }

  get(runId: string): RunRecord | undefined {
    return this.runs.get(runId);
  }

  update(runId: string, patch: Partial<RunRecord>): RunRecord | undefined {
    const rec = this.runs.get(runId);
    if (!rec) return undefined;
    const next: RunRecord = { ...rec, ...patch, updated_at: new Date().toISOString() };
    if (patch.status && FINISHED_STATUSES.includes(patch.status)) {
      next.finished_at = next.finished_at ?? next.updated_at;
    }
    if (typeof next.output === "string") {
      next.output = redactText(next.output);
      if (next.output.length > MAX_OUTPUT_CHARS) {
        next.output = next.output.slice(0, MAX_OUTPUT_CHARS) + "\n[truncated]";
      }
    }
    if (typeof next.error === "string") next.error = redactText(next.error);
    if (next.prompt_preview) next.prompt_preview = redactText(next.prompt_preview);
    this.runs.set(runId, next);
    this.persist(next);
    return next;
  }

  appendEvent(runId: string, event: RunEvent): void {
    const safe = redactStrings(event) as RunEvent;
    const rec = this.runs.get(runId);
    if (rec) {
      rec.event_count += 1;
      if (typeof safe.event === "string" && safe.event.startsWith("tool.")) {
        if (safe.event === "tool.started") rec.tool_calls += 1;
      }
      if (typeof safe.event === "string") rec.last_event = safe.event;
      rec.updated_at = new Date().toISOString();
      this.persist(rec);
    }
    try {
      fs.appendFileSync(this.logPath(runId), JSON.stringify(safe) + "\n", { encoding: "utf8", mode: 0o600 });
    } catch (exc) {
      logger.warn("failed to append run event", { run_id: runId, error: (exc as Error).message });
    }
  }

  appendLogLine(runId: string, line: string): void {
    try {
      fs.appendFileSync(
        this.logPath(runId),
        JSON.stringify({ event: "log", timestamp: Date.now() / 1000, line: redactText(line.slice(0, 4000)) }) + "\n",
        { encoding: "utf8", mode: 0o600 },
      );
    } catch {
      /* best effort */
    }
  }

  logs(runId: string, tail: number): Array<Record<string, unknown>> {
    const file = this.logPath(runId);
    let raw: string;
    try {
      raw = fs.readFileSync(file, "utf8");
    } catch {
      return [];
    }
    const lines = raw.split("\n").filter(Boolean);
    return lines
      .slice(Math.max(0, lines.length - Math.min(tail, MAX_LOG_LINES)))
      .flatMap((line) => {
        try {
          return [redactStrings(JSON.parse(line)) as Record<string, unknown>];
        } catch {
          return [];
        }
      });
  }

  list(filter: { profile?: string; status?: string; limit?: number } = {}): RunRecord[] {
    let out = [...this.runs.values()];
    if (filter.profile) out = out.filter((r) => r.profile === filter.profile);
    if (filter.status) out = out.filter((r) => r.status === filter.status);
    out.sort((a, b) => b.created_at.localeCompare(a.created_at));
    return out.slice(0, filter.limit ?? 25);
  }

  /** Runs that still hold a profile slot (everything not finished, incl. unknown). */
  unresolved(profile?: string): RunRecord[] {
    let out = [...this.runs.values()].filter((r) => isUnresolved(r.status));
    if (profile) out = out.filter((r) => r.profile === profile);
    return out;
  }

  nonTerminal(): RunRecord[] {
    return this.unresolved();
  }

  /** How many runs were created at or after `since` (ISO 8601), for the daily cap. */
  countCreatedSince(since: string): number {
    let n = 0;
    for (const rec of this.runs.values()) {
      if (rec.created_at >= since) n += 1;
    }
    return n;
  }
}

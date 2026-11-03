/**
 * Run manager: starts agent runs, follows them to completion and keeps the
 * local index in sync.
 *
 * Backends:
 *  - "gateway" (default): POST /p/<profile>/v1/runs, poll GET /v1/runs/<id> and
 *    follow the SSE event stream. The gateway owns the agent process.
 *  - "cli" (host-only fallback): spawn `hermes -p <profile> chat -q "<prompt>"`
 *    and track the process here. Not available inside the container.
 */
import { spawn, type ChildProcess } from "node:child_process";
import type { Config } from "./config.js";
import { GatewayClient, GatewayError, isTerminal, type GatewayRunStatus } from "./hermes.js";
import { logger } from "./logger.js";
import type { RunRecord, RunStore } from "./store.js";

export interface StartRunOptions {
  prompt: string;
  model?: string;
  provider?: string;
  reasoning_effort?: string;
  session_id?: string;
}

interface ActiveRun {
  controller: AbortController;
  startedAt: number;
  child?: ChildProcess;
}

const MAX_EVENT_HISTORY_SECONDS = 7200;

export class RunManager {
  private readonly active = new Map<string, ActiveRun>();

  constructor(
    private readonly cfg: Config,
    private readonly client: GatewayClient,
    private readonly store: RunStore,
  ) {}

  /** Re-attach to runs that were still open when the container restarted. */
  async resume(): Promise<void> {
    const open = this.store.nonTerminal();
    if (open.length === 0) return;
    for (const rec of open) {
      if (rec.backend === "cli") {
        this.store.update(rec.run_id, { status: "unknown", error: "MCP restarted while the CLI run was in flight" });
        continue;
      }
      try {
        const status = await this.client.getRun(rec.profile, rec.run_id);
        this.applyStatus(rec.run_id, status);
        if (!isTerminal(status.status)) this.watch(rec.run_id);
      } catch {
        this.store.update(rec.run_id, {
          status: "unknown",
          error: "Gateway no longer knows this run (gateway restart or retention)",
        });
      }
    }
    logger.info("resumed runs", { count: open.length });
  }

  activeCount(profile?: string): number {
    if (!profile) return this.active.size;
    let n = 0;
    for (const runId of this.active.keys()) {
      if (this.store.get(runId)?.profile === profile) n += 1;
    }
    return n;
  }

  async start(profile: string, opts: StartRunOptions): Promise<RunRecord> {
    if (this.activeCount(profile) >= this.cfg.maxActiveRunsPerProfile) {
      throw new GatewayError(
        429,
        "too_many_active_runs",
        `Profile ${profile} already has ${this.activeCount(profile)} active run(s) (limit ${this.cfg.maxActiveRunsPerProfile}). Wait or cancel one.`,
      );
    }

    if (this.cfg.backend === "cli") {
      return this.startCli(profile, opts);
    }

    const { run_id } = await this.client.startRun(profile, opts);
    const now = new Date().toISOString();
    const record: RunRecord = {
      run_id,
      profile,
      backend: "gateway",
      status: "queued",
      created_at: now,
      updated_at: now,
      prompt_preview: opts.prompt.slice(0, 200),
      prompt_chars: opts.prompt.length,
      model: opts.model,
      provider: opts.provider,
      reasoning_effort: opts.reasoning_effort,
      session_id: opts.session_id,
      last_event: "run.started",
      tool_calls: 0,
      event_count: 0,
    };
    this.store.create(record);
    this.watch(run_id);
    return record;
  }

  private startCli(profile: string, opts: StartRunOptions): RunRecord {
    const runId = `cli_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
    const now = new Date().toISOString();
    const record: RunRecord = {
      run_id: runId,
      profile,
      backend: "cli",
      status: "running",
      created_at: now,
      updated_at: now,
      prompt_preview: opts.prompt.slice(0, 200),
      prompt_chars: opts.prompt.length,
      model: opts.model,
      reasoning_effort: opts.reasoning_effort,
      last_event: "run.started",
      tool_calls: 0,
      event_count: 0,
    };
    this.store.create(record);

    const args = ["-p", profile, "chat", "-q", opts.prompt];
    if (opts.reasoning_effort) args.push("--reasoning", opts.reasoning_effort);
    if (opts.model) args.push("-m", opts.model);

    const controller = new AbortController();
    this.active.set(runId, { controller, startedAt: Date.now() });
    let child: ChildProcess;
    try {
      child = spawn(this.cfg.cliCommand, args, { stdio: ["ignore", "pipe", "pipe"] });
    } catch (exc) {
      this.store.update(runId, { status: "failed", error: `failed to spawn ${this.cfg.cliCommand}: ${(exc as Error).message}` });
      this.active.delete(runId);
      const failed = this.store.get(runId)!;
      return failed;
    }
    this.active.get(runId)!.child = child;

    const capture = (chunk: Buffer) => {
      for (const line of chunk.toString("utf8").split(/\r?\n/)) {
        if (line.trim()) this.store.appendLogLine(runId, line);
      }
    };
    child.stdout?.on("data", capture);
    child.stderr?.on("data", capture);

    child.on("error", (exc) => {
      this.store.update(runId, { status: "failed", error: exc.message });
      this.active.delete(runId);
    });
    child.on("close", (code, signal) => {
      const cancelled = this.store.get(runId)?.cancel_requested;
      this.store.update(runId, {
        status: cancelled ? "cancelled" : code === 0 ? "completed" : "failed",
        error: cancelled || code === 0 ? undefined : `hermes exited with code ${code}${signal ? ` (${signal})` : ""}`,
        last_event: cancelled ? "run.cancelled" : "run.completed",
      });
      this.active.delete(runId);
    });

    return record;
  }

  private watch(runId: string): void {
    if (this.active.has(runId)) return;
    const rec = this.store.get(runId);
    if (!rec) return;
    const controller = new AbortController();
    this.active.set(runId, { controller, startedAt: Date.now() });
    void this.follow(runId, rec.profile, controller);
  }

  private async follow(runId: string, profile: string, controller: AbortController): Promise<void> {
    const eventTask = this.consumeEvents(runId, profile, controller.signal).catch((exc) => {
      logger.warn("event stream ended with error", { run_id: runId, error: (exc as Error).message });
    });
    try {
      await this.pollStatus(runId, profile, controller.signal);
    } finally {
      controller.abort();
      await eventTask.catch(() => undefined);
      this.active.delete(runId);
      const rec = this.store.get(runId);
      if (rec && !isTerminal(rec.status)) {
        this.store.update(runId, { status: "unknown", error: "run tracking stopped before a terminal status" });
      }
    }
  }

  private applyStatus(runId: string, status: GatewayRunStatus): void {
    // Only overwrite fields the gateway actually reported: the SSE stream carries
    // richer `last_event` detail that a bare status poll must not erase.
    const patch: Partial<RunRecord> = { status: String(status.status ?? "unknown") };
    if (typeof status.last_event === "string") patch.last_event = status.last_event;
    if (typeof status.output === "string") patch.output = status.output;
    if (typeof status.error === "string") patch.error = status.error;
    if (status.usage && typeof status.usage === "object") patch.usage = status.usage as Record<string, number>;
    if (typeof status.model === "string") patch.model = status.model;
    if (typeof status.session_id === "string") patch.session_id = status.session_id;
    this.store.update(runId, patch);
  }

  private async pollStatus(runId: string, profile: string, signal: AbortSignal): Promise<void> {
    const deadline = Date.now() + this.cfg.maxRunSeconds * 1000;
    let consecutiveErrors = 0;

    while (!signal.aborted) {
      const rec = this.store.get(runId);
      if (!rec || isTerminal(rec.status)) return;
      if (Date.now() > deadline) {
        this.store.update(runId, {
          status: "unknown",
          error: `hermes-mcp stopped tracking after ${this.cfg.maxRunSeconds}s (the run may still be alive in the gateway)`,
        });
        return;
      }
      try {
        const status = await this.client.getRun(profile, runId);
        consecutiveErrors = 0;
        this.applyStatus(runId, status);
        if (isTerminal(status.status)) return;
      } catch (exc) {
        consecutiveErrors += 1;
        const err = exc as GatewayError;
        if (err.status === 404) {
          this.store.update(runId, { status: "unknown", error: "gateway lost this run (restart or retention)" });
          return;
        }
        logger.warn("run status poll failed", { run_id: runId, attempt: consecutiveErrors, error: err.message });
        if (consecutiveErrors >= 30) {
          this.store.update(runId, { status: "unknown", error: `gateway unreachable: ${err.message}` });
          return;
        }
      }
      await new Promise((r) => setTimeout(r, this.cfg.pollIntervalMs));
    }
  }

  /** Follow the gateway's SSE stream and persist every lifecycle event. */
  private async consumeEvents(runId: string, profile: string, signal: AbortSignal): Promise<void> {
    let lastSeq = -1;
    let attempts = 0;

    while (!signal.aborted && attempts < 4) {
      attempts += 1;
      let res: Response;
      try {
        res = await this.client.openEvents(profile, runId, signal, lastSeq);
      } catch (exc) {
        logger.debug("event stream connect failed", { run_id: runId, error: (exc as Error).message });
        await new Promise((r) => setTimeout(r, 1500));
        continue;
      }
      if (!res.body) return;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let idx: number;
          while ((idx = buffer.indexOf("\n\n")) !== -1) {
            const frame = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            const parsed = parseSseFrame(frame);
            if (!parsed) continue;
            if (typeof parsed.seq === "number") lastSeq = parsed.seq;
            this.store.appendEvent(runId, parsed);
            const name = String(parsed.event ?? "");
            if (name === "run.completed" || name === "run.failed" || name === "run.cancelled" || name === "run.interrupted") {
              const rec = this.store.get(runId);
              if (rec && !isTerminal(rec.status)) {
                this.store.update(runId, {
                  status: name.replace("run.", ""),
                  last_event: name,
                  output: typeof parsed.output === "string" ? parsed.output : rec.output,
                  error: typeof parsed.error === "string" ? parsed.error : rec.error,
                });
              }
              return;
            }
          }
        }
      } catch (exc) {
        if (signal.aborted) return;
        logger.debug("event stream read error", { run_id: runId, error: (exc as Error).message });
      } finally {
        reader.releaseLock();
      }
      const rec = this.store.get(runId);
      if (rec && isTerminal(rec.status)) return;
      if (signal.aborted) return;
      await new Promise((r) => setTimeout(r, 1000));
    }
  }

  async cancel(runId: string): Promise<RunRecord> {
    const rec = this.store.get(runId);
    if (!rec) throw new GatewayError(404, "run_not_found", `Unknown run_id: ${runId}`);
    if (isTerminal(rec.status)) return rec;

    this.store.update(runId, { cancel_requested: true, last_event: "run.cancel_requested" });

    if (rec.backend === "cli") {
      const active = this.active.get(runId);
      if (active?.child?.pid) {
        active.child.kill("SIGTERM");
        setTimeout(() => {
          try {
            active.child?.kill("SIGKILL");
          } catch {
            /* already gone */
          }
        }, 5000).unref();
      }
      return this.store.get(runId)!;
    }

    await this.client.stopRun(rec.profile, runId);
    // Keep the watcher alive: the gateway reports `stopping`, then a terminal
    // `cancelled`/`completed` status. Aborting here would lose that transition.
    return this.store.get(runId)!;
  }

  shutdown(): void {
    for (const [, run] of this.active) {
      run.controller.abort();
      try {
        run.child?.kill("SIGTERM");
      } catch {
        /* ignore */
      }
    }
    this.active.clear();
  }
}

/** Minimal SSE frame parser: joins `data:` lines and ignores comments/ids. */
function parseSseFrame(frame: string): Record<string, unknown> | null {
  const dataLines: string[] = [];
  for (const rawLine of frame.split(/\r?\n/)) {
    const line = rawLine.trimEnd();
    if (!line || line.startsWith(":")) continue;
    if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
  }
  if (dataLines.length === 0) return null;
  try {
    const parsed = JSON.parse(dataLines.join("\n")) as Record<string, unknown>;
    return typeof parsed === "object" && parsed !== null ? parsed : null;
  } catch {
    return null;
  }
}

export { MAX_EVENT_HISTORY_SECONDS };

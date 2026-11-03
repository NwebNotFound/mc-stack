/**
 * Run manager: starts agent runs, follows them to completion and keeps the
 * local index in sync.
 *
 * Backends:
 *  - "gateway" (default): POST /p/<profile>/v1/runs, poll GET /v1/runs/<id> and
 *    follow the SSE event stream. The gateway owns the agent process.
 *  - "cli" (host-only fallback): spawn `hermes -p <profile> chat -q "<prompt>"`
 *    and track the process here. Not available inside the container.
 *
 * Guardrails before a run starts:
 *  - model/provider/reasoning_effort must be on the configured allowlist
 *    (fail closed: omitting model= uses the profile default);
 *  - a global per-UTC-day budget (HERMES_MCP_MAX_RUNS_PER_DAY);
 *  - the per-profile slot is reserved atomically before the run starts, so two
 *    concurrent start_run calls cannot overshoot the limit.
 * A run that waits for approval is stopped after HERMES_MCP_APPROVAL_WAIT_SECONDS
 * (hermes-mcp never approves on the agent's behalf). A run we lost sight of
 * (`unknown`) keeps counting against the slot until it is resolved or the
 * HERMES_MCP_UNKNOWN_TIMEOUT_SECONDS sweep marks it `timed_out`.
 */
import { spawn, type ChildProcess } from "node:child_process";
import type { Config } from "./config.js";
import { GatewayClient, GatewayError, isTerminal, isUnresolved, type GatewayRunStatus } from "./hermes.js";
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

/** Midnight UTC of the current day, as an ISO string (lexicographic == chronological). */
export function startOfUtcDay(now = new Date()): string {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate())).toISOString();
}

export class RunManager {
  private readonly active = new Map<string, ActiveRun>();
  /** Per-profile slots held by an in-flight start_run (not yet in the store). */
  private readonly reserved = new Map<string, number>();
  private sweeper?: ReturnType<typeof setInterval>;

  constructor(
    private readonly cfg: Config,
    private readonly client: GatewayClient,
    private readonly store: RunStore,
  ) {}

  /** Re-attach to runs that were still open (incl. `unknown`) when we restarted. */
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

  /** Periodically resolve stale `unknown` runs so they stop holding a slot. */
  startSweeper(): void {
    if (this.sweeper) return;
    const intervalMs = Math.min(60_000, Math.max(5_000, this.cfg.pollIntervalMs * 4));
    this.sweeper = setInterval(() => {
      try {
        const n = this.sweepUnknown();
        if (n > 0) logger.warn("marked stale unknown runs as timed_out", { count: n });
      } catch (exc) {
        logger.warn("unknown-run sweep failed", { error: (exc as Error).message });
      }
    }, intervalMs);
    this.sweeper.unref?.();
  }

  /** Mark `unknown` runs older than the timeout as `timed_out`. Returns how many. */
  sweepUnknown(now = Date.now()): number {
    let changed = 0;
    for (const rec of this.store.unresolved()) {
      if (rec.status !== "unknown") continue;
      const ts = Date.parse(rec.updated_at || rec.created_at);
      if (Number.isFinite(ts) && now - ts > this.cfg.unknownTimeoutSeconds * 1000) {
        this.store.update(rec.run_id, {
          status: "timed_out",
          last_event: "run.timed_out",
          error: `unknown run was not resolved within ${this.cfg.unknownTimeoutSeconds}s; marked timed_out to release the profile slot`,
        });
        changed += 1;
      }
    }
    return changed;
  }

  private reservedCount(profile?: string): number {
    if (!profile) {
      let n = 0;
      for (const count of this.reserved.values()) n += count;
      return n;
    }
    return this.reserved.get(profile) ?? 0;
  }

  private reserve(profile: string): void {
    this.reserved.set(profile, (this.reserved.get(profile) ?? 0) + 1);
  }

  private release(profile: string): void {
    const n = (this.reserved.get(profile) ?? 0) - 1;
    if (n > 0) this.reserved.set(profile, n);
    else this.reserved.delete(profile);
  }

  /**
   * Active runs for a profile = every run in the store that is not finished
   * (including `unknown`) plus the slots reserved by in-flight starts.
   */
  activeCount(profile?: string): number {
    return this.store.unresolved(profile).length + this.reservedCount(profile);
  }

  /** Reject model/provider/reasoning_effort overrides that are not allowlisted. */
  private validateOptions(opts: StartRunOptions): void {
    if (opts.model && !this.cfg.allowedModels.includes(opts.model)) {
      throw new GatewayError(
        400,
        "model_not_allowed",
        `model ${JSON.stringify(opts.model)} is not allowed. Allowed models: ${this.cfg.allowedModels.join(", ") || "(none)"}. Omit model= to use the profile's default model.`,
      );
    }
    if (opts.provider && !this.cfg.allowedProviders.includes(opts.provider)) {
      throw new GatewayError(
        400,
        "provider_not_allowed",
        `provider ${JSON.stringify(opts.provider)} is not allowed. Allowed providers: ${this.cfg.allowedProviders.join(", ") || "(none)"}. Omit provider= to use the profile's default provider.`,
      );
    }
    if (opts.reasoning_effort && !this.cfg.allowedReasoningEfforts.includes(opts.reasoning_effort)) {
      throw new GatewayError(
        400,
        "reasoning_effort_not_allowed",
        `reasoning_effort ${JSON.stringify(opts.reasoning_effort)} is not allowed. Allowed: ${this.cfg.allowedReasoningEfforts.join(", ") || "(none)"}.`,
      );
    }
  }

  async start(profile: string, opts: StartRunOptions): Promise<RunRecord> {
    this.validateOptions(opts);

    const since = startOfUtcDay();
    const today = this.store.countCreatedSince(since);
    if (today >= this.cfg.maxRunsPerDay) {
      throw new GatewayError(
        429,
        "daily_run_limit",
        `Daily run limit reached (${this.cfg.maxRunsPerDay} run(s) per UTC day; ${today} started). Try again after 00:00 UTC.`,
      );
    }

    if (this.activeCount(profile) >= this.cfg.maxActiveRunsPerProfile) {
      throw new GatewayError(
        429,
        "too_many_active_runs",
        `Profile ${profile} already has ${this.activeCount(profile)} active run(s) (limit ${this.cfg.maxActiveRunsPerProfile}). Wait or cancel one.`,
      );
    }

    // Reserve the slot atomically (no await between check and reserve), so a
    // concurrent start_run for the same profile cannot double-book it.
    this.reserve(profile);
    try {
      if (this.cfg.backend === "cli") {
        const rec = this.startCli(profile, opts);
        this.release(profile);
        return rec;
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
      // The store now counts this run, so the reservation is no longer needed.
      this.release(profile);
      return record;
    } catch (exc) {
      this.release(profile);
      throw exc;
    }
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
      if (rec && isUnresolved(rec.status)) {
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
    let approvalSince = 0;
    let approvalStopRequested = false;

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

        // A run waiting for approval must not hold the slot indefinitely:
        // stop it (never approve) after the configured wait.
        if (status.status === "waiting_for_approval") {
          if (approvalSince === 0) approvalSince = Date.now();
          if (!approvalStopRequested && Date.now() - approvalSince > this.cfg.approvalWaitSeconds * 1000) {
            approvalStopRequested = true;
            this.store.update(runId, {
              last_event: "run.approval_timeout",
              error: `run waited ${this.cfg.approvalWaitSeconds}s for approval; hermes-mcp never approves, so it is being stopped`,
            });
            try {
              await this.client.stopRun(profile, runId);
            } catch (exc) {
              logger.warn("failed to stop a run waiting for approval", {
                run_id: runId,
                error: (exc as Error).message,
              });
            }
          }
        } else {
          approvalSince = 0;
          approvalStopRequested = false;
        }
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
    if (this.sweeper) {
      clearInterval(this.sweeper);
      this.sweeper = undefined;
    }
    this.reserved.clear();
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

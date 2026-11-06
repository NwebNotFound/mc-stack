/**
 * Gate state: cooldowns (persisted, so a helper restart cannot be used to skip
 * one) and the in-memory rate window per action.
 */
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { logger } from "./logger.js";

interface PersistedState {
  version: 1;
  last_run_at: Record<string, number>;
}

export class OpsState {
  private lastRunAt = new Map<string, number>();
  private hits = new Map<string, number[]>();
  private dirty = false;
  /** Name of the T2 action currently running (in-memory; one process). */
  private t2Holder: string | null = null;

  constructor(
    private readonly file: string,
    private readonly now: () => number = Date.now,
  ) {}

  load(): void {
    if (!existsSync(this.file)) return;
    try {
      const parsed = JSON.parse(readFileSync(this.file, "utf8")) as PersistedState;
      if (parsed && parsed.version === 1 && parsed.last_run_at && typeof parsed.last_run_at === "object") {
        for (const [key, value] of Object.entries(parsed.last_run_at)) {
          if (typeof value === "number" && Number.isFinite(value)) this.lastRunAt.set(key, value);
        }
      }
    } catch (exc) {
      logger.warn("state file unreadable, starting with empty cooldowns", {
        file: this.file,
        error: (exc as Error).message,
      });
    }
  }

  private save(): void {
    if (!this.dirty) return;
    try {
      mkdirSync(dirname(this.file), { recursive: true });
      const payload: PersistedState = { version: 1, last_run_at: Object.fromEntries(this.lastRunAt) };
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, JSON.stringify(payload), { encoding: "utf8", mode: 0o600 });
      renameSync(tmp, this.file);
      this.dirty = false;
    } catch (exc) {
      logger.warn("state file write failed", { file: this.file, error: (exc as Error).message });
    }
  }

  /** Seconds left of the cooldown for a key, 0 when free. */
  cooldownRemaining(key: string, seconds: number, at: number = this.now()): number {
    if (seconds <= 0) return 0;
    const last = this.lastRunAt.get(key);
    if (last === undefined) return 0;
    const left = Math.ceil((last + seconds * 1000 - at) / 1000);
    return left > 0 ? left : 0;
  }

  recordRun(key: string, at: number = this.now()): void {
    this.lastRunAt.set(key, at);
    this.dirty = true;
    this.save();
  }

  /** Counts this call inside the sliding window (and remembers it). */
  rateHit(key: string, windowSeconds: number, at: number = this.now()): number {
    const cutoff = at - windowSeconds * 1000;
    const list = (this.hits.get(key) ?? []).filter((t) => t > cutoff);
    list.push(at);
    this.hits.set(key, list);
    return list.length;
  }

  /**
   * Process-wide T2 mutex (issue #144): at most ONE T2 action runs at a time,
   * a second one is refused (not queued). Returns false when it is held.
   */
  tryAcquireT2(action: string): boolean {
    if (this.t2Holder !== null) return false;
    this.t2Holder = action;
    return true;
  }

  releaseT2(): void {
    this.t2Holder = null;
  }

  t2Busy(): string | null {
    return this.t2Holder;
  }

  /** Peek without recording (used by tests/diagnostics). */
  rateCount(key: string, windowSeconds: number, at: number = this.now()): number {
    const cutoff = at - windowSeconds * 1000;
    return (this.hits.get(key) ?? []).filter((t) => t > cutoff).length;
  }
}

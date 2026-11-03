/**
 * Build/test job channel to the `plugin-builder` service.
 *
 * ops-helper no longer starts a container for a build or a test: the
 * docker-socket-filter refuses `POST /containers/create` on purpose (that route
 * is what would let a caller mount `/` and become host root). Instead the helper
 * drops a tiny, strictly shaped request into a shared volume:
 *
 *   <jobsDir>/job-<uuid>.json     { "v": 1, "action": "build"|"test",
 *                                   "ref": "<40-hex commit sha>", "target": "?" }
 *
 * `plugin-builder` (a separate, permanent service with `network_mode: none` and
 * NO Docker socket) polls that directory, re-validates everything, runs one fixed
 * command and writes
 *
 *   <resultsDir>/result-<uuid>.json
 *
 * into the artifact volume. The helper polls for that file. The builder never
 * trusts the job - a compromised helper could at most ask it to build/test
 * something it is already allowed to build.
 *
 * The job file is the ONLY thing the two services share: no exec, no socket, no
 * network.
 */
import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

export const JOB_VERSION = 1;
/** A full commit SHA. Nothing else is ever written into a job file. */
export const REF_RE = /^[a-f0-9]{40}$/;
const TARGET_RE = /^[a-z0-9][a-z0-9-]{0,31}$/;

export type JobAction = "build" | "test";

export interface BuilderJob {
  v: number;
  id: string;
  action: JobAction;
  ref: string;
  target?: string;
  requested_at: string;
}

export interface BuilderArtifact {
  path?: string;
  sha256?: string;
  size_bytes?: number;
}

export interface BuilderResult {
  v: number;
  id: string;
  action: JobAction;
  ok: boolean;
  code?: string | null;
  ref: string;
  target?: string | null;
  started_at?: string;
  finished_at?: string;
  tests?: { tests: number; failures: number; errors: number; skipped: number } | null;
  /** Whether the builder's working tree diverged from the commit (a count only). */
  dirty?: boolean | null;
  dirty_files?: number | null;
  artifact?: BuilderArtifact | null;
  output_tail?: string;
  truncated?: boolean;
}

/** A deterministic, per-request job id (uuid v4). */
export function newJobId(): string {
  return randomUUID();
}

/** Build the exact on-disk job object. Validated first; throws on anything odd. */
export function makeJob(action: JobAction, ref: string, target: string | undefined, id: string, at: string): BuilderJob {
  if (action !== "build" && action !== "test") throw new Error(`invalid job action: ${String(action)}`);
  if (!REF_RE.test(ref)) throw new Error("ref must be a full lowercase 40-hex commit sha");
  if (action === "test") {
    if (target === undefined || !TARGET_RE.test(target)) throw new Error("a test job needs a valid target");
  } else if (target !== undefined) {
    throw new Error("a build job must not carry a target");
  }
  const job: BuilderJob = { v: JOB_VERSION, id, action, ref, ...(target ? { target } : {}), requested_at: at };
  return job;
}

export interface JobClient {
  submit(job: BuilderJob): string;
  awaitResult(id: string, timeoutMs: number): Promise<BuilderResult | null>;
  resultPath(id: string): string;
  jobPath(id: string): string;
}

export class FileJobClient implements JobClient {
  constructor(
    private readonly jobsDir: string,
    private readonly resultsDir: string,
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
    private readonly pollMs = 1000,
  ) {}

  jobPath(id: string): string {
    return `${this.jobsDir}/job-${id}.json`;
  }

  resultPath(id: string): string {
    return `${this.resultsDir}/result-${id}.json`;
  }

  /** Atomically (exclusive create) write the job file. Returns the file path. */
  submit(job: BuilderJob): string {
    mkdirSync(this.jobsDir, { recursive: true, mode: 0o777 });
    const path = this.jobPath(job.id);
    writeFileSync(path, JSON.stringify(job, null, 2), { encoding: "utf8", mode: 0o644, flag: "wx" });
    return path;
  }

  /** Poll for the builder's answer. Returns null on timeout (nothing was run). */
  async awaitResult(id: string, timeoutMs: number): Promise<BuilderResult | null> {
    const path = this.resultPath(id);
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const result = this.readResult(path);
      if (result) return result;
      if (Date.now() >= deadline) return null;
      await this.sleep(this.pollMs);
    }
  }

  private readResult(path: string): BuilderResult | null {
    if (!existsSync(path)) return null;
    let parsed: unknown;
    try {
      parsed = JSON.parse(readFileSync(path, "utf8"));
    } catch {
      // The builder writes with a temp file + rename, so a half-written file is
      // not expected; treat an unparseable one as "not ready yet".
      return null;
    }
    if (typeof parsed !== "object" || parsed === null) return null;
    const r = parsed as BuilderResult;
    if (r.v !== JOB_VERSION || typeof r.id !== "string" || typeof r.ok !== "boolean") return null;
    return r;
  }
}

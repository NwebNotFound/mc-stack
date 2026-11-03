/**
 * Process runner. `shell: false` always: every command is an argv list, so a
 * parameter can never turn into a second command, a redirect or a glob.
 *
 * Output is capped (per stream) and the child is killed on timeout or when the
 * cap is hit, so a chatty container cannot exhaust the helper's memory.
 */
import { spawn } from "node:child_process";
import { accessSync, constants } from "node:fs";
import { delimiter, join } from "node:path";

export interface RunOptions {
  cwd?: string;
  env?: Record<string, string>;
  timeoutMs?: number;
  maxBytes?: number;
}

export interface RunResult {
  ok: boolean;
  code: number | null;
  signal: string | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
  truncated: boolean;
  durationMs: number;
  error?: string;
}

export interface Runner {
  run(argv: string[], opts?: RunOptions): Promise<RunResult>;
}

const DEFAULT_TIMEOUT_MS = 120_000;
const DEFAULT_MAX_BYTES = 262_144;

export class ExecRunner implements Runner {
  async run(argv: string[], opts: RunOptions = {}): Promise<RunResult> {
    if (argv.length === 0) throw new Error("empty argv");
    const maxBytes = opts.maxBytes ?? DEFAULT_MAX_BYTES;
    const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    const started = Date.now();

    return await new Promise<RunResult>((resolve) => {
      let child;
      try {
        child = spawn(argv[0], argv.slice(1), {
          cwd: opts.cwd,
          env: { ...process.env, ...(opts.env ?? {}) },
          shell: false,
          stdio: ["ignore", "pipe", "pipe"],
        });
      } catch (exc) {
        resolve({
          ok: false,
          code: null,
          signal: null,
          stdout: "",
          stderr: "",
          timedOut: false,
          truncated: false,
          durationMs: Date.now() - started,
          error: (exc as Error).message,
        });
        return;
      }

      let stdout = "";
      let stderr = "";
      let truncated = false;
      let timedOut = false;
      let settled = false;

      const timer = setTimeout(() => {
        timedOut = true;
        child.kill("SIGTERM");
        setTimeout(() => child.kill("SIGKILL"), 2000).unref();
      }, timeoutMs);

      const collect = (chunk: Buffer, which: "out" | "err") => {
        const text = chunk.toString("utf8");
        if (which === "out") {
          if (stdout.length + text.length > maxBytes) {
            stdout += text.slice(0, Math.max(0, maxBytes - stdout.length));
            truncated = true;
            child.kill("SIGTERM");
          } else stdout += text;
        } else if (stderr.length + text.length > maxBytes) {
          stderr += text.slice(0, Math.max(0, maxBytes - stderr.length));
          truncated = true;
        } else stderr += text;
      };

      child.stdout?.on("data", (c: Buffer) => collect(c, "out"));
      child.stderr?.on("data", (c: Buffer) => collect(c, "err"));

      const finish = (code: number | null, signal: string | null, error?: string) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve({
          ok: code === 0 && !timedOut && error === undefined,
          code,
          signal,
          stdout,
          stderr,
          timedOut,
          truncated,
          durationMs: Date.now() - started,
          error,
        });
      };

      child.on("error", (err) => finish(null, null, err.message));
      child.on("close", (code, signal) => finish(code, signal));
    });
  }
}

/** Resolve an executable on PATH (used to detect pwsh/powershell, nothing else). */
export function findExecutable(name: string, pathEnv = process.env.PATH ?? ""): string | null {
  if (!/^[A-Za-z0-9._-]{1,32}$/.test(name)) return null;
  for (const dir of pathEnv.split(delimiter)) {
    if (!dir) continue;
    const candidate = join(dir, name);
    try {
      accessSync(candidate, constants.X_OK);
      return candidate;
    } catch {
      // keep looking
    }
  }
  return null;
}

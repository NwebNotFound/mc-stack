/**
 * git helpers for plugin-builder: build EXACTLY a commit, and report how far the
 * working tree has drifted from it.
 *
 * Why this module exists: a job carries a commit sha, so the artifact must
 * contain that commit's files and nothing else. Copying the working tree would
 * silently bake local, uncommitted edits into an artifact that carries the name
 * of a commit. `git archive` reads the object database only (no working tree, no
 * index refresh), so it works against a READ-ONLY `.git` mount and cannot be
 * influenced by local edits.
 *
 * Both processes are spawned with an argv list and `shell: false` - no shell
 * string is ever built from a value that came out of a job file.
 */
import { spawn } from "node:child_process";

const DEFAULT_TIMEOUT_MS = 300_000;

/**
 * Export the tree of `ref` (limited to the pathspec `source`) into `destDir`.
 *
 * @returns {Promise<{ok: boolean, code: string|null, stderr: string}>}
 */
export function extractCommit({ repoDir, ref, source, destDir, timeoutMs = DEFAULT_TIMEOUT_MS }) {
  return new Promise((resolve) => {
    let settled = false;
    let gitCode = null;
    let tarCode = null;
    let timedOut = false;
    let gitErr = "";
    let tarErr = "";

    let git;
    let tar;
    try {
      git = spawn("git", ["-C", repoDir, "archive", "--format=tar", ref, "--", source], {
        shell: false,
        stdio: ["ignore", "pipe", "pipe"],
        env: { ...process.env, GIT_OPTIONAL_LOCKS: "0" },
      });
      tar = spawn("tar", ["-x", "-C", destDir], { shell: false, stdio: ["pipe", "pipe", "pipe"] });
    } catch (exc) {
      resolve({ ok: false, code: "archive_spawn_failed", stderr: String(exc.message).slice(0, 500) });
      return;
    }

    const finish = () => {
      if (settled || gitCode === null || tarCode === null) return;
      settled = true;
      clearTimeout(timer);
      const ok = gitCode === 0 && tarCode === 0 && !timedOut;
      const code = ok ? null : timedOut ? "archive_timeout" : gitCode !== 0 ? "archive_git_failed" : "archive_tar_failed";
      resolve({ ok, code, stderr: `${gitErr}${tarErr}`.slice(0, 4000) });
    };

    const timer = setTimeout(() => {
      timedOut = true;
      git.kill("SIGKILL");
      tar.kill("SIGKILL");
    }, timeoutMs);

    // A tar that dies early gives git an EPIPE; that must not crash the poller.
    git.stdout.on("error", () => {});
    tar.stdin.on("error", () => {});
    git.on("error", (exc) => {
      gitErr += exc.message;
    });
    tar.on("error", (exc) => {
      tarErr += exc.message;
    });
    git.stdout.on("data", () => {});
    git.stderr.on("data", (c) => {
      if (gitErr.length < 1000) gitErr += c.toString("utf8");
    });
    tar.stderr.on("data", (c) => {
      if (tarErr.length < 1000) tarErr += c.toString("utf8");
    });
    git.stdout.pipe(tar.stdin);

    git.on("close", (code) => {
      gitCode = code;
      finish();
    });
    tar.on("close", (code) => {
      tarCode = code;
      finish();
    });
  });
}

/**
 * How many tracked paths under `source` differ between the commit and the
 * WORKING TREE? A count only - never file names. `GIT_OPTIONAL_LOCKS=0` keeps
 * `git status` from trying to refresh (and therefore write) the index, so this
 * works with the read-only `.git` mount.
 *
 * @returns {Promise<{ok: boolean, count: number|null, stderr: string}>}
 */
export function worktreeStatus({ repoDir, source, timeoutMs = 30_000 }) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn("git", ["-C", repoDir, "status", "--porcelain", "--untracked-files=no", "--", source], {
        shell: false,
        stdio: ["ignore", "pipe", "pipe"],
        env: { ...process.env, GIT_OPTIONAL_LOCKS: "0" },
      });
    } catch (exc) {
      resolve({ ok: false, count: null, stderr: String(exc.message).slice(0, 500) });
      return;
    }
    let out = "";
    let err = "";
    let settled = false;
    const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
    child.stdout.on("data", (c) => {
      if (out.length < 262144) out += c.toString("utf8");
    });
    child.stderr.on("data", (c) => {
      if (err.length < 1000) err += c.toString("utf8");
    });
    child.on("error", (exc) => {
      err += exc.message;
    });
    child.on("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const lines = out.split("\n").filter((line) => line.trim().length > 0);
      resolve({ ok: code === 0, count: code === 0 ? lines.length : null, stderr: err.slice(0, 1000) });
    });
  });
}

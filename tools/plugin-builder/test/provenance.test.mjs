import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { extractCommit, worktreeStatus } from "../src/git.mjs";

/**
 * Provenance is a security property, not a nicety: a job names a COMMIT, so the
 * artifact must contain that commit's tree. Copying the working tree (what the
 * builder used to do) would bake local, uncommitted edits into an artifact that
 * carries a commit's sha.
 *
 * These tests build a throwaway git repo in a temp dir, leave a local edit in
 * the tree and assert that the edit does NOT reach the extracted tree - while
 * the tree is still reported as dirty. `.git` is made read-only first, mirroring
 * the `:ro` mount the real service runs with.
 */

const hasGit = spawnSync("git", ["--version"], { stdio: "ignore" }).status === 0;
const hasTar = spawnSync("tar", ["--version"], { stdio: "ignore" }).status === 0;
const skip = hasGit && hasTar ? false : "git and tar are required for the provenance tests";

function git(repo, args) {
  return execFileSync("git", ["-C", repo, ...args], {
    encoding: "utf8",
    env: {
      ...process.env,
      GIT_OPTIONAL_LOCKS: "0",
      GIT_AUTHOR_NAME: "t",
      GIT_AUTHOR_EMAIL: "t@example.invalid",
      GIT_COMMITTER_NAME: "t",
      GIT_COMMITTER_EMAIL: "t@example.invalid",
    },
  }).trim();
}

/** A repo with plugins-src/demo/tracked.txt, tools/other/other.txt and README.md committed. */
function makeRepo() {
  const root = mkdtempSync(join(tmpdir(), "builder-prov-"));
  git(root, ["init", "--quiet", "-b", "main"]);
  mkdirSync(join(root, "plugins-src", "demo"), { recursive: true });
  mkdirSync(join(root, "tools", "other"), { recursive: true });
  writeFileSync(join(root, "plugins-src", "demo", "tracked.txt"), "COMMITTED\n");
  writeFileSync(join(root, "tools", "other", "other.txt"), "OTHER\n");
  writeFileSync(join(root, "README.md"), "ROOT\n");
  git(root, ["add", "-A"]);
  git(root, ["-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "initial"]);
  return { root, ref: git(root, ["rev-parse", "HEAD"]) };
}

/** Recursively set permissions so the tree can be removed again. */
function makeWritable(path) {
  try {
    chmodSync(path, 0o755);
  } catch {
    /* ignore */
  }
  let entries = [];
  try {
    entries = readdirSync(path, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const full = join(path, entry.name);
    if (entry.isDirectory()) makeWritable(full);
    else
      try {
        chmodSync(full, 0o644);
      } catch {
        /* ignore */
      }
  }
}

/** Model the production `:ro` mount for `.git`. */
function makeReadOnly(path) {
  const entries = readdirSync(path, { withFileTypes: true, recursive: true });
  for (const entry of entries) {
    try {
      chmodSync(join(entry.parentPath ?? entry.path, entry.name), entry.isDirectory() ? 0o555 : 0o444);
    } catch {
      /* best effort */
    }
  }
  chmodSync(path, 0o555);
}

test("extractCommit exports the COMMIT, never the locally edited working tree", { skip }, async () => {
  const { root, ref } = makeRepo();
  const dest = mkdtempSync(join(tmpdir(), "builder-prov-out-"));
  try {
    // The local edit that must NOT reach the build.
    writeFileSync(join(root, "plugins-src", "demo", "tracked.txt"), "LOCAL EDIT\n");
    writeFileSync(join(root, "plugins-src", "demo", "untracked.txt"), "UNTRACKED\n");
    makeReadOnly(join(root, ".git"));

    const res = await extractCommit({ repoDir: root, ref, source: "plugins-src/demo", destDir: dest });
    assert.equal(res.ok, true, `extractCommit failed: ${res.code} ${res.stderr}`);

    const built = readFileSync(join(dest, "plugins-src", "demo", "tracked.txt"), "utf8");
    assert.equal(built, "COMMITTED\n", "the artifact must contain the commit's bytes, not the local edit");
    assert.equal(existsSync(join(dest, "plugins-src", "demo", "untracked.txt")), false, "an untracked file must not be exported");
    // The pathspec limits the export: a sibling source tree is not in the archive.
    assert.equal(existsSync(join(dest, "tools", "other", "other.txt")), false, "only the requested source is exported");
  } finally {
    makeWritable(root);
    rmSync(root, { recursive: true, force: true });
    rmSync(dest, { recursive: true, force: true });
  }
});

test("worktreeStatus reports the local edit as a count (dirty) and a clean tree as 0", { skip }, async () => {
  const { root, ref } = makeRepo();
  try {
    const clean = await worktreeStatus({ repoDir: root, source: "plugins-src/demo" });
    assert.equal(clean.ok, true, `worktreeStatus failed: ${clean.stderr}`);
    assert.equal(clean.count, 0, "a freshly checked out tree must be clean");

    writeFileSync(join(root, "plugins-src", "demo", "tracked.txt"), "LOCAL EDIT\n");
    // Untracked files are not edits to the commit; keep them out of the count.
    writeFileSync(join(root, "plugins-src", "demo", "untracked.txt"), "UNTRACKED\n");
    const dirty = await worktreeStatus({ repoDir: root, source: "plugins-src/demo" });
    assert.equal(dirty.ok, true, `worktreeStatus failed: ${dirty.stderr}`);
    assert.equal(dirty.count, 1, "one modified tracked file, untracked files not counted");

    // A read-only .git (the production mount) must not break the check.
    makeReadOnly(join(root, ".git"));
    const roDirty = await worktreeStatus({ repoDir: root, source: "plugins-src/demo" });
    assert.equal(roDirty.ok, true, `worktreeStatus must work with a read-only .git: ${roDirty.stderr}`);
    assert.equal(roDirty.count, 1);
    assert.equal(ref.length, 40);
  } finally {
    makeWritable(root);
    rmSync(root, { recursive: true, force: true });
  }
});

test("extractCommit refuses a commit sha that does not exist", { skip }, async () => {
  const { root } = makeRepo();
  const dest = mkdtempSync(join(tmpdir(), "builder-prov-out-"));
  try {
    const res = await extractCommit({ repoDir: root, ref: "b".repeat(40), source: "plugins-src/demo", destDir: dest });
    assert.equal(res.ok, false);
    assert.equal(res.code, "archive_git_failed");
  } finally {
    makeWritable(root);
    rmSync(root, { recursive: true, force: true });
    rmSync(dest, { recursive: true, force: true });
  }
});

test("a sparsely mounted repo (only the source tree present) is still reported clean", { skip }, async () => {
  const { root } = makeRepo();
  try {
    // Mirror the real mount: only plugins-src and tools are there, the repo root
    // files are missing. A pathspec-limited `git status` must not call the
    // unmounted paths "deleted", or `dirty` would be permanently true.
    rmSync(join(root, "README.md"), { force: true });
    rmSync(join(root, "tools"), { recursive: true, force: true });

    const status = await worktreeStatus({ repoDir: root, source: "plugins-src/demo" });
    assert.equal(status.ok, true, `worktreeStatus failed: ${status.stderr}`);
    assert.equal(status.count, 0, `unmounted paths must not be counted as edits (got ${status.count})`);

    // ...but a real edit inside the mounted source is still seen.
    writeFileSync(join(root, "plugins-src", "demo", "tracked.txt"), "LOCAL EDIT\n");
    const dirty = await worktreeStatus({ repoDir: root, source: "plugins-src/demo" });
    assert.equal(dirty.count, 1);
  } finally {
    makeWritable(root);
    rmSync(root, { recursive: true, force: true });
  }
});

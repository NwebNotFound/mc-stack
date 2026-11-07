import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

/**
 * The docker-socket-filter is the privilege boundary between ops-helper and the
 * Docker socket, so its least-privilege configuration is asserted, not trusted.
 *
 * This reads compose.yaml with a tiny indent-based block splitter (no YAML
 * dependency) and checks the properties that actually matter:
 *  - exactly ONE service mounts the socket, and it is the filter;
 *  - that service has no host port and lives only in the internal `ops` network;
 *  - it is hardened (read_only, cap_drop ALL, no-new-privileges);
 *  - ops-helper keeps no socket, talks to the filter over TCP and depends on it;
 *  - the restart allowlist names the two T1 containers and NO game server;
 *  - the replaced tecnativa image is gone.
 */

const COMPOSE_PATH = new URL("../../../compose.yaml", import.meta.url).pathname;

function serviceBlocks() {
  const lines = readFileSync(COMPOSE_PATH, "utf8")
    .split("\n")
    .map((line) => line.replace(/\r$/, ""));
  const start = lines.findIndex((line) => line === "services:");
  if (start < 0) throw new Error("no `services:` key in compose.yaml");
  const blocks = new Map();
  let current = null;
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (/^\S/.test(line)) break; // a new top-level key ends the services section
    if (/^ {2}#/.test(line)) {
      // A section-level comment: it documents the NEXT service, so it must not
      // be attributed to the previous one.
      current = null;
      continue;
    }
    const m = /^ {2}([A-Za-z0-9._-]+):\s*$/.exec(line);
    if (m) {
      current = m[1];
      blocks.set(current, []);
      continue;
    }
    if (current) blocks.get(current).push(line);
  }
  return blocks;
}

/** Lines of a top-level block such as `networks:` -> `ops:`. */
function topBlock(name) {
  const lines = readFileSync(COMPOSE_PATH, "utf8")
    .split("\n")
    .map((line) => line.replace(/\r$/, ""));
  const start = lines.findIndex((line) => line === `  ${name}:`);
  if (start < 0) throw new Error(`no \`${name}:\` key in compose.yaml`);
  const out = [];
  for (let i = start + 1; i < lines.length; i += 1) {
    if (/^\S/.test(lines[i]) || /^ {2}\S/.test(lines[i])) break;
    out.push(lines[i]);
  }
  return out;
}

const BLOCKS = serviceBlocks();
const blockOf = (name) => {
  const block = BLOCKS.get(name);
  assert.ok(block, `compose.yaml must define the ${name} service`);
  return block;
};

/** KEY -> value from the `environment:` section of a service block. */
function envOf(block) {
  const env = {};
  for (const line of block) {
    const m = /^ {6}([A-Z0-9_]+):\s*"?([^"]*?)"?\s*$/.exec(line);
    if (m) env[m[1]] = m[2];
  }
  return env;
}

const mountsSocket = (block) => block.some((line) => /\/var\/run\/docker\.sock/.test(line));
const hasHostPorts = (block) => block.some((line) => /^ {4}ports:/.test(line));

test("exactly one service mounts the docker socket, and it is docker-socket-filter", () => {
  const holders = [...BLOCKS.entries()].filter(([, block]) => mountsSocket(block)).map(([name]) => name);
  assert.deepEqual(holders, ["docker-socket-filter"], `socket holders: ${holders.join(", ") || "(none)"}`);
});

test("docker-socket-filter is the only socket holder and has no host port", () => {
  const block = blockOf("docker-socket-filter");
  assert.ok(mountsSocket(block), "the filter must mount the socket");
  assert.equal(hasHostPorts(block), false, "the filter must not publish a host port");
  assert.ok(
    block.some((line) => /^ {4}networks: \[ops-docker\]\s*$/.test(line)),
    "the filter must live only in the internal `ops-docker` network",
  );
});

test("only ops-helper shares a network with the filter (hermes-mcp cannot reach it)", () => {
  const members = [...BLOCKS.entries()]
    .filter(([, block]) => block.some((line) => /^ {4}networks: \[.*\bops-docker\b.*\]\s*$/.test(line)))
    .map(([name]) => name)
    .sort();
  assert.deepEqual(members, ["docker-socket-filter", "ops-helper"]);
  const net = topBlock("ops-docker");
  assert.ok(net.some((line) => /internal: true/.test(line)), "ops-docker must be internal");
  const hermes = blockOf("hermes-mcp");
  assert.ok(!hermes.some((line) => /ops-docker/.test(line)), "hermes-mcp must not join ops-docker");
});

test("filter and helper share OPS_FILTER_TOKEN", () => {
  assert.equal(envOf(blockOf("docker-socket-filter")).OPS_FILTER_TOKEN, "${OPS_FILTER_TOKEN:-}");
  assert.equal(envOf(blockOf("ops-helper")).OPS_FILTER_TOKEN, "${OPS_FILTER_TOKEN:-}");
});

test("the ops network is internal", () => {
  const block = topBlock("ops");
  assert.ok(block.some((line) => /internal: true/.test(line)), "the ops network must be internal (no route off the host)");
  assert.ok(block.some((line) => /name: mc-stack-ops/.test(line)), "the ops network must keep its fixed name");
});

test("docker-socket-filter is hardened and self-contained", () => {
  const block = blockOf("docker-socket-filter");
  const text = block.join("\n");
  assert.match(text, /^ {4}read_only: true$/m, "read_only: true");
  assert.match(text, /^ {6}- ALL$/m, "cap_drop: ALL");
  assert.match(text, /no-new-privileges:true/, "no-new-privileges");
  assert.match(text, /^ {4}build: \.\/tools\/docker-socket-filter$/m, "built from this repo, not a third-party image");
  assert.ok(!/^\s*image:/m.test(text), "the filter must be built locally, not pulled");
  assert.match(text, /OPS_FILTER_UPSTREAM: "\/var\/run\/docker\.sock"/);
});

function fallbackOf(raw) {
  return raw.includes(":-") ? raw.slice(raw.indexOf(":-") + 2).replace(/\}$/, "") : raw;
}

test("the filter keeps the T1 and the T2 restart names in separate lists", () => {
  const env = envOf(blockOf("docker-socket-filter"));
  const t1 = fallbackOf(env.OPS_FILTER_RESTART_ALLOW ?? "");
  const t2 = fallbackOf(env.OPS_FILTER_RESTART_ALLOW_T2 ?? "");
  assert.ok(t1, "OPS_FILTER_RESTART_ALLOW must be set");
  assert.ok(t2, "OPS_FILTER_RESTART_ALLOW_T2 must be set");
  assert.deepEqual(t1.split(",").map((v) => v.trim()), ["mc-stack-bot-1", "mc-stack-cloudflared-mcp-1"]);
  assert.deepEqual(t2.split(",").map((v) => v.trim()), ["mc-stack-lobby-1", "mc-stack-survival-1", "mc-stack-velocity-1"]);
  const overlap = t1.split(",").filter((name) => t2.split(",").includes(name));
  assert.deepEqual(overlap, [], "the two restart lists must stay disjoint (T1 vs T2)");
});

test("no forbidden container is restartable through the filter", () => {
  const env = envOf(blockOf("docker-socket-filter"));
  const names = [env.OPS_FILTER_RESTART_ALLOW, env.OPS_FILTER_RESTART_ALLOW_T2]
    .map((raw) => fallbackOf(raw ?? ""))
    .join(",")
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean);
  for (const forbidden of ["craftcore-db", "hermes-mcp", "hermes-test", "ops-helper", "docker-socket-filter"]) {
    assert.ok(
      !names.some((name) => name.includes(forbidden)),
      `${forbidden} must never be restartable through the filter`,
    );
  }
});

test("plugin-builder has no socket, no network and hard resource caps", () => {
  const block = blockOf("plugin-builder");
  const text = block.join("\n");
  assert.equal(mountsSocket(block), false, "plugin-builder must not mount the docker socket");
  assert.equal(hasHostPorts(block), false, "plugin-builder must not publish a host port");
  assert.match(text, /^ {4}network_mode: none$/m, "plugin-builder must have no interface at all");
  assert.match(text, /^ {4}read_only: true$/m, "read_only: true");
  assert.match(text, /^ {6}- ALL$/m, "cap_drop: ALL");
  assert.match(text, /no-new-privileges:true/, "no-new-privileges");
  assert.match(text, /^ {4}cpus: "1\.5"$/m, "CPU cap");
  assert.match(text, /^ {4}mem_limit: 2g$/m, "memory cap");
  assert.match(text, /^ {4}pids_limit: \d+$/m, "PID cap");
  assert.match(text, /^ {4}build: \.\/tools\/plugin-builder$/m, "built from this repo");
  assert.match(text, /^ {6}- ops-jobs:\/jobs$/m, "the job volume is the only channel from ops-helper");
  // The repo is mounted NARROWLY and read-only: the two source trees the
  // allowlist can build/test, the allowlist file and `.git` - never the root.
  assert.match(text, /^ {6}- \.\/plugins-src:\/repo\/plugins-src:ro$/m, "the craftcore source tree, read-only");
  assert.match(text, /^ {6}- \.\/tools:\/repo\/tools:ro$/m, "the JS test sources, read-only");
  assert.match(text, /^ {6}- \.\/config\/ops-allowlist\.json:\/repo\/config\/ops-allowlist\.json:ro$/m, "the allowlist file, read-only");
  assert.match(text, /^ {6}- \.\/\.git:\/repo\/\.git:ro$/m, "`.git` for the commit assertion, read-only");
  assert.equal(/^ {6}- \.\/:\/repo:ro$/m.test(text), false, "the repo ROOT must not be mounted");
});

test("plugin-builder cannot reach .env, secrets/ or data/ at all", () => {
  const block = blockOf("plugin-builder");
  const mounts = block.filter((line) => /^ {6}- /.test(line));
  assert.ok(mounts.length > 0, "expected the plugin-builder volume list");
  for (const forbidden of [".env", "secrets", "data", "backups"]) {
    const re = new RegExp(`(^|[/:])${forbidden.replace(".", "\\.")}([/:]|$)`);
    assert.ok(
      !mounts.some((line) => re.test(line)),
      `${forbidden} must not be mounted into plugin-builder (mounts: ${mounts.join(" | ")})`,
    );
  }
  assert.ok(
    !mounts.some((line) => /- \.\/[:]/.test(line) || /- \.\/$/.test(line)),
    "the repo root must never be mounted into plugin-builder",
  );
  // Every mount that does reach into the repo is read-only.
  for (const line of mounts.filter((l) => l.includes("/repo"))) {
    assert.match(line, /:ro$/, `repo mount must be read-only: ${line}`);
  }
});

test("ops-helper keeps the whole repo read-only (narrowing it would break git state and df)", () => {
  const block = blockOf("ops-helper");
  const mounts = block.filter((line) => /^ {6}- /.test(line));
  assert.ok(mounts.includes("      - ./:/repo:ro"), "ops-helper reads the whole repo, read-only");
  assert.ok(
    !mounts.some((line) => /^ {6}- \.\/(\.env|secrets)/.test(line)),
    "ops-helper must not mount .env or secrets/ separately",
  );
});

test("only ops-helper and plugin-builder share the job volume (no other new socket holder)", () => {
  const holders = [...BLOCKS.entries()].filter(([, block]) => block.some((line) => /^- ops-jobs:\/jobs\s*$/.test(line.trim()))).map(([name]) => name);
  assert.deepEqual(holders.sort(), ["ops-helper", "plugin-builder"].sort());
});

test("ops-helper keeps no socket and talks to the filter over TCP", () => {
  const block = blockOf("ops-helper");
  assert.equal(mountsSocket(block), false, "ops-helper must not mount the docker socket");
  assert.equal(hasHostPorts(block), false, "ops-helper must not publish a host port");
  const env = envOf(block);
  assert.equal(env.DOCKER_HOST, "tcp://docker-socket-filter:2375");
  // Pinned API version: without it the CLI would probe GET /version, which the
  // filter refuses.
  assert.match(env.DOCKER_API_VERSION ?? "", /^1\.\d+$/);
  assert.ok(
    block.some((line) => /^ {6}- docker-socket-filter$/.test(line)),
    "ops-helper must depend on the filter",
  );
  assert.ok(!/tecnativa/.test(block.join("\n")), "no reference to the replaced proxy image");
});

test("the replaced proxy service is gone from compose.yaml", () => {
  const text = readFileSync(COMPOSE_PATH, "utf8");
  // A comment may still name it (that is how the replacement is explained), but
  // no service may pull it and the old service name must not exist any more.
  assert.ok(!/^\s*image:.*tecnativa/m.test(text), "compose.yaml must not pull the replaced proxy image");
  assert.equal(BLOCKS.has("docker-socket-proxy"), false, "the old docker-socket-proxy service must be gone");
  assert.ok(BLOCKS.has("docker-socket-filter"), "the docker-socket-filter service must exist");
});

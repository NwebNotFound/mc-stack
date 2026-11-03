import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

/**
 * The docker-socket-proxy is the privilege boundary between ops-helper and the
 * Docker socket, so its least-privilege configuration is asserted, not trusted.
 * This reads the `docker-socket-proxy` service out of the repo's compose.yaml
 * (no YAML dependency) and checks every section flag.
 */

const COMPOSE_PATH = new URL("../../../compose.yaml", import.meta.url).pathname;

function proxyBlock() {
  const lines = readFileSync(COMPOSE_PATH, "utf8").split("\n");
  const start = lines.findIndex((line) => line === "  docker-socket-proxy:");
  if (start < 0) throw new Error("docker-socket-proxy service not found in compose.yaml");

  const block = [];
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i];
    // A 2-space-indented non-space (sibling service) or a 0-indent key ends the block.
    if (/^\S/.test(line) || /^ {2}\S/.test(line)) break;
    block.push(line);
  }
  return block;
}

/** KEY -> value from the `environment:` section of that block. */
function proxyEnv() {
  const env = {};
  for (const line of proxyBlock()) {
    const m = /^ {6}([A-Z0-9_]+):\s*"([^"]*)"\s*$/.exec(line.replace(/\r$/, ""));
    if (m) env[m[1]] = m[2];
  }
  return env;
}

test("docker-socket-proxy is pinned by digest", () => {
  const image = proxyBlock()
    .map((line) => /^ {4}image:\s*(\S+)/.exec(line.replace(/\r$/, "")))
    .find(Boolean);
  assert.ok(image, "the proxy service must declare an image");
  assert.match(image[1], /@sha256:[0-9a-f]{64}$/, "the privilege-boundary image must be pinned by digest");
});

test("docker-socket-proxy grants exactly CONTAINERS and denies every method that mutates", () => {
  const env = proxyEnv();

  // The only granted API section, and the restart flag that records the intent.
  assert.equal(env.CONTAINERS, "1", "CONTAINERS=1 is the whole read surface");
  assert.equal(env.ALLOW_RESTARTS, "1", "ALLOW_RESTARTS is pinned to 1 (see README: inert under POST=0)");

  // No general POST -> no container create; no exec; every other section off.
  assert.equal(env.POST, "0", "POST must stay 0: no container create and no restart");
  assert.equal(env.EXEC, "0", "EXEC must stay 0: no docker exec");

  const mustBeZero = [
    "ALLOW_START",
    "ALLOW_STOP",
    "ALLOW_PAUSE",
    "ALLOW_UNPAUSE",
    "AUTH",
    "BUILD",
    "COMMIT",
    "CONFIGS",
    "DISTRIBUTION",
    "EVENTS",
    "GRPC",
    "IMAGES",
    "INFO",
    "NETWORKS",
    "NODES",
    "PING",
    "PLUGINS",
    "SECRETS",
    "SERVICES",
    "SESSION",
    "SWARM",
    "SYSTEM",
    "TASKS",
    "VERSION",
    "VOLUMES",
  ];
  for (const key of mustBeZero) {
    assert.equal(env[key], "0", `${key} must be pinned to 0`);
  }

  // The only values that may be "1" are the read section, the restart flag and
  // the IPv6 toggle; anything else being 1 would be a silent privilege gain.
  for (const [key, value] of Object.entries(env)) {
    if (value === "1") {
      assert.ok(
        ["CONTAINERS", "ALLOW_RESTARTS", "DISABLE_IPV6"].includes(key),
        `${key}=1 is not expected: only CONTAINERS/ALLOW_RESTARTS/DISABLE_IPV6 may be 1`,
      );
    }
  }
});

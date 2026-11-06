import assert from "node:assert/strict";
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { dockerConfigDocument, writeDockerAuthConfig } from "../dist/docker-auth.js";
import { tmpDir } from "./helpers.mjs";

test("the docker CLI config carries the filter token as a bearer header", () => {
  const token = "a".repeat(40);
  const dir = join(tmpDir(), "docker-config");
  assert.equal(writeDockerAuthConfig(token, dir), dir);
  const file = join(dir, "config.json");
  assert.deepEqual(JSON.parse(readFileSync(file, "utf8")), { HttpHeaders: { Authorization: `Bearer ${token}` } });
  assert.equal(statSync(file).mode & 0o777, 0o600, "the config file is private");
  assert.equal(dockerConfigDocument(token), readFileSync(file, "utf8"));
});

test("a short or non-printable filter token is refused", () => {
  assert.throws(() => writeDockerAuthConfig("short", join(tmpDir(), "x")), /at least 24/);
  assert.throws(() => writeDockerAuthConfig(`${"a".repeat(30)} b`, join(tmpDir(), "y")), /printable/);
});

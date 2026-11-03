import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { AuditLog } from "../dist/audit.js";
import { tmpDir } from "./helpers.mjs";

function readLines(file) {
  return readFileSync(file, "utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

test("the audit log appends one JSONL line per call and keeps the fields", () => {
  const file = `${tmpDir()}/audit.jsonl`;
  const log = new AuditLog(file);
  const id = log.append({
    action: "ops_status",
    tier: "T0",
    impl: "status",
    decision: "allow",
    params: { service: "lobby" },
    durationMs: 12,
    ok: true,
    summary: { containers_count: 3 },
  });
  log.append({ action: "ops_restart_game", tier: "T2", decision: "deny", code: "not_armed", params: {} });

  const lines = readLines(file);
  assert.equal(lines.length, 2);
  assert.equal(lines[0].id, id);
  assert.equal(lines[0].action, "ops_status");
  assert.equal(lines[0].tier, "T0");
  assert.equal(lines[0].decision, "allow");
  assert.equal(lines[0].ok, true);
  assert.equal(lines[0].params.service, "lobby");
  assert.equal(lines[1].decision, "deny");
  assert.equal(lines[1].code, "not_armed");
  assert.ok(Date.parse(lines[0].ts) > 0, "timestamp must be an ISO date");
});

test("parameters are redacted before they reach the audit log", () => {
  const file = `${tmpDir()}/audit.jsonl`;
  const log = new AuditLog(file);
  log.append({
    action: "ops_logs",
    decision: "deny",
    params: {
      service: "lobby",
      HERMES_MCP_TOKEN: "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
      note: "Authorization: Bearer secrettokenvalue1234",
    },
  });
  const raw = readFileSync(file, "utf8");
  assert.ok(!raw.includes("deadbeef"), "the token value must not be in the audit log");
  assert.ok(!raw.includes("secrettokenvalue1234"), "the bearer value must not be in the audit log");
  const entry = readLines(file)[0];
  assert.equal(entry.params.HERMES_MCP_TOKEN, "[redacted]");
  assert.match(entry.params.note, /Bearer \[redacted\]/);
});

test("the audit log is append-only (a second call never truncates the file)", () => {
  const file = `${tmpDir()}/audit.jsonl`;
  const log = new AuditLog(file);
  log.append({ action: "ops_status", decision: "allow" });
  const first = readFileSync(file, "utf8");
  log.append({ action: "ops_status", decision: "allow" });
  const second = readFileSync(file, "utf8");
  assert.ok(second.startsWith(first), "the earlier content must survive");
  assert.equal(readLines(file).length, 2);
});

test("an oversized entry is truncated instead of written whole", () => {
  const file = `${tmpDir()}/audit.jsonl`;
  const log = new AuditLog(file);
  log.append({ action: "ops_logs", decision: "allow", summary: { lines: "x".repeat(20000) } });
  const entry = readLines(file)[0];
  assert.equal(entry.summary, "[truncated]");
  assert.ok(readFileSync(file, "utf8").length < 9000);
});

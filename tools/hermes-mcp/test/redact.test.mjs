import assert from "node:assert/strict";
import { test } from "node:test";
import { redactStrings, redactText, redactValue } from "../dist/redact.js";

test("bearer headers are redacted", () => {
  assert.equal(redactText("Authorization: Bearer abcdefghijklmnop"), "Authorization: Bearer [redacted]");
});

test("GitHub, OpenAI and OpenRouter key shapes are redacted", () => {
  assert.equal(redactText("token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"), "token [redacted]");
  assert.equal(redactText("token github_pat_11ABCDEFG0abcdefghijklmnop"), "token [redacted]");
  assert.equal(redactText("key sk-or-v1-abcdef0123456789abcdef"), "key [redacted]");
  assert.equal(redactText("key sk-proj-abcdef0123456789"), "key [redacted]");
});

test(".env-style KEY=value lines are redacted", () => {
  assert.match(redactText("HERMES_MCP_TOKEN=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"), /^HERMES_MCP_TOKEN=\[redacted\]$/);
  assert.equal(redactText("API_SERVER_KEY=abc12345"), "API_SERVER_KEY=[redacted]");
  assert.equal(redactText('GH_TOKEN: "abcdef123456"'), 'GH_TOKEN: "[redacted]"');
});

test("long hex blobs are redacted", () => {
  assert.equal(redactText("id " + "a".repeat(64)), "id [redacted]");
});

test("ordinary prose is left intact", () => {
  const s = "The build finished with status completed and 3 tool calls.";
  assert.equal(redactText(s), s);
});

test("redactValue drops secret-named object keys", () => {
  const out = redactValue({ GH_TOKEN: "whatever", note: "hello" });
  assert.equal(out.GH_TOKEN, "[redacted]");
  assert.equal(out.note, "hello");
});

test("redactStrings keeps token *counts* but scrubs secret-looking strings", () => {
  const out = redactStrings({
    usage: { input_tokens: 42, output_tokens: 7 },
    echoed: "Bearer secrettokenvalue1234",
    line: "HERMES_MCP_TOKEN=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
  });
  assert.deepEqual(out.usage, { input_tokens: 42, output_tokens: 7 });
  assert.equal(out.echoed, "Bearer [redacted]");
  assert.equal(out.line, "HERMES_MCP_TOKEN=[redacted]");
});

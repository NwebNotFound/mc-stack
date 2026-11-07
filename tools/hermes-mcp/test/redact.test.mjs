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

test("secret values run to the end of the line, quotes and backticks included (issue #146)", () => {
  // A value that contains a quote used to stop the match there and leak the rest.
  assert.equal(redactText('MARIADB_ROOT_PASSWORD=ab"cdefgh`ij kl'), "MARIADB_ROOT_PASSWORD=[redacted]");
  assert.equal(redactText('PASSWORD: "x"yzsecret'), 'PASSWORD: "[redacted]');
  assert.equal(redactText("DB_PASSWORD='p@ss\"w0rd'"), "DB_PASSWORD='[redacted]'");
  // only the line with the secret is touched
  assert.equal(redactText("API_KEY=abc\"defgh tail\nnext line stays"), "API_KEY=[redacted]\nnext line stays");
  assert.equal(redactText("API_KEY=abc\"defgh tail\r\nnext"), "API_KEY=[redacted]\r\nnext");
  // short values (token counts) are left alone
  assert.equal(redactText("input_tokens: 42"), "input_tokens: 42");
});

test("the MariaDB generated root password line is redacted whatever it contains", () => {
  const line = '2026-11-05 23:00:01+00:00 [Note] [Entrypoint]: GENERATED ROOT PASSWORD: "q`W\'e)rty12345';
  const out = redactText(line);
  assert.equal(out, "2026-11-05 23:00:01+00:00 [Note] [Entrypoint]: GENERATED ROOT PASSWORD: [redacted]");
  assert.equal(redactText("GENERATED ROOT PASSWORD: abc"), "GENERATED ROOT PASSWORD: [redacted]");
  const multi = redactText(`a\n${line}\nb`);
  assert.ok(!multi.includes("rty12345"));
  assert.ok(multi.startsWith("a\n") && multi.endsWith("\nb"));
});

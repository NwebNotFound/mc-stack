import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";
import { redactStrings, redactText, redactValue } from "../dist/redact.js";

/**
 * ops-helper ships a copy of hermes-mcp's redaction (same module, same rules).
 * This test keeps the two files byte-identical so a security fix cannot land in
 * only one of them. It is skipped when the sibling file is not present, which is
 * the case inside the ops-helper build container.
 */
const CANONICAL = new URL("../../hermes-mcp/src/redact.ts", import.meta.url).pathname;
const CANONICAL_LOGGER = new URL("../../hermes-mcp/src/logger.ts", import.meta.url).pathname;

for (const [copy, canonical] of [
  [new URL("../src/redact.ts", import.meta.url).pathname, CANONICAL],
  [new URL("../src/logger.ts", import.meta.url).pathname, CANONICAL_LOGGER],
]) {
  test(`the shared copy is in sync with ${canonical.split("/").pop()}`, { skip: !existsSync(canonical) }, () => {
    assert.equal(
      readFileSync(copy, "utf8"),
      readFileSync(canonical, "utf8"),
      "the copy in tools/ops-helper/src must stay byte-identical to tools/hermes-mcp/src",
    );
  });
}

test("the shared redaction still removes the shapes that matter here", () => {
  assert.equal(redactText("Authorization: Bearer secrettokenvalue1234"), "Authorization: Bearer [redacted]");
  assert.equal(redactText("OPS_HELPER_TOKEN=deadbeefdeadbeefdeadbeefdeadbeef"), "OPS_HELPER_TOKEN=[redacted]");
  assert.equal(redactValue({ OPS_HELPER_TOKEN: "x" }).OPS_HELPER_TOKEN, "[redacted]");
  assert.deepEqual(redactStrings({ input_tokens: 12, line: "GH_TOKEN: abcdef123456" }), {
    input_tokens: 12,
    line: "GH_TOKEN: [redacted]",
  });
});

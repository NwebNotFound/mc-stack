import assert from "node:assert/strict";
import { test } from "node:test";
import { JOB_FILE_RE, validateJob } from "../src/validate.mjs";

/**
 * The job file is the ONLY thing ops-helper and plugin-builder share, so its
 * validation is a security boundary: a compromised helper must not be able to
 * smuggle a command, a path or a target into the builder through it.
 */

const ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
const SHA = "a".repeat(40);

function job(over = {}) {
  return JSON.stringify({ v: 1, id: ID, action: "build", ref: SHA, requested_at: "2026-11-03T00:00:00.000Z", ...over });
}

test("a well-formed build job passes", () => {
  const res = validateJob(job(), ID);
  assert.ok(res.job, `expected a job, got ${res.code}`);
  assert.equal(res.job.action, "build");
  assert.equal(res.job.ref, SHA);
});

test("a well-formed test job passes and needs a target", () => {
  const okJob = job({ action: "test", target: "ops-helper" });
  assert.ok(validateJob(okJob, ID).job);
  const noTarget = JSON.stringify({ v: 1, id: ID, action: "test", ref: SHA, requested_at: "x" });
  assert.equal(validateJob(noTarget, ID).code, "job_bad_keys");
});

test("a build job with a target is refused", () => {
  assert.equal(validateJob(job({ target: "hermes-mcp" }), ID).code, "job_bad_keys");
});

test("unknown keys are refused (no smuggled command/argv)", () => {
  for (const extra of [{ command: "rm -rf /" }, { argv: ["sh", "-c", "id"] }, { cwd: "/" }, { env: {} }]) {
    assert.equal(validateJob(job(extra), ID).code, "job_bad_keys", JSON.stringify(extra));
  }
});

test("the action is an enum", () => {
  assert.equal(validateJob(job({ action: "deploy" }), ID).code, "job_bad_action");
  assert.equal(validateJob(job({ action: "build; rm -rf /" }), ID).code, "job_bad_action");
});

test("the ref must be a full lowercase 40-hex commit sha", () => {
  for (const ref of ["", "abc", "main", "HEAD~1", "../../etc/passwd", SHA.toUpperCase(), `${SHA} `, "z".repeat(40)]) {
    assert.equal(validateJob(job({ ref }), ID).code, "job_bad_ref", JSON.stringify(ref));
  }
});

test("the id in the body must match the id in the file name", () => {
  assert.equal(validateJob(job(), "11111111-1111-4111-8111-111111111111").code, "job_id_mismatch");
  assert.equal(validateJob(job({ id: "../../../etc/passwd" }), ID).code, "job_id_mismatch");
});

test("the version is pinned", () => {
  assert.equal(validateJob(job({ v: 2 }), ID).code, "job_bad_version");
});

test("a test target is a plain service-shaped name", () => {
  for (const target of ["../etc/passwd", "hermes/mcp", "UPPER", "a b", "-x", ""]) {
    const res = validateJob(job({ action: "test", target }), ID);
    assert.ok(res.code, `target ${JSON.stringify(target)} must be refused, got a job`);
  }
});

test("non-JSON and oversized bodies are refused", () => {
  assert.equal(validateJob("not json", ID).code, "job_not_json");
  assert.equal(validateJob("[1,2,3]", ID).code, "job_not_object");
  assert.equal(validateJob("null", ID).code, "job_not_object");
  assert.equal(validateJob(" ".repeat(5000), ID).code, "job_too_large");
});

test("the job file name pattern only accepts a job-<uuid>.json leaf, never a path", () => {
  assert.ok(JOB_FILE_RE.test("job-0f8fad5b-d9cb-469f-a165-70867728950e.json"));
  for (const name of [
    "job-../../etc/passwd.json",
    "job-0f8fad5b-d9cb-469f-a165-70867728950e.json.bak",
    "../job-0f8fad5b-d9cb-469f-a165-70867728950e.json",
    "result-0f8fad5b-d9cb-469f-a165-70867728950e.json",
    "job-0f8fad5b-d9cb-469f-a165-70867728950e.txt",
    "job-notauuid.json",
  ]) {
    assert.equal(JOB_FILE_RE.test(name), false, `${name} must not be accepted`);
  }
});

/**
 * Strict validation for the job files ops-helper drops into the shared volume.
 *
 * Kept in its own module so it is unit-testable without starting the poller (and
 * so a test can hammer it with injection/traversal attempts). Nothing in a job is
 * trusted: the exact key set, the action enum, the sha shape and the target shape
 * are all re-checked here, independent of whatever the helper already did.
 */

export const JOB_VERSION = 1;
/** The only file name shape this service will look at. */
export const JOB_FILE_RE = /^job-([0-9a-f-]{36})\.json$/;
/** A full lowercase commit SHA. */
export const REF_RE = /^[a-f0-9]{40}$/;
/** A test target name (a key of build.tests in the allowlist). */
export const TARGET_RE = /^[a-z0-9][a-z0-9-]{0,31}$/;
export const DEFAULT_MAX_JOB_BYTES = 4096;

export const JOB_KEYS_BUILD = ["v", "id", "action", "ref", "requested_at"];
export const JOB_KEYS_TEST = ["v", "id", "action", "ref", "target", "requested_at"];

/**
 * @param {string} raw file contents
 * @param {string} fileId the uuid taken from the file NAME (must match `id`)
 * @param {number} maxBytes
 * @returns {{job?: object, code?: string, keys?: string[]}}
 */
export function validateJob(raw, fileId, maxBytes = DEFAULT_MAX_JOB_BYTES) {
  if (typeof raw !== "string") return { code: "job_not_string" };
  if (Buffer.byteLength(raw, "utf8") > maxBytes) return { code: "job_too_large" };
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { code: "job_not_json" };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return { code: "job_not_object" };
  if (parsed.action !== "build" && parsed.action !== "test") return { code: "job_bad_action" };
  const keys = Object.keys(parsed).sort();
  const expected = (parsed.action === "test" ? JOB_KEYS_TEST : JOB_KEYS_BUILD).slice().sort();
  if (JSON.stringify(keys) !== JSON.stringify(expected)) return { code: "job_bad_keys", keys };
  if (parsed.v !== JOB_VERSION) return { code: "job_bad_version" };
  if (typeof parsed.id !== "string" || parsed.id !== fileId) return { code: "job_id_mismatch" };
  if (typeof parsed.ref !== "string" || !REF_RE.test(parsed.ref)) return { code: "job_bad_ref" };
  if (parsed.action === "test") {
    if (typeof parsed.target !== "string" || !TARGET_RE.test(parsed.target)) return { code: "job_bad_target" };
  } else if ("target" in parsed) {
    return { code: "job_unexpected_target" };
  }
  return { job: parsed };
}

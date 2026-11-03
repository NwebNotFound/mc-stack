/**
 * End-to-end smoke test against a running hermes-mcp instance.
 *
 *   HERMES_MCP_TOKEN=... node test/smoke.mjs http://127.0.0.1:8765/mcp [profile]
 *
 * Checks: initialize/list tools, health, list_profiles, list_models, start_run
 * with a tiny prompt, polling get_run until terminal, get_run_logs and the
 * cancel_run confirmation gate. Exits non-zero on the first failure.
 * Never prints the token.
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** Fall back to the repo .env so a local smoke run needs no exported secret. */
function tokenFromRepoEnv() {
  try {
    const here = path.dirname(fileURLToPath(import.meta.url));
    const envPath = path.resolve(here, "..", "..", "..", ".env");
    for (const raw of readFileSync(envPath, "utf8").split(/\r?\n/)) {
      const line = raw.trim();
      if (!line.startsWith("HERMES_MCP_TOKEN=")) continue;
      let value = line.slice("HERMES_MCP_TOKEN=".length).trim();
      if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith('"') && value.endsWith('"'))) {
        value = value.slice(1, -1);
      }
      return value;
    }
  } catch {
    /* ignore */
  }
  return "";
}

const url = new URL(process.argv[2] ?? "http://127.0.0.1:8765/mcp");
const profile = process.argv[3] ?? "homelab";
const token = process.env.HERMES_MCP_TOKEN || tokenFromRepoEnv();
if (!token) {
  console.error("HERMES_MCP_TOKEN is required");
  process.exit(2);
}

const transport = new StreamableHTTPClientTransport(url, {
  requestInit: { headers: { Authorization: `Bearer ${token}` } },
});
const client = new Client({ name: "hermes-mcp-smoke", version: "1.0.0" });

function parse(result) {
  const text = result?.content?.find((c) => c.type === "text")?.text ?? "{}";
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? `  ${detail}` : ""}`);
  if (!ok) failures += 1;
}

await client.connect(transport);

const tools = await client.listTools();
const names = tools.tools.map((t) => t.name).sort();
const expected = [
  "cancel_run",
  "get_run",
  "get_run_logs",
  "health",
  "list_models",
  "list_profiles",
  "list_runs",
  "start_run",
];
check("tools registered", expected.every((n) => names.includes(n)), names.join(","));

const health = parse(await client.callTool({ name: "health", arguments: {} }));
check("health", health.status === "ok", `gateway=${health.gateway?.reachable} profiles=${health.profiles?.length}`);

const profiles = parse(await client.callTool({ name: "list_profiles", arguments: {} }));
const authed = (profiles.profiles ?? []).filter((p) => p.authenticated);
check("list_profiles", authed.length > 0, authed.map((p) => `${p.profile}:${p.default_model}`).join(" "));

const models = parse(await client.callTool({ name: "list_models", arguments: { profile, limit: 3 } }));
check("list_models", Boolean(models.current?.model), JSON.stringify(models.current));

const started = parse(
  await client.callTool({
    name: "start_run",
    arguments: { profile, prompt: "Reply with exactly one word: PONG. Do not use any tools." },
  }),
);
check("start_run", typeof started.run_id === "string", started.run_id ?? JSON.stringify(started));

const guard = await client.callTool({ name: "cancel_run", arguments: { run_id: started.run_id, confirm: false } });
check("cancel_run needs confirm", guard.isError === true, guard.content?.[0]?.text?.slice(0, 60));

let run = {};
for (let i = 0; i < 60; i += 1) {
  run = parse(await client.callTool({ name: "get_run", arguments: { run_id: started.run_id } }));
  if (run.progress?.terminal) break;
  await new Promise((r) => setTimeout(r, 2000));
}
check("get_run terminal", run.progress?.terminal === true, `status=${run.status} last_event=${run.last_event}`);
console.log("      output:", JSON.stringify((run.last_output ?? "").slice(0, 160)));

const logs = parse(await client.callTool({ name: "get_run_logs", arguments: { run_id: started.run_id, tail: 5 } }));
check("get_run_logs", Array.isArray(logs.lines) && logs.lines.length > 0, `${logs.lines?.length} lines`);

const runs = parse(await client.callTool({ name: "list_runs", arguments: { profile, limit: 5 } }));
check("list_runs", (runs.runs ?? []).some((r) => r.run_id === started.run_id), `${runs.count} rows`);

await client.close();
console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);

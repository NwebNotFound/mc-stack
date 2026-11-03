/** Cancel a live run through the MCP tools (verifies the confirm gate and gateway stop). */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const envPath = path.resolve(here, "..", "..", "..", ".env");
let token = process.env.HERMES_MCP_TOKEN ?? "";
if (!token) {
  for (const raw of readFileSync(envPath, "utf8").split(/\r?\n/)) {
    if (raw.trim().startsWith("HERMES_MCP_TOKEN=")) {
      token = raw.trim().slice("HERMES_MCP_TOKEN=".length).replace(/^['"]|['"]$/g, "");
      break;
    }
  }
}

const transport = new StreamableHTTPClientTransport(new URL("http://127.0.0.1:8766/mcp"), {
  requestInit: { headers: { Authorization: `Bearer ${token}` } },
});
const client = new Client({ name: "cancel-smoke", version: "1.0.0" });
await client.connect(transport);
const parse = (r) => JSON.parse(r?.content?.[0]?.text ?? "{}");

const started = parse(
  await client.callTool({
    name: "start_run",
    arguments: {
      profile: "minecraft",
      prompt: "Run the terminal command `sleep 120` and wait for it to finish, then reply DONE. Do not do anything else.",
    },
  }),
);
console.log("started:", started.run_id);

await new Promise((r) => setTimeout(r, 8000));
let run = parse(await client.callTool({ name: "get_run", arguments: { run_id: started.run_id } }));
console.log("before cancel:", run.status, run.last_event, "tools:", run.progress?.tool_calls);

const cancelled = parse(await client.callTool({ name: "cancel_run", arguments: { run_id: started.run_id, confirm: true } }));
console.log("cancel response:", JSON.stringify(cancelled));

for (let i = 0; i < 15; i += 1) {
  run = parse(await client.callTool({ name: "get_run", arguments: { run_id: started.run_id } }));
  console.log("after cancel:", run.status, run.last_event);
  if (run.progress?.terminal) break;
  await new Promise((r) => setTimeout(r, 2000));
}
console.log("final:", run.status, "terminal:", run.progress?.terminal);
await client.close();

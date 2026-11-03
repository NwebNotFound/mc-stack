/**
 * Local development launcher (host, no Docker).
 *
 * Loads HERMES_* values from the mc-stack .env next to this folder, points the
 * gateway at 127.0.0.1 instead of host.docker.internal and stores run state in
 * tools/hermes-mcp/.data (git-ignored). Never prints secret values.
 *
 *   node test/dev-local.mjs
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "..", "..", "..");
const envPath = path.join(repoRoot, ".env");

const wanted = new Set(["HERMES_MCP_TOKEN", "HERMES_PROFILE_KEYS", "HERMES_GATEWAY_URL", "HERMES_MCP_PUBLIC_URL"]);
const loaded = [];
for (const rawLine of readFileSync(envPath, "utf8").split(/\r?\n/)) {
  const line = rawLine.trim();
  if (!line || line.startsWith("#")) continue;
  const eq = line.indexOf("=");
  if (eq <= 0) continue;
  const key = line.slice(0, eq).trim();
  if (!wanted.has(key)) continue;
  let value = line.slice(eq + 1).trim();
  if (
    (value.startsWith("'") && value.endsWith("'")) ||
    (value.startsWith('"') && value.endsWith('"'))
  ) {
    value = value.slice(1, -1);
  }
  process.env[key] = value;
  loaded.push(key);
}

process.env.HERMES_GATEWAY_URL = process.env.HERMES_MCP_LOCAL_GATEWAY ?? "http://127.0.0.1:8642";
process.env.HERMES_MCP_DATA_DIR = path.join(here, "..", ".data");
process.env.HERMES_MCP_ALLOWED_HOSTS = "localhost,127.0.0.1,hermes-mcp";

console.log(`[dev-local] loaded ${loaded.join(", ")}; gateway=${process.env.HERMES_GATEWAY_URL}`);
await import("../dist/index.js");

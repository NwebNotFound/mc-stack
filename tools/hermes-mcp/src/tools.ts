/**
 * MCP tool definitions.
 *
 * Tools are thin, explicit and side-effect-honest:
 *  - every tool call is synchronous-looking but never blocks on a long agent run;
 *  - cancel_run requires confirm=true (destructive-ish action gate);
 *  - errors come back as isError content with a machine-readable code.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { Config } from "./config.js";
import { GatewayClient, GatewayError, isTerminal } from "./hermes.js";
import { OpsClient } from "./ops.js";
import { registerOpsTools } from "./ops-tools.js";
import { fail, json } from "./responses.js";
import type { RunManager } from "./runner.js";
import type { RunRecord, RunStore } from "./store.js";

function summarize(rec: RunRecord, opts: { includeOutput?: boolean } = {}) {
  const includeOutput = opts.includeOutput ?? true;
  return {
    run_id: rec.run_id,
    profile: rec.profile,
    status: rec.status,
    created_at: rec.created_at,
    updated_at: rec.updated_at,
    finished_at: rec.finished_at,
    last_event: rec.last_event,
    tool_calls: rec.tool_calls,
    events: rec.event_count,
    model: rec.model,
    provider: rec.provider,
    reasoning_effort: rec.reasoning_effort,
    prompt_preview: rec.prompt_preview,
    error: rec.error,
    output: includeOutput ? (rec.output ? rec.output.slice(0, OUTPUT_PREVIEW_CHARS) : undefined) : undefined,
  };
}

const REASONING = ["minimal", "low", "medium", "high", "xhigh", "none"] as const;
/** Hard cap on the single output text a tool returns. */
const OUTPUT_PREVIEW_CHARS = 1500;

export interface ToolDeps {
  cfg: Config;
  client: GatewayClient;
  runs: RunManager;
  store: RunStore;
}

export function registerTools(server: McpServer, deps: ToolDeps): void {
  const { cfg, client, runs, store } = deps;

  server.registerTool(
    "health",
    {
      title: "Health",
      description:
        "Check hermes-mcp and the Hermes gateway: reachability, version, served profiles and active run count. No secrets.",
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async () => {
      const gateway: Record<string, unknown> = { url: cfg.gatewayUrl, reachable: false };
      try {
        const h = await client.health();
        gateway.reachable = true;
        gateway.status = h.status;
        gateway.version = h.version;
      } catch (exc) {
        gateway.error = (exc as Error).message;
      }
      return json({
        status: gateway.reachable ? "ok" : "degraded",
        service: "hermes-mcp",
        version: process.env.npm_package_version ?? "1.0.0",
        backend: cfg.backend,
        gateway,
        profiles: client.profileNames(),
        active_runs: runs.activeCount(),
        tracked_runs: store.list({ limit: 1000 }).length,
        time: new Date().toISOString(),
      });
    },
  );

  server.registerTool(
    "list_profiles",
    {
      title: "List profiles",
      description:
        "List the Hermes agent profiles hermes-mcp can start runs on, with their default model and gateway authentication status.",
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async () => {
      const profiles = await Promise.all(
        client.profileNames().map(async (profile) => {
          try {
            const models = await client.models(profile);
            const ids = (models.data ?? []).map((m) => m.id);
            await client.modelOptions(profile).catch(() => undefined);
            return { profile, authenticated: true, default_model: ids[0] ?? null, routes: ids.slice(1) };
          } catch (exc) {
            const err = exc as GatewayError;
            return { profile, authenticated: false, error: err.message, code: err.code };
          }
        }),
      );
      return json({ profiles, count: profiles.length });
    },
  );

  server.registerTool(
    "list_models",
    {
      title: "List models",
      description:
        "List the models of a profile: the active provider/model plus the configured provider catalog. Use the returned model ids in start_run(model=...).",
      inputSchema: {
        profile: z.string().describe("Hermes profile name, e.g. 'minecraft'"),
        search: z.string().optional().describe("Optional case-insensitive substring filter on model ids"),
        limit: z.number().int().min(1).max(200).optional().describe("Max models per provider (default 25)"),
      },
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async ({ profile, search, limit }) => {
      try {
        const options = (await client.modelOptions(profile)) as {
          model?: string;
          provider?: string;
          providers?: Array<{
            slug?: string;
            name?: string;
            is_current?: boolean;
            authenticated?: boolean;
            total_models?: number;
            models?: string[];
          }>;
        };
        const needle = (search ?? "").toLowerCase();
        const perProvider = limit ?? 25;
        const providers = (options.providers ?? [])
          .filter((p) => p.authenticated || p.is_current || (p.models?.length ?? 0) > 0)
          .map((p) => {
            const all = (p.models ?? []).filter((m) => !needle || m.toLowerCase().includes(needle));
            return {
              slug: p.slug,
              name: p.name,
              is_current: Boolean(p.is_current),
              authenticated: Boolean(p.authenticated),
              total_models: p.total_models ?? p.models?.length ?? 0,
              models: all.slice(0, perProvider),
              truncated: all.length > perProvider,
            };
          })
          .filter((p) => p.models.length > 0 || p.is_current);
        return json({
          profile,
          current: { provider: options.provider ?? null, model: options.model ?? null },
          providers,
        });
      } catch (exc) {
        return fail(exc);
      }
    },
  );

  server.registerTool(
    "start_run",
    {
      title: "Start run",
      description:
        "Start an agent run on a profile. Returns immediately with a run_id; poll get_run / get_run_logs. " +
        "model/provider/reasoning_effort must be on the server allowlist (omit them to use the profile default); " +
        "a daily run budget applies. The run executes with that profile's full tool access, so keep prompts explicit.",
      inputSchema: {
        profile: z.string().describe("Hermes profile name (see list_profiles)"),
        prompt: z.string().min(1).max(20000).describe("The task/prompt for the agent"),
        model: z.string().optional().describe("Optional model id override, e.g. 'deepseek/deepseek-v4.1-flash-1017'"),
        reasoning_effort: z.enum(REASONING).optional().describe("Optional reasoning effort override"),
        provider: z.string().optional().describe("Optional provider slug matching the model"),
        session_id: z.string().optional().describe("Optional session id to continue an existing conversation"),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: true },
    },
    async ({ profile, prompt, model, reasoning_effort, provider, session_id }) => {
      try {
        const rec = await runs.start(profile, { prompt, model, provider, reasoning_effort, session_id });
        return json({
          run_id: rec.run_id,
          profile: rec.profile,
          status: rec.status,
          next: `get_run(run_id="${rec.run_id}")`,
        });
      } catch (exc) {
        return fail(exc);
      }
    },
  );

  server.registerTool(
    "get_run",
    {
      title: "Get run",
      description:
        "Get the status and progress of one run: status, last event, elapsed time, tool calls, usage and the final output.",
      inputSchema: {
        run_id: z.string().describe("Run id returned by start_run"),
      },
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    async ({ run_id }) => {
      const rec = store.get(run_id);
      if (!rec) return fail(new GatewayError(404, "run_not_found", `Unknown run_id: ${run_id}`));
      const elapsed = Math.max(
        0,
        Math.round(((rec.finished_at ? Date.parse(rec.finished_at) : Date.now()) - Date.parse(rec.created_at)) / 1000),
      );
      return json({
        ...summarize(rec, { includeOutput: false }),
        progress: {
          stage: rec.status,
          last_event: rec.last_event ?? null,
          tool_calls: rec.tool_calls,
          events: rec.event_count,
          elapsed_seconds: elapsed,
          terminal: isTerminal(rec.status),
        },
        // Single output text, hard-capped — do not also ship it via summarize().
        last_output: rec.output ? rec.output.slice(0, OUTPUT_PREVIEW_CHARS) : null,
        output_truncated: Boolean(rec.output && rec.output.length > OUTPUT_PREVIEW_CHARS),
        usage: rec.usage ?? null,
      });
    },
  );

  server.registerTool(
    "list_runs",
    {
      title: "List runs",
      description: "List tracked runs, newest first, optionally filtered by profile and/or status.",
      inputSchema: {
        profile: z.string().optional().describe("Filter by profile"),
        status: z
          .enum([
            "queued",
            "running",
            "waiting_for_approval",
            "stopping",
            "completed",
            "failed",
            "cancelled",
            "interrupted",
            "unknown",
            "timed_out",
          ])
          .optional()
          .describe("Filter by status"),
        limit: z.number().int().min(1).max(100).optional().describe("Max rows (default 25)"),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ profile, status, limit }) => {
      const rows = store.list({ profile, status, limit: limit ?? 25 });
      return json({ runs: rows.map((r) => summarize(r)), count: rows.length });
    },
  );

  server.registerTool(
    "get_run_logs",
    {
      title: "Get run logs",
      description:
        "Return the tail of a run's event log (tool calls, lifecycle events, streamed output). Use tail to bound the output.",
      inputSchema: {
        run_id: z.string().describe("Run id"),
        tail: z.number().int().min(1).max(500).optional().describe("Number of trailing log entries (default 50)"),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ run_id, tail }) => {
      const rec = store.get(run_id);
      if (!rec) return fail(new GatewayError(404, "run_not_found", `Unknown run_id: ${run_id}`));
      return json({ run_id, status: rec.status, lines: store.logs(run_id, tail ?? 50) });
    },
  );

  server.registerTool(
    "cancel_run",
    {
      title: "Cancel run",
      description:
        "Cancel a running agent run. Destructive: requires confirm=true. The agent is interrupted; partial work may remain on disk.",
      inputSchema: {
        run_id: z.string().describe("Run id to cancel"),
        confirm: z.boolean().describe("Must be true. Guards against accidental cancellation."),
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true },
    },
    async ({ run_id, confirm }) => {
      if (confirm !== true) {
        return {
          isError: true,
          content: [
            {
              type: "text",
              text: JSON.stringify(
                { error: "cancel_run requires confirm=true", code: "confirmation_required", run_id },
                null,
                2,
              ),
            },
          ],
        };
      }
      try {
        const rec = await runs.cancel(run_id);
        return json({ run_id, status: rec.status, cancel_requested: true });
      } catch (exc) {
        return fail(exc);
      }
    },
  );

  // Ops tools are only advertised when the helper is actually configured: a
  // capability that would fail closed on every call is worse than no tool.
  const ops = new OpsClient(cfg);
  if (ops.enabled()) registerOpsTools(server, { cfg, ops });
}

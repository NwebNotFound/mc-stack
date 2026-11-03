/**
 * MCP tools for the ops layer.
 *
 * These tools hold no privileges of their own: each one forwards a single
 * allowlisted action to ops-helper and returns its (redacted) answer. Tier
 * gates - confirm, the global armed switch, the mandatory delay, the backup
 * requirement, cooldowns and the rate limit - are enforced by ops-helper, which
 * is the security boundary; the schemas here only keep the request shape honest.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { Config } from "./config.js";
import type { OpsClient } from "./ops.js";
import { fail, json } from "./responses.js";

/** Timeouts per action: builds and rollouts legitimately take minutes. */
const TIMEOUTS: Record<string, number> = {
  ops_build_plugin: 1800,
  ops_run_tests: 1800,
  ops_deploy_plugin: 1800,
  ops_rollback_plugin: 1800,
  ops_backup_now: 1800,
  ops_restart_game: 900,
  ops_restart_service: 300,
  ops_logs: 120,
  ops_players: 60,
};

/** Which token level the caller presented at the MCP boundary. */
import type { OpsLevel } from "./auth.js";
export type { OpsLevel };

export interface OpsToolDeps {
  cfg: Config;
  ops: OpsClient;
  /** Which token level the caller presented. */
  authLevel: OpsLevel;
  /** True only on the local-only listener; a tunnel request is never T2-capable. */
  allowT2: boolean;
}

/**
 * Two token levels at the MCP boundary: a plain HERMES_MCP_TOKEN is `base`
 * (read-only T0 tools), a HERMES_MCP_OPS_TOKEN is `ops`. T2 additionally
 * requires the local-only listener, so a caller that reaches hermes-mcp through
 * the public tunnel can never restart a game server even with the ops token.
 *
 * Pure function so every rule is unit-testable without a socket.
 */
export const OPS_TOKEN_REQUIRED = "ops_token_required";
export const OPS_T2_LOCAL_ONLY = "ops_t2_local_only";

export function opsAccess(tier: string, level: OpsLevel, allowT2: boolean): string | null {
  if (tier === "T0") return null;
  if (level !== "ops") return OPS_TOKEN_REQUIRED;
  if (tier === "T2" && !allowT2) return OPS_T2_LOCAL_ONLY;
  return null;
}

export function registerOpsTools(server: McpServer, deps: OpsToolDeps): void {
  const { ops, authLevel, allowT2 } = deps;

  const denied = (action: string, code: string) => ({
    isError: true,
    content: [
      {
        type: "text" as const,
        text: JSON.stringify(
          {
            ok: false,
            action,
            code,
            message:
              code === OPS_T2_LOCAL_ONLY
                ? `${action} is a T2 action and is only reachable over the local listener (never through the tunnel)`
                : `${action} needs the second token: present HERMES_MCP_OPS_TOKEN as the bearer token`,
          },
          null,
          2,
        ),
      },
    ],
  });

  /**
   * Every call re-checks its tier before it reaches ops-helper (defence in
   * depth: the same `opsAccess` rule already decided whether the tool was
   * advertised at all).
   */
  const forward = async (
    action: string,
    params: Record<string, unknown>,
    tier: "T0" | "T1" | "T2" = "T0",
  ) => {
    const code = opsAccess(tier, authLevel, allowT2);
    if (code) return denied(action, code);
    try {
      const res = await ops.call(action, params, { timeoutSeconds: TIMEOUTS[action] ?? 120 });
      if (!res.ok) {
        return {
          isError: true,
          content: [
            {
              type: "text" as const,
              text: JSON.stringify(
                {
                  ok: false,
                  action,
                  code: res.code ?? "ops_failed",
                  message: res.message ?? "ops-helper refused the action",
                  result: res.result ?? null,
                  audit_id: res.audit_id ?? null,
                },
                null,
                2,
              ),
            },
          ],
        };
      }
      return json({ ok: true, action, tier: res.tier, audit_id: res.audit_id, result: res.result });
    } catch (exc) {
      return fail(exc);
    }
  };

  /* ---------------- T0: monitoring ---------------- */

  server.registerTool(
    "ops_status",
    {
      title: "Ops status",
      description:
        "Read-only: container status of the mc-stack compose project (state, uptime string, health, known-to-allowlist flag) plus running/stopped counts. Optional service filter.",
      inputSchema: {
        service: z.string().optional().describe("Optional single compose service name, e.g. 'lobby'"),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ service }) => forward("ops_status", service ? { service } : {}),
  );

  server.registerTool(
    "ops_logs",
    {
      title: "Ops logs",
      description:
        "Read-only: the last N lines of one service's container log (stdout+stderr), redacted and capped. tail is limited to 300 lines.",
      inputSchema: {
        service: z.string().describe("Compose service name, e.g. 'survival'"),
        tail: z.number().int().min(1).max(300).optional().describe("Trailing lines (default 50)"),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ service, tail }) => forward("ops_logs", tail === undefined ? { service } : { service, tail }),
  );

  server.registerTool(
    "ops_disk",
    {
      title: "Ops disk",
      description:
        "Read-only: filesystem size/free bytes for the repo, backups and artifact mounts, plus `du` sizes of backups/ and data/ (bounded by a timeout, reported as timed_out instead of a wrong number).",
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async () => forward("ops_disk", {}),
  );

  server.registerTool(
    "ops_backups_list",
    {
      title: "Ops backups list",
      description: "Read-only: the daily-* backup directories with their mtime, COMPLETE.txt marker and dump marker.",
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async () => forward("ops_backups_list", {}),
  );

  server.registerTool(
    "ops_players",
    {
      title: "Ops players",
      description:
        "PAUSED (disabled in the allowlist): needs docker exec, which the socket proxy denies (EXEC=0). Read-only otherwise: online players of lobby or survival via the container's own rcon-cli `list`. TPS/MSPT are not queried (Paper command availability unverified).",
      inputSchema: {
        service: z.enum(["lobby", "survival"]).describe("Which game server to ask"),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ service }) => forward("ops_players", { service }),
  );

  server.registerTool(
    "ops_git_state",
    {
      title: "Ops git state",
      description:
        "Read-only: repo branch, short HEAD, commit date, number of modified tracked files, and ahead/behind against the upstream. File names are never returned.",
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async () => forward("ops_git_state", {}),
  );

  /* ---------------- T0: development ---------------- */

  server.registerTool(
    "ops_build_plugin",
    {
      title: "Ops build plugin",
      description:
        "PAUSED (disabled in the allowlist): needs to start a container, which the socket proxy denies (POST=0). Read-only otherwise: gradle clean build of plugins-src/craftcore in a throwaway container, returns the test counts from the JUnit XML and the sha256 of the built jar. Deploys nothing.",
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async () => forward("ops_build_plugin", {}),
  );

  server.registerTool(
    "ops_run_tests",
    {
      title: "Ops run tests",
      description:
        "PAUSED (disabled in the allowlist): needs to start a container, which the socket proxy denies (POST=0). Read-only otherwise: run one project's test suite in a throwaway container (hermes-mcp, ops-helper or craftcore) and return the test counts plus a capped output tail.",
      inputSchema: {
        target: z.enum(["hermes-mcp", "ops-helper", "craftcore"]).describe("Which test suite to run"),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ target }) => forward("ops_run_tests", { target }),
  );

  /* ---------------- T1: reversible, no player impact (needs the ops token) ---------------- */

  if (opsAccess("T1", authLevel, allowT2) === null) {
    server.registerTool(
      "ops_restart_service",
      {
        title: "Ops restart service (T1)",
        description:
          "PAUSED (disabled in the allowlist): docker restart is a POST the socket proxy denies while POST=0. Restart one reversible helper service (allowlist: bot, cloudflared-mcp) when re-enabled. Requires confirm=true. Never touches the game servers, the database or hermes-mcp/ops-helper themselves.",
        inputSchema: {
          service: z.string().describe("Compose service name from the T1 allowlist, e.g. 'bot'"),
          confirm: z.boolean().describe("Must be true. Guards against accidental restarts."),
        },
        annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true },
      },
      async ({ service, confirm }) => forward("ops_restart_service", { service, confirm }, "T1"),
    );

    server.registerTool(
      "ops_backup_now",
      {
        title: "Ops backup now (T1)",
        description:
          "PAUSED (disabled in the allowlist): the helper image has no PowerShell, so backup.ps1 cannot run there. Run the repo's own backup.ps1 on the host instead. Requires confirm=true when re-enabled.",
        inputSchema: {
          confirm: z.boolean().describe("Must be true."),
        },
        annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false },
      },
      async ({ confirm }) => forward("ops_backup_now", { confirm }, "T1"),
    );
  }

  /* ---------------- T2: player impact, ops token + local listener only ---------------- */

  if (opsAccess("T2", authLevel, allowT2) === null) {
    server.registerTool(
      "ops_restart_game",
      {
        title: "Ops restart game server (T2)",
        description:
          "PAUSED (disabled in the allowlist): a T2 restart needs a POST the socket proxy denies. When re-enabled: restart lobby, survival or velocity with confirm=true, an integer delay_seconds >= 60 (players are warned first, best effort) and a fresh backup; the helper must be armed (OPS_ARMED=true). A health check follows the restart.",
        inputSchema: {
          service: z.enum(["lobby", "survival", "velocity"]).describe("Which game server to restart"),
          confirm: z.boolean().describe("Must be true."),
          delay_seconds: z.number().int().min(60).max(3600).describe("Warning lead time before the restart (>= 60)"),
        },
        annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false },
      },
      async ({ service, confirm, delay_seconds }) =>
        forward("ops_restart_game", { service, confirm, delay_seconds }, "T2"),
    );

    server.registerTool(
      "ops_deploy_plugin",
      {
        title: "Ops deploy plugin (T2)",
        description:
          "PAUSED (disabled in the allowlist): needs a built artifact (container create) and an exec for the announcement, both denied by the socket proxy. When re-enabled: deploy the last ops_build_plugin artifact into config/lobby-plugins and config/survival-plugins, rolling with delay_seconds between services, health check each, automatic rollback on failure. T2: confirm=true, delay_seconds >= 60, armed helper and a fresh backup.",
        inputSchema: {
          artifact_sha256: z.string().describe("sha256 returned by ops_build_plugin"),
          confirm: z.boolean().describe("Must be true."),
          delay_seconds: z.number().int().min(60).max(3600).describe("Warning lead time and pause between services (>= 60)"),
        },
        annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false },
      },
      async ({ artifact_sha256, confirm, delay_seconds }) =>
        forward("ops_deploy_plugin", { artifact_sha256, confirm, delay_seconds }, "T2"),
    );

    server.registerTool(
      "ops_rollback_plugin",
      {
        title: "Ops rollback plugin (T2)",
        description:
          "PAUSED (disabled in the allowlist): needs a POST restart and an exec for the announcement, both denied by the socket proxy. When re-enabled: restore the jars ops_deploy_plugin kept, rolling with delay_seconds between services and a health check each. T2: confirm=true, delay_seconds >= 60 and an armed helper.",
        inputSchema: {
          confirm: z.boolean().describe("Must be true."),
          delay_seconds: z.number().int().min(60).max(3600).describe("Warning lead time and pause between services (>= 60)"),
        },
        annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false },
      },
      async ({ confirm, delay_seconds }) => forward("ops_rollback_plugin", { confirm, delay_seconds }, "T2"),
    );
  }
}

/**
 * ops-helper entry point.
 *
 * Fail closed: without OPS_HELPER_TOKEN or without a valid allowlist the process
 * exits before it binds a socket. Nothing here creates, stops or deletes a
 * container at startup - the helper only ever acts when hermes-mcp asks for one
 * allowlisted action.
 */
import { existsSync } from "node:fs";
import { AuditLog } from "./audit.js";
import { loadAllowlist, type Allowlist } from "./allowlist.js";
import { loadConfig, type OpsConfig } from "./config.js";
import { DockerCli } from "./docker.js";
import { writeDockerAuthConfig } from "./docker-auth.js";
import { FileJobClient } from "./jobs.js";
import { logger } from "./logger.js";
import { ExecRunner } from "./runner.js";
import { createServer } from "./server.js";
import { OpsState } from "./state.js";

function bootstrap(): { cfg: OpsConfig; allow: Allowlist } | null {
  try {
    const cfg = loadConfig();
    const allow = loadAllowlist(cfg.allowlistPath);
    if (cfg.filterToken) {
      // Every `docker` child inherits DOCKER_CONFIG and so sends the filter's
      // bearer token (see docker-auth.ts).
      process.env.DOCKER_CONFIG = writeDockerAuthConfig(cfg.filterToken, cfg.dockerConfigDir);
    } else {
      logger.warn("OPS_FILTER_TOKEN is unset: docker-socket-filter will refuse every docker call (401)");
    }
    return { cfg, allow };
  } catch (exc) {
    logger.error("refusing to start", { error: (exc as Error).message });
    return null;
  }
}

function main(): void {
  const boot = bootstrap();
  if (!boot) {
    process.exit(1);
    return;
  }
  const { cfg, allow } = boot;

  const killSwitchFile = `${cfg.dataDir}/DISABLED`;
  const audit = new AuditLog(`${cfg.dataDir}/audit.jsonl`);
  const state = new OpsState(`${cfg.dataDir}/state.json`);
  state.load();

  const runner = new ExecRunner();
  const docker = new DockerCli(runner, cfg.dockerBin, allow.composeProject, cfg.actionTimeoutSeconds * 1000);
  const jobs = new FileJobClient(cfg.jobsDir, cfg.builderResultsDir, undefined, cfg.builderPollMs);

  logger.info("allowlist loaded", {
    actions: Object.keys(allow.actions).length,
    tiers: Object.fromEntries(
      Object.entries(allow.actions).map(([name, spec]) => [name, spec.tier]),
    ),
    project: allow.composeProject,
    armed: cfg.armed,
    kill_switch: cfg.disabled || existsSync(killSwitchFile),
    audit_log: audit.path(),
  });

  const deps = {
    cfg,
    allow,
    docker,
    runner,
    audit,
    state,
    jobs,
    now: () => Date.now(),
    sleep: (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)),
  };

  const server = createServer(deps);
  server.listen(cfg.port, cfg.host, () => {
    logger.info("ops-helper listening", { host: cfg.host, port: cfg.port, path: "/op" });
  });

  const shutdown = (signal: string) => {
    logger.info("shutting down", { signal });
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 8000).unref();
  };
  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("SIGINT", () => shutdown("SIGINT"));
}

main();

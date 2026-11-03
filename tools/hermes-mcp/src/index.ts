/**
 * hermes-mcp - remote MCP server for the Hermes agents.
 *
 * Transport: Streamable HTTP at /mcp (stateless; a fresh MCP server instance per
 * request). Auth: bearer token from HERMES_MCP_TOKEN (read-only level) or
 * HERMES_MCP_OPS_TOKEN (ops level; T1/T2 tools).
 *
 * Two listeners, both inside the compose network:
 *  - the MAIN listener (HERMES_MCP_PORT, 8765) is the one the Cloudflare Tunnel
 *    points at. It never advertises T2 tools, so a caller that reaches this
 *    server through the public tunnel can never restart a game server;
 *  - the LOCAL listener (HERMES_MCP_OPS_PORT, 8767) is published only on
 *    127.0.0.1 on the host. It is the only place where T2 tools exist.
 *
 * The container itself publishes no public port; it is reachable from the
 * compose networks and, for the main listener, through the tunnel.
 */
import { randomUUID } from "node:crypto";
import http from "node:http";
import express, { type Express, type NextFunction, type Request, type Response } from "express";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { resolveLevel, sha256, type OpsLevel } from "./auth.js";
import { loadConfig, type Config } from "./config.js";
import { GatewayClient } from "./hermes.js";
import { logger } from "./logger.js";
import { RunManager } from "./runner.js";
import { BruteForceGuard, resolveClientIp } from "./security.js";
import { RunStore, type RunStore as RunStoreType } from "./store.js";
import { registerTools } from "./tools.js";

const VERSION = process.env.npm_package_version ?? "1.0.0";

interface Bucket {
  count: number;
  resetAt: number;
}

function rateLimiter(limitPerMinute: number) {
  const buckets = new Map<string, Bucket>();
  return (key: string): boolean => {
    const now = Date.now();
    const bucket = buckets.get(key);
    if (!bucket || bucket.resetAt < now) {
      buckets.set(key, { count: 1, resetAt: now + 60_000 });
      return true;
    }
    bucket.count += 1;
    return bucket.count <= limitPerMinute;
  };
}

interface ServerDeps {
  cfg: Config;
  client: GatewayClient;
  runs: RunManager;
  store: RunStoreType;
  /** Per-listener: does this listener serve T2 tools? */
  allowT2: boolean;
  /** Per-listener brute-force brake (the ops listener has its own, stricter one). */
  bruteForce: BruteForceGuard;
}

function buildServer(
  deps: { cfg: Config; client: GatewayClient; runs: RunManager; store: RunStoreType },
  ctx: { authLevel: OpsLevel; allowT2: boolean },
): McpServer {
  const server = new McpServer(
    { name: "hermes-mcp", version: VERSION },
    {
      instructions:
        "Manage Nils' Hermes agents. Call list_profiles first, then start_run with an explicit prompt. " +
        "Runs are asynchronous: poll get_run for status/progress and get_run_logs for details. " +
        "cancel_run requires confirm=true. The ops_* tools forward to ops-helper; the T1/T2 ones " +
        "(restart, deploy, backup_now) need the higher ops token, and T2 only exists on the local listener.",
    },
  );
  registerTools(server, { ...deps, authLevel: ctx.authLevel, allowT2: ctx.allowT2 });
  return server;
}

function createApp(deps: ServerDeps): Express {
  const { cfg } = deps;

  const app = express();
  app.disable("x-powered-by");
  // Only a configured trusted proxy may set X-Forwarded-For; otherwise Express
  // (and resolveClientIp) fall back to the socket address.
  app.set("trust proxy", cfg.trustedProxies.length > 0 ? cfg.trustedProxies : false);
  app.use(express.json({ limit: "512kb" }));

  app.use((req: Request, res: Response, next: NextFunction) => {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader("Cache-Control", "no-store");
    const started = Date.now();
    res.on("finish", () => {
      logger.info("request", {
        method: req.method,
        path: req.path,
        status: res.statusCode,
        ms: Date.now() - started,
        t2_listener: deps.allowT2,
      });
    });
    next();
  });

  /** DNS-rebinding guard: Host must be an expected name (Cloudflare rewrites it to the origin host). */
  app.use((req: Request, res: Response, next: NextFunction) => {
    const host = (req.headers.host ?? "").split(":")[0].toLowerCase();
    if (host && cfg.allowedHosts.length > 0 && !cfg.allowedHosts.includes(host)) {
      logger.warn("rejected unexpected Host header", { host });
      res.status(403).json({ error: "forbidden_host" });
      return;
    }
    next();
  });

  const allowToken = rateLimiter(120);

  const requireAuth = (req: Request, res: Response, next: NextFunction): void => {
    const clientIp = resolveClientIp(req, cfg.trustedProxies);
    const bruteForce = deps.bruteForce;

    const lockout = bruteForce.check(clientIp);
    if (lockout) {
      logger.warn("rejected a locked-out client", { path: req.path, remote: clientIp, t2_listener: deps.allowT2 });
      res
        .status(429)
        .setHeader("Retry-After", String(lockout.retryAfterSeconds))
        .json({
          error: "too_many_bad_tokens",
          message: `Too many invalid tokens from this client. Retry in ${lockout.retryAfterSeconds}s.`,
        });
      return;
    }

    const header = req.headers.authorization ?? "";
    const match = /^Bearer\s+(.+)$/i.exec(header);
    if (!match) {
      bruteForce.failure(clientIp);
      res
        .status(401)
        .setHeader("WWW-Authenticate", `Bearer realm="hermes-mcp"`)
        .json({ error: "unauthorized", message: "Bearer token required" });
      return;
    }
    const token = match[1].trim();
    const level = resolveLevel(token, cfg);
    if (!level) {
      bruteForce.failure(clientIp);
      logger.warn("rejected invalid MCP bearer token", { path: req.path, remote: clientIp, t2_listener: deps.allowT2 });
      res.status(401).json({ error: "unauthorized", message: "Invalid or expired token" });
      return;
    }
    bruteForce.success(clientIp);
    if (!allowToken(sha256(token).toString("hex"))) {
      res.status(429).json({ error: "rate_limited", message: "Too many requests" });
      return;
    }
    res.locals.authLevel = level;
    next();
  };

  /** Liveness only: no profile data, no versions, no auth material. */
  app.get("/healthz", (_req: Request, res: Response) => {
    res.json({ status: "ok" });
  });

  app.post("/mcp", requireAuth, async (req: Request, res: Response) => {
    // Stateless: one MCP server + transport per request, closed when the response ends.
    const authLevel = (res.locals.authLevel as OpsLevel | undefined) ?? "base";
    const server = buildServer(
      { cfg, client: deps.client, runs: deps.runs, store: deps.store },
      { authLevel, allowT2: deps.allowT2 },
    );
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
      enableJsonResponse: false,
    });
    res.on("close", () => {
      void transport.close();
      void server.close();
    });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (exc) {
      logger.error("mcp request failed", { error: (exc as Error).message, rid: randomUUID() });
      if (!res.headersSent) res.status(500).json({ error: "internal_error" });
    }
  });

  // Stateless mode has no server-initiated stream and no session to delete.
  app.get("/mcp", requireAuth, (_req: Request, res: Response) => {
    res.status(405).setHeader("Allow", "POST").json({ error: "method_not_allowed", message: "Stateless server: use POST" });
  });
  app.delete("/mcp", requireAuth, (_req: Request, res: Response) => {
    res.status(405).setHeader("Allow", "POST").json({ error: "method_not_allowed" });
  });

  app.use((_req: Request, res: Response) => {
    res.status(404).json({ error: "not_found" });
  });

  app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => {
    logger.warn("request rejected", { error: err.message });
    if (!res.headersSent) res.status(400).json({ error: "bad_request" });
  });

  return app;
}

async function main(): Promise<void> {
  let cfg: Config;
  try {
    cfg = loadConfig();
  } catch (exc) {
    logger.error("refusing to start", { error: (exc as Error).message });
    process.exit(1);
  }

  const store = new RunStore(cfg.dataDir);
  const client = new GatewayClient(cfg);
  const runs = new RunManager(cfg, client, store);
  await runs.resume();
  runs.startSweeper();

  // Two independent brute-force brakes: guessing the elevated token on the local
  // listener is throttled separately (and sooner) than the public listener.
  const mainBruteForce = new BruteForceGuard({
    threshold: cfg.lockoutAfter,
    baseSeconds: 5,
    maxSeconds: cfg.lockoutMaxSeconds,
    windowSeconds: Math.max(900, cfg.lockoutMaxSeconds),
  });
  const opsBruteForce = new BruteForceGuard({
    threshold: cfg.opsLockoutAfter,
    baseSeconds: 10,
    maxSeconds: cfg.lockoutMaxSeconds,
    windowSeconds: Math.max(900, cfg.lockoutMaxSeconds),
  });

  const shared = { cfg, client, runs, store };
  const mainApp = createApp({ ...shared, allowT2: false, bruteForce: mainBruteForce });
  const opsApp = createApp({ ...shared, allowT2: true, bruteForce: opsBruteForce });

  const mainServer = http.createServer(mainApp);
  mainServer.listen(cfg.port, cfg.host, () => {
    logger.info("hermes-mcp listening", {
      host: cfg.host,
      port: cfg.port,
      mcp_path: "/mcp",
      t2_listener: false,
      note: "this is the tunnel-facing listener: T2 tools are never advertised here",
    });
  });

  const opsServer = http.createServer(opsApp);
  opsServer.listen(cfg.opsPort, cfg.opsHost, () => {
    logger.info("hermes-mcp ops listener listening", {
      host: cfg.opsHost,
      port: cfg.opsPort,
      mcp_path: "/mcp",
      t2_listener: true,
      note: "local-only listener: publish it on 127.0.0.1 and never point the tunnel at it",
    });
  });

  const shutdown = (signal: string) => {
    logger.info("shutting down", { signal });
    runs.shutdown();
    mainServer.close(() => process.exit(0));
    opsServer.close(() => undefined);
    setTimeout(() => process.exit(0), 8000).unref();
  };
  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("SIGINT", () => shutdown("SIGINT"));
}

void main();

/**
 * hermes-mcp â€” remote MCP server for the Hermes agents.
 *
 * Transport: Streamable HTTP at /mcp (stateless; a fresh MCP server instance per
 * request). Auth: bearer token from HERMES_MCP_TOKEN. The container publishes no
 * host port; it is only reachable from the compose network (or through the
 * Cloudflare Tunnel, which is prepared but NOT created).
 */
import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import http from "node:http";
import express, { type NextFunction, type Request, type Response } from "express";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { loadConfig, type Config } from "./config.js";
import { GatewayClient } from "./hermes.js";
import { logger } from "./logger.js";
import { RunManager } from "./runner.js";
import { RunStore } from "./store.js";
import { registerTools } from "./tools.js";

const VERSION = process.env.npm_package_version ?? "1.0.0";

function sha256(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}

/** Constant-time bearer check that does not leak the token length. */
function tokenMatches(candidate: string, tokens: string[]): boolean {
  const candidateDigest = sha256(candidate);
  let ok = false;
  for (const token of tokens) {
    if (timingSafeEqual(candidateDigest, sha256(token))) ok = true;
  }
  return ok;
}

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

function buildServer(deps: { cfg: Config; client: GatewayClient; runs: RunManager; store: RunStore }): McpServer {
  const server = new McpServer(
    { name: "hermes-mcp", version: VERSION },
    {
      instructions:
        "Manage Nils' Hermes agents. Call list_profiles first, then start_run with an explicit prompt. " +
        "Runs are asynchronous: poll get_run for status/progress and get_run_logs for details. " +
        "cancel_run requires confirm=true.",
    },
  );
  registerTools(server, deps);
  return server;
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

  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", true);
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
    const header = req.headers.authorization ?? "";
    const match = /^Bearer\s+(.+)$/i.exec(header);
    if (!match) {
      res
        .status(401)
        .setHeader("WWW-Authenticate", `Bearer realm="hermes-mcp"`)
        .json({ error: "unauthorized", message: "Bearer token required" });
      return;
    }
    const token = match[1].trim();
    if (!tokenMatches(token, cfg.tokens)) {
      logger.warn("rejected invalid MCP bearer token", { path: req.path, remote: req.ip });
      res.status(401).json({ error: "unauthorized", message: "Invalid or expired token" });
      return;
    }
    if (!allowToken(sha256(token).toString("hex"))) {
      res.status(429).json({ error: "rate_limited", message: "Too many requests" });
      return;
    }
    next();
  };

  /** Liveness only: no profile data, no versions, no auth material. */
  app.get("/healthz", (_req: Request, res: Response) => {
    res.json({ status: "ok" });
  });

  app.post("/mcp", requireAuth, async (req: Request, res: Response) => {
    // Stateless: one MCP server + transport per request, closed when the response ends.
    const server = buildServer({ cfg, client, runs, store });
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

  const server = http.createServer(app);
  server.listen(cfg.port, cfg.host, () => {
    logger.info("hermes-mcp listening", { host: cfg.host, port: cfg.port, mcp_path: "/mcp" });
  });

  const shutdown = (signal: string) => {
    logger.info("shutting down", { signal });
    runs.shutdown();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 8000).unref();
  };
  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("SIGINT", () => shutdown("SIGINT"));
}

void main();

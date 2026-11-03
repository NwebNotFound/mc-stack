/**
 * HTTP surface of ops-helper: exactly two routes, on an internal compose network,
 * with no published host port.
 *
 *  - GET  /healthz : liveness only (no data, no auth material)
 *  - POST /op      : the single dispatcher; requires the shared secret
 *
 * The secret is compared in constant time over SHA-256 digests (no length leak),
 * is never logged and never returned. Every response is redacted and capped.
 */
import { createHash, timingSafeEqual } from "node:crypto";
import http from "node:http";
import type { ActionDeps } from "./actions.js";
import { handleOp } from "./actions.js";
import { logger } from "./logger.js";
import { redactValue } from "./redact.js";

const MAX_BODY_BYTES = 64 * 1024;

function sha256(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}

/** Constant-time bearer check that does not leak the token length. */
export function tokenMatches(candidate: string, expected: string): boolean {
  if (!candidate || !expected) return false;
  return timingSafeEqual(sha256(candidate), sha256(expected));
}

function readBody(req: http.IncomingMessage): Promise<{ ok: boolean; text?: string; tooLarge?: boolean }> {
  return new Promise((resolve) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        resolve({ ok: false, tooLarge: true });
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve({ ok: true, text: Buffer.concat(chunks).toString("utf8") }));
    req.on("error", () => resolve({ ok: false }));
  });
}

export function createServer(deps: ActionDeps): http.Server {
  const cap = deps.allow.limits.maxOutputBytes;

  return http.createServer((req, res) => {
    const started = Date.now();
    const send = (status: number, payload: Record<string, unknown>) => {
      let body = JSON.stringify(redactValue(payload));
      if (Buffer.byteLength(body, "utf8") > cap + 8192) {
        body = JSON.stringify({
          ok: payload.ok === true,
          action: payload.action,
          code: "response_truncated",
          message: `response exceeded max_output_bytes (${cap})`,
          audit_id: payload.audit_id,
        });
      }
      res.writeHead(status, {
        "content-type": "application/json",
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
      });
      res.end(body);
    };

    res.on("finish", () => {
      logger.info("request", { method: req.method, path: req.url, status: res.statusCode, ms: Date.now() - started });
    });

    if (req.method === "GET" && req.url === "/healthz") {
      send(200, { status: "ok" });
      return;
    }

    if (req.method !== "POST" || req.url !== "/op") {
      send(404, { ok: false, code: "not_found", message: "POST /op" });
      return;
    }

    const header = req.headers.authorization ?? "";
    const match = /^Bearer\s+(.+)$/i.exec(header);
    if (!match || !tokenMatches(match[1].trim(), deps.cfg.token)) {
      logger.warn("rejected ops-helper request", { reason: "bad_token" });
      send(401, { ok: false, code: "unauthorized", message: "valid bearer token required" });
      return;
    }

    void (async () => {
      const body = await readBody(req);
      if (!body.ok) {
        send(body.tooLarge ? 413 : 400, { ok: false, code: "bad_request", message: "unreadable body" });
        return;
      }
      let parsed: { action?: unknown; params?: unknown };
      try {
        parsed = JSON.parse(body.text ?? "{}") as { action?: unknown; params?: unknown };
      } catch {
        send(400, { ok: false, code: "bad_request", message: "body must be JSON" });
        return;
      }
      try {
        const outcome = await handleOp(parsed.action, parsed.params, deps);
        send(outcome.ok ? 200 : 200, {
          ok: outcome.ok,
          action: typeof parsed.action === "string" ? parsed.action : null,
          tier: outcome.tier ?? null,
          impl: outcome.impl ?? null,
          code: outcome.code ?? null,
          message: outcome.message ?? null,
          result: outcome.result ?? null,
          audit_id: outcome.audit_id,
          duration_ms: Date.now() - started,
        });
      } catch (exc) {
        // handleOp is not supposed to throw; if it does, never leak a stack trace.
        logger.error("op dispatch failed", { error: (exc as Error).message });
        send(500, { ok: false, code: "internal_error", message: "op failed" });
      }
    })();
  });
}

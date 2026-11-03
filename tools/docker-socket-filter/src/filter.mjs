/**
 * docker-socket-filter — a tiny, dependency-free, path-exact HTTP filter in
 * front of /var/run/docker.sock.
 *
 * Why this exists: tecnativa/docker-socket-proxy filters by API *section*
 * (`CONTAINERS`, `POST`, `EXEC`, ...). That granularity cannot express "GET is
 * fine, but only this one POST path, and only for these two container names".
 * Turning `POST=1` on there re-opens `/containers/create`, `/stop`, `/kill` and
 * `/exec` for the whole container API. This filter instead allowlists exact
 * method + path + query, so `ops_restart_service` can work while create/exec/
 * start/stop/kill/remove stay impossible.
 *
 * Design rules (all enforced in `classify`, which is a pure function so the
 * tests can hammer it without a socket):
 *  - exact method (GET/POST/HEAD, case sensitive), exact path (case sensitive);
 *  - an optional `v1.<digits>` API-version prefix, nothing else;
 *  - no percent-encoding in the path, no `.`/`..` segment, no `//`, no `\`;
 *  - query keys allowlisted per route, no duplicate keys, bounded lengths;
 *  - no request body at all (Content-Length must be absent or "0",
 *    Transfer-Encoding and Upgrade are refused outright -> no request
 *    smuggling);
 *  - a restart is only ever accepted for an exact container NAME from the
 *    allowlist: no ids, no prefix matches, no globs.
 * Everything else is a 403 and lands in the audit log.
 *
 * Residual risk, stated plainly: whoever compromises THIS process has the
 * socket. It is the privilege boundary, so it is small, has no dependencies, no
 * outbound network, and is the only service that mounts the socket.
 */
import net from "node:net";

export const CRLF = "\r\n";
const CRLFCRLF = Buffer.from("\r\n\r\n", "latin1");

export const MAX_HEAD_BYTES = 16 * 1024;
export const MAX_QUERY_BYTES = 4096;
export const MAX_FILTERS_BYTES = 2048;
export const DEFAULT_MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
export const DEFAULT_MAX_LOG_TARGET = 512;

/**
 * Restart allowlists. Names are the real compose container names
 * (`<project>-<service>-<index>`), matched EXACTLY.
 *
 * There are TWO lists on purpose (one path, two intents):
 *  - T1 are the reversible helper services (bot, cloudflared-mcp);
 *  - T2 are the game servers (lobby, survival, velocity).
 * The filter itself cannot tell a T1 call from a T2 call - the Docker API has a
 * single `/containers/<name>/restart` path - so the union is what passes here and
 * the T1/T2 split is enforced one level up, by the helper's own
 * `services.restart_t1` / `services.restart_game` allowlists. Keeping the two
 * lists separate in configuration means a game-server restart can only ever be
 * triggered by the T2 action, and it is visible in the audit line which list
 * matched (`restarts: "t1" | "t2"`).
 */
export const DEFAULT_RESTART_ALLOW_T1 = ["mc-stack-bot-1", "mc-stack-cloudflared-mcp-1"];
export const DEFAULT_RESTART_ALLOW_T2 = ["mc-stack-lobby-1", "mc-stack-survival-1", "mc-stack-velocity-1"];

/**
 * @deprecated kept as the union for callers that only need "what may restart at
 * all". Prefer resolveRestartAllow().
 */
export const DEFAULT_RESTART_ALLOW = [...DEFAULT_RESTART_ALLOW_T1, ...DEFAULT_RESTART_ALLOW_T2];

/** Which of the two lists a name belongs to, or null when it belongs to neither. */
export function restartTierOf(name, cfg = {}) {
  const t1 = cfg.restartAllow ?? DEFAULT_RESTART_ALLOW_T1;
  const t2 = cfg.restartAllowT2 ?? DEFAULT_RESTART_ALLOW_T2;
  if (t1.includes(name)) return "t1";
  if (t2.includes(name)) return "t2";
  return null;
}

/** The union of both lists: what may be restarted through this filter at all. */
export function resolveRestartAllow(cfg = {}) {
  const t1 = cfg.restartAllow ?? DEFAULT_RESTART_ALLOW_T1;
  const t2 = cfg.restartAllowT2 ?? DEFAULT_RESTART_ALLOW_T2;
  return [...new Set([...t1, ...t2])];
}

/** Container name / id shape accepted in a path segment. */
export const REF_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/;
const VERSION_RE = /^v1\.\d+$/;
const QUERY_KEY_RE = /^[A-Za-z0-9_]+$/;
const FILTERS_RE = /^[A-Za-z0-9%_.:{}\[\],"=+-]*$/;
const TIME_RE = /^[0-9A-Za-z:.TZ+-]{1,64}$/;
const BOOL01 = new Set(["0", "1"]);

/** Query keys each route may carry. Nothing outside this table is accepted. */
export const ROUTE_QUERY_KEYS = {
  ping: new Set([]),
  containers_json: new Set(["all", "filters"]),
  container_json: new Set([]),
  container_logs: new Set(["stdout", "stderr", "tail", "timestamps", "since", "until"]),
  container_restart: new Set(["t"]),
};

export class Denied extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function deny(code) {
  throw new Denied(code);
}

/**
 * Parse a raw request head (everything up to, but not including, the final
 * CRLFCRLF). Strict on purpose: ASCII only, no bare LF, no obs-fold, no
 * duplicate Content-Length, no Transfer-Encoding, no Upgrade, no body.
 */
export function parseHead(raw) {
  if (typeof raw !== "string" || raw.length === 0) deny("empty_request");
  const lines = raw.split(CRLF);
  for (const line of lines) {
    if (line.includes("\n") || line.includes("\r")) deny("bare_lf");
    for (let i = 0; i < line.length; i += 1) {
      const c = line.charCodeAt(i);
      if (c < 0x20 || c === 0x7f || c > 0x7e) deny("non_ascii_or_control");
    }
  }

  const m = /^(GET|POST|HEAD) (\S+) (HTTP\/1\.1)$/.exec(lines[0]);
  if (!m) deny("bad_request_line");
  const method = m[1];
  const target = m[2];

  const headers = new Map();
  let contentLengths = 0;
  for (let i = 1; i < lines.length; i += 1) {
    const hm = /^([A-Za-z0-9-]+): ?(.*)$/.exec(lines[i]);
    if (!hm) deny("bad_header_line");
    const name = hm[1].toLowerCase();
    const value = hm[2];
    if (name === "content-length") {
      contentLengths += 1;
      if (contentLengths > 1) deny("duplicate_content_length");
    }
    if (name === "transfer-encoding") deny("transfer_encoding");
    if (name === "upgrade") deny("upgrade");
    if (name === "connection" && /upgrade/i.test(value)) deny("connection_upgrade");
    if (!headers.has(name)) headers.set(name, value);
  }

  const cl = headers.get("content-length");
  if (cl !== undefined && cl.trim() !== "0") deny("body_not_allowed");

  return { method, target, headers };
}

/** Split a request target into path segments and a validated query map. */
function splitTarget(target) {
  const qIdx = target.indexOf("?");
  const rawPath = qIdx === -1 ? target : target.slice(0, qIdx);
  const rawQuery = qIdx === -1 ? "" : target.slice(qIdx + 1);

  if (rawQuery.includes("?")) deny("double_question");
  if (qIdx !== -1 && rawQuery === "") deny("empty_query");
  if (!rawPath.startsWith("/")) deny("target_not_absolute");
  if (rawPath.includes("%")) deny("encoded_path");
  if (rawPath.includes("..")) deny("dotdot_segment");
  if (rawPath.includes("\\")) deny("backslash");
  if (rawPath.length > 1 && rawPath.endsWith("/")) deny("trailing_slash");

  const segments = rawPath.split("/").slice(1);
  if (segments.length === 0 || segments.some((s) => s === "")) deny("empty_segment");
  if (segments.some((s) => s === ".")) deny("dot_segment");
  if (segments.length > 1 && VERSION_RE.test(segments[0])) segments.shift();

  const query = new Map();
  if (rawQuery.length > MAX_QUERY_BYTES) deny("query_too_long");
  if (rawQuery !== "") {
    for (const item of rawQuery.split("&")) {
      if (item === "") deny("empty_query_item");
      const eq = item.indexOf("=");
      const key = eq === -1 ? item : item.slice(0, eq);
      const value = eq === -1 ? "" : item.slice(eq + 1);
      if (!QUERY_KEY_RE.test(key)) deny("bad_query_key");
      if (query.has(key)) deny("duplicate_query_key");
      query.set(key, value);
    }
  }
  return { segments, query };
}

/**
 * Pure decision function: may this method+target be forwarded?
 * Returns `{ route, ref, query }` on success, throws `Denied` otherwise.
 */
export function classify(method, target, cfg) {
  const { segments, query } = splitTarget(target);
  const allowed = resolveRestartAllow(cfg);

  const keysAllowed = (route) => {
    for (const key of query.keys()) {
      if (!ROUTE_QUERY_KEYS[route].has(key)) deny(`query_key_not_allowed:${key}`);
    }
  };

  // GET|HEAD /_ping — the Docker CLI's API-version negotiation / health probe.
  // Documented exception: it is read-only and returns only the API version.
  if (segments.length === 1 && segments[0] === "_ping") {
    if (method !== "GET" && method !== "HEAD") deny("method_not_allowed");
    keysAllowed("ping");
    return { route: "ping", ref: null, query };
  }

  if (segments[0] !== "containers") deny("route_not_allowed");

  // GET /containers/json
  if (segments.length === 2 && segments[1] === "json") {
    if (method !== "GET") deny("method_not_allowed");
    keysAllowed("containers_json");
    const all = query.get("all");
    if (all !== undefined && !BOOL01.has(all)) deny("bad_all_value");
    const filters = query.get("filters");
    if (filters !== undefined) {
      if (filters.length > MAX_FILTERS_BYTES) deny("filters_too_long");
      if (!FILTERS_RE.test(filters)) deny("bad_filters_value");
    }
    return { route: "containers_json", ref: null, query };
  }

  if (segments.length !== 3) deny("route_not_allowed");
  const ref = segments[1];
  if (!REF_RE.test(ref)) deny("bad_container_ref");
  const action = segments[2];

  if (action === "json") {
    if (method !== "GET") deny("method_not_allowed");
    keysAllowed("container_json");
    return { route: "container_json", ref, query };
  }

  if (action === "logs") {
    if (method !== "GET") deny("method_not_allowed");
    keysAllowed("container_logs");
    const tail = query.get("tail");
    if (tail !== undefined) {
      if (!/^\d{1,4}$/.test(tail)) deny("bad_tail_value");
      if (Number(tail) > 300) deny("tail_too_large");
    }
    for (const key of ["stdout", "stderr", "timestamps"]) {
      const value = query.get(key);
      if (value !== undefined && !BOOL01.has(value)) deny(`bad_${key}_value`);
    }
    for (const key of ["since", "until"]) {
      const value = query.get(key);
      if (value !== undefined && !TIME_RE.test(value)) deny(`bad_${key}_value`);
    }
    return { route: "container_logs", ref, query };
  }

  if (action === "restart") {
    if (method !== "POST") deny("method_not_allowed");
    keysAllowed("container_restart");
    // Exact name match only: no ids, no prefixes, no globs.
    if (!allowed.includes(ref)) deny("container_not_restartable");
    const t = query.get("t");
    if (t !== undefined) {
      if (!/^\d{1,2}$/.test(t)) deny("bad_restart_timeout");
      if (Number(t) > 30) deny("restart_timeout_too_large");
    }
    return { route: "container_restart", ref, query, restarts: restartTierOf(ref, cfg) };
  }

  deny("route_not_allowed");
  return null;
}

function statusText(status) {
  return status === 403 ? "Forbidden" : status === 502 ? "Bad Gateway" : "Error";
}

/** Send a small JSON error and close. */
export function sendError(socket, status, code) {
  const body = JSON.stringify({ message: `docker-socket-filter: ${code}`, code });
  socket.end(
    `HTTP/1.1 ${status} ${statusText(status)}${CRLF}` +
      `Content-Type: application/json${CRLF}` +
      `Content-Length: ${Buffer.byteLength(body)}${CRLF}` +
      `Connection: close${CRLF}${CRLF}${body}`,
  );
}

/**
 * Re-frame an upstream response for the client: drop hop-by-hop headers,
 * recompute Content-Length (so framing can never be ambiguous) and keep the
 * connection alive. Returns null when the upstream bytes are not HTTP.
 */
export function rewriteResponse(raw, method, opts = {}) {
  const idx = raw.indexOf(CRLFCRLF);
  if (idx === -1) return null;
  const lines = raw.subarray(0, idx).toString("latin1").split(CRLF);
  const statusLine = lines[0];
  if (!/^HTTP\/1\.[01] \d{3}\b/.test(statusLine)) return null;
  let body = raw.subarray(idx + 4);
  const drop = new Set(["connection", "keep-alive", "transfer-encoding", "content-length", "upgrade", "proxy-connection", "trailer"]);
  const kept = [];
  let chunked = false;
  for (let i = 1; i < lines.length; i += 1) {
    const m = /^([A-Za-z0-9-]+): ?(.*)$/.exec(lines[i]);
    if (!m) continue;
    if (m[1].toLowerCase() === "transfer-encoding" && /chunked/i.test(m[2])) chunked = true;
    if (drop.has(m[1].toLowerCase())) continue;
    kept.push(lines[i]);
  }
  // dockerd answers larger bodies with `Transfer-Encoding: chunked`. We re-frame
  // with a plain Content-Length, so the chunk framing must be removed first or
  // the client sees the hex size lines as part of the JSON.
  if (chunked) {
    body = dechunk(body);
    if (body === null) return null;
  }
  if (opts.transformBody && /^HTTP\/1\.[01] 200\b/.test(statusLine) && method !== "HEAD") {
    body = opts.transformBody(body);
    if (body === null) return null;
  }
  const bodyLen = method === "HEAD" ? 0 : body.length;
  const head = [statusLine, ...kept, `Content-Length: ${bodyLen}`, "Connection: keep-alive", "X-Ops-Filter: docker-socket-filter", "", ""].join(CRLF);
  const parts = [Buffer.from(head, "latin1")];
  if (bodyLen > 0) parts.push(body);
  return Buffer.concat(parts);
}

const CRLF_BUF = Buffer.from(CRLF, "latin1");

/**
 * Decode an HTTP/1.1 chunked body. Strict: hex size only (no chunk extensions),
 * every chunk CRLF-terminated, terminated by a 0 chunk. Trailers are ignored.
 * Returns null on any malformed framing.
 */
export function dechunk(buf) {
  const parts = [];
  let pos = 0;
  for (;;) {
    const eol = buf.indexOf(CRLF_BUF, pos);
    if (eol === -1) return null;
    const sizeStr = buf.subarray(pos, eol).toString("latin1");
    if (!/^[0-9a-fA-F]{1,8}$/.test(sizeStr)) return null;
    const size = parseInt(sizeStr, 16);
    pos = eol + 2;
    if (size === 0) return Buffer.concat(parts);
    if (pos + size + 2 > buf.length) return null;
    parts.push(buf.subarray(pos, pos + size));
    pos += size;
    if (buf[pos] !== 13 || buf[pos + 1] !== 10) return null;
    pos += 2;
  }
}

/**
 * `GET /containers/<name>/json` returns the full container config, including
 * `Config.Env` (database passwords, tokens). The ops helper never needs it, so
 * the filter removes it: a bug in or a compromise of a caller can then not read
 * secrets through the inspect route. Returns null if the body is not JSON.
 */
export function stripInspectEnv(body) {
  try {
    const obj = JSON.parse(body.toString("utf8"));
    if (obj && typeof obj === "object" && obj.Config && typeof obj.Config === "object") {
      obj.Config.Env = [];
    }
    return Buffer.from(JSON.stringify(obj), "utf8");
  } catch {
    return null;
  }
}

function connectUnix(path) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(path);
    sock.once("connect", () => resolve(sock));
    sock.once("error", reject);
  });
}

/** Read a full upstream response (Connection: close) with a hard size cap. */
function readUpstream(sock, maxBytes) {
  return new Promise((resolve) => {
    const chunks = [];
    let total = 0;
    let overflow = false;
    let done = false;
    const finish = (error) => {
      if (done) return;
      done = true;
      sock.destroy();
      resolve({ chunks, total, overflow, error });
    };
    sock.on("data", (c) => {
      if (overflow) return;
      total += c.length;
      if (total > maxBytes) {
        overflow = true;
        finish("response_too_large");
        return;
      }
      chunks.push(c);
    });
    sock.on("end", () => finish(null));
    sock.on("close", () => finish(null));
    sock.on("error", (err) => finish(err.message));
  });
}

/**
 * The filter server. `cfg`:
 *   upstreamSocketPath, restartAllow[], maxResponseBytes, maxHeadBytes,
 *   maxLogTarget, log(entry)
 */
export function createFilterServer(cfg) {
  const log = cfg.log ?? (() => {});
  const maxHeadBytes = cfg.maxHeadBytes ?? MAX_HEAD_BYTES;
  const maxResponseBytes = cfg.maxResponseBytes ?? DEFAULT_MAX_RESPONSE_BYTES;
  const maxLogTarget = cfg.maxLogTarget ?? DEFAULT_MAX_LOG_TARGET;

  const server = net.createServer((socket) => {
    socket.setNoDelay(true);
    let buf = Buffer.alloc(0);
    let busy = false;
    let closed = false;

    socket.on("error", () => {});
    socket.on("close", () => {
      closed = true;
    });

    const record = (entry) => {
      log({ ...entry, remote: socket.remoteAddress ?? null });
    };

    // `socket.end()` flushes what is already queued (an earlier allowed
    // response may still be in flight) and only then closes; an immediate
    // `destroy()` could drop the 403 that is the whole point of this path.
    const fail = (status, code) => {
      closed = true;
      sendError(socket, status, code);
      socket.setTimeout(2000, () => socket.destroy());
    };

    const reject = (code, target, method) => {
      record({ event: "denied", code, method, target: (target ?? "").slice(0, maxLogTarget) });
      fail(403, code);
    };

    const pump = async () => {
      if (busy || closed) return;
      busy = true;
      try {
        for (;;) {
          const idx = buf.indexOf(CRLFCRLF);
          if (idx === -1) {
            if (buf.length > maxHeadBytes) {
              reject("head_too_large", null, null);
            }
            return;
          }
          const head = buf.subarray(0, idx).toString("latin1");
          buf = buf.subarray(idx + 4);

          let req;
          try {
            req = parseHead(head);
          } catch (err) {
            reject(err.code ?? "bad_request", head.slice(0, maxLogTarget), null);
            return;
          }
          let route;
          try {
            route = classify(req.method, req.target, cfg);
          } catch (err) {
            reject(err.code ?? "denied", req.target, req.method);
            return;
          }

          record({ event: "allowed", route: route.route, ref: route.ref, restarts: route.restarts, method: req.method, target: req.target.slice(0, maxLogTarget) });

          const upstream = await connectUnix(cfg.upstreamSocketPath).catch(() => null);
          if (!upstream) {
            record({ event: "error", code: "upstream_unreachable", method: req.method, target: req.target.slice(0, maxLogTarget) });
            fail(502, "upstream_unreachable");
            return;
          }
          const headLines = [`${req.method} ${req.target} HTTP/1.1`, "Host: docker", "Connection: close"];
          if (req.method === "POST") headLines.push("Content-Length: 0");
          upstream.write(headLines.join(CRLF) + CRLF + CRLF);

          const { chunks, overflow, error } = await readUpstream(upstream, maxResponseBytes);
          if (overflow) {
            record({ event: "error", code: "response_too_large", route: route.route });
            fail(502, "response_too_large");
            return;
          }
          if (error && chunks.length === 0) {
            record({ event: "error", code: "upstream_error", detail: String(error).slice(0, 120) });
            fail(502, "upstream_error");
            return;
          }
          const out = rewriteResponse(
            Buffer.concat(chunks),
            req.method,
            route.route === "container_json" ? { transformBody: stripInspectEnv } : undefined,
          );
          if (!out) {
            record({ event: "error", code: "bad_upstream_response" });
            fail(502, "bad_upstream_response");
            return;
          }
          socket.write(out);
        }
      } finally {
        busy = false;
        if (!closed && buf.length > 0) queueMicrotask(pump);
      }
    };

    socket.on("data", (chunk) => {
      if (closed) return;
      buf = Buffer.concat([buf, chunk]);
      if (buf.length > maxHeadBytes && buf.indexOf(CRLFCRLF) === -1) {
        reject("head_too_large", null, null);
        return;
      }
      void pump();
    });
  });

  server.on("error", (err) => log({ event: "error", code: "listen_error", detail: err.message }));
  return server;
}

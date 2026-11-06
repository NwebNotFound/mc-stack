/**
 * Request-origin and brute-force protection for the bearer-token auth.
 *
 * `resolveClientIp` trusts the `X-Forwarded-For` header ONLY when the immediate
 * socket peer is one of the configured trusted proxies (the tunnel / reverse
 * proxy). Without a configured trust list the socket address is used verbatim,
 * so a client cannot spoof its own identity with a header.
 *
 * `BruteForceGuard` remembers consecutive bad tokens per client IP and locks the
 * IP out with an exponential backoff (in-memory, one process).
 */
import type { Request } from "express";

export function normalizeIp(ip: string | undefined | null): string {
  if (!ip) return "";
  let v = ip.trim().toLowerCase();
  if (v.startsWith("::ffff:")) v = v.slice(7);
  const zone = v.indexOf("%");
  if (zone !== -1) v = v.slice(0, zone);
  return v;
}

function ipv4ToBigInt(ip: string): bigint | null {
  const parts = ip.split(".");
  if (parts.length !== 4) return null;
  let n = 0n;
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return null;
    const octet = Number(part);
    if (octet > 255) return null;
    n = (n << 8n) | BigInt(octet);
  }
  return n;
}

function ipv6ToBigInt(ip: string): bigint | null {
  if (!ip.includes(":")) return null;
  const halves = ip.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const groups: string[] = [];
  for (const g of head) groups.push(g);
  if (halves.length === 2) {
    const missing = 8 - head.length - tail.length;
    if (missing < 0) return null;
    for (let i = 0; i < missing; i += 1) groups.push("0");
  }
  for (const g of tail) groups.push(g);
  if (groups.length !== 8) return null;
  let n = 0n;
  for (const g of groups) {
    if (!/^[0-9a-f]{1,4}$/.test(g)) return null;
    n = (n << 16n) | BigInt(parseInt(g, 16));
  }
  return n;
}

function ipToBigInt(ip: string): bigint | null {
  return ip.includes(":") ? ipv6ToBigInt(ip) : ipv4ToBigInt(ip);
}

/** True when `ip` falls inside `cidr` (an exact address or `addr/prefix`). */
export function ipMatches(ip: string, cidr: string): boolean {
  const target = normalizeIp(ip);
  const spec = normalizeIp(cidr);
  if (!target || !spec) return false;
  const slash = spec.indexOf("/");
  const netStr = slash === -1 ? spec : spec.slice(0, slash);
  const v6 = netStr.includes(":");
  const bits = v6 ? 128 : 32;
  const targetNum = ipToBigInt(target);
  const netNum = ipToBigInt(netStr);
  if (targetNum === null || netNum === null) return false;
  if (v6 !== target.includes(":")) return false;
  let prefix = bits;
  if (slash !== -1) {
    if (!/^\d{1,3}$/.test(spec.slice(slash + 1))) return false;
    prefix = Number(spec.slice(slash + 1));
    if (prefix < 0 || prefix > bits) return false;
  }
  const shift = BigInt(bits - prefix);
  if (shift === 0n) return targetNum === netNum;
  return targetNum >> shift === netNum >> shift;
}

export function isTrustedProxy(ip: string, trusted: string[]): boolean {
  return trusted.some((entry) => ipMatches(ip, entry));
}

/** True when `ip` is a syntactically valid IPv4/IPv6 address. */
export function isIp(ip: string): boolean {
  const v = normalizeIp(ip);
  return v !== "" && ipToBigInt(v) !== null;
}

/**
 * Client IP for logging/limiting. Proxy headers are only consulted when the
 * direct socket peer is a trusted proxy (`trusted`: configured IPs/CIDRs, plus
 * `extraTrusted`: e.g. the resolved addresses of the cloudflared-mcp container).
 *
 * Behind a trusted proxy:
 *  - `CF-Connecting-IP` wins when it is a valid IP. Cloudflare's edge sets it
 *    to the real client and overwrites any client-supplied value, and
 *    cloudflared passes it through, so through the tunnel every internet caller
 *    gets their OWN key instead of all sharing the cloudflared container's;
 *  - otherwise the right-most untrusted `X-Forwarded-For` hop, so a client
 *    cannot prepend a fake address.
 */
export function resolveClientIp(req: Request, trusted: string[], extraTrusted: string[] = []): string {
  const socketIp = normalizeIp(req.socket?.remoteAddress ?? "");
  const all = extraTrusted.length > 0 ? [...trusted, ...extraTrusted] : trusted;
  if (all.length === 0 || !isTrustedProxy(socketIp, all)) {
    return socketIp || "unknown";
  }
  const cf = req.headers["cf-connecting-ip"];
  const cfValue = Array.isArray(cf) ? (cf.length === 1 ? cf[0] : "") : String(cf ?? "");
  if (cfValue && isIp(cfValue.trim())) return normalizeIp(cfValue);
  const header = req.headers["x-forwarded-for"];
  const raw = Array.isArray(header) ? header.join(",") : String(header ?? "");
  const chain = raw
    .split(",")
    .map((part) => normalizeIp(part))
    .filter(Boolean);
  chain.push(socketIp);
  for (let i = chain.length - 1; i >= 0; i -= 1) {
    if (!isTrustedProxy(chain[i], all)) return chain[i];
  }
  return chain[0] || socketIp || "unknown";
}

type LookupAll = (host: string) => Promise<Array<{ address: string }>>;

/**
 * Resolves the compose service names of trusted proxies (default
 * `cloudflared-mcp`) to their current container addresses and refreshes them
 * periodically, so the tunnel peer is recognised without hard-coding a subnet.
 * A name that does not resolve (e.g. the tunnel profile is not running) simply
 * contributes nothing.
 */
export class ProxyHostResolver {
  private current: string[] = [];
  private timer: NodeJS.Timeout | null = null;

  constructor(
    private readonly hostnames: string[],
    private readonly lookup: LookupAll,
  ) {}

  addresses(): string[] {
    return this.current;
  }

  async refresh(): Promise<string[]> {
    const next = new Set<string>();
    for (const host of this.hostnames) {
      try {
        for (const entry of await this.lookup(host)) {
          if (isIp(entry.address)) next.add(normalizeIp(entry.address));
        }
      } catch {
        // not resolvable right now: contributes nothing
      }
    }
    this.current = [...next];
    return this.current;
  }

  start(intervalMs = 30_000): void {
    if (this.hostnames.length === 0 || this.timer) return;
    void this.refresh();
    this.timer = setInterval(() => void this.refresh(), intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
}

export interface BruteForceOptions {
  /** Failures before the IP is locked out (must be >= 1). */
  threshold: number;
  /** Lockout length for the first block; doubles per further failure. */
  baseSeconds: number;
  /** Upper bound for the exponential lockout. */
  maxSeconds: number;
  /** Failures older than this are forgotten (sliding window). */
  windowSeconds: number;
}

interface Entry {
  failures: number;
  lockedUntil: number;
  lastFailure: number;
}

export interface LockoutVerdict {
  retryAfterSeconds: number;
}

const MAX_TRACKED_IPS = 20_000;

/** In-memory consecutive-failure brake with exponential backoff. */
export class BruteForceGuard {
  private readonly state = new Map<string, Entry>();

  constructor(private readonly opts: BruteForceOptions) {}

  /** Returns the active lockout, or null when the key may proceed. */
  check(key: string, now = Date.now()): LockoutVerdict | null {
    const entry = this.state.get(key);
    if (!entry) return null;
    if (entry.lockedUntil > now) {
      return { retryAfterSeconds: Math.ceil((entry.lockedUntil - now) / 1000) };
    }
    return null;
  }

  /** Record one failed attempt; arms/lengthens the lockout at the threshold. */
  failure(key: string, now = Date.now()): void {
    this.prune(now);
    let entry = this.state.get(key);
    if (!entry || now - entry.lastFailure > this.opts.windowSeconds * 1000) {
      entry = { failures: 0, lockedUntil: 0, lastFailure: now };
    }
    entry.failures += 1;
    entry.lastFailure = now;
    if (entry.failures >= this.opts.threshold) {
      const over = entry.failures - this.opts.threshold;
      const seconds = Math.min(this.opts.maxSeconds, this.opts.baseSeconds * 2 ** over);
      entry.lockedUntil = now + seconds * 1000;
    }
    this.state.set(key, entry);
  }

  /** A valid token clears the counter for that IP. */
  success(key: string): void {
    this.state.delete(key);
  }

  private prune(now: number): void {
    if (this.state.size < MAX_TRACKED_IPS) return;
    const cutoff = now - this.opts.windowSeconds * 1000;
    for (const [key, entry] of this.state) {
      if (entry.lockedUntil < now && entry.lastFailure < cutoff) this.state.delete(key);
    }
  }
}

/** One local interface address as `os.networkInterfaces()` reports it. */
export interface LocalAddress {
  address: string;
  cidr: string | null;
  family: string | number;
  internal: boolean;
}

function isLoopback(ip: string): boolean {
  return ipMatches(ip, "127.0.0.0/8") || ip === "::1";
}

/** First host address of an IPv4 CIDR (Docker's default bridge gateway). */
export function gatewayOf(cidr: string): string | null {
  const m = /^(\d{1,3}(?:\.\d{1,3}){3})\/(\d{1,2})$/.exec(cidr);
  if (!m) return null;
  const prefix = Number(m[2]);
  if (prefix > 30) return null;
  const n = ipv4ToBigInt(m[1]);
  if (n === null) return null;
  const mask = ((1n << 32n) - 1n) ^ ((1n << BigInt(32 - prefix)) - 1n);
  const gw = (n & mask) + 1n;
  return [24n, 16n, 8n, 0n].map((shift) => String((gw >> shift) & 255n)).join(".");
}

/**
 * Peer check for the T2-capable local listener (issue #141).
 *
 * The listener binds 0.0.0.0 inside the container, so every container that
 * shares one of hermes-mcp's networks (cloudflared-mcp in `mcp`, or any
 * container of another compose project that joins the fixed-name `mcp`
 * network) could otherwise reach it at `hermes-mcp:8767`. The intended client
 * comes in through the host port mapping `127.0.0.1:8767`, which Docker
 * delivers from the bridge GATEWAY (or from loopback / a Docker Desktop proxy
 * address outside the container's subnets), never from a sibling container.
 *
 * Rule:
 *  - loopback is always allowed;
 *  - with an explicit allowlist (HERMES_MCP_OPS_ALLOWED_PEERS), ONLY those
 *    addresses/CIDRs (plus loopback) are allowed;
 *  - otherwise a peer inside one of the container's own subnets is refused
 *    unless it is that subnet's gateway (first host address). Peers outside
 *    every attached subnet can only arrive via the host's port mapping.
 */
export function opsPeerAllowed(peer: string, locals: LocalAddress[], explicit: string[] = []): boolean {
  const ip = normalizeIp(peer);
  if (!ip) return false;
  if (isLoopback(ip)) return true;
  if (explicit.length > 0) return isTrustedProxy(ip, explicit);
  for (const local of locals) {
    if (local.internal || !local.cidr) continue;
    if (!ipMatches(ip, local.cidr)) continue;
    const gw = gatewayOf(local.cidr);
    return gw !== null && gw === ip;
  }
  return true;
}

/** Host header check for the local listener: only loopback names by default. */
export function opsHostAllowed(hostHeader: string | undefined, allowed: string[]): boolean {
  const raw = (hostHeader ?? "").trim().toLowerCase();
  if (!raw) return false;
  let host = raw;
  if (host.startsWith("[")) {
    const end = host.indexOf("]");
    host = end === -1 ? host : host.slice(1, end);
  } else {
    host = host.split(":")[0];
  }
  return allowed.includes(host);
}

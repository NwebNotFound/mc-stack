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

/**
 * Client IP for logging/limiting. `X-Forwarded-For` is only consulted when the
 * direct socket peer is a trusted proxy; the right-most untrusted hop wins, so a
 * client cannot prepend a fake address.
 */
export function resolveClientIp(req: Request, trusted: string[]): string {
  const socketIp = normalizeIp(req.socket?.remoteAddress ?? "");
  if (trusted.length === 0 || !isTrustedProxy(socketIp, trusted)) {
    return socketIp || "unknown";
  }
  const header = req.headers["x-forwarded-for"];
  const raw = Array.isArray(header) ? header.join(",") : String(header ?? "");
  const chain = raw
    .split(",")
    .map((part) => normalizeIp(part))
    .filter(Boolean);
  chain.push(socketIp);
  for (let i = chain.length - 1; i >= 0; i -= 1) {
    if (!isTrustedProxy(chain[i], trusted)) return chain[i];
  }
  return chain[0] || socketIp || "unknown";
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

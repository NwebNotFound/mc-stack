import assert from "node:assert/strict";
import { test } from "node:test";
import { BruteForceGuard, gatewayOf, ipMatches, isIp, isTrustedProxy, normalizeIp, opsHostAllowed, opsPeerAllowed, ProxyHostResolver, resolveClientIp } from "../dist/security.js";

test("normalizeIp strips the IPv4-mapped prefix", () => {
  assert.equal(normalizeIp("::ffff:10.0.0.5"), "10.0.0.5");
  assert.equal(normalizeIp("10.0.0.5"), "10.0.0.5");
});

test("ipMatches handles exact addresses and CIDRs", () => {
  assert.equal(ipMatches("172.18.0.4", "172.16.0.0/12"), true);
  assert.equal(ipMatches("10.0.0.4", "172.16.0.0/12"), false);
  assert.equal(ipMatches("192.168.1.5", "192.168.1.5"), true);
  assert.equal(ipMatches("192.168.1.6", "192.168.1.5"), false);
  assert.equal(ipMatches("2001:db8::1", "2001:db8::/32"), true);
  assert.equal(ipMatches("2001:dead::1", "2001:db8::/32"), false);
  assert.equal(isTrustedProxy("172.18.0.9", ["172.16.0.0/12"]), true);
});

function fakeReq(remote, xff) {
  return { socket: { remoteAddress: remote }, headers: xff === undefined ? {} : { "x-forwarded-for": xff } };
}

test("without a trusted proxy the socket address wins and XFF is ignored", () => {
  assert.equal(resolveClientIp(fakeReq("1.2.3.4", "9.9.9.9"), []), "1.2.3.4");
});

test("an untrusted peer cannot spoof its address via X-Forwarded-For", () => {
  assert.equal(resolveClientIp(fakeReq("1.2.3.4", "9.9.9.9"), ["10.0.0.0/8"]), "1.2.3.4");
});

test("behind a trusted proxy the right-most untrusted hop is the client", () => {
  // socket = trusted proxy, XFF = client, proxy2 -> client is left-most untrusted from the right
  assert.equal(resolveClientIp(fakeReq("172.18.0.8", "203.0.113.7, 172.18.0.9"), ["172.16.0.0/12"]), "203.0.113.7");
  // a client that prepends a fake address: the real one is still the right-most untrusted hop
  assert.equal(resolveClientIp(fakeReq("172.18.0.8", "9.9.9.9, 203.0.113.7"), ["172.16.0.0/12"]), "203.0.113.7");
});

test("BruteForceGuard locks out after the threshold and clears on success", () => {
  const guard = new BruteForceGuard({ threshold: 3, baseSeconds: 5, maxSeconds: 300, windowSeconds: 900 });
  const t0 = 1_000_000_000_000;
  assert.equal(guard.check("ip", t0), null);
  guard.failure("ip", t0);
  guard.failure("ip", t0 + 10);
  assert.equal(guard.check("ip", t0 + 20), null, "still under the threshold");
  guard.failure("ip", t0 + 30);
  const verdict = guard.check("ip", t0 + 31);
  assert.ok(verdict, "locked after the threshold");
  assert.equal(verdict.retryAfterSeconds, 5);
  guard.success("ip");
  assert.equal(guard.check("ip", t0 + 40), null, "success resets the counter");
});

test("BruteForceGuard backoff is exponential and capped", () => {
  const guard = new BruteForceGuard({ threshold: 1, baseSeconds: 10, maxSeconds: 40, windowSeconds: 900 });
  const t0 = 1_000_000_000_000;
  guard.failure("ip", t0); // 1st: 10s
  assert.equal(guard.check("ip", t0)?.retryAfterSeconds, 10);
  guard.failure("ip", t0 + 1); // 2nd: 20s
  assert.equal(guard.check("ip", t0 + 2)?.retryAfterSeconds, 20);
  guard.failure("ip", t0 + 3); // 3rd: 40s
  assert.equal(guard.check("ip", t0 + 4)?.retryAfterSeconds, 40);
  guard.failure("ip", t0 + 5); // 4th: would be 80s but capped at 40s
  assert.equal(guard.check("ip", t0 + 6)?.retryAfterSeconds, 40);
});

test("BruteForceGuard forgets failures outside the window", () => {
  const guard = new BruteForceGuard({ threshold: 2, baseSeconds: 5, maxSeconds: 300, windowSeconds: 60 });
  const t0 = 1_000_000_000_000;
  guard.failure("ip", t0);
  guard.failure("ip", t0 + 120_000); // 2 min later: window expired, counter restarts at 1
  assert.equal(guard.check("ip", t0 + 120_001), null);
});

const LOCALS = [
  { address: "127.0.0.1", cidr: "127.0.0.1/8", family: "IPv4", internal: true },
  { address: "172.20.0.3", cidr: "172.20.0.3/16", family: "IPv4", internal: false }, // mcp
  { address: "172.21.0.2", cidr: "172.21.0.2/24", family: "IPv4", internal: false }, // ops
];

test("gatewayOf returns the first host address of an IPv4 subnet", () => {
  assert.equal(gatewayOf("172.20.0.3/16"), "172.20.0.1");
  assert.equal(gatewayOf("172.21.0.130/25"), "172.21.0.129");
  assert.equal(gatewayOf("10.0.0.1/32"), null);
  assert.equal(gatewayOf("fd00::1/64"), null);
});

test("ops listener refuses sibling containers but allows the host port mapping", () => {
  // cloudflared-mcp (or any container on `mcp`) -> refused
  assert.equal(opsPeerAllowed("172.20.0.7", LOCALS), false);
  assert.equal(opsPeerAllowed("::ffff:172.21.0.9", LOCALS), false);
  // the bridge gateway (Docker's userland proxy for 127.0.0.1:8767) -> allowed
  assert.equal(opsPeerAllowed("172.20.0.1", LOCALS), true);
  assert.equal(opsPeerAllowed("::ffff:172.21.0.1", LOCALS), true);
  // loopback and addresses outside every attached subnet (Docker Desktop proxy) -> allowed
  assert.equal(opsPeerAllowed("127.0.0.1", LOCALS), true);
  assert.equal(opsPeerAllowed("::1", LOCALS), true);
  assert.equal(opsPeerAllowed("192.168.65.1", LOCALS), true);
  assert.equal(opsPeerAllowed("", LOCALS), false);
});

test("an explicit ops peer allowlist replaces the built-in rule", () => {
  assert.equal(opsPeerAllowed("192.168.65.1", LOCALS, ["172.20.0.1"]), false);
  assert.equal(opsPeerAllowed("172.20.0.1", LOCALS, ["172.20.0.1"]), true);
  assert.equal(opsPeerAllowed("127.0.0.1", LOCALS, ["172.20.0.1"]), true, "loopback always");
});

test("ops listener only accepts loopback Host headers", () => {
  const allowed = ["localhost", "127.0.0.1", "::1"];
  assert.equal(opsHostAllowed("127.0.0.1:8767", allowed), true);
  assert.equal(opsHostAllowed("localhost", allowed), true);
  assert.equal(opsHostAllowed("[::1]:8767", allowed), true);
  assert.equal(opsHostAllowed("mcp.nwebnotfound.dev", allowed), false);
  assert.equal(opsHostAllowed("hermes-mcp:8767", allowed), false);
  assert.equal(opsHostAllowed(undefined, allowed), false);
});

function cfReq(remote, cf, xff) {
  const headers = {};
  if (cf !== undefined) headers["cf-connecting-ip"] = cf;
  if (xff !== undefined) headers["x-forwarded-for"] = xff;
  return { socket: { remoteAddress: remote }, headers };
}

test("through a trusted tunnel peer every caller gets its own key (CF-Connecting-IP)", () => {
  const tunnel = ["172.20.0.5"];
  assert.equal(resolveClientIp(cfReq("172.20.0.5", "203.0.113.7"), [], tunnel), "203.0.113.7");
  assert.equal(resolveClientIp(cfReq("172.20.0.5", "198.51.100.9"), [], tunnel), "198.51.100.9");
  // garbage in the header falls back to XFF / the peer
  assert.equal(resolveClientIp(cfReq("172.20.0.5", "not-an-ip", "203.0.113.8"), [], tunnel), "203.0.113.8");
  assert.equal(resolveClientIp(cfReq("172.20.0.5", "not-an-ip"), [], tunnel), "172.20.0.5");
  // an untrusted peer cannot choose its key with the header
  assert.equal(resolveClientIp(cfReq("172.20.0.6", "203.0.113.7"), [], tunnel), "172.20.0.6");
  assert.equal(resolveClientIp(cfReq("172.20.0.6", "203.0.113.7"), []), "172.20.0.6");
});

test("one tunnel caller's bad tokens no longer lock out another tunnel caller", () => {
  const guard = new BruteForceGuard({ threshold: 5, baseSeconds: 5, maxSeconds: 300, windowSeconds: 900 });
  const tunnel = ["172.20.0.5"];
  const attacker = resolveClientIp(cfReq("172.20.0.5", "203.0.113.66"), [], tunnel);
  const owner = resolveClientIp(cfReq("172.20.0.5", "198.51.100.1"), [], tunnel);
  for (let i = 0; i < 10; i += 1) guard.failure(attacker, 1000);
  assert.ok(guard.check(attacker, 1000));
  assert.equal(guard.check(owner, 1000), null);
});

test("ProxyHostResolver keeps only valid addresses and tolerates lookup failures", async () => {
  const resolver = new ProxyHostResolver(["cloudflared-mcp", "missing"], async (host) => {
    if (host === "missing") throw new Error("ENOTFOUND");
    return [{ address: "172.20.0.5" }, { address: "junk" }];
  });
  assert.deepEqual(resolver.addresses(), []);
  assert.deepEqual(await resolver.refresh(), ["172.20.0.5"]);
  assert.equal(isIp("::ffff:10.1.2.3"), true);
  assert.equal(isIp("10.1.2"), false);
});

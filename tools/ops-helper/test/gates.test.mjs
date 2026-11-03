import assert from "node:assert/strict";
import { test } from "node:test";
import { checkGates } from "../dist/gates.js";

const T0 = { confirm_required: false, armed_required: false, cooldown_seconds: 0 };
const T1 = { confirm_required: true, armed_required: false, cooldown_seconds: 300 };
const T2 = {
  confirm_required: true,
  armed_required: true,
  cooldown_seconds: 1800,
  min_delay_seconds: 60,
  backup_required: true,
};

const action = (tier, over = {}) => ({ name: "ops_test", tier, impl: "restart_game", enabled: true, ...over });

function ctx(over = {}) {
  return {
    armed: false,
    killSwitch: false,
    now: 0,
    cooldownRemaining: () => 0,
    rateHit: () => 1,
    rateWindowSeconds: 60,
    rateMaxCalls: 12,
    ...over,
  };
}

test("T0 passes with no confirm, unarmed and even under the kill switch", () => {
  assert.equal(checkGates(action("T0"), T0, {}, ctx()).ok, true);
  assert.equal(checkGates(action("T0"), T0, {}, ctx({ killSwitch: true })).ok, true);
});

test("a disabled action is refused regardless of tier", () => {
  const decision = checkGates(action("T0", { enabled: false }), T0, {}, ctx());
  assert.equal(decision.ok, false);
  assert.equal(decision.code, "action_disabled");
});

test("a disabled action explains itself with its disabled_reason", () => {
  const withReason = checkGates(
    action("T1", { enabled: false, disabledReason: "paused: needs POST /containers/create" }),
    T1,
    { confirm: true },
    ctx(),
  );
  assert.equal(withReason.code, "action_disabled");
  assert.equal(withReason.message, "paused: needs POST /containers/create");

  const without = checkGates(action("T1", { enabled: false }), T1, { confirm: true }, ctx());
  assert.equal(without.message, "ops_test is disabled in the allowlist");
});

test("T1 needs confirm=true", () => {
  assert.equal(checkGates(action("T1"), T1, {}, ctx()).code, "confirmation_required");
  assert.equal(checkGates(action("T1"), T1, { confirm: false }, ctx()).code, "confirmation_required");
  assert.equal(checkGates(action("T1"), T1, { confirm: "true" }, ctx()).code, "confirmation_required");
  assert.equal(checkGates(action("T1"), T1, { confirm: true }, ctx()).ok, true);
});

test("T2 without the global arm switch is refused, even with confirm and delay", () => {
  const decision = checkGates(action("T2"), T2, { confirm: true, delay_seconds: 60 }, ctx({ armed: false }));
  assert.equal(decision.ok, false);
  assert.equal(decision.code, "not_armed");
});

test("T2 armed but without confirm is refused", () => {
  const decision = checkGates(action("T2"), T2, { delay_seconds: 60 }, ctx({ armed: true }));
  assert.equal(decision.code, "confirmation_required");
});

test("T2 requires an integer delay_seconds >= 60", () => {
  for (const delay of [undefined, null, 0, 30, 59, "60", 60.5, Number.NaN]) {
    const decision = checkGates(action("T2"), T2, { confirm: true, delay_seconds: delay }, ctx({ armed: true }));
    assert.equal(decision.ok, false, `delay ${String(delay)} must be refused`);
    assert.equal(decision.code, "delay_too_short");
  }
  assert.equal(checkGates(action("T2"), T2, { confirm: true, delay_seconds: 60 }, ctx({ armed: true })).ok, true);
  assert.equal(checkGates(action("T2"), T2, { confirm: true, delay_seconds: 600 }, ctx({ armed: true })).ok, true);
});

test("the kill switch stops everything above T0, even an armed T2", () => {
  const decision = checkGates(
    action("T2"),
    T2,
    { confirm: true, delay_seconds: 60 },
    ctx({ armed: true, killSwitch: true }),
  );
  assert.equal(decision.code, "kill_switch_active");
  const t1 = checkGates(action("T1"), T1, { confirm: true }, ctx({ killSwitch: true }));
  assert.equal(t1.code, "kill_switch_active");
});

test("a cooldown is reported with the remaining seconds", () => {
  const decision = checkGates(action("T1"), T1, { confirm: true }, ctx({ cooldownRemaining: () => 42 }));
  assert.equal(decision.code, "cooldown_active");
  assert.equal(decision.retry_after_seconds, 42);
});

test("the per-action rate limit is enforced after the window count", () => {
  assert.equal(checkGates(action("T0"), T0, {}, ctx({ rateHit: () => 12 })).ok, true);
  const decision = checkGates(action("T0"), T0, {}, ctx({ rateHit: () => 13 }));
  assert.equal(decision.code, "rate_limited");
  assert.equal(decision.retry_after_seconds, 60);
});

test("the confirm check runs before the armed check (no information leak order change)", () => {
  const decision = checkGates(action("T2"), T2, {}, ctx({ armed: false }));
  assert.equal(decision.code, "confirmation_required");
});

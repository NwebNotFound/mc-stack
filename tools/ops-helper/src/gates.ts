/**
 * Tier gates - pure functions, no I/O, so every rule is unit-testable.
 *
 * Order of the checks matters: the cheap, hard refusals come first, and the
 * expensive side effects (announcement, backup) are only attempted after every
 * gate passed. A gate never has a bypass flag.
 */
import type { ActionSpec, TierRules } from "./allowlist.js";

export interface GateContext {
  armed: boolean;
  killSwitch: boolean;
  now: number;
  /** Seconds left of the cooldown for a key (0 = free). */
  cooldownRemaining: (key: string, seconds: number) => number;
  /** Records this call and returns how many calls happened inside the window. */
  rateHit: (key: string, windowSeconds: number) => number;
  rateWindowSeconds: number;
  rateMaxCalls: number;
}

export interface GateDecision {
  ok: boolean;
  code?: string;
  message?: string;
  retry_after_seconds?: number;
}

const OK: GateDecision = { ok: true };

function deny(code: string, message: string, retryAfterSeconds?: number): GateDecision {
  return retryAfterSeconds === undefined
    ? { ok: false, code, message }
    : { ok: false, code, message, retry_after_seconds: retryAfterSeconds };
}

/**
 * @param action the allowlist entry (tier + impl)
 * @param rules  the tier rules from the allowlist
 * @param params the raw parameters (only `confirm` / `delay_seconds` are read)
 */
export function checkGates(
  action: ActionSpec,
  rules: TierRules,
  params: Record<string, unknown>,
  ctx: GateContext,
): GateDecision {
  if (!action.enabled) {
    return deny("action_disabled", `${action.name} is disabled in the allowlist`);
  }

  // Kill switch: everything above read-only stops.
  if (ctx.killSwitch && action.tier !== "T0") {
    return deny(
      "kill_switch_active",
      `kill switch is active (${action.tier} refused); remove data/ops-helper/DISABLED or unset OPS_DISABLED`,
    );
  }

  if (rules.confirm_required && params.confirm !== true) {
    return deny("confirmation_required", `${action.name} requires confirm=true`);
  }

  if (rules.armed_required && !ctx.armed) {
    return deny(
      "not_armed",
      `${action.name} is a ${action.tier} action and the helper is not armed (OPS_ARMED=true required)`,
    );
  }

  const minDelay = rules.min_delay_seconds ?? 0;
  if (minDelay > 0) {
    const delay = params.delay_seconds;
    if (typeof delay !== "number" || !Number.isInteger(delay) || delay < minDelay) {
      return deny(
        "delay_too_short",
        `${action.name} requires an integer delay_seconds >= ${minDelay} so the action can be announced`,
      );
    }
  }

  const cooldownKey = `${action.name}`;
  const left = ctx.cooldownRemaining(cooldownKey, rules.cooldown_seconds);
  if (left > 0) {
    return deny("cooldown_active", `${action.name} is cooling down`, left);
  }

  const hits = ctx.rateHit(action.name, ctx.rateWindowSeconds);
  if (hits > ctx.rateMaxCalls) {
    return deny(
      "rate_limited",
      `${action.name} exceeded ${ctx.rateMaxCalls} calls per ${ctx.rateWindowSeconds}s`,
      ctx.rateWindowSeconds,
    );
  }

  return OK;
}

package de.mcstack.craftcore;

import java.util.*;

/** Deterministic combat timing rules. Time values are server ticks; no Bukkit state is touched. */
final class MineRushCombat {
  enum Ability { DASH, GRAB, THROW, LASER, SUMMON_SHIELD, SPIN, GHOST, SLAM, GROUND_FIELD, PROJECTILE_SALVO, TELEPORT, COMBO, ENRAGE, RAGE }
  record Attack(Ability ability, int windup, int recovery, int cooldown, double damage, double radius) {
    Attack {
      Objects.requireNonNull(ability);
      if (windup < 1 || recovery < 0 || cooldown < 1 || !Double.isFinite(damage) || damage < 0 || !Double.isFinite(radius) || radius < 0)
        throw new IllegalArgumentException("Invalid attack parameters");
    }
  }
  record Phase(double healthFraction, List<Attack> attacks) {
    Phase {
      if (!Double.isFinite(healthFraction) || healthFraction <= 0 || healthFraction > 1 || attacks == null || attacks.isEmpty())
        throw new IllegalArgumentException("Invalid phase");
      attacks = List.copyOf(attacks);
    }
  }
  static int phase(double health, double maximum, List<Phase> phases) {
    if (!Double.isFinite(health) || !Double.isFinite(maximum) || maximum <= 0 || phases.isEmpty()) throw new IllegalArgumentException();
    double prior = Double.POSITIVE_INFINITY;
    int chosen = 0;
    for (int i = 0; i < phases.size(); i++) {
      double threshold = phases.get(i).healthFraction();
      if (threshold >= prior || (i == 0 && threshold != 1)) throw new IllegalArgumentException("Phases must start at 1 and descend");
      if (health / maximum <= threshold) chosen = i;
      prior = threshold;
    }
    return chosen;
  }
  static final class Dodge {
    private final double max, cost, regeneration;
    private final long iframes, cooldown;
    private double stamina;
    private long immuneUntil = Long.MIN_VALUE, readyAt = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;
    Dodge(double max, double cost, double regeneration, long iframes, long cooldown) {
      if (!Double.isFinite(max) || !Double.isFinite(cost) || !Double.isFinite(regeneration) || max <= 0 || cost <= 0 || cost > max || regeneration < 0 || iframes < 1 || cooldown < iframes) throw new IllegalArgumentException();
      this.max=max; this.cost=cost; this.regeneration=regeneration; this.iframes=iframes; this.cooldown=cooldown; stamina=max;
    }
    void advance(long tick) {
      if (lastTick != Long.MIN_VALUE) {
        if (tick < lastTick) throw new IllegalArgumentException("Time moved backwards");
        stamina=Math.min(max, stamina + (tick-lastTick)*regeneration);
      }
      lastTick=tick;
    }
    boolean roll(long tick) {
      advance(tick);
      if (tick < readyAt || stamina < cost) return false;
      stamina-=cost; immuneUntil=Math.addExact(tick,iframes); readyAt=Math.addExact(tick,cooldown); return true;
    }
    boolean immune(long tick) { return tick < immuneUntil && tick >= immuneUntil-iframes; }
    double stamina() { return stamina; }
  }
  static final class Poise {
    private final double maximum;
    private double remaining;
    Poise(double maximum) {
      if (!Double.isFinite(maximum) || maximum <= 0) throw new IllegalArgumentException();
      this.maximum=maximum; remaining=maximum;
    }
    boolean hit(double amount) {
      if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException();
      remaining-=amount;
      if (remaining <= 0) { remaining=maximum; return true; }
      return false;
    }
    double remaining() { return remaining; }
  }
  private MineRushCombat() {}
}

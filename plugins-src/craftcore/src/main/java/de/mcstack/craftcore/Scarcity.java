package de.mcstack.craftcore;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.Configuration;

/**
 * Supply and demand factor on top of the trade curve (docs/market-pricing-plan.md §11).
 * {@code s = clamp((ref / (smoothed + 1))^k * (1 + weight * max(0, netDemand) / ref), smin, smax)}.
 * Off by default ({@code market.scarcity.enabled}); disabled or unknown input is always {@code s = 1}.
 */
final class Scarcity {
  record Settings(boolean enabled, double k, double smin, double smax, double uniqueSmax, double emaAlpha,
                  double refCheap, double refMid, double refRare, double refUnique, double demandWeight,
                  int minHolders, long holderTtlMs, Set<String> unique) {}

  private Scarcity() {}

  static Settings settings(Configuration cfg) {
    double k = num(cfg, "market.scarcity.k", 0.5, 0, 4);
    double smin = num(cfg, "market.scarcity.smin", 0.5, 0.01, 1);
    double smax = num(cfg, "market.scarcity.smax", 3.0, 1, 10);
    double uniqueSmax = Math.max(smax, num(cfg, "market.scarcity.unique-smax", 8.0, 1, 20));
    double alpha = num(cfg, "market.scarcity.ema-alpha", 0.25, 0.01, 1);
    double weight = num(cfg, "market.scarcity.demand-weight", 1.0, 0, 10);
    int minHolders = (int) num(cfg, "market.scarcity.min-holders", 3, 1, 1000);
    double ttlDays = num(cfg, "market.scarcity.holder-ttl-days", 30, 1, 365);
    Set<String> unique = new HashSet<>();
    if (cfg != null) for (String name : cfg.getStringList("market.scarcity.unique-items")) unique.add(name.toUpperCase(Locale.ROOT));
    if (unique.isEmpty()) unique.add("DRAGON_EGG");
    boolean enabled = cfg != null && cfg.getBoolean("market.scarcity.enabled", false);
    return new Settings(enabled, k, smin, smax, uniqueSmax, alpha,
      num(cfg, "market.scarcity.ref-cheap", 4096, 1, 1e7), num(cfg, "market.scarcity.ref-mid", 256, 1, 1e7),
      num(cfg, "market.scarcity.ref-rare", 16, 1, 1e7), num(cfg, "market.scarcity.ref-unique", 4, 1, 1e7),
      weight, minHolders, (long) (ttlDays * 86_400_000d), Set.copyOf(unique));
  }

  /** A missing, non-finite or out-of-range value falls back to the default instead of being clamped into the range. */
  private static double num(Configuration cfg, String key, double fallback, double min, double max) {
    double value = cfg == null ? fallback : cfg.getDouble(key, fallback);
    return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
  }

  static double reference(double base, Settings settings, boolean unique) {
    if (unique) return settings.refUnique();
    if (base < CurvePrice.CHEAP_BASE) return settings.refCheap();
    if (base < 50) return settings.refMid();
    return settings.refRare();
  }

  /** Negative previous means "no sample yet" and adopts the observation instead of sliding. */
  static double ema(double previous, double observed, double alpha) {
    if (!(previous >= 0)) return observed;
    return alpha * observed + (1 - alpha) * previous;
  }

  /** Half-life decay, same shape as the trade curve: amount * 2^(-elapsed / halfLife). Works for signed amounts. */
  static double decay(double amount, long elapsedMs, double halfLifeHours) {
    if (elapsedMs <= 0 || amount == 0 || !(halfLifeHours > 0)) return amount;
    return amount * Math.pow(0.5, elapsedMs / (halfLifeHours * 3_600_000d));
  }

  /**
   * The factor itself. {@code smoothedSupply < 0} (no sample) keeps the supply part at 1. Net demand below zero
   * (an item that was produced more than consumed) never lowers the price: only {@code max(0, demand)} counts.
   */
  static double factor(double base, double smoothedSupply, double netDemand, Settings settings, boolean unique) {
    if (!settings.enabled()) return 1;
    double cap = unique ? settings.uniqueSmax() : settings.smax();
    double ref = reference(base, settings, unique);
    double supplyPart = smoothedSupply >= 0 && Double.isFinite(smoothedSupply) ? Math.pow(ref / (smoothedSupply + 1), settings.k()) : 1;
    double demand = Double.isFinite(netDemand) ? Math.max(0, netDemand) : 0;
    double raw = supplyPart * (1 + settings.demandWeight() * demand / ref);
    if (Double.isNaN(raw)) raw = 1;
    return Math.min(cap, Math.max(settings.smin(), raw));
  }
}

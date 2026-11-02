package de.mcstack.craftcore;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.Configuration;

/** Smooth supply factor applied on top of the trade curve. Unknown supply stays at 1. */
final class Scarcity {
  record Settings(boolean enabled, double k, double smin, double smax, double uniqueSmax, double emaAlpha,
                  double refCheap, double refMid, double refRare, double refUnique, Set<String> unique) {}

  private Scarcity() {}

  static Settings settings(Configuration cfg) {
    double k = num(cfg, "market.scarcity.k", 0.5);
    double smin = num(cfg, "market.scarcity.smin", 0.5);
    double smax = num(cfg, "market.scarcity.smax", 3.0);
    double uniqueSmax = num(cfg, "market.scarcity.unique-smax", 8.0);
    double alpha = num(cfg, "market.scarcity.ema-alpha", 0.25);
    if (smin <= 0) smin = 0.5;
    if (smax < smin) smax = smin;
    if (uniqueSmax < smax) uniqueSmax = smax;
    if (k < 0) k = 0;
    if (alpha <= 0 || alpha > 1) alpha = 0.25;
    Set<String> unique = new HashSet<>();
    if (cfg != null) for (String name : cfg.getStringList("market.scarcity.unique-items")) unique.add(name.toUpperCase(Locale.ROOT));
    if (unique.isEmpty()) unique.add("DRAGON_EGG");
    return new Settings(cfg == null || cfg.getBoolean("market.scarcity.enabled", true), k, smin, smax, uniqueSmax, alpha,
      num(cfg, "market.scarcity.ref-cheap", 4096), num(cfg, "market.scarcity.ref-mid", 256),
      num(cfg, "market.scarcity.ref-rare", 16), num(cfg, "market.scarcity.ref-unique", 4), Set.copyOf(unique));
  }

  private static double num(Configuration cfg, String key, double fallback) {
    double value = cfg == null ? fallback : cfg.getDouble(key, fallback);
    return Double.isFinite(value) && value > 0 ? value : fallback;
  }

  static double reference(double base, Settings settings, boolean unique) {
    if (unique) return settings.refUnique();
    if (base < 1) return settings.refCheap();
    if (base < 50) return settings.refMid();
    return settings.refRare();
  }

  static double raw(double reference, double supply, double k) {
    return Math.pow(reference / (Math.max(0, supply) + 1), k);
  }

  static double clamp(double factor, double smin, double smax) {
    return Math.min(smax, Math.max(smin, factor));
  }

  /** Negative previous means "no sample yet" and adopts the observation instead of sliding. */
  static double ema(double previous, double observed, double alpha) {
    if (previous < 0) return observed;
    return alpha * observed + (1 - alpha) * previous;
  }

  static double factor(double base, double smoothedSupply, Settings settings, boolean unique) {
    if (!settings.enabled()) return 1;
    double cap = unique ? settings.uniqueSmax() : settings.smax();
    return clamp(raw(reference(base, settings, unique), smoothedSupply, settings.k()), settings.smin(), cap);
  }
}

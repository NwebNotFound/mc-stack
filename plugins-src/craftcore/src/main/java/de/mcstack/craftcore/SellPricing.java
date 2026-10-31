package de.mcstack.craftcore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Pure per-item sell calculation; all prices are in cents. */
final class SellPricing {
  record Enchant(String key, int level, boolean cursed) {}
  record Config(long bonusPerLevel, Map<String, Long> overrides, long enchantCap, double minFactor, Long tippedArrowPrice) {
    Config {
      overrides = Map.copyOf(overrides);
      if (bonusPerLevel < 0 || enchantCap < 0 || overrides.values().stream().anyMatch(v -> v < 0)
          || !Double.isFinite(minFactor) || minFactor < 0 || minFactor > 1
          || (tippedArrowPrice != null && tippedArrowPrice < 0)) throw new IllegalArgumentException("Ungueltige Verkaufspreise");
    }
  }
  record Breakdown(long base, long durabilityReduction, long enchantBonus, long enchantBonusCapped, long scaledBonus, boolean baseLimited, long total) {}
  static Breakdown calculate(long base, List<Enchant> enchants, int damage, int maxDurability, Config config) {
    if (base < 0) throw new IllegalArgumentException("Ungueltiger Basispreis");
    long bonus = 0;
    for (Enchant enchant : enchants) {
      String key = enchant.key().toLowerCase(java.util.Locale.ROOT);
      String bare = key.substring(key.indexOf(':') + 1);
      if (enchant.cursed() || bare.equals("binding_curse") || bare.equals("vanishing_curse")) continue;
      long rate = config.overrides().getOrDefault(key, config.overrides().getOrDefault(bare, config.bonusPerLevel()));
      bonus = Math.addExact(bonus, Math.multiplyExact(rate, Math.max(0, enchant.level())));
    }
    BigDecimal numerator = BigDecimal.ONE, denominator = BigDecimal.ONE;
    if (maxDurability > 0) {
      int remaining = Math.max(0, maxDurability - Math.min(maxDurability, Math.max(0, damage)));
      numerator = BigDecimal.valueOf(remaining); denominator = BigDecimal.valueOf(maxDurability);
      if (numerator.compareTo(BigDecimal.valueOf(config.minFactor()).multiply(denominator)) < 0) {
        numerator = BigDecimal.valueOf(config.minFactor()); denominator = BigDecimal.ONE;
      }
    }
    long cappedBonus = Math.min(bonus, config.enchantCap());
    BigDecimal baseNumerator = BigDecimal.valueOf(base).multiply(numerator);
    BigDecimal bonusNumerator = BigDecimal.valueOf(cappedBonus).multiply(numerator);
    BigDecimal bonusLimit = BigDecimal.valueOf(base).multiply(denominator);
    boolean limited = bonusNumerator.compareTo(bonusLimit) > 0;
    bonusNumerator = bonusNumerator.min(bonusLimit);
    long adjustedBase = baseNumerator.divide(denominator, 0, RoundingMode.FLOOR).longValueExact();
    long scaledBonus = bonusNumerator.divide(denominator, 0, RoundingMode.FLOOR).longValueExact();
    long total = baseNumerator.add(bonusNumerator).divide(denominator, 0, RoundingMode.FLOOR).longValueExact();
    return new Breakdown(base, base - adjustedBase, bonus, cappedBonus, scaledBonus, limited, total);
  }

  static long base(long catalogPrice, boolean tippedArrow, Config config) {
    return tippedArrow && config.tippedArrowPrice() != null ? config.tippedArrowPrice() : catalogPrice;
  }
}
